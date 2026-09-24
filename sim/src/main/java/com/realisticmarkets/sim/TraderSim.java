package com.realisticmarkets.sim;

import com.realisticmarkets.agents.FloorCatalog;
import com.realisticmarkets.agents.TradingFloor;
import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.dealer.WorldEvents;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.PriceHistory;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.exchange.Side;
import com.realisticmarkets.money.Money;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Does the Trading Floor make money for a player, beyond being a better place to sell? Runs the real
 * {@link TradingFloor} (NPC books, day orders, custody) against a drifting Dealer and scores player strategies:
 * <ol>
 *   <li><b>Gatherer:</b> a profile's daily output, Floor items sold on the Floor (limit orders repriced each visit,
 *       leftovers to the Dealer at dusk) vs everything to the Dealer. Income uplift and days to pay back the
 *       Floor.</li>
 *   <li><b>Traders</b> with $1,000 and no gathering: quote inside the market maker's spread; buy below the Dealer's
 *       Normal price and sell above it; iron block vs 9 ingots arbitrage. Profit a day vs the Bank Vault's
 *       0.3% a day on the same $1,000.</li>
 * </ol>
 * The player visits the Floor {@link #VISITS_PER_DAY} times a day; every order placed costs an Order Slip.
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
    static final int SEEDS = 5;
    static boolean freeSlips = false; // diagnostic: `sim trader free`

    /** The Floor and Dealer as a player meets them. */
    static final class World {
        final TradingFloor floor;
        final long slipCents;
        double day = 0;
        long slips = 0;

        World(long seed) {
            Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), seed);
            this.dealerRef = d;
            d.setShocks(WorldEvents.loadDefault(d.catalog(), seed ^ 0x4576656E7473L));
            floor = new TradingFloor(new Exchange(), new PriceHistory(), FloorCatalog.loadDefault(), this::dealerFair, seed);
            slipCents = freeSlips ? 0 : Math.round(d.quoteBuy("realisticmarkets:ledger_paper", 1, 0, true).cents() / 8.0);
        }

        final Dealer dealerRef;

        long dealerFair(String item) {
            return Math.max(1, Math.round(dealerRef.fairValue(item, day) * 100));
        }

        long fair(String item) {
            return floor.fairOnFloor(item, dealerFair(item), day);
        }

        void auctions(int n) {
            for (int i = 0; i < n; i++) {
                day += DAY_STEP;
                for (FloorCatalog.Book b : floor.catalog().all()) floor.auction(b.item(), fair(b.item()), DAY_STEP, (long) day);
                if (Math.floor(day) > Math.floor(day - DAY_STEP)) floor.dawn((long) day);
            }
        }

        long[] quotes(String item) {
            return floor.population(item).makerQuotes(fair(item));
        }

        /** Places a day limit order from the player's wallet; false if rejected. */
        boolean limit(Wallet w, String item, Side side, long qty, long price) {
            if (qty <= 0 || price <= 0) return false;
            if (side == Side.BUY && w.cash < qty * price) return false;
            if (side == Side.SELL && w.stock.getOrDefault(item, 0L) < qty) return false;
            try {
                floor.place(ME, item, side, qty, price, false, 0, (long) day);
            } catch (RejectedException e) {
                return false;
            }
            if (side == Side.BUY) w.cash -= qty * price;
            else w.stock.merge(item, -qty, Long::sum);
            slips++;
            w.cash -= slipCents;
            return true;
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

        long value(Wallet w) {
            long v = w.cash;
            for (Map.Entry<String, Long> e : w.stock.entrySet()) v += e.getValue() * fair(e.getKey());
            return v;
        }
    }

    static final class Wallet {
        long cash;
        final Map<String, Long> stock = new LinkedHashMap<>();
    }

    interface Strategy {
        void visit(World w, Wallet me);
    }

    /** Runs a trading strategy for DAYS; returns profit per day in cents (inventory marked at fair value). */
    static long[] trade(Strategy s, long seed) {
        World w = new World(seed);
        Wallet me = new Wallet();
        me.cash = CAPITAL;
        w.auctions(AUCTIONS_PER_DAY); // settle the books first
        long start = w.value(me);
        for (int v = 0; v < DAYS * VISITS_PER_DAY; v++) {
            w.cancelAll();
            w.collect(me);
            s.visit(w, me);
            w.auctions(AUCTIONS_PER_DAY / VISITS_PER_DAY);
        }
        w.cancelAll();
        w.collect(me);
        return new long[] {(w.value(me) - start) / DAYS, w.slips / DAYS};
    }

    /** Quote one cent inside the NPC market maker on every book, holding at most $150 of each item. */
    static final Strategy INSIDE_SPREAD = (w, me) -> {
        for (FloorCatalog.Book b : w.floor.catalog().all()) {
            long[] q = w.quotes(b.item());
            if (q[1] - q[0] < 3) continue;
            long bid = q[0] + 1, ask = q[1] - 1;
            long have = me.stock.getOrDefault(b.item(), 0L);
            long room = Math.max(0, CAP_PER_BOOK / bid - have);
            long size = Math.max(1, b.depth() / SIZE_DIV);
            w.limit(me, b.item(), Side.BUY, Math.min(size, Math.min(room, me.cash / bid)), bid);
            w.limit(me, b.item(), Side.SELL, Math.min(size, have), ask);
        }
    };

    /** Buy 3% under the Dealer's Normal price, sell 1% over it (the Basic Exchange shows Normal). */
    static final Strategy VALUE = (w, me) -> {
        for (FloorCatalog.Book b : w.floor.catalog().all()) {
            long fair = w.fair(b.item());
            long have = me.stock.getOrDefault(b.item(), 0L);
            long bid = Math.max(1, Math.round(fair * 0.97)), ask = Math.round(fair * 1.01);
            long room = Math.max(0, CAP_PER_BOOK / bid - have);
            long size = Math.max(1, b.depth() / SIZE_DIV);
            w.limit(me, b.item(), Side.BUY, Math.min(size, Math.min(room, me.cash / bid)), bid);
            w.limit(me, b.item(), Side.SELL, Math.min(size, have), ask);
        }
    };

    /**
     * Iron block vs 9 ingots: buy whichever side is cheap at its ask, craft (free, instant), and sell at the other
     * book's bid when that pays at least 2% after slips.
     */
    static final Strategy TWO_BOOKS = (w, me) -> {
        String blk = "minecraft:iron_block", ing = "minecraft:iron_ingot";
        // Craft whatever we bought last visit into the form that sells better now, and offer it.
        long[] qb = w.quotes(blk), qi = w.quotes(ing);
        long ingots = me.stock.getOrDefault(ing, 0L), blocks = me.stock.getOrDefault(blk, 0L);
        if (qb[0] > 9 * qi[0]) { // blocks sell better: craft ingots up
            long n = ingots / 9;
            me.stock.merge(ing, -9 * n, Long::sum);
            me.stock.merge(blk, n, Long::sum);
        } else { // ingots sell better
            me.stock.merge(blk, -blocks, Long::sum);
            me.stock.merge(ing, 9 * blocks, Long::sum);
        }
        w.limit(me, blk, Side.SELL, me.stock.getOrDefault(blk, 0L), qb[0]);
        w.limit(me, ing, Side.SELL, me.stock.getOrDefault(ing, 0L), qi[0]);
        long budget = me.cash / 2;
        if (qb[0] > 9 * qi[1] * 1.02) w.limit(me, ing, Side.BUY, Math.min(36, budget / qi[1]) / 9 * 9, qi[1]);
        else if (9 * qi[0] > qb[1] * 1.02) w.limit(me, blk, Side.BUY, Math.min(4, budget / qb[1]), qb[1]);
    };

    /** Gatherer: daily income selling a profile's output, with or without the Floor. */
    static long gather(List<ProgressionSim.Source> sources, boolean useFloor, long seed) {
        World w = new World(seed);
        Wallet me = new Wallet();
        w.auctions(AUCTIONS_PER_DAY);
        long steady = 0;
        int steadyDays = 0;
        for (int day = 1; day <= DAYS; day++) {
            long cash0 = me.cash;
            for (ProgressionSim.Source s : sources) me.stock.merge(s.item(), (long) Math.floor(s.output(day)), Long::sum);
            if (useFloor) {
                for (int v = 0; v < VISITS_PER_DAY; v++) {
                    w.cancelAll();
                    w.collect(me);
                    for (FloorCatalog.Book b : w.floor.catalog().all()) {
                        long have = me.stock.getOrDefault(b.item(), 0L);
                        if (have <= 0) continue;
                        long[] q = w.quotes(b.item());
                        // First visits ask near the middle; later ones step down to the bid.
                        long price = v == 0 ? (q[0] + q[1]) / 2 : q[0];
                        w.limit(me, b.item(), Side.SELL, have, price);
                    }
                    w.auctions(AUCTIONS_PER_DAY / VISITS_PER_DAY);
                }
                w.cancelAll();
                w.collect(me);
            } else {
                w.auctions(AUCTIONS_PER_DAY);
            }
            for (Map.Entry<String, Long> e : new ArrayList<>(me.stock.entrySet())) { // the rest to the Dealer at dusk
                if (e.getValue() <= 0 || !w.dealerRef.catalog().trades(e.getKey())) continue;
                try {
                    me.cash += w.dealerRef.sell(e.getKey(), e.getValue(), w.day, true).cents();
                    me.stock.put(e.getKey(), 0L);
                } catch (RejectedException collapsed) {
                    me.stock.put(e.getKey(), 0L);
                }
            }
            if (day > DAYS / 2) {
                steady += me.cash - cash0;
                steadyDays++;
            }
        }
        return steady / steadyDays;
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) { // diagnostics: free visits=N capital=CENTS size=DIV cap=CENTS
            if (a.equals("free")) freeSlips = true;
            else if (a.startsWith("visits=")) VISITS_PER_DAY = Integer.parseInt(a.substring(7));
            else if (a.startsWith("capital=")) CAPITAL = Long.parseLong(a.substring(8));
            else if (a.startsWith("size=")) SIZE_DIV = Integer.parseInt(a.substring(5));
            else if (a.startsWith("cap=")) CAP_PER_BOOK = Long.parseLong(a.substring(4));
        }
        World probe = new World(1);
        long floorCost = 300_000 + probe.dealerRef.quoteBuy("realisticmarkets:clockwork_gear", 2, 0, true).cents()
                + probe.dealerRef.quoteBuy("minecraft:gold_ingot", 4, 0, true).cents();
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
        System.out.println();
        System.out.printf(Locale.ROOT, "2. Trader with %s, no gathering: profit a day (holdings at fair value), vs the vault's %s%n",
                Money.format(CAPITAL), Money.format(Math.round(CAPITAL * 0.003)));
        System.out.printf(Locale.ROOT, "%-16s  %10s  %10s  %10s  %8s%n", "strategy", "avg $/day", "worst seed", "best seed", "slips/day");
        Map<String, Strategy> strategies = new LinkedHashMap<>();
        strategies.put("inside spread", INSIDE_SPREAD);
        strategies.put("value", VALUE);
        strategies.put("two books", TWO_BOOKS);
        for (Map.Entry<String, Strategy> e : strategies.entrySet()) {
            long sum = 0, min = Long.MAX_VALUE, max = Long.MIN_VALUE, slips = 0;
            for (long s = 1; s <= SEEDS; s++) {
                long[] r = trade(e.getValue(), s);
                sum += r[0];
                min = Math.min(min, r[0]);
                max = Math.max(max, r[0]);
                slips += r[1];
            }
            System.out.printf(Locale.ROOT, "%-16s  %10s  %10s  %10s  %8d%n", e.getKey(), signed(sum / SEEDS), signed(min), signed(max),
                    slips / SEEDS);
        }
    }

    static String signed(long cents) {
        return (cents < 0 ? "-" : "+") + Money.format(Math.abs(cents));
    }
}
