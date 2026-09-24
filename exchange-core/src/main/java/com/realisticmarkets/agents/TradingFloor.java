package com.realisticmarkets.agents;

import com.realisticmarkets.exchange.Account;
import com.realisticmarkets.exchange.AuctionResult;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.Fill;
import com.realisticmarkets.exchange.Order;
import com.realisticmarkets.exchange.OrderRequest;
import com.realisticmarkets.exchange.PriceHistory;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.exchange.Side;
import com.realisticmarkets.exchange.TimeInForce;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * The Trading Floor: the exchange, one NPC population per book, price history, and the players' orders. Players
 * hand over goods (to sell) or cash (to buy) when they place an order; fills and refunds collect in their exchange
 * account until they pick them up at the Floor. Every finished order (filled, cancelled or expired) produces one
 * {@link Receipt}.
 */
public final class TradingFloor {
    public static final String HEADER = "# Realistic Markets floor tickets v1";
    /** A market order is a limit this far through the reference price, filled at once or refunded. */
    public static final double MARKET_REACH = 0.5;

    /** A player's order as the Floor tracks it. */
    public static final class Ticket {
        final long orderId;
        final String account, item;
        final Side side;
        final boolean market;
        final long qty, limitCents, dealerBidMills, placedDay;
        long filledQty, filledCents;

        Ticket(long orderId, String account, String item, Side side, boolean market, long qty, long limitCents,
               long dealerBidMills, long placedDay) {
            this.orderId = orderId;
            this.account = account;
            this.item = item;
            this.side = side;
            this.market = market;
            this.qty = qty;
            this.limitCents = limitCents;
            this.dealerBidMills = dealerBidMills;
            this.placedDay = placedDay;
        }

        public long orderId() { return orderId; }
        public String item() { return item; }
        public Side side() { return side; }
        public boolean market() { return market; }
        public long qty() { return qty; }
        public long limitCents() { return limitCents; }
        public long filledQty() { return filledQty; }
    }

    public enum Ending { FILLED, CANCELLED, EXPIRED }

    public record Receipt(String account, String item, Side side, boolean market, long qty, long filledQty,
                          long filledCents, long dealerBidMills, long day, Ending ending) {
        /** Average price per item in mills (0 if nothing filled). */
        public long avgMills() {
            return filledQty == 0 ? 0 : Math.round(filledCents * 10.0 / filledQty);
        }
    }

    private final Exchange ex;
    private final FloorCatalog catalog;
    private final PriceHistory history;
    private final Map<String, AgentPopulation> pops = new LinkedHashMap<>();
    private final Map<Long, Ticket> tickets = new LinkedHashMap<>();
    private final long seed;
    private final Map<String, double[]> basisCache = new LinkedHashMap<>(); // item -> {quarter, basis}

    /** A fresh floor or a reloaded one: {@code ex} and {@code history} may come from save files. */
    public TradingFloor(Exchange ex, PriceHistory history, FloorCatalog catalog, ToLongFunction<String> fairCents, long seed) {
        this.ex = ex;
        this.history = history;
        this.catalog = catalog;
        this.seed = seed;
        long s = seed;
        for (FloorCatalog.Book b : catalog.all()) pops.put(b.item(), new AgentPopulation(ex, b, fairCents.applyAsLong(b.item()), s++));
    }

    public Exchange exchange() { return ex; }
    public PriceHistory history() { return history; }
    public FloorCatalog catalog() { return catalog; }
    public AgentPopulation population(String item) { return pops.get(item); }

    public List<Ticket> openTickets(String account) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets.values()) if (t.account.equals(account)) out.add(t);
        return out;
    }

    /** Basis mean-reverts with this half-life. */
    public static final double BASIS_HALF_LIFE_DAYS = 1.0;
    private static final double BASIS_STEP_DAYS = 0.25;
    private static final int BASIS_STEPS_KEPT = 40; // 0.5^(40/4) = 0.1%: older steps no longer matter

    /**
     * The Floor's own fair value for a book: the Dealer's, moved by the book's basis. Only books priced from another
     * item (iron block) have one, so the iron block book and the ingot book drift apart and back.
     */
    public long fairOnFloor(String item, long dealerFairCents, double day) {
        return Math.max(1, Math.round(dealerFairCents * Math.exp(basis(item, day))));
    }

    /**
     * Log basis at {@code day}: a mean-reverting walk in quarter-day steps, derived from the seed and the step
     * number alone (no save state), with long-run standard deviation {@link FloorCatalog.Book#basis()}.
     */
    public double basis(String item, double day) {
        FloorCatalog.Book b = catalog.trades(item) ? catalog.book(item) : null;
        if (b == null || b.basis() == 0) return 0;
        long q = (long) Math.floor(day / BASIS_STEP_DAYS);
        double[] cached = basisCache.get(item);
        if (cached != null && cached[0] == q) return cached[1];
        double phi = Math.pow(0.5, BASIS_STEP_DAYS / BASIS_HALF_LIFE_DAYS);
        double innov = b.basis() * Math.sqrt(1 - phi * phi);
        double sum = 0, w = 1;
        for (int k = 0; k < BASIS_STEPS_KEPT; k++, w *= phi) {
            long step = q - k;
            long mix = seed ^ (item.hashCode() * 0x9E3779B97F4A7C15L) ^ (step * 0xBF58476D1CE4E5B9L);
            sum += w * innov * new java.util.SplittableRandom(mix).nextGaussian();
        }
        basisCache.put(item, new double[] {q, sum});
        return sum;
    }

    /** Last clearing price, or fair value before the first trade. */
    public long reference(String item, long fairCents) {
        return ex.lastPrice(item).orElse(fairCents);
    }

    /** Cash a buy order must escrow: the limit, or for a market buy, the reach above the reference. */
    public long buyLimit(String item, long fairCents, boolean market, long limitCents) {
        return market ? Math.max(1, Math.round(reference(item, fairCents) * (1 + MARKET_REACH))) : limitCents;
    }

    public long sellLimit(String item, long fairCents, boolean market, long limitCents) {
        return market ? Math.max(1, Math.round(reference(item, fairCents) * (1 - MARKET_REACH))) : limitCents;
    }

    /**
     * Places an order, taking the collateral into custody: {@code qty} items for a sell, {@code qty x limit} cents for
     * a buy (the caller has already removed them from the player). Day orders rest until the next dawn; market
     * orders go into the next auction only.
     */
    public Ticket place(String account, String item, Side side, long qty, long limitCents, boolean market,
                        long dealerBidMills, long day) {
        if (!catalog.trades(item)) throw new RejectedException("The Floor has no book for " + item);
        if (qty <= 0 || limitCents <= 0) throw new RejectedException("Quantity and price must be positive");
        if (side == Side.SELL) ex.depositPosition(account, item, qty);
        else ex.deposit(account, Math.multiplyExact(qty, limitCents));
        OrderRequest r = market ? OrderRequest.ioc(account, item, side, qty, limitCents)
                : OrderRequest.day(account, item, side, qty, limitCents);
        Order o = ex.submit(r);
        Ticket t = new Ticket(o.id(), account, item, side, market, qty, limitCents, dealerBidMills, day);
        tickets.put(o.id(), t);
        return t;
    }

    /** Runs one auction on {@code item}: NPC recovery and orders, the auction, receipts for finished orders. */
    public List<Receipt> auction(String item, long fairCents, double daysSinceLast, long day) {
        AgentPopulation pop = pops.get(item);
        pop.recover(daysSinceLast, fairCents);
        pop.submitOrders(fairCents);
        AuctionResult r = ex.runAuction(item);
        pop.onAuction(r);
        if (r.traded()) history.record(item, day, r.clearingPrice().getAsLong(), r.volume());
        for (Fill f : r.fills()) {
            credit(f.buyOrderId(), f);
            credit(f.sellOrderId(), f);
        }
        List<Receipt> out = new ArrayList<>();
        for (Ticket t : List.copyOf(tickets.values())) {
            if (!t.item.equals(item)) continue;
            if (ex.liveOrder(t.orderId).isEmpty()) out.add(finish(t, t.filledQty == t.qty ? Ending.FILLED : Ending.CANCELLED, day));
        }
        return out;
    }

    private void credit(long orderId, Fill f) {
        Ticket t = tickets.get(orderId);
        if (t == null) return;
        t.filledQty += f.quantity();
        t.filledCents += f.quantity() * f.price();
    }

    /**
     * Extra cash a buy order needs to move to {@code newLimitCents}, beyond what it already holds and any cash
     * waiting in the account. 0 for sells and for lower prices.
     */
    public long repriceCost(String account, long orderId, long newLimitCents) {
        Ticket t = ticket(account, orderId);
        if (t.side == Side.SELL) return 0;
        long remaining = ex.liveOrder(orderId).orElseThrow().remaining();
        long have = remaining * t.limitCents + ex.account(account).cash();
        return Math.max(0, Math.multiplyExact(remaining, newLimitCents) - have);
    }

    /**
     * Moves an open limit order to a new price, keeping its fills and its Order Slip. It joins the back of the
     * queue at its new price. For a buy, {@code extraCents} (at least {@link #repriceCost}) is deposited first;
     * collateral it no longer needs waits in the account for collection.
     */
    public Ticket reprice(String account, long orderId, long newLimitCents, long extraCents, long day) {
        Ticket t = ticket(account, orderId);
        if (t.market) throw new RejectedException("Market orders can't be repriced");
        if (newLimitCents <= 0) throw new RejectedException("Price must be positive");
        if (extraCents < repriceCost(account, orderId, newLimitCents)) throw new RejectedException("Not enough cash to raise that bid");
        long remaining = ex.liveOrder(orderId).orElseThrow().remaining();
        if (extraCents > 0) ex.deposit(account, extraCents);
        ex.cancel(orderId);
        Order o = ex.submit(OrderRequest.day(account, t.item, t.side, remaining, newLimitCents));
        Ticket moved = new Ticket(o.id(), account, t.item, t.side, false, t.qty, newLimitCents, t.dealerBidMills, t.placedDay);
        moved.filledQty = t.filledQty;
        moved.filledCents = t.filledCents;
        tickets.remove(orderId);
        tickets.put(o.id(), moved);
        return moved;
    }

    private Ticket ticket(String account, long orderId) {
        Ticket t = tickets.get(orderId);
        if (t == null || !t.account.equals(account) || ex.liveOrder(orderId).isEmpty()) throw new RejectedException("No such order");
        return t;
    }

    public Receipt cancel(String account, long orderId, long day) {
        Ticket t = tickets.get(orderId);
        if (t == null || !t.account.equals(account)) throw new RejectedException("No such order");
        ex.cancel(orderId);
        return finish(t, Ending.CANCELLED, day);
    }

    /** At dawn: every day order still resting expires, its collateral left for collection. */
    public List<Receipt> dawn(long day) {
        List<Receipt> out = new ArrayList<>();
        for (Order o : ex.expire(TimeInForce.DAY)) {
            Ticket t = tickets.get(o.id());
            if (t != null) out.add(finish(t, Ending.EXPIRED, day));
        }
        return out;
    }

    private Receipt finish(Ticket t, Ending ending, long day) {
        tickets.remove(t.orderId);
        return new Receipt(t.account, t.item, t.side, t.market, t.qty, t.filledQty, t.filledCents, t.dealerBidMills,
                day, ending);
    }

    /** What the player can pick up now: unlocked cash (whole dimes) and goods. */
    public record Pickup(long cents, Map<String, Long> items) {
        public boolean isEmpty() { return cents == 0 && items.isEmpty(); }
    }

    public Pickup available(String account) {
        Account a = ex.account(account);
        Map<String, Long> items = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : a.positions().entrySet()) if (e.getValue() > 0) items.put(e.getKey(), e.getValue());
        return new Pickup(a.cash() / 10 * 10, items);
    }

    /** Takes {@code p} out of custody (the caller hands it to the player). Cents under a dime stay behind. */
    public void collect(String account, Pickup p) {
        if (p.cents() > 0) ex.withdrawCash(account, p.cents());
        for (Map.Entry<String, Long> e : p.items().entrySet()) ex.withdrawPosition(account, e.getKey(), e.getValue());
    }

    // ------------------------------------------------------------------ persistence (tickets; the exchange and
    // history have their own save formats)

    public void writeTickets(Writer w) throws IOException {
        w.write(HEADER + "\n");
        for (Ticket t : tickets.values()) {
            w.write("ticket\t" + t.orderId + "\t" + t.account + "\t" + t.item + "\t" + t.side + "\t" + t.market + "\t"
                    + t.qty + "\t" + t.limitCents + "\t" + t.dealerBidMills + "\t" + t.placedDay + "\t" + t.filledQty
                    + "\t" + t.filledCents + "\n");
        }
        w.flush();
    }

    public void readTickets(Reader r) throws IOException {
        BufferedReader br = new BufferedReader(r);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String[] c = s.split("\t");
            try {
                Ticket t = new Ticket(Long.parseLong(c[1]), c[2], c[3], Side.valueOf(c[4]), Boolean.parseBoolean(c[5]),
                        Long.parseLong(c[6]), Long.parseLong(c[7]), Long.parseLong(c[8]), Long.parseLong(c[9]));
                t.filledQty = Long.parseLong(c[10]);
                t.filledCents = Long.parseLong(c[11]);
                if (ex.liveOrder(t.orderId).isPresent()) tickets.put(t.orderId, t);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("ticket line " + n + " is malformed: '" + s + "'", e);
            }
        }
    }
}
