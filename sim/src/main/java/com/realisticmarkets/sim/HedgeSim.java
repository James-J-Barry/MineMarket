package com.realisticmarkets.sim;

import com.realisticmarkets.contracts.ForwardBook;
import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.dealer.WorldEvents;
import com.realisticmarkets.futures.ClearingHouse;
import com.realisticmarkets.money.Money;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Does hedging work? A wheat farmer harvests {@link #HARVEST} wheat every quarter day and sells it to the Dealer.
 * Three ways, each in its own copy of the same world (same seed, same events, same drift):
 * <ul>
 *   <li><b>Unhedged</b>: sells the harvest at the Dealer on harvest day.</li>
 *   <li><b>Forward</b>: a week earlier, signs a forward for the harvest, delivered on harvest day.</li>
 *   <li><b>Futures</b>: a week earlier, sells wheat futures worth what the harvest will fetch (1 lot: selling 512 wheat
 *       into the Dealer pays about 43% of its fair value, so 2 lots would over-hedge); on harvest day sells the harvest at
 *       the Dealer, and the futures settle in cash at the Dealer's fair value.</li>
 * </ul>
 * What a hedge removes is the <i>surprise</i>: the difference between what a harvest fetched and what it looked like
 * fetching a week earlier (the forward price then). Reports that surprise's spread and worst week over {@link #WEEKS}
 * weeks in {@link #WORLDS} worlds, then a forced 20% fall in wheat's fair value three days before one harvest. Last, a
 * speculator who holds wheat futures long on thin margin, rolled every quarter.
 */
public final class HedgeSim {
    static final int WORLDS = 8, WEEKS = 20, HARVEST = 512, WEEK = ClearingHouse.EXPIRY_DAYS;
    static final String WHEAT = "minecraft:wheat";
    static final int LOTS = 1; // the harvest's value at the Dealer, not its face value

    private HedgeSim() {}

    record Weekly(long[] unhedged, long[] forward, long[] futures) {}

    public static void main(String[] args) {
        System.out.printf(Locale.ROOT, "Hedging a wheat harvest: %d wheat every %d days, %d weeks x %d worlds%n", HARVEST, WEEK, WEEKS, WORLDS);
        List<Long> u = new ArrayList<>(), f = new ArrayList<>(), x = new ArrayList<>();
        List<Long> surU = new ArrayList<>(), surF = new ArrayList<>(), surX = new ArrayList<>();
        for (int w = 0; w < WORLDS; w++) {
            Weekly r = run(1000L + w, -1);
            for (int i = 0; i < WEEKS; i++) {
                u.add(r.unhedged()[i]);
                f.add(r.forward()[i]);
                x.add(r.futures()[i]);
                surU.add(r.unhedged()[i] - r.forward()[i]); // the forward price is what a week ahead looked like
                surF.add(0L);
                surX.add(r.futures()[i] - r.forward()[i]);
            }
        }
        System.out.printf(Locale.ROOT, "%-10s  %10s  %10s  %10s  %10s%n", "income", "avg week", "std dev", "worst", "best");
        row("unhedged", u);
        row("forward", f);
        row("futures", x);
        System.out.println();
        System.out.printf(Locale.ROOT, "%-10s  %10s  %10s  %10s  %10s%n", "surprise", "avg", "std dev", "worst", "best");
        row("unhedged", surU);
        row("forward", surF);
        row("futures", surX);
        System.out.println("(surprise: the week's income minus the forward price agreed a week earlier)");

        System.out.println();
        System.out.println("A crash: wheat's fair value falls 20% at dawn 3 days before week 10's harvest (a blight, say).");
        System.out.printf(Locale.ROOT, "%-8s  %12s  %12s  %12s%n", "world", "unhedged", "forward", "futures");
        long su = 0, sf = 0, sx = 0, bu = 0, bf = 0, bx = 0;
        for (int w = 0; w < WORLDS; w++) {
            Weekly base = run(1000L + w, -1), crash = run(1000L + w, 10);
            long du = crash.unhedged()[10] - base.unhedged()[10];
            long df = crash.forward()[10] - base.forward()[10];
            long dx = crash.futures()[10] - base.futures()[10];
            su += du;
            sf += df;
            sx += dx;
            bu += base.unhedged()[10];
            bf += base.forward()[10];
            bx += base.futures()[10];
            System.out.printf(Locale.ROOT, "%-8d  %12s  %12s  %12s%n", w, signed(du), signed(df), signed(dx));
        }
        System.out.printf(Locale.ROOT, "%-8s  %12s  %12s  %12s   (change in that week's income)%n", "average", signed(su / WORLDS),
                signed(sf / WORLDS), signed(sx / WORLDS));
        System.out.printf(Locale.ROOT, "%-8s  %12s  %12s  %12s   (that week's income without the crash)%n", "normal",
                Money.format(bu / WORLDS), Money.format(bf / WORLDS), Money.format(bx / WORLDS));
        System.out.println("Target (design doc M8): the hedges offset the drop; the unhedged farmer takes it.");

        System.out.println();
        speculator();
    }

    static void row(String name, List<Long> weeks) {
        double avg = weeks.stream().mapToLong(Long::longValue).average().orElse(0);
        double var = weeks.stream().mapToDouble(v -> (v - avg) * (v - avg)).average().orElse(0);
        long worst = weeks.stream().mapToLong(Long::longValue).min().orElse(0);
        long best = weeks.stream().mapToLong(Long::longValue).max().orElse(0);
        System.out.printf(Locale.ROOT, "%-10s  %10s  %10s  %10s  %10s%n", name, Money.format(Math.round(avg)),
                Money.format(Math.round(Math.sqrt(var))), Money.format(worst), Money.format(best));
    }

    static String signed(long cents) {
        return (cents < 0 ? "-" : "+") + Money.format(Math.abs(cents));
    }

    static final double CRASH = Math.log(0.8);

    static Dealer world(long seed) {
        return world(seed, Long.MIN_VALUE);
    }

    /** A world with its events, plus a -20% permanent shock to wheat at dawn of {@code crashDay}. */
    static Dealer world(long seed, long crashDay) {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), seed);
        WorldEvents events = WorldEvents.loadDefault(d.catalog(), seed ^ 0x4576656E7473L);
        d.setShocks(new Dealer.Shocks() {
            @Override
            public double permanent(String item, long day) {
                return events.permanent(item, day) + (day == crashDay && item.equals(WHEAT) ? CRASH : 0);
            }

            @Override
            public double fading(String item, double day) {
                return events.fading(item, day);
            }
        });
        return d;
    }

    /** Income (cents) per harvest week for each strategy; {@code crashWeek} >= 0 dumps a glut before that harvest. */
    static Weekly run(long seed, int crashWeek) {
        long crashDay = crashWeek < 0 ? Long.MIN_VALUE : (long) (crashWeek + 2) * WEEK - 3;
        Dealer du = world(seed, crashDay), df = world(seed, crashDay), dx = world(seed, crashDay);
        ForwardBook forwards = new ForwardBook();
        ClearingHouse house = new ClearingHouse(dx);
        house.deposit("farmer", 1_000_000);
        long[] u = new long[WEEKS], f = new long[WEEKS], x = new long[WEEKS];
        for (int w = 0; w < WEEKS; w++) {
            long start = (long) (w + 1) * WEEK, harvest = start + WEEK;
            // A week before the harvest: sign the forward, sell the futures.
            var fwd = forwards.sign("farmer", df, WHEAT, HARVEST, WEEK, start, false);
            long cashBefore = house.account("farmer").cashCents();
            house.trade("farmer", "WHT", harvest, -LOTS, start + 0.1);
            for (long d = start + 1; d <= harvest; d++) house.dawn(d); // daily marks; the last one settles at expiry
            u[w] = du.sell(WHEAT, HARVEST, harvest + 0.2, false).cents();
            f[w] = forwards.deliver(fwd.id(), df, harvest + 0.2) - fwd.depositCents();
            long futuresPnl = house.account("farmer").cashCents() - cashBefore;
            x[w] = dx.sell(WHEAT, HARVEST, harvest + 0.2, false).cents() + futuresPnl;
        }
        return new Weekly(u, f, x);
    }

    /** Long 5 wheat lots on $150 of margin, rolled each quarter: leverage both ways. */
    static void speculator() {
        System.out.println("Speculator: long 5 wheat lots (about $640 of wheat) on $150 of margin (4x), rolled each quarter.");
        List<Long> weeks = new ArrayList<>();
        int calls = 0, closeouts = 0, weeksRun = 0, busts = 0;
        for (int w = 0; w < WORLDS; w++) {
            Dealer d = world(2000L + w);
            ClearingHouse h = new ClearingHouse(d);
            h.deposit("s", 15_000);
            for (int q = 0; q < WEEKS; q++) {
                long start = (long) (q + 1) * WEEK, expiry = start + WEEK;
                long before = h.equity("s", start + 0.1);
                try {
                    h.trade("s", "WHT", expiry, 5, start + 0.1);
                } catch (RuntimeException notEnough) {
                    busts++; // not enough margin left (or in debt): out of the game
                    break;
                }
                for (long day = start + 1; day <= expiry; day++) {
                    for (var dawn : h.dawn(day)) {
                        if (dawn.called()) calls++;
                        if (dawn.closedOut()) closeouts++;
                    }
                }
                weeks.add(h.equity("s", expiry + 0.1) - before);
                weeksRun++;
            }
        }
        long[] arr = weeks.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(arr);
        double avg = Arrays.stream(arr).average().orElse(0);
        System.out.printf(Locale.ROOT, "  average week %s on $150 (%.2f%% a day; the vault pays about 0.30%%), worst %s, best %s%n",
                signed(Math.round(avg)), avg / 15_000.0 / WEEK * 100, signed(arr[0]), signed(arr[arr.length - 1]));
        System.out.printf(Locale.ROOT, "  %d weeks, %d margin calls, %d close-outs, %d of %d worlds bust before week %d%n", weeksRun, calls,
                closeouts, busts, WORLDS, WEEKS);
    }
}
