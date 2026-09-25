package com.realisticmarkets.sim;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.dealer.WorldEvents;
import com.realisticmarkets.futures.ClearingHouse;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.options.OptionDesk;
import com.realisticmarkets.options.WrittenBook;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Options in play. First the design doc's done-when: the premium for writing a wheat call, covered by wheat, backed by
 * cash, and backed by weak collateral. Then, over 20 weeks in 8 worlds, each week at the quarter day:
 * <ul>
 *   <li>buying an at-the-money call or put and holding it to expiry (what it pays vs what it cost);</li>
 *   <li>a covered-call writer (holds 256 wheat, writes a 110% call each week) vs just holding the wheat;</li>
 *   <li>a farmer with a 512-wheat harvest who buys a 90% put each week, vs not insuring.</li>
 * </ul>
 */
public final class OptionsSim {
    static final int WORLDS = 32, WEEKS = 40, WEEK = ClearingHouse.EXPIRY_DAYS;
    static final String WHEAT = "minecraft:wheat";

    private OptionsSim() {}

    /** A desk priced off a Dealer, with the rate flat at 0.3% a day. */
    static OptionDesk desk(Dealer d) {
        return new OptionDesk(new OptionDesk.Market() {
            public double spotCents(String u, double day) {
                var p = ClearingHouse.product(u);
                return d.fairValue(p.item(), day) * p.lot() * 100.0;
            }
            public double forwardCents(String u, long expiry, double day) {
                var p = ClearingHouse.product(u);
                return d.expectedFair(p.item(), day, Math.max(expiry, day)) * p.lot() * 100.0;
            }
            public double rate(double day) { return 0.003; }
        });
    }

    static Dealer world(long seed) {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), seed);
        d.setShocks(WorldEvents.loadDefault(d.catalog(), seed ^ 0x4576656E7473L));
        return d;
    }

    public static void main(String[] args) {
        doneWhen();
        System.out.println();
        weeks();
    }

    static void doneWhen() {
        Dealer d = world(1);
        OptionDesk desk = desk(d);
        double day = 7.2;
        double f = desk.market().forwardCents("WHT", 14, day);
        var call = new OptionDesk.Series("WHT", true, Math.round(f * 1.05 / 100) * 100, 14);
        System.out.printf(Locale.ROOT, "Writing one wheat call (strike %s, a lot at %s), design doc done-when:%n",
                Money.format(call.strikeCents()), Money.format(Math.round(f)));
        row(desk, d, call, "covered by 256 wheat", Map.of(WHEAT, 256), 0, day);
        row(desk, d, call, "naked, $50 cash", Map.of(), 5_000, day);
        row(desk, d, call, "naked, 3 diamonds", Map.of("minecraft:diamond", 3), 0, day);
        row(desk, d, call, "naked, 192 oak logs", Map.of("minecraft:oak_log", 192), 0, day);
        row(desk, d, call, "naked, 1,500 oak logs", Map.of("minecraft:oak_log", 1_500), 0, day);
        System.out.println("Target: the covered call pays the full premium; naked calls pay less the weaker the collateral.");
    }

    static void row(OptionDesk desk, Dealer d, OptionDesk.Series s, String label, Map<String, Integer> items, long cash, double day) {
        var t = WrittenBook.terms(desk, d, s, 1, items, cash, day);
        System.out.printf(Locale.ROOT, "  %-24s premium %8s   quality %.2f   needs %8s   has %8s%n", label, Money.format(t.premiumCents()),
                t.quality(), Money.format(t.requiredCents()), Money.format(t.collateralCents()));
    }

    static void weeks() {
        List<Double> callRet = new ArrayList<>(), putRet = new ArrayList<>(), ccWeek = new ArrayList<>(), holdWeek = new ArrayList<>();
        List<Double> insured = new ArrayList<>(), bare = new ArrayList<>();
        int callsWorthless = 0, putsWorthless = 0, calledAway = 0;
        long callSpent = 0, callPaid = 0, putSpent = 0, putPaid = 0;
        for (int w = 0; w < WORLDS; w++) {
            Dealer d = world(3000L + w);
            OptionDesk desk = desk(d);
            long observed = 0;
            for (int q = 4; q < 4 + WEEKS; q++) { // four weeks of closes first, as a running desk would have
                long start = (long) q * WEEK, expiry = start + WEEK;
                double day = start + 0.2;
                while (observed < start) {
                    observed++;
                    desk.observe("WHT", observed, desk.market().spotCents("WHT", observed),
                            desk.market().forwardCents("WHT", observed + OptionDesk.HORIZON, observed)); // each dawn, in order
                }
                double f = desk.market().forwardCents("WHT", expiry, day);
                long atm = Math.round(f / 100) * 100;
                var call = new OptionDesk.Series("WHT", true, atm, expiry);
                var put = new OptionDesk.Series("WHT", false, atm, expiry);
                var otmCall = new OptionDesk.Series("WHT", true, Math.round(f * 1.1 / 100) * 100, expiry);
                var insurance = new OptionDesk.Series("WHT", false, Math.round(f * 0.9 / 100) * 100, expiry);
                long callCost = desk.ask("s", call, day), putCost = desk.ask("s", put, day), insCost = desk.ask("s", insurance, day);
                long premium = WrittenBook.terms(desk, d, otmCall, 1, Map.of(WHEAT, 256), 0, day).premiumCents();
                double lotNow = desk.market().spotCents("WHT", day);
                while (observed < expiry) {
                    observed++;
                    desk.observe("WHT", observed, desk.market().spotCents("WHT", observed),
                            desk.market().forwardCents("WHT", observed + OptionDesk.HORIZON, observed));
                }
                long settle = Math.round(desk.market().spotCents("WHT", expiry));
                long callPays = call.intrinsic(settle), putPays = put.intrinsic(settle);
                callSpent += callCost;
                callPaid += callPays;
                putSpent += putCost;
                putPaid += putPays;
                callRet.add(callPays / (double) callCost - 1);
                putRet.add(putPays / (double) putCost - 1);
                if (callPays == 0) callsWorthless++;
                if (putPays == 0) putsWorthless++;
                // Covered call: hold the lot, collect the premium, give up the gain above the strike.
                long capped = Math.min(settle, otmCall.strikeCents());
                if (settle > otmCall.strikeCents()) calledAway++;
                ccWeek.add((capped + premium) / lotNow - 1);
                holdWeek.add(settle / lotNow - 1);
                // The farmer: a harvest of 512 wheat (2 lots of value at the Dealer's fair) sold at expiry.
                double expected = 2 * f; // the harvest's value a week earlier
                long harvest = settle * 2;
                bare.add(harvest / expected);
                insured.add((harvest + 2 * insurance.intrinsic(settle) - 2 * insCost) / expected);
            }
        }
        System.out.printf(Locale.ROOT, "Buying at the money and holding to expiry (%d weeks):%n", callRet.size());
        System.out.printf(Locale.ROOT, "  calls: %+.0f%% on the money spent (average week %+.0f%%), %d%% expire worthless, best %+.0f%%%n",
                (callPaid / (double) callSpent - 1) * 100, avg(callRet) * 100, callsWorthless * 100 / callRet.size(), max(callRet) * 100);
        System.out.printf(Locale.ROOT, "  puts:  %+.0f%% on the money spent (average week %+.0f%%), %d%% expire worthless, best %+.0f%%%n",
                (putPaid / (double) putSpent - 1) * 100, avg(putRet) * 100, putsWorthless * 100 / putRet.size(), max(putRet) * 100);
        System.out.println("  (priced fairly, the spread makes buying a little negative on average: options are insurance or a bet)");
        System.out.printf(Locale.ROOT, "Covered calls on a lot of wheat (110%% strike each week) vs holding it:%n");
        System.out.printf(Locale.ROOT, "  covered: %+.2f%% a week, worst %+.1f%%, best %+.1f%%, called away %d%% of weeks%n", avg(ccWeek) * 100,
                min(ccWeek) * 100, max(ccWeek) * 100, calledAway * 100 / ccWeek.size());
        System.out.printf(Locale.ROOT, "  holding: %+.2f%% a week, worst %+.1f%%, best %+.1f%%%n", avg(holdWeek) * 100, min(holdWeek) * 100,
                max(holdWeek) * 100);
        System.out.printf(Locale.ROOT, "A farmer's 2-lot harvest, insured each week with a 90%% put vs not (as %% of its value a week earlier):%n");
        System.out.printf(Locale.ROOT, "  insured:   average %.1f%%, worst week %.1f%%%n", avg(insured) * 100, min(insured) * 100);
        System.out.printf(Locale.ROOT, "  uninsured: average %.1f%%, worst week %.1f%%%n", avg(bare) * 100, min(bare) * 100);
    }

    static double avg(List<Double> xs) { return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0); }
    static double min(List<Double> xs) { return xs.stream().mapToDouble(Double::doubleValue).min().orElse(0); }
    static double max(List<Double> xs) { return xs.stream().mapToDouble(Double::doubleValue).max().orElse(0); }
}
