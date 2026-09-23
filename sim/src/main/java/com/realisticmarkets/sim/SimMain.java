package com.realisticmarkets.sim;

import com.realisticmarkets.exchange.AuctionResult;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.OrderRequest;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.exchange.Side;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Headless market simulation: zero-intelligence traders (Gode & Sunder style) quoting IOC orders
 * around a fundamental value that follows a random walk. Writes one CSV row per auction.
 *
 * <p>Usage: {@code ./scripts/dev.sh sim [steps] [seed] [out.csv]}
 *
 * <p>This is the playground for bot and pricing work: iterate here in seconds, then port what
 * works into the mod.
 */
public final class SimMain {

    public static void main(String[] args) throws IOException {
        int steps = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 42L;
        Path out = Path.of(args.length > 2 ? args[2] : "build/sim/diamond.csv");

        String ins = "DIAMOND";
        int traders = 40;
        Random rnd = new Random(seed);
        Exchange ex = new Exchange();
        ex.listInstrument(ins);
        for (int i = 0; i < traders; i++) {
            ex.deposit("t" + i, 1_000_000);
            ex.depositPosition("t" + i, ins, 500);
        }

        double fundamental = 100.0;
        long totalVolume = 0;
        int tradedSteps = 0;

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("step,fundamental,clearing_price,volume");
            for (int step = 0; step < steps; step++) {
                fundamental = Math.max(5, fundamental * Math.exp(0.01 * rnd.nextGaussian()));
                for (int i = 0; i < traders; i++) {
                    if (rnd.nextDouble() > 0.5) continue;
                    Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                    // Each trader has a noisy private estimate of value and shades their quote.
                    double estimate = fundamental * (1 + 0.03 * rnd.nextGaussian());
                    double shade = side == Side.BUY ? 1 - 0.02 * rnd.nextDouble() : 1 + 0.02 * rnd.nextDouble();
                    long price = Math.max(1, Math.round(estimate * shade));
                    long qty = 1 + rnd.nextInt(10);
                    try {
                        ex.submit(OrderRequest.ioc("t" + i, ins, side, qty, price));
                    } catch (RejectedException ignored) {
                        // trader is out of cash or diamonds
                    }
                }
                AuctionResult r = ex.runAuction(ins);
                if (r.traded()) {
                    tradedSteps++;
                    totalVolume += r.volume();
                }
                w.printf("%d,%.2f,%s,%d%n", step, fundamental,
                        r.clearingPrice().isPresent() ? Long.toString(r.clearingPrice().getAsLong()) : "",
                        r.volume());
            }
        }

        System.out.printf("Simulated %d auctions: %d traded, total volume %d, last price %s, final fundamental %.2f%n",
                steps, tradedSteps, totalVolume,
                ex.lastPrice(ins).isPresent() ? ex.lastPrice(ins).getAsLong() : "n/a", fundamental);
        System.out.println("CSV written to " + out.toAbsolutePath());
    }
}
