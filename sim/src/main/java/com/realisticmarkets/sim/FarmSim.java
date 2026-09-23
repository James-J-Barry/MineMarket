package com.realisticmarkets.sim;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.money.Money;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Balance check for the Dealer: a player with a farm of a given size sells its whole daily output
 * once per in-game day. Reports steady-state dollars per day for each farm size.
 *
 * <p>Design target (doc, "Why farms can't break it"): wheat peaks around $21/day near 128/day,
 * and bigger farms earn less, not more.
 */
public final class FarmSim {

    public static void main(String[] args) throws Exception {
        String item = DealerCatalog.normalize(args.length > 0 ? args[0] : "wheat");
        int days = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        int[] sizes = {16, 32, 64, 96, 128, 192, 256, 512, 1024, 4096};
        Path out = Path.of("build/sim/farm_" + item.replace(':', '_') + ".csv");
        Files.createDirectories(out.toAbsolutePath().getParent());

        System.out.printf(Locale.ROOT, "Farm income for %s, selling once per in-game day, %d days (steady state = last half)%n", item, days);
        System.out.printf(Locale.ROOT, "%10s  %14s  %16s%n", "units/day", "$/day steady", "bid before sale");
        double bestIncome = -1;
        int bestSize = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("units_per_day,day,proceeds_cents,bid_before");
            for (int size : sizes) {
                Dealer dealer = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 7L);
                long steadyCents = 0;
                double lastBid = 0;
                for (int d = 1; d <= days; d++) {
                    double bid = dealer.bid(item, d, false);
                    long cents;
                    try {
                        cents = dealer.sell(item, size, d, false).cents();
                    } catch (RejectedException collapsed) {
                        cents = 0;
                    }
                    w.printf(Locale.ROOT, "%d,%d,%d,%.4f%n", size, d, cents, bid);
                    if (d > days / 2) {
                        steadyCents += cents;
                        lastBid = bid;
                    }
                }
                double perDay = steadyCents / 100.0 / (days - days / 2);
                if (perDay > bestIncome) {
                    bestIncome = perDay;
                    bestSize = size;
                }
                System.out.printf(Locale.ROOT, "%10d  %14s  %16.3f%n", size, Money.format(Math.round(perDay * 100)), lastBid);
            }
        }
        System.out.printf(Locale.ROOT, "Best: %d units/day earns about %s/day. CSV: %s%n",
                bestSize, Money.format(Math.round(bestIncome * 100)), out.toAbsolutePath());
    }
}
