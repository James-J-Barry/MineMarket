package com.realisticmarkets.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.exchange.Account;
import com.realisticmarkets.exchange.AuctionResult;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.Order;
import com.realisticmarkets.exchange.OrderRequest;
import com.realisticmarkets.exchange.PriceHistory;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.exchange.Side;
import com.realisticmarkets.exchange.TimeInForce;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FloorTest {
    static final String WHEAT = "minecraft:wheat";

    static Agent.Context ctx(Exchange ex, String account, long fair, long ref, List<Long> closes, long seed) {
        return new Agent.Context(WHEAT, fair, ref, closes, ex.account(account), new Random(seed));
    }

    static Exchange funded(String id, long cash, long stock) {
        Exchange ex = new Exchange();
        ex.listInstrument(WHEAT);
        if (cash > 0) ex.deposit(id, cash);
        if (stock > 0) ex.depositPosition(id, WHEAT, stock);
        return ex;
    }

    // ------------------------------------------------------------------ agents

    @Test
    void marketMakerQuotesAroundTheReferenceAndLeansAgainstInventory() {
        MarketMaker mm = new MarketMaker("mm", 0.02, 16, 100);
        long[] flat = mm.quotes(1_000, 100);
        assertTrue(flat[0] < 1_000 && flat[1] > 1_000, "bid below, ask above the reference");
        assertEquals(1_000 - flat[0], flat[1] - 1_000, 1, "symmetric at target inventory");
        long[] longInv = mm.quotes(1_000, 180);
        assertTrue(longInv[0] < flat[0] && longInv[1] < flat[1], "long: both quotes drop to shed stock");
        assertTrue(longInv[1] - longInv[0] > flat[1] - flat[0], "and the spread widens");
        long[] shortInv = mm.quotes(1_000, 20);
        assertTrue(shortInv[0] > flat[0] && shortInv[1] > flat[1], "short: both quotes rise to buy stock back");
    }

    @Test
    void marketMakerOnlyQuotesWhatItCanFund() {
        MarketMaker mm = new MarketMaker("mm", 0.02, 16, 100);
        assertEquals(List.of(), mm.decide(ctx(funded("mm", 0, 0), "mm", 1_000, 1_000, List.of(), 1)));
        List<OrderRequest> sellOnly = mm.decide(ctx(funded("mm", 0, 50), "mm", 1_000, 1_000, List.of(), 1));
        assertEquals(1, sellOnly.size());
        assertEquals(Side.SELL, sellOnly.getFirst().side());
        assertEquals(TimeInForce.IOC, sellOnly.getFirst().timeInForce());
    }

    @Test
    void noiseTradersScatterAroundTheReference() {
        NoiseTrader n = new NoiseTrader("n", 0.02, 8);
        Exchange ex = funded("n", 10_000_000, 1_000);
        int orders = 0;
        Random shared = new Random(11); // one stream, like the population uses
        for (int i = 0; i < 400; i++) {
            for (OrderRequest r : n.decide(new Agent.Context(WHEAT, 1_000, 1_000, List.of(), ex.account("n"), shared))) {
                orders++;
                assertTrue(r.limitPrice() > 900 && r.limitPrice() < 1_100, "within 5 sigma: " + r.limitPrice());
                assertTrue(r.quantity() >= 1 && r.quantity() <= 8);
            }
        }
        assertTrue(orders > 150 && orders < 250, "trades about half the time: " + orders);
        assertEquals(n.decide(ctx(ex, "n", 1_000, 1_000, List.of(), 7)), n.decide(ctx(ex, "n", 1_000, 1_000, List.of(), 7)),
                "seeded: the same run twice gives the same orders");
    }

    @Test
    void fundamentalistsBuyCheapSellDearAndSitOutNearFairValue() {
        Fundamentalist f = new Fundamentalist("f", 0.02, 4);
        Exchange ex = funded("f", 1_000_000, 100);
        assertEquals(List.of(), f.decide(ctx(ex, "f", 1_000, 1_010, List.of(), 1)), "1% off fair: no trade");
        List<OrderRequest> buy = f.decide(ctx(ex, "f", 1_000, 900, List.of(), 1));
        assertEquals(Side.BUY, buy.getFirst().side());
        assertTrue(buy.getFirst().limitPrice() < 1_000, "pays less than fair value");
        List<OrderRequest> sell = f.decide(ctx(ex, "f", 1_000, 1_100, List.of(), 1));
        assertEquals(Side.SELL, sell.getFirst().side());
        assertTrue(sell.getFirst().quantity() > f.decide(ctx(ex, "f", 1_000, 1_030, List.of(), 1)).getFirst().quantity(),
                "a bigger gap, a bigger trade");
    }

    @Test
    void momentumTradersFollowTheTrend() {
        MomentumTrader m = new MomentumTrader("m", 3, 0.01, 4);
        Exchange ex = funded("m", 1_000_000, 100);
        assertEquals(List.of(), m.decide(ctx(ex, "m", 1_000, 1_000, List.of(1_000L, 1_000L), 1)), "not enough history");
        assertEquals(Side.BUY, m.decide(ctx(ex, "m", 1_000, 1_050, List.of(1_000L, 1_010L, 1_030L, 1_050L), 1)).getFirst().side());
        assertEquals(Side.SELL, m.decide(ctx(ex, "m", 1_000, 950, List.of(1_000L, 990L, 970L, 950L), 1)).getFirst().side());
        assertEquals(List.of(), m.decide(ctx(ex, "m", 1_000, 1_002, List.of(1_000L, 1_001L, 999L, 1_002L), 1)));
    }

    @Test
    void aPopulationKeepsTheBookTradingNearFairValue() {
        Exchange ex = new Exchange();
        FloorCatalog.Book book = FloorCatalog.loadDefault().book(WHEAT);
        AgentPopulation pop = new AgentPopulation(ex, book, 50, 42);
        int traded = 0;
        double err = 0;
        for (int i = 0; i < 200; i++) {
            pop.submitOrders(50);
            AuctionResult r = ex.runAuction(WHEAT);
            pop.onAuction(r);
            if (r.traded()) {
                traded++;
                err += Math.abs(r.clearingPrice().getAsLong() - 50) / 50.0;
            }
        }
        assertTrue(traded > 150, "trades in most auctions with no player: " + traded + "/200");
        assertTrue(err / traded < 0.06, "average distance from fair value " + err / traded);
    }

    @Test
    void npcHoldingsDriftBackToBaselineOverDays() {
        Exchange ex = new Exchange();
        FloorCatalog.Book book = FloorCatalog.loadDefault().book(WHEAT);
        AgentPopulation pop = new AgentPopulation(ex, book, 50, 1);
        String mm = pop.agents().getFirst().accountId();
        long baseline = ex.account(mm).cash();
        ex.withdrawCash(mm, baseline - 1_000); // a player drained its cash
        pop.recover(2, 50);
        long after = ex.account(mm).cash();
        assertEquals(baseline - (baseline - 1_000) * Math.exp(-1), after, baseline * 0.01, "one recovery time closes ~63% of the gap");
        pop.recover(20, 50);
        assertEquals(baseline, ex.account(mm).cash(), baseline * 0.01);
    }

    // ------------------------------------------------------------------ exchange additions

    @Test
    void dayOrdersRestThenExpireWithAFullRefund() {
        Exchange ex = funded("player", 10_000, 0);
        Order o = ex.submit(OrderRequest.day("player", WHEAT, Side.BUY, 100, 40));
        assertEquals(4_000, ex.account("player").lockedCash());
        ex.runAuction(WHEAT); // nobody sells
        assertTrue(ex.liveOrder(o.id()).isPresent(), "a day order rests between auctions");
        List<Order> expired = ex.expire(TimeInForce.DAY);
        assertEquals(List.of(o), expired);
        assertEquals(10_000, ex.account("player").cash(), "all escrow back");
        assertEquals(0, ex.account("player").lockedCash());
        assertFalse(ex.liveOrder(o.id()).isPresent());
    }

    @Test
    void custodyNeverCreatesOrDestroysMoneyOrItems() {
        Exchange ex = new Exchange();
        AgentPopulation pop = new AgentPopulation(ex, FloorCatalog.loadDefault().book(WHEAT), 50, 9);
        Random rnd = new Random(3);
        ex.deposit("player", 50_000);
        ex.depositPosition("player", WHEAT, 2_000);
        for (int i = 0; i < 300; i++) {
            pop.submitOrders(50);
            try {
                if (rnd.nextBoolean()) ex.submit(OrderRequest.day("player", WHEAT, Side.BUY, 1 + rnd.nextInt(50), 40 + rnd.nextInt(20)));
                else ex.submit(OrderRequest.day("player", WHEAT, Side.SELL, 1 + rnd.nextInt(50), 40 + rnd.nextInt(20)));
            } catch (RejectedException broke) {
                // out of money or wheat
            }
            pop.onAuction(ex.runAuction(WHEAT));
            if (i % 30 == 29) {
                ex.expire(TimeInForce.DAY);
                pop.recover(1, 50);
                Account p = ex.account("player");
                if (p.cash() > 100) ex.withdrawCash("player", p.cash() / 2);
                if (p.position(WHEAT) > 10) ex.withdrawPosition("player", WHEAT, p.position(WHEAT) / 2);
            }
            long cash = ex.accounts().stream().mapToLong(a -> a.cash() + a.lockedCash()).sum();
            long wheat = ex.accounts().stream().mapToLong(a -> a.position(WHEAT) + a.lockedPosition(WHEAT)).sum();
            assertEquals(ex.netCashIn(), cash, "cash held = cash in - cash out, after step " + i);
            assertEquals(ex.netItemsIn(WHEAT), wheat, "wheat held = wheat in - wheat out, after step " + i);
        }
    }

    @Test
    void exchangeStateRoundTrips() throws Exception {
        Exchange ex = new Exchange();
        AgentPopulation pop = new AgentPopulation(ex, FloorCatalog.loadDefault().book(WHEAT), 50, 5);
        ex.deposit("player", 9_000);
        ex.depositPosition("player", WHEAT, 64);
        for (int i = 0; i < 20; i++) {
            pop.submitOrders(50);
            pop.onAuction(ex.runAuction(WHEAT));
        }
        Order resting = ex.submit(OrderRequest.day("player", WHEAT, Side.BUY, 30, 30));
        ex.submit(OrderRequest.limit("player", WHEAT, Side.SELL, 10, 90));
        StringWriter w = new StringWriter();
        ex.write(w);
        Exchange back = Exchange.read(new StringReader(w.toString()));
        StringWriter again = new StringWriter();
        back.write(again);
        assertEquals(w.toString(), again.toString(), "a reload writes exactly the same state");
        assertEquals(ex.lastPrice(WHEAT), back.lastPrice(WHEAT));
        assertEquals(TimeInForce.DAY, back.liveOrder(resting.id()).orElseThrow().timeInForce());
        assertEquals(ex.netCashIn(), back.netCashIn());
        // The reloaded book keeps trading, and new orders get fresh ids.
        Order next = back.submit(OrderRequest.ioc("player", WHEAT, Side.SELL, 1, 1));
        assertTrue(next.id() > resting.id());
    }

    @Test
    void priceHistoryKeepsDailyBars() throws Exception {
        PriceHistory h = new PriceHistory();
        h.record(WHEAT, 3, 50, 10);
        h.record(WHEAT, 3, 56, 5);
        h.record(WHEAT, 3, 47, 2);
        h.record(WHEAT, 3, 52, 1);
        h.record(WHEAT, 5, 60, 4);
        var bars = h.lastDays(WHEAT, 9, 7);
        assertEquals(2, bars.size(), "days with no trades are skipped");
        assertEquals(new PriceHistory.Bar(3, 50, 56, 47, 52, 18), bars.getFirst());
        assertEquals(List.of(), h.lastDays(WHEAT, 20, 7), "older than a week");
        for (int d = 0; d < 40; d++) h.record("x", d, 1, 1);
        assertEquals(1, h.lastDays("x", 39, 7).getFirst().close());
        assertEquals(PriceHistory.KEEP_DAYS, h.lastDays("x", 39, 100).size(), "only the last 30 days are kept");
        StringWriter w = new StringWriter();
        h.write(w);
        assertEquals(h, PriceHistory.read(new StringReader(w.toString())));
    }
}
