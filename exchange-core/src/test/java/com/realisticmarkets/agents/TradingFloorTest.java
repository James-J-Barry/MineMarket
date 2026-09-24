package com.realisticmarkets.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.PriceHistory;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.exchange.Side;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TradingFloorTest {
    static final String WHEAT = "minecraft:wheat";
    static final long FAIR = 50;

    static TradingFloor floor() {
        return new TradingFloor(new Exchange(), new PriceHistory(), FloorCatalog.loadDefault(), item -> FAIR, 7);
    }

    static List<TradingFloor.Receipt> run(TradingFloor f, int auctions, long day) {
        List<TradingFloor.Receipt> out = new ArrayList<>();
        for (int i = 0; i < auctions; i++) out.addAll(f.auction(WHEAT, FAIR, 1.0 / 120, day));
        return out;
    }

    @Test
    void aSellOrderFillsAgainstNpcsAndThePlayerCollectsCash() {
        TradingFloor f = floor();
        TradingFloor.Ticket t = f.place("p", WHEAT, Side.SELL, 32, 48, false, 450, 1);
        List<TradingFloor.Receipt> receipts = run(f, 60, 1);
        assertEquals(1, receipts.size(), "one receipt when the order finishes");
        TradingFloor.Receipt r = receipts.getFirst();
        assertEquals(TradingFloor.Ending.FILLED, r.ending());
        assertEquals(32, r.filledQty());
        assertTrue(r.avgMills() >= 480, "at or above the limit: " + r.avgMills());
        TradingFloor.Pickup p = f.available("p");
        assertEquals(r.filledCents() / 10 * 10, p.cents());
        assertEquals(Map.of(), p.items());
        f.collect("p", p);
        assertTrue(f.available("p").isEmpty());
        assertTrue(f.openTickets("p").isEmpty());
        assertTrue(t.filledQty() == 32);
    }

    @Test
    void aBuyLimitBelowTheMarketRestsUntilDawnThenIsRefunded() {
        TradingFloor f = floor();
        f.place("p", WHEAT, Side.BUY, 20, 25, false, 450, 1); // half of fair: nobody sells that low
        assertTrue(run(f, 30, 1).isEmpty(), "still resting");
        assertEquals(1, f.openTickets("p").size());
        List<TradingFloor.Receipt> expired = f.dawn(2);
        assertEquals(TradingFloor.Ending.EXPIRED, expired.getFirst().ending());
        assertEquals(0, expired.getFirst().filledQty());
        assertEquals(500, f.available("p").cents(), "the full $5 escrow comes back");
    }

    @Test
    void aMarketOrderFillsOrIsRefundedInTheNextAuction() {
        TradingFloor f = floor();
        long limit = f.buyLimit(WHEAT, FAIR, true, 0);
        assertEquals(75, limit, "market buys escrow 50% above the reference");
        f.place("p", WHEAT, Side.BUY, 8, limit, true, 450, 1);
        List<TradingFloor.Receipt> r = f.auction(WHEAT, FAIR, 1.0 / 120, 1);
        assertEquals(1, r.size(), "finished after one auction, whatever happened");
        TradingFloor.Receipt got = r.getFirst();
        TradingFloor.Pickup p = f.available("p");
        assertEquals(got.filledQty(), p.items().getOrDefault(WHEAT, 0L));
        assertEquals(8 * limit - got.filledCents(), f.exchange().account("p").cash(), "everything not spent comes back");
    }

    @Test
    void cancellingReturnsEverything() {
        TradingFloor f = floor();
        TradingFloor.Ticket t = f.place("p", WHEAT, Side.SELL, 40, 99, false, 450, 1);
        TradingFloor.Receipt r = f.cancel("p", t.orderId(), 1);
        assertEquals(TradingFloor.Ending.CANCELLED, r.ending());
        assertEquals(Map.of(WHEAT, 40L), f.available("p").items());
        assertThrows(RejectedException.class, () -> f.cancel("someone else", t.orderId(), 1));
    }

    @Test
    void onlyFloorBooksTrade() {
        assertThrows(RejectedException.class, () -> floor().place("p", "minecraft:dirt", Side.SELL, 1, 1, false, 0, 1));
    }

    @Test
    void ticketsSurviveAReload() throws Exception {
        TradingFloor f = floor();
        f.place("p", WHEAT, Side.BUY, 20, 25, false, 450, 1);
        StringWriter ex = new StringWriter(), hist = new StringWriter(), tickets = new StringWriter();
        f.exchange().write(ex);
        f.history().write(hist);
        f.writeTickets(tickets);
        TradingFloor back = new TradingFloor(Exchange.read(new StringReader(ex.toString())),
                PriceHistory.read(new StringReader(hist.toString())), FloorCatalog.loadDefault(), item -> FAIR, 7);
        back.readTickets(new StringReader(tickets.toString()));
        assertEquals(1, back.openTickets("p").size());
        assertEquals(500, back.exchange().account("p").lockedCash(), "escrow survives");
        assertEquals(TradingFloor.Ending.EXPIRED, back.dawn(2).getFirst().ending());
    }
}
