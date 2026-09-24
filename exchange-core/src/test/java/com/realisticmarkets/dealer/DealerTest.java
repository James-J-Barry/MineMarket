package com.realisticmarkets.dealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.exchange.RejectedException;
import java.io.StringReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the numbers in the design doc's "Basic Exchange and the Dealer" section.
 * If a balance change breaks these, update the doc and the tests together.
 */
class DealerTest {
    static final String WHEAT = "minecraft:wheat";
    Dealer dealer;

    @BeforeEach
    void setUp() {
        dealer = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1234L);
    }

    @Test
    void firstUnitBidIsFairValueMinusHalfSpread() {
        assertEquals(0.45, dealer.bid(WHEAT, 0, false), 1e-9);
        assertEquals(0.55, dealer.ask(WHEAT, 0, false), 1e-9);
        assertEquals(0.47, dealer.bid(WHEAT, 0, true), 1e-9); // 12% licensed spread
    }

    @Test
    void midSitsBetweenBidAndAskAndStartsAtFairValue() {
        assertEquals(0.50, dealer.mid(WHEAT, 0), 1e-9);
        assertEquals(dealer.fairValue(WHEAT, 0), dealer.mid(WHEAT, 0), 1e-9);
        dealer.sell(WHEAT, 64, 0, false);
        double mid = dealer.mid(WHEAT, 0);
        assertTrue(dealer.bid(WHEAT, 0, false) < mid && mid < dealer.ask(WHEAT, 0, false));
        assertTrue(mid < dealer.fairValue(WHEAT, 0), "dealer holding stock trades below fair value");
        assertEquals(9 * dealer.mid("iron_ingot", 0), dealer.mid("iron_block", 0), 1e-9);
    }

    @Test
    void designDocDumpTable() {
        // Doc: 64 -> $25.48, 256 -> $72.82, 1,024 -> $113.09 (before rounding down to a dime)
        assertEquals(2548.2, dealer.quoteSell(WHEAT, 64, 0, false).rawCents(), 0.5);
        assertEquals(2540, dealer.quoteSell(WHEAT, 64, 0, false).cents());
        assertEquals(7282.4, dealer.quoteSell(WHEAT, 256, 0, false).rawCents(), 0.5);
        assertEquals(11309.0, dealer.quoteSell(WHEAT, 1024, 0, false).rawCents(), 1.0);
    }

    @Test
    void bidAfterSellingMatchesTable() {
        dealer.sell(WHEAT, 64, 0, false);
        assertEquals(0.35, dealer.bid(WHEAT, 0, false), 0.005);
        dealer.sell(WHEAT, 192, 0, false);
        assertEquals(0.17, dealer.bid(WHEAT, 0, false), 0.005);
    }

    @Test
    void proceedsNeverExceedTheCeiling() {
        // Ceiling = V (1 - s/2) L / k = 0.45 * 256 = $115.20
        double total = 0;
        for (int i = 0; i < 200; i++) {
            try {
                total += dealer.sell(WHEAT, 64, 0, false).rawCents();
            } catch (RejectedException collapsed) {
                break;
            }
        }
        assertTrue(total <= 11520.0 + 1e-6, "total " + total);
        assertTrue(total > 11400, "should approach the ceiling, got " + total);
    }

    @Test
    void splittingASaleDoesNotChangeRawProceeds() {
        Dealer other = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1234L);
        double oneShot = dealer.sell(WHEAT, 256, 0, false).rawCents();
        double pieces = 0;
        for (int i = 0; i < 4; i++) pieces += other.sell(WHEAT, 64, 0, false).rawCents();
        assertEquals(oneShot, pieces, 1e-6);
    }

    @Test
    void inventoryRecoversOverTau() {
        dealer.sell(WHEAT, 256, 0, false);
        assertEquals(256.0, dealer.inventory(WHEAT, 0), 1e-9);
        assertEquals(256.0 / Math.E, dealer.inventory(WHEAT, 2.0), 1e-6); // tau = 2 days
        assertTrue(dealer.bid(WHEAT, 20.0, false) > 0.449);
    }

    @Test
    void buyingPushesPriceUpAndRoundTripLosesTheSpread() {
        long paid = dealer.buy(WHEAT, 64, 0, false).cents();
        assertTrue(dealer.ask(WHEAT, 0, false) > 0.55);
        long got = dealer.sell(WHEAT, 64, 0, false).cents();
        assertTrue(paid - got >= Math.round(0.20 * 0.50 * 64 * 100) - 20,
                "round trip should cost about the spread: paid " + paid + " got " + got);
        assertTrue(got < paid);
    }

    @Test
    void linkedItemsShareTheBasePool() {
        Dealer other = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1234L);
        double blocks = dealer.sell("iron_block", 10, 0, false).rawCents();
        double ingots = other.sell("iron_ingot", 90, 0, false).rawCents();
        assertEquals(ingots, blocks, 1e-6);
        assertEquals(90.0, dealer.inventory("minecraft:iron_ingot", 0), 1e-9);
        assertEquals(dealer.bid("iron_ingot", 0, false) * 9, dealer.bid("iron_block", 0, false), 1e-9);
    }

    @Test
    void cannotArbitrageBlocksAgainstIngots() {
        long soldBlock = dealer.sell("iron_block", 1, 0, false).cents();
        long boughtIngots = dealer.buy("iron_ingot", 9, 0, false).cents();
        assertTrue(boughtIngots > soldBlock);
    }

    @Test
    void licenseImprovesProceeds() {
        assertTrue(dealer.quoteSell(WHEAT, 64, 0, true).cents() > dealer.quoteSell(WHEAT, 64, 0, false).cents());
    }

    @Test
    void quotesDoNotMoveState() {
        dealer.quoteSell(WHEAT, 500, 0, false);
        dealer.quoteBuy(WHEAT, 500, 0, false);
        assertEquals(0.0, dealer.inventory(WHEAT, 0), 1e-12);
    }

    @Test
    void refusesUnknownItemsAndWorthlessSales() {
        assertThrows(RejectedException.class, () -> dealer.quoteSell("minecraft:dirt", 1, 0, false));
        // Crash cobblestone so one more item rounds to $0.00
        for (int i = 0; i < 40; i++) {
            try { dealer.sell("cobblestone", 64, 0, false); } catch (RejectedException ignored) { }
        }
        assertThrows(RejectedException.class, () -> dealer.sell("cobblestone", 1, 0, false));
        assertThrows(RejectedException.class, () -> dealer.quoteSell(WHEAT, 0, 0, false));
    }

    @Test
    void fairValueDriftIsDeterministicAndBounded() {
        Dealer a = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 99L);
        Dealer b = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 99L);
        Dealer c = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 100L);
        assertEquals(a.fairValue(WHEAT, 50), b.fairValue(WHEAT, 50), 0.0);
        assertTrue(a.fairValue(WHEAT, 50) != c.fairValue(WHEAT, 50));
        // Touch order doesn't matter: b looks at day 10 first, then 50.
        b.fairValue(WHEAT, 10);
        assertEquals(a.fairValue(WHEAT, 400), b.fairValue(WHEAT, 400), 0.0);
        for (int d = 0; d < 2000; d += 10) {
            double v = a.fairValue(WHEAT, d) / a.priceLevel(d); // in today's money
            assertTrue(v > 0.2 && v < 1.25, "day " + d + " real fair value " + v); // the weak anchor keeps it in range
        }
    }

    @Test
    void fairValueNoLongerSnapsBack() {
        // After 30 days a price is typically ~10% from where it was, and it doesn't return within a week.
        int stillAway = 0, n = 0;
        for (long seed = 1; seed <= 40; seed++) {
            Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), seed);
            double v30 = Math.log(d.fairValue(WHEAT, 30) / 0.5);
            double v37 = Math.log(d.fairValue(WHEAT, 37) / 0.5);
            if (Math.abs(v30) > 0.08) {
                n++;
                if (Math.signum(v37) == Math.signum(v30) && Math.abs(v37) > Math.abs(v30) * 0.5) stillAway++;
            }
        }
        assertTrue(n >= 10, "prices move: " + n + " of 40 seeds are over 8% away after 30 days");
        assertTrue(stillAway >= n * 0.7, "and mostly stay away a week later: " + stillAway + " of " + n);
    }

    @Test
    void pricesCreepUpWithInflation() {
        DealerParams d = DealerParams.defaults();
        assertEquals(0.001, d.inflation(), 0.0, "0.1% a day, about 3% a month");
        DealerParams onlyInflation = new DealerParams(d.spread(), d.licensedSpread(), d.k(), d.recoveryDays(), 0, d.anchorHalfLifeDays(),
                0, d.trendHalfLifeDays(), 0, d.inflation());
        Dealer dealer = new Dealer(DealerCatalog.loadDefault(), onlyInflation, 1L);
        assertEquals(0.50, dealer.fairValue(WHEAT, 0), 1e-12);
        assertEquals(0.50 * Math.exp(0.1), dealer.fairValue(WHEAT, 100), 1e-12, "100 days later, about 10.5% dearer");
        assertEquals(0.50 * Math.exp(0.1) * 0.9, dealer.bid(WHEAT, 100, false), 1e-12, "and so is what the Dealer pays");
        assertTrue(DealerParams.noDrift().inflation() == 0, "exact-number tests stay put");
    }

    @Test
    void normalIsYesterdaysClose() {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 21L);
        Dealer probe = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 21L);
        double close = probe.fairValue(WHEAT, 9.999999);
        d.fairValue(WHEAT, 9.5);
        assertEquals(close, d.normalValue(WHEAT, 10.3), close * 1e-4, "Normal on day 10 = the value at the end of day 9");
        assertTrue(Math.abs(d.fairValue(WHEAT, 10.3) / d.normalValue(WHEAT, 10.3) - 1) > 1e-4, "and the true value has moved on");
        d.sell(WHEAT, 256, 10.4, false);
        assertEquals(close, d.normalValue(WHEAT, 10.5), close * 1e-4, "today's trades show in Normal tomorrow");
        assertTrue(d.normalValue(WHEAT, 11.2) < probe.fairValue(WHEAT, 10.999999), "there they are");
        int lagging = 0;
        for (int day = 1; day < 200; day++) {
            if (Math.abs(d.fairValue(WHEAT, day + 0.5) / d.normalValue(WHEAT, day + 0.5) - 1) > 0.02) lagging++;
        }
        assertTrue(lagging > 40, "Normal is off by over 2% on many days: " + lagging + " of 199");
    }

    @Test
    void sellingLowersFairValueForGood() {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 5L);
        Dealer quiet = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 5L);
        d.sell(WHEAT, 256, 0, false); // one depth's worth
        assertEquals(Math.exp(-0.01), d.fairValue(WHEAT, 0) / quiet.fairValue(WHEAT, 0), 1e-9, "1% per depth sold");
        // The Dealer's inventory recovers in days; the supply effect fades only with the 60-day anchor.
        double gap20 = d.fairValue(WHEAT, 20) / quiet.fairValue(WHEAT, 20);
        assertEquals(Math.exp(-0.01 * Math.pow(0.5, 20 / 60.0)), gap20, 1e-9);
        assertTrue(d.mid(WHEAT, 20) / d.fairValue(WHEAT, 20) > 0.999, "inventory long gone");
        d.buy(WHEAT, 256, 20, false);
        assertTrue(d.fairValue(WHEAT, 20) > quiet.fairValue(WHEAT, 20), "buying raises it back and more");
        Dealer still = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 5L);
        still.sell(WHEAT, 256, 0, false);
        assertEquals(0.50, still.fairValue(WHEAT, 3), 1e-12, "noDrift keeps the design doc's fixed numbers");
    }

    @Test
    void worldShocksMoveFairValue() {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 5L);
        d.setShocks(new Dealer.Shocks() {
            public double permanent(String item, long day) { return item.equals(WHEAT) && day == 3 ? 0.1 : 0; }
            public double fading(String item, double day) { return item.equals(WHEAT) && day >= 3 ? 0.2 : 0; }
        });
        assertEquals(0.50, d.fairValue(WHEAT, 2.5), 1e-12);
        assertEquals(0.50 * Math.exp(0.3), d.fairValue(WHEAT, 3.2), 1e-12);
        assertEquals(0.50, d.fairValue("minecraft:carrot", 3.2) / d.catalog().spec("minecraft:carrot").fairValue() * 0.5, 1e-12);
    }

    @Test
    void snapshotRoundTrip() {
        dealer.sell(WHEAT, 100, 1.5, false);
        Dealer copy = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1234L);
        copy.restore(dealer.snapshot());
        assertEquals(dealer.bid(WHEAT, 3.0, false), copy.bid(WHEAT, 3.0, false), 1e-12);
    }

    @Test
    void stateSurvivesSaveAndLoad() throws Exception {
        dealer.sell(WHEAT, 300, 1.5, false);
        dealer.buy("diamond", 3, 2.0, false);
        java.io.StringWriter out = new java.io.StringWriter();
        DealerStateIO.write(new DealerStateIO.Saved(dealer.snapshot(), 2.5), out);

        DealerStateIO.Saved loaded = DealerStateIO.read(new StringReader(out.toString()));
        assertEquals(2.5, loaded.dayOffset(), 0.0);
        Dealer copy = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1234L);
        copy.restore(loaded.pools());
        for (String item : new String[] {WHEAT, "minecraft:diamond", "minecraft:iron_block"}) {
            assertEquals(dealer.bid(item, 5.0, false), copy.bid(item, 5.0, false), 0.0, item);
            assertEquals(dealer.ask(item, 5.0, false), copy.ask(item, 5.0, false), 0.0, item);
        }
        assertThrows(IllegalArgumentException.class,
                () -> DealerStateIO.read(new StringReader("minecraft:wheat\tnot-a-number\t1\t1\t0\n")));
    }

    @Test
    void movingPricesSurviveSaveAndLoadAndV1SavesStillLoad() throws Exception {
        Dealer live = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 77L);
        live.sell(WHEAT, 300, 12.5, false);
        java.io.StringWriter out = new java.io.StringWriter();
        DealerStateIO.write(new DealerStateIO.Saved(live.snapshot(), 0), out);
        Dealer copy = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 77L);
        copy.restore(DealerStateIO.read(new StringReader(out.toString())).pools());
        assertEquals(live.fairValue(WHEAT, 40), copy.fairValue(WHEAT, 40), 0.0, "trend and level carry over");
        Dealer again = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 77L);
        again.restore(DealerStateIO.read(new StringReader(out.toString())).pools());
        assertEquals(live.normalValue(WHEAT, 40.5), again.normalValue(WHEAT, 40.5), 0.0, "so does yesterday's close");

        var v1 = DealerStateIO.read(new StringReader("# Realistic Markets dealer state v1\nminecraft:wheat\t256.0\t12.5\t12\t-0.013\n"));
        assertEquals(-0.013, v1.pools().get(WHEAT).logDeviation(), 0.0);
        assertEquals(0.0, v1.pools().get(WHEAT).trend(), 0.0);
    }

    @Test
    void catalogValidation() throws Exception {
        String bad = "item,fair_value,depth,group,base_item,base_units\nminecraft:iron_block,,,mining,minecraft:iron_ingot,9\n";
        assertThrows(IllegalArgumentException.class, () -> DealerCatalog.parseCsv(new StringReader(bad)));
        String ok = "wheat,0.5,256,farm,,\nhay_block,,,farm,wheat,9\n";
        DealerCatalog c = DealerCatalog.parseCsv(new StringReader(ok));
        assertEquals("minecraft:wheat", c.pool("hay_block").itemId());
        DealerCatalog def = DealerCatalog.loadDefault();
        assertEquals(47, def.basePools().size()); // 40 M1 items + 3 Tier 1 + 2 Tier 2 + 1 Tier 3 + 1 Tier 4 components
        assertEquals(0.40, def.spec("realisticmarkets:brass_fittings").spread());
        assertEquals(null, def.spec("minecraft:wheat").spread());
    }
}
