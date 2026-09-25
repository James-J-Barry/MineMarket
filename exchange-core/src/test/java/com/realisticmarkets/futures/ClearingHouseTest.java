package com.realisticmarkets.futures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.exchange.RejectedException;
import java.io.StringReader;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class ClearingHouseTest {
    static final String WHEAT = "minecraft:wheat";

    static Dealer dealer() {
        return new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 42L);
    }

    @Test
    void twoQuarterlyExpiriesAndThePriceIsFairValueCarriedToExpiry() {
        assertArrayEquals(new long[] {14, 21}, ClearingHouse.expiries(10));
        assertArrayEquals(new long[] {21, 28}, ClearingHouse.expiries(14), "an expiry day lists the next two");
        Dealer d = dealer();
        ClearingHouse h = new ClearingHouse(d);
        assertEquals(d.expectedFair(WHEAT, 10.5, 14) * 256 * 100 * Math.exp(d.intradayNoise(WHEAT, 10.5)), h.price("WHT", 14, 10.5), 1e-6,
                "256 wheat at the value expected on day 14 (and the day's noise)");
        Dealer fresh = dealer();
        assertEquals(fresh.fairValue(WHEAT, 0) * Math.exp(0.001 * 14), fresh.expectedFair(WHEAT, 0, 14), 1e-9,
                "with no drift or news yet, just inflation to expiry");
        assertEquals(0, d.intradayNoise(WHEAT, 11.0), 0, "no noise at dawn: marks are clean");
        for (String item : new String[] {"minecraft:wheat", "minecraft:oak_log", "minecraft:iron_ingot", "minecraft:gold_ingot",
                "minecraft:redstone", "minecraft:diamond"}) {
            assertTrue(d.catalog().trades(item), item + " is a Dealer good");
        }
    }

    @Test
    void theHouseQuotesASpreadThatWidensWithVolumeAndCapsPositions() {
        ClearingHouse h = new ClearingHouse(dealer());
        h.deposit("a", 10_000_000);
        double f = h.price("IRN", 14, 10);
        assertEquals(f * 1.0025, h.quote("a", "IRN", 14, 1, 10), 1e-6, "buy one lot: a quarter percent over");
        assertEquals(f * 0.9975, h.quote("a", "IRN", 14, -1, 10), 1e-6, "sell one: a quarter under");
        h.trade("a", "IRN", 14, 5, 10);
        assertEquals(f * (1.0025 + 0.001 * 5), h.quote("a", "IRN", 14, 1, 10), 1e-6, "0.1% more for each lot bought today");
        assertEquals(f * (0.9975 + 0.001 * 5), h.quote("a", "IRN", 14, -1, 10), 1e-6, "and selling back gets that too");
        h.trade("a", "IRN", 14, 15, 10);
        assertThrows(RejectedException.class, () -> h.trade("a", "IRN", 14, 1, 10), "20 lots at most");
        h.trade("a", "IRN", 14, -3, 10); // reducing is always allowed
        assertEquals(17, h.account("a").positions().get(0).lots());
        assertThrows(RejectedException.class, () -> h.trade("a", "IRN", 35, 1, 10), "only the next two expiries");
        assertEquals(h.price("IRN", 14, 11) * 1.0025, h.quote("a", "IRN", 14, 1, 11), 1e-6, "a new day, a fresh quote");
    }

    @Test
    void openingNeedsTenPercentMargin() {
        ClearingHouse h = new ClearingHouse(dealer());
        double lot = h.price("WHT", 14, 10);
        h.deposit("a", Math.round(lot * 0.09));
        assertThrows(RejectedException.class, () -> h.trade("a", "WHT", 14, 1, 10), "9% isn't enough");
        assertTrue(h.account("a").positions().isEmpty(), "nothing opened");
        h.deposit("a", Math.round(lot * 0.03));
        h.trade("a", "WHT", 14, 1, 10);
        assertEquals(1, h.account("a").positions().get(0).lots());
        assertTrue(h.free("a", 10) < lot * 0.03, "only what isn't needed as margin can be withdrawn");
        assertThrows(RejectedException.class, () -> h.withdraw("a", Math.round(lot * 0.1), 10));
    }

    @Test
    void theDawnMarkMovesGainsAndLossesIntoCash() {
        Dealer d = dealer();
        ClearingHouse h = new ClearingHouse(d);
        h.deposit("long", 1_000_000);
        h.deposit("short", 1_000_000);
        var buy = h.trade("long", "GLD", 14, 2, 10.2);
        var sell = h.trade("short", "GLD", 14, -2, 10.2);
        double mark = h.price("GLD", 14, 11);
        var dawns = h.dawn(11);
        assertEquals(2, dawns.size());
        assertEquals(1_000_000 + Math.round((mark - buy.priceCents()) * 2), h.account("long").cashCents(), "long: the move since buying");
        assertEquals(1_000_000 + Math.round((sell.priceCents() - mark) * 2), h.account("short").cashCents(), "short: the opposite");
        assertEquals(mark, h.account("long").positions().get(0).entryCents(), 1e-9, "counted from today's mark from now on");
        long cash = h.account("long").cashCents();
        double mark2 = h.price("GLD", 14, 12);
        h.dawn(12);
        assertEquals(cash + Math.round((mark2 - mark) * 2), h.account("long").cashCents(), "and again the next dawn");
    }

    @Test
    void aMarginCallIsMetOrTheAccountIsClosedOutAtTheNextDawn() {
        Dealer d = dealer();
        ClearingHouse h = new ClearingHouse(d);
        double lot = h.price("WHT", 14, 10);
        h.deposit("a", Math.round(lot * 10 * 0.11));
        h.deposit("b", Math.round(lot * 10 * 0.11));
        h.trade("a", "WHT", 14, 10, 10);
        h.trade("b", "WHT", 14, 10, 10);
        d.absorb(WHEAT, 256 * 12, 10.5); // a glut: wheat falls several percent, most of the margin
        var first = h.dawn(11);
        assertTrue(first.stream().allMatch(x -> x.called() && x.variationCents() < 0), "both called at dawn: " + first);
        assertTrue(h.account("a").underCall());
        // a tops up; b doesn't.
        assertFalse(h.callMet("a", 11.3));
        h.deposit("a", h.required("a", 11.3, true) - h.equity("a", 11.3) + 100);
        assertTrue(h.callMet("a", 11.3), "back to the initial margin: the call is met");
        var second = h.dawn(12);
        var b = second.stream().filter(x -> x.account().equals("b")).findFirst().orElseThrow();
        assertTrue(b.closedOut(), "b's positions are closed at the dawn mark");
        assertTrue(h.account("b").positions().isEmpty());
        assertFalse(h.account("a").positions().isEmpty(), "a keeps its position");
    }

    @Test
    void debtBlocksNewPositionsAndExpiryCashSettles() {
        Dealer d = dealer();
        ClearingHouse h = new ClearingHouse(d);
        double lot = h.price("WHT", 14, 10);
        h.deposit("a", Math.round(lot * 20 * 0.115));
        h.trade("a", "WHT", 14, 20, 10);
        d.absorb(WHEAT, 256 * 20, 10.5); // a crash far bigger than the margin
        h.dawn(11);
        h.dawn(12); // not met: closed out
        assertTrue(h.account("a").cashCents() < 0, "the loss ran past the margin: a debt, " + h.account("a").cashCents());
        assertThrows(RejectedException.class, () -> h.trade("a", "WHT", 14, 1, 12), "no new positions while in debt");
        h.deposit("a", -h.account("a").cashCents() + Math.round(lot * 0.2));
        h.trade("a", "WHT", 14, 1, 12.5);
        var settled = h.dawn(14);
        assertTrue(h.account("a").positions().isEmpty(), "expired");
        double fair = d.fairValue(WHEAT, 14) * 256 * 100;
        assertEquals(fair, h.price("WHT", 14, 14), 1e-6, "on expiry day the future is the Dealer's fair value");
        assertTrue(settled.get(0).expiredCents() != 0);
    }

    @Test
    void readingTheNewsGivesNoSureIntradayProfit() {
        // A world with news: buy at dawn the future of any good the Newsstand says will rise, sell before the next dawn.
        long wins = 0, trades = 0;
        double pnl = 0;
        for (long seed = 1; seed <= 6; seed++) {
            var events = com.realisticmarkets.dealer.WorldEvents.loadDefault(DealerCatalog.loadDefault(), seed);
            Dealer w = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), seed);
            w.setShocks(events);
            ClearingHouse h = new ClearingHouse(w);
            for (long day = 8; day < 120; day++) {
                for (var e : events.startingOn(day)) {
                    if (e.type().shock() <= 0) continue;
                    for (ClearingHouse.Product p : PRODUCTS_IN(e, events)) {
                        long expiry = ClearingHouse.expiries(day)[0];
                        double buy = h.price(p.code(), expiry, day + 0.02) * (1 + ClearingHouse.HALF_SPREAD);
                        double sell = h.price(p.code(), expiry, day + 0.95) * (1 - ClearingHouse.HALF_SPREAD);
                        double noNoise = w.expectedFair(p.item(), day + 0.95, expiry) / w.expectedFair(p.item(), day + 0.02, expiry);
                        assertEquals(1.0, noNoise, 1e-9, "the expected value doesn't move within the day: the news is in at dawn");
                        pnl += sell / buy - 1;
                        if (sell > buy) wins++;
                        trades++;
                    }
                }
            }
        }
        assertTrue(trades > 30, "enough news: " + trades);
        assertTrue(pnl / trades < 0.002, "no edge from the news on the futures: average " + pnl / trades);
        assertTrue(wins < trades * 0.7, "and no sure thing: " + wins + " of " + trades + " won");
    }

    static java.util.List<ClearingHouse.Product> PRODUCTS_IN(com.realisticmarkets.dealer.WorldEvents.Event e,
                                                             com.realisticmarkets.dealer.WorldEvents events) {
        java.util.List<ClearingHouse.Product> out = new java.util.ArrayList<>();
        for (ClearingHouse.Product p : ClearingHouse.PRODUCTS) {
            if (events.fadingExpected(p.item(), e.day(), e.day() + 3) != 0) out.add(p);
        }
        return out;
    }

    @Test
    void savesAndLoads() throws Exception {
        Dealer d = dealer();
        ClearingHouse h = new ClearingHouse(d);
        h.deposit("a", 500_000);
        h.trade("a", "DIA", 21, -3, 15);
        StringWriter w = new StringWriter();
        h.write(w);
        ClearingHouse back = ClearingHouse.read(new StringReader(w.toString()), d);
        assertEquals(h.account("a").cashCents(), back.account("a").cashCents());
        assertEquals(h.account("a").positions(), back.account("a").positions());
        assertEquals(h.equity("a", 15), back.equity("a", 15));
    }
}
