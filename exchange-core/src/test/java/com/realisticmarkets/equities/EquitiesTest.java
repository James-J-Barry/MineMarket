package com.realisticmarkets.equities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.dealer.DealerCatalog;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;

class EquitiesTest {
    static final CompanyCatalog CATALOG = CompanyCatalog.loadDefault();
    static final DealerCatalog DEALER = DealerCatalog.loadDefault();

    /** Catalog fair values for items, 1.0 for "fees" and "shipping". */
    static double base(String key) {
        return DEALER.trades(key) ? DEALER.spec(key).fairValue() : 1.0;
    }

    static ToDoubleFunction<String> with(Map<String, Double> factors) {
        return k -> base(k) * factors.getOrDefault(k, 1.0);
    }

    /** Runs {@code quarters} quarters at the given prices, one observation a day, with events on the given days. */
    static Equities run(long seed, int quarters, ToDoubleFunction<String> price, Map<Long, List<String>> events) {
        Equities e = new Equities(CATALOG, EquitiesTest::base, seed);
        for (long d = 0; d <= quarters * 7L; d++) e.observe(d, price, events.getOrDefault(d, List.of()));
        return e;
    }

    @Test
    void catalogLoadsSixCompaniesAtSensiblePrices() {
        assertEquals(List.of("DSMC", "GHF", "NRP", "ENCH", "RSD", "OWL"), CATALOG.all().stream().map(Company::ticker).toList());
        Equities e = new Equities(CATALOG, EquitiesTest::base, 1);
        for (Company c : CATALOG.all()) {
            long v = e.startValueCents(c.ticker());
            assertTrue(v >= 2_000 && v <= 10_000, c.ticker() + " starts at " + v + " cents");
            assertTrue(c.requiredReturn() > 0.003, c.ticker() + " asks more than the vault pays");
        }
        assertTrue(CATALOG.company("RSD").requiredReturn() > CATALOG.company("OWL").requiredReturn(), "risk costs more");
    }

    @Test
    void aQuarterIsSevenDaysAndReportsOnce() {
        Equities e = new Equities(CATALOG, EquitiesTest::base, 1);
        for (long d = 0; d < 7; d++) assertTrue(e.observe(d, EquitiesTest::base, List.of()).isEmpty());
        List<Equities.Report> issued = e.observe(7, EquitiesTest::base, List.of());
        assertEquals(6, issued.size(), "every company reports at the start of day 7");
        assertEquals(0, issued.getFirst().quarter());
        assertEquals(0, e.lastReportedQuarter());
        e.observe(30, EquitiesTest::base, List.of());
        assertEquals(3, e.lastReportedQuarter(), "skipped days still close each quarter");
    }

    @Test
    void ironDownHitsTheMinersEarningsHarderThanItsSales() {
        Equities normal = run(9, 1, EquitiesTest::base, Map.of());
        Equities cheapIron = run(9, 1, with(Map.of("minecraft:iron_ingot", 0.8)), Map.of());
        Equities.Report a = normal.latest("DSMC").orElseThrow(), b = cheapIron.latest("DSMC").orElseThrow();
        double revenueDrop = 1 - b.revenue() / (double) a.revenue(), earningsDrop = 1 - b.earnings() / (double) a.earnings();
        assertTrue(revenueDrop > 0.1 && revenueDrop < 0.15, "iron is most of DSMC's sales: " + revenueDrop);
        assertTrue(earningsDrop > 2 * revenueDrop, "fixed costs magnify it: earnings fell " + earningsDrop);
        assertEquals(normal.latest("GHF"), cheapIron.latest("GHF"), "farms don't care about iron");
        assertTrue(cheapIron.latest("NRP").orElseThrow().earnings() > normal.latest("NRP").orElseThrow().earnings(),
                "iron is the railway's cost, so it gains");
        assertTrue(cheapIron.fairValueCents("DSMC") < normal.fairValueCents("DSMC"));
    }

    @Test
    void dividendsFollowPayoutRatios() {
        Equities e = run(3, 2, EquitiesTest::base, Map.of());
        for (Company c : CATALOG.all()) {
            Equities.Report r = e.latest(c.ticker()).orElseThrow();
            assertTrue(r.earnings() > 0, c.ticker() + " profitable at normal prices");
            assertEquals((long) Math.floor(c.payout() * r.earnings() / c.shares()), r.dividend(), c.ticker());
        }
        assertEquals(0, e.latest("RSD").orElseThrow().dividend(), "Redstone Dynamics keeps everything to grow");
        assertTrue(e.latest("OWL").orElseThrow().dividend() > e.latest("DSMC").orElseThrow().dividend() / 2, "the utility pays out");
        long both = e.reports("OWL").get(0).dividend() + e.reports("OWL").get(1).dividend();
        assertEquals(both, e.dividendsSince("OWL", -1), "never paid: both quarters owed");
        assertEquals(e.reports("OWL").get(1).dividend(), e.dividendsSince("OWL", 0), "paid through quarter 0: one owed");
        assertEquals(0, e.dividendsSince("OWL", 1));
        Equities slump = run(3, 1, with(Map.of("minecraft:iron_ingot", 0.4, "minecraft:gold_ingot", 0.5)), Map.of());
        assertTrue(slump.latest("DSMC").orElseThrow().earnings() < 0, "a deep slump puts the miner in the red");
        assertEquals(0, slump.latest("DSMC").orElseThrow().dividend(), "no dividend from a loss");
    }

    @Test
    void fairValueCapitalizesEarnings() {
        Equities e = new Equities(CATALOG, EquitiesTest::base, 1);
        Company owl = CATALOG.company("OWL");
        double expected = (100_000 - 30_500 - 12_000 * base("minecraft:coal")) * 100;
        double r = Math.pow(1.0035, 7) - 1, g = 0.002;
        assertEquals(Math.round(expected * (1 + g) / (r - g) / owl.shares()), e.startValueCents("OWL"));
        assertEquals(e.startValueCents("OWL"), e.fairValueCents("OWL"), "before any report: the starting value");
        // A higher required return for the same earnings means a lower price.
        Company risky = new Company("X", "X", owl.revenue(), owl.fixedCost(), owl.inputs(), owl.growth(), owl.outputVol(),
                owl.payout(), owl.shares(), 0.006, owl.events());
        Equities e2 = new Equities(new CompanyCatalog(List.of(risky)), EquitiesTest::base, 1);
        assertTrue(e2.startValueCents("X") < e.startValueCents("OWL"));
        // Deep losses can't push the price below the floor.
        Equities slump = run(3, 4, with(Map.of("minecraft:iron_ingot", 0.3, "minecraft:gold_ingot", 0.3, "minecraft:diamond", 0.3)), Map.of());
        assertEquals(Math.round(slump.startValueCents("DSMC") * Equities.VALUE_FLOOR), slump.fairValueCents("DSMC"));
    }

    @Test
    void eventsMoveTheCompaniesTheyTouch() {
        Equities quiet = run(4, 1, EquitiesTest::base, Map.of());
        Equities raid = run(4, 1, EquitiesTest::base, Map.of(2L, List.of("pillager_raid")));
        Equities drought = run(4, 1, EquitiesTest::base, Map.of(2L, List.of("drought")));
        assertEquals(Math.round(quiet.latest("ENCH").orElseThrow().revenue() * 1.4), raid.latest("ENCH").orElseThrow().revenue(), 2);
        assertEquals(quiet.latest("DSMC"), raid.latest("DSMC"));
        assertTrue(drought.latest("GHF").orElseThrow().earnings() < quiet.latest("GHF").orElseThrow().earnings(),
                "at the same prices, a drought's lost harvest hurts the farm");
    }

    @Test
    void seededAndReproducible() {
        Equities a = run(21, 8, EquitiesTest::base, Map.of()), b = run(21, 8, EquitiesTest::base, Map.of());
        Equities c = run(22, 8, EquitiesTest::base, Map.of());
        assertEquals(a.reports("RSD"), b.reports("RSD"));
        assertNotEquals(a.reports("RSD"), c.reports("RSD"), "another world, another business");
        // The business's own luck: RSD swings far more than OWL.
        assertTrue(spread(a, "RSD") > 3 * spread(a, "OWL"), spread(a, "RSD") + " vs " + spread(a, "OWL"));
    }

    static double spread(Equities e, String t) {
        List<Equities.Report> rs = e.reports(t);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 1; i < rs.size(); i++) {
            double ch = Math.log(rs.get(i).revenue() / (double) rs.get(i - 1).revenue());
            lo = Math.min(lo, ch);
            hi = Math.max(hi, ch);
        }
        return hi - lo;
    }

    @Test
    void stateSurvivesSaveAndLoad() throws Exception {
        Equities a = new Equities(CATALOG, EquitiesTest::base, 5);
        for (long d = 0; d <= 17; d++) a.observe(d, with(Map.of("minecraft:coal", 1.2)), d == 15 ? List.of("pillager_raid") : List.of());
        StringWriter w = new StringWriter();
        a.write(w);
        Equities b = Equities.read(new StringReader(w.toString()), new Equities(CATALOG, EquitiesTest::base, 5));
        for (long d = 18; d <= 30; d++) {
            a.observe(d, EquitiesTest::base, Set.of());
            b.observe(d, EquitiesTest::base, Set.of());
        }
        for (Company c : CATALOG.all()) {
            assertEquals(a.reports(c.ticker()), b.reports(c.ticker()), c.ticker());
            assertEquals(a.fairValueCents(c.ticker()), b.fairValueCents(c.ticker()));
        }
    }

    @Test
    void keptEarningsBuildCashThatCountsInTheValue() {
        Equities e = run(8, 3, EquitiesTest::base, Map.of());
        Company rsd = CATALOG.company("RSD");
        double cash = 0;
        for (Equities.Report r : e.reports("RSD")) cash = cash * (1 + rsd.quarterReturn()) + r.earnings();
        assertEquals(Math.round(cash / rsd.shares()), e.cashPerShareCents("RSD"), "no dividend: it all stays in");
        Company owl = CATALOG.company("OWL");
        double owlCash = 0;
        for (Equities.Report r : e.reports("OWL")) owlCash = owlCash * (1 + owl.quarterReturn()) + r.earnings() - r.dividend() * owl.shares();
        assertEquals(Math.round(owlCash / owl.shares()), e.cashPerShareCents("OWL"), "only the 10% not paid out");
    }

    @Test
    void theValueMovesWithinTheQuarter() {
        Equities e = new Equities(CATALOG, EquitiesTest::base, 2);
        for (long d = 0; d <= 9; d++) e.observe(d, EquitiesTest::base, List.of());
        long before = e.fairValueCents("DSMC");
        e.observe(10, with(Map.of("minecraft:iron_ingot", 0.5)), List.of());
        assertTrue(e.fairValueCents("DSMC") < before, "iron crashing today shows in today's value");
        long ench = e.fairValueCents("ENCH");
        e.observe(11, EquitiesTest::base, List.of("pillager_raid"));
        assertTrue(e.fairValueCents("ENCH") > ench, "so does a raid");
    }

    @Test
    void onAverageOwnersEarnTheRequiredReturn() {
        Company owl = CATALOG.company("OWL");
        Company calm = new Company("CALM", "Calm", owl.revenue(), owl.fixedCost(), owl.inputs(), owl.growth(), 0, owl.payout(),
                owl.shares(), owl.requiredReturn(), owl.events());
        Company keeper = new Company("KEEP", "Keeper", owl.revenue(), owl.fixedCost(), owl.inputs(), owl.growth(), 0, 0,
                owl.shares(), owl.requiredReturn(), owl.events());
        Equities e = new Equities(new CompanyCatalog(List.of(calm, keeper)), EquitiesTest::base, 1);
        for (long d = 0; d <= 28; d++) e.observe(d, EquitiesTest::base, List.of()); // settle the first year
        Map<String, Long> start = new java.util.HashMap<>(), paid = new java.util.HashMap<>();
        for (String t : List.of("CALM", "KEEP")) {
            start.put(t, e.fairValueCents(t));
            paid.put(t, 0L);
        }
        for (long d = 29; d <= 29 + 7 * 20; d++) {
            for (Equities.Report r : e.observe(d, EquitiesTest::base, List.of())) paid.merge(r.ticker(), r.dividend(), Long::sum);
        }
        for (String t : List.of("CALM", "KEEP")) {
            double perQuarter = Math.pow((e.fairValueCents(t) + (double) paid.get(t)) / start.get(t), 1 / 20.0) - 1;
            assertEquals(calm.quarterReturn(), perQuarter, 0.004, t + ": price growth plus dividends");
        }
    }
}
