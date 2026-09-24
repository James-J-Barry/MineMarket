package com.realisticmarkets.exchange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * The exchange: accounts, order books, and frequent batch auctions.
 *
 * <p>Orders accumulate between auctions; {@link #runAuction(String)} crosses them at a single
 * uniform price. This is deliberately NOT continuous matching: it removes latency races between
 * players with different ping and makes every auction deterministic given the order set.
 *
 * <p>Not thread-safe. The Minecraft layer calls it from the server thread only.
 */
public final class Exchange {
    private final Map<String, Account> accounts = new TreeMap<>();
    private final Map<String, OrderBook> books = new LinkedHashMap<>();
    private final Map<Long, Order> liveOrders = new HashMap<>();
    private final Map<String, Long> lastPrice = new HashMap<>();
    private long nextOrderId = 1;
    private long auctionSeq = 0;
    // Everything that ever entered or left, so tests can prove settlement never creates or destroys value.
    private long cashIn, cashOut;
    private final Map<String, Long> itemsIn = new TreeMap<>();
    private final Map<String, Long> itemsOut = new TreeMap<>();

    // ------------------------------------------------------------------ instruments & accounts

    public void listInstrument(String instrument) {
        books.computeIfAbsent(instrument, OrderBook::new);
    }

    public Collection<String> instruments() {
        return List.copyOf(books.keySet());
    }

    public OrderBook book(String instrument) {
        OrderBook book = books.get(instrument);
        if (book == null) throw new RejectedException("unknown instrument " + instrument);
        return book;
    }

    public Account account(String id) {
        return accounts.computeIfAbsent(id, Account::new);
    }

    public Collection<Account> accounts() {
        return List.copyOf(accounts.values());
    }

    /** External money entering the system (a faucet). Track these: they are inflation. */
    public void deposit(String accountId, long cash) {
        if (cash <= 0) throw new IllegalArgumentException("deposit must be positive");
        account(accountId).creditCash(cash);
        cashIn += cash;
    }

    /** Money leaving the system: only available (unlocked) cash. */
    public void withdrawCash(String accountId, long cash) {
        if (cash <= 0) throw new IllegalArgumentException("withdrawal must be positive");
        account(accountId).debitCash(cash);
        cashOut += cash;
    }

    /** External goods entering the system, e.g. a player depositing real items into custody. */
    public void depositPosition(String accountId, String instrument, long qty) {
        if (qty <= 0) throw new IllegalArgumentException("deposit must be positive");
        book(instrument);
        account(accountId).creditPosition(instrument, qty);
        itemsIn.merge(instrument, qty, Long::sum);
    }

    /** Goods leaving the system, e.g. a player withdrawing real items. Only unlocked quantity. */
    public void withdrawPosition(String accountId, String instrument, long qty) {
        account(accountId).debitPosition(instrument, qty);
        itemsOut.merge(instrument, qty, Long::sum);
    }

    /** Net money that entered: must always equal the cash held in accounts, locked or not. */
    public long netCashIn() {
        return cashIn - cashOut;
    }

    public long netItemsIn(String instrument) {
        return itemsIn.getOrDefault(instrument, 0L) - itemsOut.getOrDefault(instrument, 0L);
    }

    public OptionalLong lastPrice(String instrument) {
        Long p = lastPrice.get(instrument);
        return p == null ? OptionalLong.empty() : OptionalLong.of(p);
    }

    // ------------------------------------------------------------------ orders

    /** Validates, escrows collateral, and rests the order until the next auction. */
    public Order submit(OrderRequest req) {
        OrderBook book = book(req.instrument());
        Account acct = account(req.accountId());
        if (req.side() == Side.BUY) {
            acct.lockCash(Math.multiplyExact(req.quantity(), req.limitPrice()));
        } else {
            acct.lockPosition(req.instrument(), req.quantity());
        }
        Order order = new Order(nextOrderId++, req);
        book.add(order);
        liveOrders.put(order.id(), order);
        return order;
    }

    public void cancel(long orderId) {
        Order order = liveOrders.get(orderId);
        if (order == null) throw new RejectedException("no live order " + orderId);
        removeAndRelease(order);
    }

    public List<Order> openOrders(String accountId) {
        List<Order> out = new ArrayList<>();
        for (Order o : liveOrders.values()) if (o.accountId().equals(accountId)) out.add(o);
        out.sort((a, b) -> Long.compare(a.id(), b.id()));
        return out;
    }

    /** Cancels every resting order with this time in force (DAY orders at dawn), releasing collateral. */
    public List<Order> expire(TimeInForce tif) {
        List<Order> out = new ArrayList<>();
        for (Order o : List.copyOf(liveOrders.values())) {
            if (o.timeInForce() == tif) {
                removeAndRelease(o);
                out.add(o);
            }
        }
        out.sort((a, b) -> Long.compare(a.id(), b.id()));
        return out;
    }

    public java.util.Optional<Order> liveOrder(long orderId) {
        return java.util.Optional.ofNullable(liveOrders.get(orderId));
    }

    // ------------------------------------------------------------------ auctions

    public List<AuctionResult> runAllAuctions() {
        List<AuctionResult> results = new ArrayList<>();
        for (String instrument : books.keySet()) results.add(runAuction(instrument));
        return results;
    }

    public AuctionResult runAuction(String instrument) {
        OrderBook book = book(instrument);
        long seq = ++auctionSeq;
        List<Fill> fills = new ArrayList<>();

        ClearingPrice.Result clear =
                ClearingPrice.computeDetailed(book.mutableBids(), book.mutableAsks(), lastPrice(instrument));

        if (clear != null) {
            long p = clear.price();
            // Walk both sides in priority order among eligible orders. Everyone trades at p.
            Iterator<Order> bidIt = eligible(book.mutableBids(), o -> o.limitPrice() >= p).iterator();
            Iterator<Order> askIt = eligible(book.mutableAsks(), o -> o.limitPrice() <= p).iterator();
            Order bid = bidIt.next();
            Order ask = askIt.next();
            long toMatch = clear.volume();
            while (toMatch > 0) {
                long q = Math.min(toMatch, Math.min(bid.remaining(), ask.remaining()));
                settle(bid, ask, q, p);
                fills.add(new Fill(seq, instrument, bid.id(), ask.id(), bid.accountId(), ask.accountId(), q, p));
                toMatch -= q;
                if (toMatch == 0) break;
                if (bid.isDone()) bid = bidIt.next();
                if (ask.isDone()) ask = askIt.next();
            }
            lastPrice.put(instrument, p);
        }

        // Clean up: drop filled orders, cancel IOC remainders.
        List<Long> cancelledIoc = new ArrayList<>();
        for (List<Order> side : List.of(book.mutableBids(), book.mutableAsks())) {
            for (Order o : List.copyOf(side)) {
                if (o.isDone()) {
                    side.remove(o);
                    liveOrders.remove(o.id());
                } else if (o.timeInForce() == TimeInForce.IOC) {
                    removeAndRelease(o);
                    cancelledIoc.add(o.id());
                }
            }
        }

        return new AuctionResult(
                instrument,
                seq,
                clear == null ? OptionalLong.empty() : OptionalLong.of(clear.price()),
                clear == null ? 0 : clear.volume(),
                List.copyOf(fills),
                List.copyOf(cancelledIoc));
    }

    // ------------------------------------------------------------------ persistence

    public static final String HEADER = "# Realistic Markets exchange state v1";

    /**
     * <pre>
     * @counters	next_order_id	auction_seq	cash_in	cash_out
     * instrument	minecraft:wheat
     * flow	minecraft:wheat	in	out
     * last	minecraft:wheat	52
     * acct	id	cash	locked_cash
     * pos	id	instrument	qty	locked
     * order	id	account	instrument	side	quantity	remaining	limit	tif
     * </pre>
     */
    public void write(java.io.Writer w) throws java.io.IOException {
        w.write(HEADER + "\n");
        w.write("@counters\t" + nextOrderId + "\t" + auctionSeq + "\t" + cashIn + "\t" + cashOut + "\n");
        for (String i : books.keySet()) w.write("instrument\t" + i + "\n");
        java.util.Set<String> flows = new java.util.TreeSet<>(itemsIn.keySet());
        flows.addAll(itemsOut.keySet());
        for (String i : flows) w.write("flow\t" + i + "\t" + itemsIn.getOrDefault(i, 0L) + "\t" + itemsOut.getOrDefault(i, 0L) + "\n");
        for (Map.Entry<String, Long> e : new TreeMap<>(lastPrice).entrySet()) w.write("last\t" + e.getKey() + "\t" + e.getValue() + "\n");
        for (Account a : accounts.values()) {
            w.write("acct\t" + a.id() + "\t" + a.cash() + "\t" + a.lockedCash() + "\n");
            java.util.Set<String> ins = new java.util.TreeSet<>(a.positions().keySet());
            ins.addAll(a.lockedPositions().keySet());
            for (String i : ins) w.write("pos\t" + a.id() + "\t" + i + "\t" + a.position(i) + "\t" + a.lockedPosition(i) + "\n");
        }
        List<Order> orders = new ArrayList<>(liveOrders.values());
        orders.sort((x, y) -> Long.compare(x.id(), y.id()));
        for (Order o : orders) {
            w.write("order\t" + o.id() + "\t" + o.accountId() + "\t" + o.instrument() + "\t" + o.side() + "\t"
                    + o.originalQuantity() + "\t" + o.remaining() + "\t" + o.limitPrice() + "\t" + o.timeInForce() + "\n");
        }
        w.flush();
    }

    public static Exchange read(java.io.Reader r) throws java.io.IOException {
        Exchange ex = new Exchange();
        java.io.BufferedReader br = new java.io.BufferedReader(r);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            try {
                switch (c[0]) {
                    case "@counters" -> {
                        ex.nextOrderId = Long.parseLong(c[1]);
                        ex.auctionSeq = Long.parseLong(c[2]);
                        ex.cashIn = Long.parseLong(c[3]);
                        ex.cashOut = Long.parseLong(c[4]);
                    }
                    case "instrument" -> ex.listInstrument(c[1]);
                    case "flow" -> {
                        ex.itemsIn.put(c[1], Long.parseLong(c[2]));
                        ex.itemsOut.put(c[1], Long.parseLong(c[3]));
                    }
                    case "last" -> ex.lastPrice.put(c[1], Long.parseLong(c[2]));
                    case "acct" -> ex.account(c[1]).restore(Long.parseLong(c[2]), Long.parseLong(c[3]));
                    case "pos" -> ex.account(c[1]).restorePosition(c[2], Long.parseLong(c[3]), Long.parseLong(c[4]));
                    case "order" -> {
                        OrderRequest req = new OrderRequest(c[2], c[3], Side.valueOf(c[4]), Long.parseLong(c[5]),
                                Long.parseLong(c[7]), TimeInForce.valueOf(c[8]));
                        Order o = new Order(Long.parseLong(c[1]), req);
                        long filled = req.quantity() - Long.parseLong(c[6]);
                        if (filled > 0) o.fill(filled);
                        ex.book(req.instrument()).add(o);
                        ex.liveOrders.put(o.id(), o);
                    }
                    default -> throw new IllegalArgumentException("unknown key " + c[0]);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("exchange line " + n + " is malformed: '" + t + "'", e);
            }
        }
        return ex;
    }

    // ------------------------------------------------------------------ internals

    private interface Pred { boolean test(Order o); }

    private static List<Order> eligible(List<Order> side, Pred pred) {
        List<Order> out = new ArrayList<>();
        for (Order o : side) {
            if (pred.test(o)) out.add(o);
            else break; // sides are sorted best-first, so eligibility is a prefix
        }
        return out;
    }

    private void settle(Order bid, Order ask, long qty, long price) {
        Account buyer = account(bid.accountId());
        Account seller = account(ask.accountId());
        long escrowed = Math.multiplyExact(qty, bid.limitPrice());
        long cost = Math.multiplyExact(qty, price);

        buyer.consumeLockedCash(escrowed);
        buyer.creditCash(escrowed - cost); // price improvement refund
        buyer.creditPosition(bid.instrument(), qty);

        seller.consumeLockedPosition(ask.instrument(), qty);
        seller.creditCash(cost);

        bid.fill(qty);
        ask.fill(qty);
    }

    private void removeAndRelease(Order order) {
        book(order.instrument()).remove(order);
        liveOrders.remove(order.id());
        Account acct = account(order.accountId());
        if (order.side() == Side.BUY) {
            acct.releaseLockedCash(Math.multiplyExact(order.remaining(), order.limitPrice()));
        } else {
            acct.releaseLockedPosition(order.instrument(), order.remaining());
        }
    }
}
