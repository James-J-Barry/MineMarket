package com.realisticmarkets.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.equities.CompanyCatalog;
import com.realisticmarkets.equities.Equities;
import com.realisticmarkets.rates.CentralBank;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;

class BondDeskTest {
    static final DealerCatalog DEALER = DealerCatalog.loadDefault();

    static double base(String k) {
        return DEALER.trades(k) ? DEALER.spec(k).fairValue() : 1.0;
    }

    static Equities companies(int quarters, ToDoubleFunction<String> price) {
        Equities e = new Equities(CompanyCatalog.loadDefault(), BondDeskTest::base, 3);
        for (long d = 0; d <= quarters * 7L; d++) e.observe(d, price, List.of());
        return e;
    }

    @Test
    void newBondsIssueAtParAndCompaniesPayMore() {
        BondDesk desk = new BondDesk(CentralBank.constant(0.003), companies(0, BondDeskTest::base));
        Bond t = desk.issue(Bond.TREASURY, 4, 0), owl = desk.issue("OWL", 4, 0), rsd = desk.issue("RSD", 4, 0);
        assertEquals(Bond.FACE_CENTS, desk.price(t, 0), Bond.FACE_CENTS * 0.002, "par");
        assertTrue(t.couponRate() < owl.couponRate() && owl.couponRate() < rsd.couponRate(), "riskier issuers pay more");
        assertEquals(10_025, desk.issuePrice(t), "$100 plus the desk's 0.25%");
        assertTrue(desk.bid(t, 0) < Bond.FACE_CENTS, "and it buys back a little under");
    }

    @Test
    void aRateRiseLowersTheDesksBidForOldBonds() {
        CentralBank bank = new CentralBank(11);
        long q = 1;
        while (bank.decisionOn(q * 7).orElseThrow().move() != CentralBank.Move.RAISE) q++;
        var d = bank.decisionOn(q * 7).orElseThrow();
        BondDesk desk = new BondDesk(bank, null);
        Bond b = desk.issue(Bond.TREASURY, 8, d.day() - 3);
        long dawn = desk.bid(b, d.day() + 0.01), before = desk.bid(b, d.day() + d.delay() - 0.01);
        long heard = desk.bid(b, d.day() + d.delay() + 0.01);
        assertTrue(before >= dawn, "the bank raised at dawn, but bond prices haven't heard yet: " + dawn + " -> " + before);
        assertTrue(heard < before - 50, "once the market hears, the old bond is worth less: " + before + " -> " + heard);
    }

    @Test
    void twoLosingQuartersDefaultTheBondsOutstanding() {
        ToDoubleFunction<String> slump = k -> base(k) * (k.startsWith("minecraft:") ? 0.3 : 1);
        Equities e = new Equities(CompanyCatalog.loadDefault(), BondDeskTest::base, 3);
        BondDesk desk = new BondDesk(CentralBank.constant(0.003), e);
        e.observe(0, BondDeskTest::base, List.of());
        Bond dsmc = desk.issue("DSMC", 8, 0), owl = desk.issue("OWL", 8, 0);
        for (long d = 1; d <= 14; d++) e.observe(d, slump, List.of()); // quarters 0 and 1 are losses
        assertTrue(e.reports("DSMC").get(0).earnings() < 0 && e.reports("DSMC").get(1).earnings() < 0, "two losses");
        assertEquals(14L, desk.defaultDay(dsmc).orElseThrow(), "defaults at the second report");
        assertTrue(desk.defaulted(dsmc, 15) && !desk.defaulted(dsmc, 13));
        assertEquals(CreditModel.recoveryCents(), desk.price(dsmc, 15), 0.0, "worth the recovery");
        assertEquals(CreditModel.recoveryCents(), desk.redemption(dsmc, 15));
        assertEquals(dsmc.couponCents(), desk.couponsOwed(dsmc, 0, 30), "the coupon before the default is still owed; none after");
        assertTrue(desk.defaultDay(owl).isEmpty() || e.reports("OWL").get(1).earnings() < 0, "OWL has fees, not goods");
        assertTrue(e.cashPerShareCents("DSMC") >= 0, "the default wrote off its debts");
        Equities noRestructure = new Equities(CompanyCatalog.loadDefault(), BondDeskTest::base, 3);
        for (long d = 0; d <= 14; d++) noRestructure.observe(d, d == 0 ? BondDeskTest::base : slump, List.of());
        for (long d = 15; d <= 21; d++) {
            e.observe(d, BondDeskTest::base, List.of());
            noRestructure.observe(d, BondDeskTest::base, List.of());
        }
        assertTrue(e.reports("DSMC").get(2).costs() < e.reports("DSMC").get(0).costs(), "and cut its fixed costs");
        Bond afterwards = desk.issue("DSMC", 2, 15);
        assertTrue(desk.defaultDay(afterwards).isEmpty(), "a bond issued after the default isn't caught by it");
        assertTrue(afterwards.couponRate() > dsmc.couponRate(), "but the troubled company pays far more to borrow");
    }

    @Test
    void maturityPaysTheFace() {
        BondDesk desk = new BondDesk(CentralBank.constant(0.003), null);
        Bond b = desk.issue(Bond.TREASURY, 2, 0);
        assertEquals(0, desk.redemption(b, 13));
        assertEquals(Bond.FACE_CENTS, desk.redemption(b, 14));
        assertEquals(2 * b.couponCents(), desk.couponsOwed(b, 0, 14));
        assertEquals(Map.of(), Map.of());
    }
}
