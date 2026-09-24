package com.realisticmarkets.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.equities.CompanyCatalog;
import com.realisticmarkets.equities.Equities;
import com.realisticmarkets.rates.CentralBank;
import java.util.List;
import org.junit.jupiter.api.Test;

class BondsTest {

    @Test
    void theCentralRateMovesInStepsAtReviewsWithinItsRange() {
        CentralBank a = new CentralBank(5), b = new CentralBank(5), c = new CentralBank(6);
        int raises = 0, cuts = 0;
        double sum = 0;
        boolean differs = false;
        for (long q = 0; q < 400; q++) {
            long day = q * CentralBank.REVIEW_DAYS;
            double r = a.rate(day);
            assertEquals(r, b.rate(day), 0.0, "seeded");
            differs |= r != c.rate(day);
            assertTrue(r >= CentralBank.MIN - 1e-12 && r <= CentralBank.MAX + 1e-12, "in range: " + r);
            assertEquals(0, Math.round((r - CentralBank.START) / CentralBank.STEP * 1e6) % 1_000_000, "whole steps: " + r);
            for (int d = 1; d < CentralBank.REVIEW_DAYS; d++) assertEquals(r, a.rate(day + d + 0.5), 0.0, "fixed between reviews");
            var dec = a.decisionOn(day);
            if (q > 0) {
                assertTrue(dec.isPresent());
                if (dec.get().move() == CentralBank.Move.RAISE) raises++;
                if (dec.get().move() == CentralBank.Move.CUT) cuts++;
            }
            sum += r;
        }
        assertTrue(differs, "another world, another path");
        assertTrue(raises > 40 && cuts > 40, "both happen: " + raises + " raises, " + cuts + " cuts");
        assertEquals(CentralBank.LONG_RUN, sum / 400, 0.0005, "pulled toward the long-run rate");
        assertTrue(a.decisionOn(3).isEmpty(), "reviews only every 7 days");
    }

    @Test
    void theMarketHearsARateDecisionAfterTheBankActs() {
        CentralBank bank = new CentralBank(5);
        long q = 1;
        while (bank.decisionOn(q * 7).orElseThrow().move() == CentralBank.Move.HOLD) q++;
        var d = bank.decisionOn(q * 7).orElseThrow();
        double before = bank.rate(d.day() - 0.5);
        assertEquals(d.rate(), bank.rate(d.day() + 0.01), 0.0, "the bank applies it at dawn");
        assertEquals(before, bank.marketRate(d.day() + d.delay() - 0.01), 0.0, "bond prices haven't heard yet");
        assertEquals(d.rate(), bank.marketRate(d.day() + d.delay() + 0.01), 0.0, "then they have");
        assertTrue(bank.yield(d.day() + 1, 8) > bank.yield(d.day() + 1, 2), "longer maturities yield a little more");
    }

    @Test
    void aBondIssuedAtItsYieldPricesAtFaceAndFallsWhenRatesRise() {
        double y = 0.003;
        Bond shortB = new Bond(Bond.TREASURY, 0, 14, y), longB = new Bond(Bond.TREASURY, 0, 56, y);
        assertEquals(Bond.FACE_CENTS, BondMath.price(shortB, 0, y), Bond.FACE_CENTS * 0.001, "par at issue");
        assertEquals(Bond.FACE_CENTS, BondMath.price(longB, 0, y), Bond.FACE_CENTS * 0.002, "par at issue");
        assertEquals(211, shortB.couponCents(), "$100 at 0.3% a day for 7 days: $2.11 a quarter");
        double up = y + CentralBank.STEP;
        double shortDrop = 1 - BondMath.price(shortB, 0, up) / BondMath.price(shortB, 0, y);
        double longDrop = 1 - BondMath.price(longB, 0, up) / BondMath.price(longB, 0, y);
        assertTrue(shortDrop > 0 && longDrop > 3 * shortDrop, "a raise hurts both, the long bond far more: " + shortDrop + " vs " + longDrop);
        assertTrue(BondMath.price(longB, 0, y - CentralBank.STEP) > BondMath.price(longB, 0, y), "and a cut helps");
        assertTrue(BondMath.duration(longB, 0, y) > 3 * BondMath.duration(shortB, 0, y));
    }

    @Test
    void couponsFallDueEachQuarterAndTheFaceAtMaturity() {
        Bond b = new Bond(Bond.TREASURY, 7, 35, 0.003); // 4 quarters from day 7
        assertEquals(4, b.coupons());
        assertEquals(0, b.couponsDueBy(13.9));
        assertEquals(1, b.couponsDueBy(14));
        assertEquals(4, b.couponsDueBy(100), "no more than it has");
        assertEquals(2 * b.couponCents(), BondMath.couponsOwed(b, 1, 28.5), "paid through 1, 3 due: 2 owed");
        assertEquals(0, BondMath.price(b, 35, 0.003), 0.0, "matured: only the face, paid at the desk");
        assertTrue(b.matured(35) && !b.matured(34.9));
        assertEquals(b.series(), new Bond(Bond.TREASURY, 7, 35, 0.003).series(), "same terms, same paper");
    }

    static Equities.Report report(long q, long earnings) {
        return new Equities.Report("X", q, 1_000_000, 1_000_000 - earnings, earnings, 0);
    }

    @Test
    void spreadsWidenAsEarningsFallAndTwoLossesMeanDefault() {
        double base = 0.0003, normal = 100_000;
        assertEquals(base, CreditModel.spread(base, List.of(report(0, 100_000), report(1, 120_000)), normal), 1e-12, "healthy: base");
        double half = CreditModel.spread(base, List.of(report(0, 50_000), report(1, 50_000)), normal);
        double loss = CreditModel.spread(base, List.of(report(0, -20_000), report(1, -10_000)), normal);
        assertTrue(half > base && loss > half, "wider as earnings fall: " + half + ", " + loss);
        assertTrue(CreditModel.defaultQuarter(List.of(report(0, 10), report(1, -5), report(2, 7))).isEmpty(), "one loss isn't default");
        assertEquals(3L, CreditModel.defaultQuarter(List.of(report(0, 10), report(1, 5), report(2, -5), report(3, -1))).orElseThrow());
        assertEquals(4_000, CreditModel.recoveryCents(), "40% of $100");
        var companies = CompanyCatalog.loadDefault();
        assertTrue(companies.company("OWL").creditSpread() < companies.company("RSD").creditSpread(), "steady companies borrow cheaper");
    }
}
