package com.realisticmarkets.equities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.progression.PlayerProgress;
import com.realisticmarkets.progression.ProgressionEvent;
import com.realisticmarkets.progression.Quests;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CertificatesTest {
    @Test
    void fewestCertificatesAndSplits() {
        assertEquals(Map.of(100, 2L, 10, 3L, 1, 7L), Certificates.fewest(237));
        assertEquals(Map.of(10, 1L), Certificates.fewest(10));
        assertEquals(Map.of(), Certificates.fewest(0));
        long total = 0;
        for (Map.Entry<Integer, Long> e : Certificates.fewest(1234).entrySet()) total += e.getKey() * e.getValue();
        assertEquals(1234, total, "no share lost");
        assertEquals(10, Certificates.splitInto(100));
        assertEquals(1, Certificates.splitInto(10));
        assertEquals(0, Certificates.splitInto(1));
        assertTrue(Certificates.valid(10) && !Certificates.valid(5));
    }

    @Test
    void dividendsOwedSinceTheQuarterPaidThrough() {
        Equities eq = new Equities(EquitiesTest.CATALOG, EquitiesTest::base, 3);
        for (long d = 0; d <= 14; d++) eq.observe(d, EquitiesTest::base, List.of());
        long q0 = eq.reports("OWL").get(0).dividend(), q1 = eq.reports("OWL").get(1).dividend();
        assertEquals(10 * (q0 + q1), Certificates.owed(eq, "OWL", 10, -1));
        assertEquals(10 * q1, Certificates.owed(eq, "OWL", 10, 0));
        assertEquals(0, Certificates.owed(eq, "OWL", 10, eq.lastReportedQuarter()), "paid up: nothing more");
    }

    @Test
    void costBasisAtAverageCost() throws Exception {
        CostBasis cb = new CostBasis();
        cb.bought("p", "DSMC", 10, 50_000);
        cb.bought("p", "DSMC", 10, 70_000);
        assertEquals(30_000, cb.sold("p", "DSMC", 5), "average $60 a share");
        assertEquals(15, cb.shares("p", "DSMC"));
        assertEquals(-1, cb.sold("p", "OWL", 1), "never bought here: unknown cost");
        assertEquals(-1, cb.sold("p", "DSMC", 100), "more than bought here: unknown");
        cb.bought("q", "GHF", 3, 9_000);
        StringWriter w = new StringWriter();
        cb.write(w);
        assertEquals(3, CostBasis.read(new StringReader(w.toString())).shares("q", "GHF"));
    }

    @Test
    void shareQuests() {
        PlayerProgress p = new PlayerProgress();
        Quests q = Quests.loadDefault();
        assertTrue(p.apply(new ProgressionEvent.DividendCollected("OWL", 0, 1), q).isEmpty(), "nothing paid, no quest");
        assertEquals("shareholder", p.apply(new ProgressionEvent.DividendCollected("OWL", 250, 1), q).getFirst().id());
        assertTrue(p.apply(new ProgressionEvent.SharesHeld("DSMC", 99, 1), q).isEmpty());
        assertEquals("buy_the_business", p.apply(new ProgressionEvent.SharesHeld("DSMC", 100, 1), q).getFirst().id());
        assertTrue(p.apply(new ProgressionEvent.StockSold("DSMC", 10, 50_000, -1, 2), q).isEmpty(), "unknown cost doesn't count");
        assertTrue(p.apply(new ProgressionEvent.StockSold("DSMC", 10, 50_000, 60_000, 2), q).isEmpty(), "a loss doesn't");
        assertEquals("beat_the_market", p.apply(new ProgressionEvent.StockSold("DSMC", 10, 70_000, 60_000, 3), q).getFirst().id());
        assertTrue(p.apply(new ProgressionEvent.CompaniesHeld(2, 3), q).isEmpty());
        assertEquals("diversified", p.apply(new ProgressionEvent.CompaniesHeld(3, 3), q).getFirst().id());
        assertEquals("coupon_clipper", p.apply(new ProgressionEvent.CouponCollected(211, 4), q).getFirst().id());
        assertTrue(p.apply(new ProgressionEvent.BondRedeemed(1, 4_000, false, 4), q).isEmpty(), "a default's recovery isn't maturity");
        assertEquals("held_to_maturity", p.apply(new ProgressionEvent.BondRedeemed(1, 10_000, true, 4), q).getFirst().id());
        assertTrue(p.apply(new ProgressionEvent.BondSold(1, 10_100, 10_025, false, 5), q).isEmpty(), "no cut: not Rate Watcher");
        assertEquals("rate_watcher", p.apply(new ProgressionEvent.BondSold(1, 10_100, 10_025, true, 5), q).getFirst().id());
    }
}
