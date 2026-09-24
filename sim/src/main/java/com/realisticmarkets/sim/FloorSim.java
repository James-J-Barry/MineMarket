package com.realisticmarkets.sim;

import com.realisticmarkets.agents.AgentPopulation;
import com.realisticmarkets.agents.FloorCatalog;
import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.exchange.AuctionResult;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.OrderRequest;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.exchange.Side;
import com.realisticmarkets.money.Money;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * M5 balance check for the Trading Floor, per book:
 * <ol>
 *   <li>30 in-game days with no player: share of auctions that trade, the market maker's average spread, and the
 *       average distance of the clearing price from the Dealer's (drifting) fair value;</li>
 *   <li>a player selling 64 and 1,024 as market orders (in chunks, one per auction) vs selling to the Dealer;</li>
 *   <li>round trips between the Floor and the Dealer (licensed), which must lose money.</li>
 * </ol>
 * Targets (M5 spec): trades in most auctions; spread 2-6%; within about 5% of fair value; 64 on the Floor pays more
 * than the Dealer; 1,024 pays less than 1,024 x the Floor's price; every round trip loses.
 */
public final class FloorSim {
    static final int AUCTIONS_PER_DAY = 120; // one every 10 s of a 20-minute day
    static final double DAY_STEP = 1.0 / AUCTIONS_PER_DAY;
    static final int DAYS = 30;

    record Market(Exchange ex, AgentPopulation pop, Dealer dealer, String item, double[] day) {
        long fair() {
            return Math.max(1, Math.round(dealer.fairValue(item, day[0]) * 100));
        }

        AuctionResult step() {
            day[0] += DAY_STEP;
            long fair = fair();
            pop.recover(DAY_STEP, fair);
            pop.submitOrders(fair);
            AuctionResult r = ex.runAuction(item);
            pop.onAuction(r);
            return r;
        }

        long price() {
            return ex.lastPrice(item).orElse(fair());
        }
    }

    static Market market(FloorCatalog.Book book, long seed) {
        Dealer dealer = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), seed);
        dealer.setShocks(com.realisticmarkets.dealer.WorldEvents.loadDefault(dealer.catalog(), seed));
        Exchange ex = new Exchange();
        long fair = Math.round(dealer.fairValue(book.item(), 0) * 100);
        return new Market(ex, new AgentPopulation(ex, book, fair, seed), dealer, book.item(), new double[] {0});
    }

    record Traded(long cents, long qty) {
        double avg() { return qty == 0 ? 0 : cents / (double) qty; }
    }

    /** Market-sells or buys {@code qty} in chunks of {@code chunk}, one IOC order per auction, for up to a day. */
    static Traded trade(Market m, Side side, long qty, long chunk) {
        String me = "player-" + side;
        if (side == Side.SELL) m.ex().depositPosition(me, m.item(), qty);
        else m.ex().deposit(me, 1_000_000_000L);
        long cash0 = m.ex().account(me).cash();
        long pos0 = m.ex().account(me).position(m.item());
        for (int i = 0; i < AUCTIONS_PER_DAY; i++) {
            long done = Math.abs(m.ex().account(me).position(m.item()) - pos0);
            if (done >= qty) break;
            long q = Math.min(chunk, qty - done);
            long limit = side == Side.SELL ? 1 : Math.max(2, m.price() * 3);
            try {
                m.ex().submit(OrderRequest.ioc(me, m.item(), side, q, limit));
            } catch (RejectedException e) {
                break;
            }
            m.step();
        }
        long cash1 = m.ex().account(me).cash();
        long done = Math.abs(m.ex().account(me).position(m.item()) - pos0);
        return new Traded(side == Side.SELL ? cash1 - cash0 : cash0 - cash1, done);
    }

    public static void main(String[] args) throws Exception {
        FloorCatalog catalog = FloorCatalog.loadDefault();
        Path out = Path.of("build/sim/floor.csv");
        Files.createDirectories(out.toAbsolutePath().getParent());
        System.out.printf(Locale.ROOT, "Trading Floor, %d books, %d in-game days with no player (%d auctions a day)%n",
                catalog.all().size(), DAYS, AUCTIONS_PER_DAY);
        System.out.printf(Locale.ROOT, "%-12s %7s %7s %8s | %10s %10s | %16s | %s%n", "book", "traded", "spread", "vs fair",
                "64: floor", "64: dealer", "1024: avg/start", "round trips");
        boolean allOk = true;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("item,traded_share,avg_spread,avg_fair_error,sell64_floor,sell64_dealer,sell1024_cents,sell1024_qty,loop_floor_to_dealer,loop_dealer_to_floor");
            long seed = 1;
            for (FloorCatalog.Book book : catalog.all()) {
                Market m = market(book, seed++);
                int traded = 0, n = 0;
                double spread = 0, err = 0;
                for (int i = 0; i < DAYS * AUCTIONS_PER_DAY; i++) {
                    long fair = m.fair();
                    long[] q = m.pop().makerQuotes(fair);
                    spread += (q[1] - q[0]) / ((q[0] + q[1]) / 2.0);
                    AuctionResult r = m.step();
                    n++;
                    if (r.traded()) {
                        traded++;
                        err += Math.abs(r.clearingPrice().getAsLong() - fair) / (double) fair;
                    }
                }
                double tradedShare = traded / (double) n;
                double avgSpread = spread / n;
                double avgErr = traded == 0 ? 1 : err / traded;

                long chunk = Math.max(1, book.depth() / 16);
                Market s64 = market(book, seed);
                Traded f64 = trade(s64, Side.SELL, 64, chunk);
                long d64 = s64.dealer().quoteSell(book.item(), 64, 0, false).cents();
                Market s1k = market(book, seed);
                long start = s1k.price();
                Traded f1k = trade(s1k, Side.SELL, 1_024, chunk);
                double impact = f1k.avg() / start; // average price received vs the price before selling

                // Round trips on 16 items, licensed at the Dealer (the best a player can do there).
                Market a = market(book, seed + 100);
                Traded bought = trade(a, Side.BUY, 16, 16);
                long loopFloorToDealer = a.dealer().quoteSell(book.item(), bought.qty(), a.day()[0], true).cents() - bought.cents();
                Market b = market(book, seed + 200);
                long cost = b.dealer().quoteBuy(book.item(), 16, 0, true).cents();
                long loopDealerToFloor = trade(b, Side.SELL, 16, 16).cents() - cost;

                boolean ok = tradedShare > 0.5 && avgSpread >= 0.02 && avgSpread <= 0.06 && avgErr <= 0.05
                        && f64.qty() == 64 && f64.cents() > d64 && impact < 1 && loopFloorToDealer < 0 && loopDealerToFloor < 0;
                allOk &= ok;
                System.out.printf(Locale.ROOT, "%-12s %6.0f%% %6.1f%% %7.1f%% | %10s %10s | %4d sold, %4.0f%% | %s %s%s%n",
                        book.item().replace("minecraft:", ""), tradedShare * 100, avgSpread * 100, avgErr * 100,
                        Money.format(f64.cents()), Money.format(d64), f1k.qty(), impact * 100,
                        Money.format(Math.abs(loopFloorToDealer)).replace("$", loopFloorToDealer < 0 ? "-$" : "+$"),
                        Money.format(Math.abs(loopDealerToFloor)).replace("$", loopDealerToFloor < 0 ? "-$" : "+$"),
                        ok ? "" : "   <-- off target");
                w.printf(Locale.ROOT, "%s,%.3f,%.4f,%.4f,%d,%d,%d,%d,%d,%d%n", book.item(), tradedShare, avgSpread, avgErr,
                        f64.cents(), d64, f1k.cents(), f1k.qty(), loopFloorToDealer, loopDealerToFloor);
            }
        }
        System.out.println("Round trips: buy 16 on the Floor and sell to the Dealer / buy 16 from the Dealer and sell on the Floor.");
        System.out.println("1024: how many sold within a day in chunks, and the average price as a share of the price before selling.");
        System.out.println("Targets: traded > 50%, spread 2-6%, within 5% of fair, 64 beats the Dealer, 1,024 shows impact, loops lose.");
        System.out.println(allOk ? "All books on target." : "Some books are off target (marked).");
        System.out.println("CSV: " + out.toAbsolutePath());
    }
}
