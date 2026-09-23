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
    }

    /** External goods entering the system, e.g. a player depositing real items into custody. */
    public void depositPosition(String accountId, String instrument, long qty) {
        if (qty <= 0) throw new IllegalArgumentException("deposit must be positive");
        book(instrument);
        account(accountId).creditPosition(instrument, qty);
    }

    /** Goods leaving the system, e.g. a player withdrawing real items. Only unlocked quantity. */
    public void withdrawPosition(String accountId, String instrument, long qty) {
        account(accountId).debitPosition(instrument, qty);
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
