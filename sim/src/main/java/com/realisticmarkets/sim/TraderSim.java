package com.realisticmarkets.sim;

import com.realisticmarkets.agents.FloorCatalog;
import com.realisticmarkets.agents.TradingFloor;
import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.dealer.WorldEvents;
import com.realisticmarkets.exchange.Account;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.PriceHistory;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.exchange.Side;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.progression.Blueprints;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Does the Trading Floor make money for a player, beyond being a better place to sell? Runs the real
 * {@link TradingFloor} (NPC books, day orders, custody, repricing) against a Dealer whose prices trend and react to
 * world events, and scores player strategies:
 * <ol>
 *   <li><b>Gatherer:</b> a profile's daily output, Floor items sold on the Floor (a limit order at mid, repriced
 *       to the bid later in the day, leftovers to the Dealer at dusk) vs everything to the Dealer. Income uplift and
 *       days to pay back the Floor.</li>
 *   <li><b>Traders</b> with $1,000 and no gathering, scored against the Bank Vault's 0.3% a day: quoting inside
 *       the market maker; buying under the Dealer's Normal price and selling over it; trading the news; iron block
 *       vs 9 ingots; and buy-and-hold (a benchmark with no edge, to show the risk).</li>
 * </ol>
 * The player visits the Floor {@link #VISITS_PER_DAY} times a day, the first at dawn. Each new order costs an Order
 * Slip; repricing an open order is free.
 */
public final class TraderSim {
    static final int AUCTIONS_PER_DAY = 120;
    static final double DAY_STEP = 1.0 / AUCTIONS_PER_DAY;
    static final int DAYS = 30;
    static int VISITS_PER_DAY = 4;
    static long CAPITAL = 100_000;
    static int SIZE_DIV = 32; // order size = book depth / SIZE_DIV
    static long CAP_PER_BOOK = 15_000;
    static final String ME = "player";
    static int SEEDS = 8;
    static boolean freeSlips = false; // diagnostic: `sim trader free`

    /** The Floor and Dealer as a player meets them. */
    static final class World {
        final Dealer dealer;
        final WorldEvents events;
        final TradingFloor floor;
        final long slipCents;
        double day = 0;
        long slips = 0;

        World(long seed) {
            dealer = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), seed);
            events = WorldEvents.loadDefault(dealer.catalog(), seed ^ 0x4576656E7473L);
            dealer.setShocks(events);
            floor = new TradingFloor(new Exchange(), new PriceHistory(), FloorCatalog.loadDefault(), this::dealerFair, seed);
            int perPaper = Blueprints.loadDefault().forResult("realisticmarkets:order_slip").resultCount();
            slipCents = freeSlips ? 0 : Math.round(dealer.quoteBuy("realisticmarkets:ledger_paper", 1, 0, true).cents() / (double) perPaper);
        }

        long dealerFair(String item) {
            return Math.max(1, Math.round(dealer.fairValue(item, day) * 100));
        }

        long fair(String item) {
            return floor.fairOnFloor(item, dealerFair(item), day);
        }

        void auctions(int n) {
            for (int i = 0; i < n; i++) {
                double before = day;
                day += DAY_STEP;
                for (FloorCatalog.Book b : floor.catalog().all()) floor.auction(b.item(), fair(b.item()), DAY_STEP, day);
                if (Math.floor(day + 1e-9) > Math.floor(before + 1e-9)) floor.dawn((long) Math.floor(day + 1e-9));
            }
        }

        long[] quotes(String item) {
            return floor.population(item).makerQuotes(fair(item));
        }

        TradingFloor.Ticket open(String item, Side side) {
            for (TradingFloor.Ticket t : floor.openTickets(ME)) if (t.item().equals(item) && t.side() == side) return t;
            return null;
        }

        /**
         * Keeps one order per book and side at {@code price}: reprices an open one (free), or places a new one
         * (one slip). A sell with fresh stock to add is cancelled and replaced.
         */
        void order(Wallet w, String item, Side side, long qty, long price) {
            if (price <= 0) return;
            TradingFloor.Ticket t = open(item, side);
            if (t != null && side == Side.SELL && qty > 0 && w.stock.getOrDefault(item, 0L) > 0) {
                floor.cancel(ME, t.orderId(), (long) day);
                collect(w);
                qty = w.stock.getOrDefault(item, 0L);
                t = null;
            }
            if (t != null) {
                if (t.limitCents() == price) return;
                long extra = Money.roundUpToDime(floor.repriceCost(ME, t.orderId(), price));
                if (extra > w.cash) return;
                floor.reprice(ME, t.orderId(), price, extra, (long) day);
                w.cash -= extra;
                return;
            }
            if (qty <= 0) return;
            if (side == Side.BUY) qty = Math.min(qty, (w.cash - slipCents) / price);
            else qty = Math.min(qty, w.stock.getOrDefault(item, 0L));
            if (qty <= 0) return;
            long pay = side == Side.BUY ? Money.roundUpToDime(qty * price) : 0;
            try {
                floor.place(ME, item, side, qty, price, false, 0, (long) day);
            } catch (RejectedException e) {
                return;
            }
            if (pay > qty * price) floor.exchange().deposit(ME, pay - qty * price);
            if (side == Side.BUY) w.cash -= pay;
            else w.stock.merge(item, -qty, Long::sum);
            slips++;
            w.cash -= slipCents;
        }

        void cancel(String item, Side side) {
            TradingFloor.Ticket t = open(item, side);
            if (t != null) floor.cancel(ME, t.orderId(), (long) day);
        }

        void cancelAll() {
            for (TradingFloor.Ticket t : floor.openTickets(ME)) floor.cancel(ME, t.orderId(), (long) day);
        }

        void collect(Wallet w) {
            TradingFloor.Pickup p = floor.available(ME);
            floor.collect(ME, p);
            w.cash += p.cents();
            p.items().forEach((k, v) -> w.stock.merge(k, v, Long::sum));
        }

        /** Everything the player owns, at the Floor's fair value, including what's held by open orders. */
        long value(Wallet w) {
            Account a = floor.exchange().account(ME);
            long v = w.cash + a.cash() + a.lockedCash();
            for (FloorCatalog.Book b : floor.catalog().all()) {
                long q = w.stock.getOrDefault(b.item(), 0L) + a.position(b.item()) + a.lockedPosition(b.item());
                v += q * fair(b.item());
            }
            return v;
        }
    }

    static final class Wallet {
        long cash;
        final Map<String, Long> stock = new LinkedHashMap<>();

        long have(String item) {
            return stock.getOrDefault(item, 0L);
        }
    }

    interface Strategy {
        void visit(World w, Wallet me, boolean dawn);
    }

    record Score(long perDay, long worstWeek, double slipsPerDay) {}

    /** Runs a trading strategy for DAYS; profit per day and the worst 7-day stretch (holdings at fair value). */
    static Score trade(Supplier<Strategy> make, long seed) {
        World w = new World(seed);
        Wallet me = new Wallet();
        me.cash = CAPITAL;
        Strategy s = make.get();
        w.auctions(AUCTIONS_PER_DAY); // settle the books first
        List<Long> daily = new ArrayList<>();
        daily.add(w.value(me));
        for (int v = 0; v < DAYS * VISITS_PER_DAY; v++) {
            w.collect(me);
            s.visit(w, me, v % VISITS_PER_DAY == 0);
            w.auctions(AUCTIONS_PER_DAY / VISITS_PER_DAY);
            if ((v + 1) % VISITS_PER_DAY == 0) daily.add(w.value(me));
        }
        w.cancelAll();
        w.collect(me);
        long end = w.value(me);
        long worstWeek = Long.MAX_VALUE;
        for (int d = 7; d < daily.size(); d++) worstWeek = Math.min(worstWeek, daily.get(d) - daily.get(d - 7));
        return new Score((end - daily.getFirst()) / DAYS, worstWeek, w.slips / (double) DAYS);
    }

    static long size(FloorCatalog.Book b) {
        return Math.max(1, b.depth() / SIZE_DIV);
    }

    /** Quote one cent inside the NPC market maker on every book, holding at most CAP_PER_BOOK of each item. */
    static Strategy insideSpread() {
        return (w, me, dawn) -> {
            for (FloorCatalog.Book b : w.floor.catalog().all()) {
                long[] q = w.quotes(b.item());
                if (q[1] - q[0] < 3) continue;
                long bid = q[0] + 1, ask = q[1] - 1;
                long room = Math.max(0, CAP_PER_BOOK / bid - me.have(b.item()));
                w.order(me, b.item(), Side.BUY, Math.min(size(b), room), bid);
                w.order(me, b.item(), Side.SELL, Math.min(size(b), me.have(b.item())), ask);
            }
        };
    }

    /**
     * Buy 3% under the Dealer's Normal price, sell 1% over it. Normal is what the Basic Exchange shows: yesterday's
     * close, so it lags the true value.
     */
    static Strategy value() {
        return (w, me, dawn) -> {
            for (FloorCatalog.Book b : w.floor.catalog().all()) {
                long fair = Math.max(1, Math.round(w.dealer.normalValue(b.item(), w.day) * 100));
                long bid = Math.max(1, Math.round(fair * 0.97)), ask = Math.round(fair * 1.01);
                long room = Math.max(0, CAP_PER_BOOK / bid - me.have(b.item()));
                w.order(me, b.item(), Side.BUY, Math.min(size(b), room), bid);
                w.order(me, b.item(), Side.SELL, me.have(b.item()), ask);
            }
        };
    }

    /**
     * Trade the Newsstand's paper. At dawn, when a story will push a book up, buy at the ask before the market
     * hears at midday; when one has knocked a book down, buy the next dawn, after the fall, for the part that fades.
     * Sell at 60% of the event type's usual move (40% for a dip), or at the bid after 3 days.
     */
    static Strategy news() {
        Map<String, double[]> held = new LinkedHashMap<>(); // item -> {entry cents, entry day, target gain}
        return (w, me, dawn) -> {
            if (dawn) {
                long today = (long) Math.floor(w.day + 1e-9);
                for (WorldEvents.Event e : w.events.recent(today + 0.5, 2)) { // (w.day can sit a hair before dawn)
                    boolean up = e.type().shock() > 0 && e.day() == today;
                    boolean dip = e.type().shock() < 0 && e.day() == today - 1;
                    if (!up && !dip) continue;
                    for (String item : w.events.affects(e.type())) {
                        if (!w.floor.catalog().trades(item) || held.containsKey(item)) continue;
                        long[] q = w.quotes(item);
                        long price = up ? q[1] + 1 : q[0] + 1;
                        w.order(me, item, Side.BUY, Math.min(30_000, me.cash / 3) / price, price);
                        // Aim for 60% of this kind of event's usual move (a dip only recovers part of it).
                        double gain = up ? 0.6 * e.type().shock() : 0.4 * -e.type().shock();
                        held.put(item, new double[] {price, w.day, gain});
                    }
                }
            }
            for (Map.Entry<String, double[]> h : new ArrayList<>(held.entrySet())) {
                String item = h.getKey();
                long[] q = w.quotes(item);
                boolean stale = w.day - h.getValue()[1] > 3;
                if (me.have(item) == 0 && w.open(item, Side.SELL) == null) {
                    if (stale) {
                        w.cancel(item, Side.BUY);
                        held.remove(item);
                    }
                    continue;
                }
                w.cancel(item, Side.BUY);
                long target = stale ? q[0] : Math.max(q[1] - 1, Math.round(h.getValue()[0] * (1 + h.getValue()[2])));
                w.order(me, item, Side.SELL, me.have(item), target);
            }
            held.keySet().removeIf(item -> me.have(item) == 0 && w.open(item, Side.SELL) == null
                    && w.open(item, Side.BUY) == null && w.day - held.get(item)[1] > 0.5);
        };
    }

    /**
     * Iron block vs 9 ingots: when one side is cheap enough (after a 2% margin), buy it at the ask, craft it (free,
     * instant) into the other form and offer that at the other book's ask minus a cent.
     */
    static Strategy twoBooks() {
        return (w, me, dawn) -> {
            String blk = "minecraft:iron_block", ing = "minecraft:iron_ingot";
            long[] qb = w.quotes(blk), qi = w.quotes(ing);
            // Craft whatever we hold into the form that sells better now.
            if (qb[0] > 9 * qi[0]) {
                long n = me.have(ing) / 9;
                me.stock.merge(ing, -9 * n, Long::sum);
                me.stock.merge(blk, n, Long::sum);
            } else if (me.have(blk) > 0) {
                me.stock.merge(ing, 9 * me.have(blk), Long::sum);
                me.stock.put(blk, 0L);
            }
            w.order(me, blk, Side.SELL, me.have(blk), Math.max(qb[0], qb[1] - 1));
            w.order(me, ing, Side.SELL, me.have(ing), Math.max(qi[0], qi[1] - 1));
            long budget = me.cash / 2;
            if (qb[0] > 9 * qi[1] * 1.02) {
                w.order(me, ing, Side.BUY, Math.min(72, budget / qi[1]) / 9 * 9, qi[1]);
            } else {
                w.cancel(ing, Side.BUY);
            }
            if (9 * qi[0] > qb[1] * 1.02) {
                w.order(me, blk, Side.BUY, Math.min(8, budget / qb[1]), qb[1]);
            } else {
                w.cancel(blk, Side.BUY);
            }
        };
    }

    /** Benchmark: spend it all on the first day, a basket across every book, and hold. No edge; all risk. */
    static Strategy buyAndHold() {
        boolean[] done = {false};
        return (w, me, dawn) -> {
            if (done[0]) return;
            done[0] = true;
            List<FloorCatalog.Book> books = w.floor.catalog().all();
            long each = me.cash / books.size();
            for (FloorCatalog.Book b : books) {
                long[] q = w.quotes(b.item());
                long price = q[1] + Math.max(1, q[1] / 50);
                w.order(me, b.item(), Side.BUY, (each - w.slipCents) / price, price);
            }
        };
    }

    /** Gatherer: daily income selling a profile's output, with or without the Floor. */
    static long gather(List<ProgressionSim.Source> sources, boolean useFloor, long seed) {
        World w = new World(seed);
        Wallet me = new Wallet();
        me.cash = 10_000; // pocket money for slips
        w.auctions(AUCTIONS_PER_DAY);
        long steady = 0;
        int steadyDays = 0;
        for (int day = 1; day <= DAYS; day++) {
            long cash0 = me.cash;
            for (ProgressionSim.Source s : sources) me.stock.merge(s.item(), (long) Math.floor(s.output(day)), Long::sum);
            if (useFloor) {
                for (int v = 0; v < VISITS_PER_DAY; v++) {
                    w.collect(me);
                    for (FloorCatalog.Book b : w.floor.catalog().all()) {
                        long[] q = w.quotes(b.item());
                        // Ask near the middle at dawn; later visits step down to the bid (repricing is free).
                        long price = v == 0 ? (q[0] + q[1]) / 2 : q[0];
                        w.order(me, b.item(), Side.SELL, me.have(b.item()), price);
                    }
                    w.auctions(AUCTIONS_PER_DAY / VISITS_PER_DAY);
                }
                w.cancelAll();
                w.collect(me);
            } else {
                w.auctions(AUCTIONS_PER_DAY);
            }
            for (Map.Entry<String, Long> e : new ArrayList<>(me.stock.entrySet())) { // the rest to the Dealer at dusk
                if (e.getValue() <= 0 || !w.dealer.catalog().trades(e.getKey())) continue;
                try {
                    me.cash += w.dealer.sell(e.getKey(), e.getValue(), w.day, true).cents();
                } catch (RejectedException collapsed) {
                    // sold for nothing
                }
                me.stock.put(e.getKey(), 0L);
            }
            if (day > DAYS / 2) {
                steady += me.cash - cash0;
                steadyDays++;
            }
        }
        return steady / steadyDays;
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) { // diagnostics: free visits=N capital=CENTS size=DIV cap=CENTS seeds=N
            if (a.equals("free")) freeSlips = true;
            else if (a.startsWith("visits=")) VISITS_PER_DAY = Integer.parseInt(a.substring(7));
            else if (a.startsWith("capital=")) CAPITAL = Long.parseLong(a.substring(8));
            else if (a.startsWith("size=")) SIZE_DIV = Integer.parseInt(a.substring(5));
            else if (a.startsWith("cap=")) CAP_PER_BOOK = Long.parseLong(a.substring(4));
            else if (a.startsWith("seeds=")) SEEDS = Integer.parseInt(a.substring(6));
        }
        World probe = new World(1);
        long floorCost = 300_000 + probe.dealer.quoteBuy("realisticmarkets:clockwork_gear", 2, 0, true).cents()
                + probe.dealer.quoteBuy("minecraft:gold_ingot", 4, 0, true).cents();
        System.out.printf(Locale.ROOT, "Trading Floor for players: %d days, %d visits a day, Order Slip %s each, %d seeds averaged%n",
                DAYS, VISITS_PER_DAY, Money.format(probe.slipCents), SEEDS);
        System.out.println();
        System.out.println("1. Gatherer: steady $/day, everything to the Dealer (licensed) vs Floor items on the Floor first");
        System.out.printf(Locale.ROOT, "%-16s  %12s  %12s  %8s  %s%n", "profile", "dealer only", "with floor", "uplift",
                "payback of " + Money.format(floorCost));
        for (String name : List.of("early_survival", "farm_heavy")) {
            List<ProgressionSim.Source> src = ProgressionSim.loadProfile(name);
            long d = 0, f = 0;
            for (long s = 1; s <= SEEDS; s++) {
                d += gather(src, false, s);
                f += gather(src, true, s);
            }
            d /= SEEDS;
            f /= SEEDS;
            System.out.printf(Locale.ROOT, "%-16s  %12s  %12s  %7.0f%%  %s%n", name, Money.format(d), Money.format(f),
                    (f / (double) d - 1) * 100, f > d ? String.format(Locale.ROOT, "%.0f days", floorCost / (double) (f - d)) : "never");
        }
        System.out.println("Target (M5c): early_survival uplift at least 20%.");
        System.out.println();
        System.out.printf(Locale.ROOT, "2. Trader with %s, no gathering: profit a day (holdings at fair value), vs the vault's %s%n",
                Money.format(CAPITAL), Money.format(Math.round(CAPITAL * 0.003)));
        System.out.printf(Locale.ROOT, "%-16s  %10s  %10s  %10s  %11s  %8s%n", "strategy", "avg $/day", "worst seed", "best seed",
                "worst week", "slips/day");
        Map<String, Supplier<Strategy>> strategies = new LinkedHashMap<>();
        strategies.put("inside spread", TraderSim::insideSpread);
        strategies.put("value", TraderSim::value);
        strategies.put("news", TraderSim::news);
        strategies.put("two books", TraderSim::twoBooks);
        strategies.put("buy and hold", TraderSim::buyAndHold);
        for (Map.Entry<String, Supplier<Strategy>> e : strategies.entrySet()) {
            long sum = 0, min = Long.MAX_VALUE, max = Long.MIN_VALUE, worstWeek = Long.MAX_VALUE;
            double slips = 0;
            for (long s = 1; s <= SEEDS; s++) {
                Score r = trade(e.getValue(), s);
                sum += r.perDay();
                min = Math.min(min, r.perDay());
                max = Math.max(max, r.perDay());
                slips += r.slipsPerDay();
                worstWeek = Math.min(worstWeek, r.worstWeek());
            }
            System.out.printf(Locale.ROOT, "%-16s  %10s  %10s  %10s  %11s  %8.1f%n", e.getKey(), signed(sum / SEEDS), signed(min),
                    signed(max), signed(worstWeek), slips / SEEDS);
        }
        System.out.println("Targets (M5c): active strategies average 3-5x the vault ($9-15/day) and can still lose in a bad week;");
        System.out.println("  two books is profitable on average; buy and hold has no edge (it swings with the market).");
    }

    static String signed(long cents) {
        return (cents < 0 ? "-" : "+") + Money.format(Math.abs(cents));
    }
}
