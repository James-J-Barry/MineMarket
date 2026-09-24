package com.realisticmarkets.collateral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.collateral.CollateralValuer.Grade;
import com.realisticmarkets.collateral.CollateralValuer.Valuation;
import com.realisticmarkets.collateral.MarginCheck.Status;
import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.exchange.RejectedException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollateralTest {
    static Dealer dealer() {
        return new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1L);
    }

    static Valuation value(String item, int n) {
        return CollateralValuer.value(Map.of(item, n), 0, dealer(), 0);
    }

    /** Design doc, "Worked example: borrowing with different collateral". Dollars rounded as the table does. */
    static void row(String item, int n, int market, int p, int c, double q, int maxLoan, double ratePct) {
        Valuation v = value(item, n);
        assertEquals(market, v.marketCents() / 100.0, 0.5, item + " market value");
        assertEquals(p, v.liquidationCents() / 100.0, 0.5, item + " P(n)");
        assertEquals(c, v.valueCents() / 100.0, 0.5, item + " C");
        assertEquals(q, v.quality(), 0.005, item + " Q");
        assertEquals(maxLoan, v.maxLoanCents() / 100.0, 0.5, item + " max loan");
        assertEquals(ratePct, v.dailyRate() * 100, 0.005, item + " daily rate");
    }

    @Test
    void workedLoanTableFromTheDesignDoc() {
        row("minecraft:diamond", 15, 1_500, 1_078, 970, 0.65, 776, 0.75);
        row("minecraft:iron_ingot", 256, 2_048, 1_018, 814, 0.40, 651, 1.00);
        row("minecraft:gold_ingot", 64, 960, 631, 568, 0.59, 454, 0.81);
        row("minecraft:oak_log", 1_500, 1_500, 230, 138, 0.09, 110, 1.31);
    }

    @Test
    void noAmountOfOakLogsBacksMoreThanAbout110Dollars() {
        assertTrue(value("minecraft:oak_log", 100_000).maxLoanCents() <= 11_060);
    }

    @Test
    void gradesFollowTheCatalog() {
        DealerCatalog c = DealerCatalog.loadDefault();
        assertEquals(Grade.A, CollateralValuer.gradeOf(c, "minecraft:diamond"));
        assertEquals(Grade.A, CollateralValuer.gradeOf(c, "minecraft:gold_block"), "linked items follow their base");
        assertEquals(Grade.B, CollateralValuer.gradeOf(c, "minecraft:iron_ingot"));
        assertEquals(Grade.C, CollateralValuer.gradeOf(c, "minecraft:oak_log"));
        assertEquals(Grade.C, CollateralValuer.gradeOf(c, "minecraft:wheat"));
        assertEquals(Grade.D, CollateralValuer.gradeOf(c, "minecraft:cobblestone"));
        assertEquals(Grade.D, CollateralValuer.gradeOf(c, "minecraft:rotten_flesh"));
        assertEquals(Grade.D, CollateralValuer.gradeOf(c, "realisticmarkets:ink_bottle"), "components");
        assertEquals(Grade.D, CollateralValuer.gradeOf(c, "minecraft:dirt"), "not traded");
    }

    @Test
    void ingotsAndBlocksAreValuedAsOneSale() {
        Map<String, Integer> mixed = Map.of("minecraft:iron_ingot", 64, "minecraft:iron_block", 5);
        Valuation v = CollateralValuer.value(mixed, 0, dealer(), 0);
        assertEquals(1, v.lines().size());
        assertEquals(109, v.lines().getFirst().units());
        assertEquals(value("minecraft:iron_ingot", 109).liquidationCents(), v.liquidationCents());
    }

    @Test
    void cashCountsAtFaceLessTenPercent() {
        Valuation v = CollateralValuer.value(Map.of(), 10_000, dealer(), 0);
        assertEquals(9_000, v.valueCents());
        assertEquals(0.90, v.quality(), 1e-9);
    }

    @Test
    void refusedCollateralAndOverborrowingAreRejected() {
        Valuation v = CollateralValuer.value(Map.of("minecraft:cobblestone", 64, "minecraft:diamond", 1), 0, dealer(), 0);
        assertEquals(java.util.List.of("minecraft:cobblestone"), v.refused());
        assertThrows(RejectedException.class, () -> Loan.open(Map.of("minecraft:cobblestone", 64), 0, 100, dealer(), 0));
        assertThrows(RejectedException.class, () -> Loan.open(Map.of("minecraft:diamond", 15), 0, 77_600, dealer(), 0));
        assertEquals(77_580, Loan.open(Map.of("minecraft:diamond", 15), 0, 77_580, dealer(), 0).owedCents());
    }

    @Test
    void interestAccruesDailyAtTheCurrentRate() {
        Dealer d = dealer();
        Loan loan = Loan.open(Map.of("minecraft:diamond", 15), 0, 50_000, d, 0);
        double r = loan.dailyRate();
        assertEquals(0, loan.accrueTo(0));
        loan.accrueTo(3);
        assertEquals(50_000 * Math.pow(1 + r, 3), loan.owedCents(), 10);
    }

    @Test
    void theRateFloatsWithCollateralQuality() {
        Dealer d = dealer();
        Loan loan = Loan.open(Map.of("minecraft:diamond", 15), 0, 30_000, d, 0);
        double before = loan.dailyRate();
        d.sell("minecraft:diamond", 32, 0, false); // someone floods the diamond market
        MarginCheck.atDawn(loan, d, 1);
        assertTrue(loan.dailyRate() > before, "rate rose from " + before + " to " + loan.dailyRate());
    }

    @Test
    void marginCallThenLiquidationSellsTheWorstCollateralFirst() {
        Dealer d = dealer();
        Map<String, Integer> posted = new LinkedHashMap<>();
        posted.put("minecraft:diamond", 15);
        posted.put("minecraft:wheat", 256);
        Loan loan = Loan.open(posted, 0, 70_000, d, 0);

        assertEquals(Status.OK, MarginCheck.atDawn(loan, d, 1).status());
        d.sell("minecraft:diamond", 200, 1, false); // diamond price crashes
        MarginCheck.Result call = MarginCheck.atDawn(loan, d, 2);
        assertEquals(Status.CALL_ISSUED, call.status());
        assertTrue(call.coverage() < 1.10);
        assertTrue(loan.underMarginCall());

        MarginCheck.Result sold = MarginCheck.atDawn(loan, d, 3);
        assertEquals(Status.LIQUIDATED, sold.status());
        assertEquals("minecraft:wheat", sold.sales().getFirst().item(), "class C (wheat) goes before class A (diamonds)");
        long wheatSales = sold.sales().stream().filter(s -> s.item().equals("minecraft:wheat")).count();
        long firstDiamond = sold.sales().stream().takeWhile(s -> s.item().equals("minecraft:wheat")).count();
        assertEquals(wheatSales, firstDiamond, "all wheat sold before any diamond");
        assertTrue(sold.coverage() >= 1.10 || loan.repaid() || sold.shortfallCents() > 0,
                "stops when coverage is back, the debt is paid, or collateral runs out");
    }

    @Test
    void liquidationSurplusAndShortfall() {
        Dealer d = dealer();
        Loan loan = Loan.open(Map.of("minecraft:wheat", 256), 0, 3_000, d, 0);
        d.sell("minecraft:wheat", 2_000, 0, false); // wheat crashes: the collateral can't cover the loan
        assertEquals(Status.CALL_ISSUED, MarginCheck.atDawn(loan, d, 1).status());
        long owedBefore = loan.owedCents();
        MarginCheck.Result r = MarginCheck.atDawn(loan, d, 2);
        assertEquals(Status.LIQUIDATED, r.status());
        long proceeds = r.sales().stream().mapToLong(MarginCheck.Sale::cents).sum();
        long paidDown = owedBefore + r.interestCents() - loan.owedCents();
        assertEquals(proceeds, paidDown + r.surplusCents(), "every cent of the sales either pays debt or is surplus");
        assertTrue(loan.collateral().isEmpty() || r.coverage() >= 1.10, "sold until covered or out of collateral");
        if (loan.collateral().isEmpty() && !loan.repaid()) {
            assertEquals(loan.owedCents(), r.shortfallCents(), "what's left is a shortfall, still owed");
        }
    }

    @Test
    void repayingReleasesTheCollateral() {
        Dealer d = dealer();
        Loan loan = Loan.open(Map.of("minecraft:gold_ingot", 64), 0, 20_000, d, 0);
        assertThrows(RejectedException.class, loan::releaseCollateral, "not while money is owed");
        assertEquals(15_000, loan.repay(15_000));
        assertFalse(loan.repaid());
        assertEquals(5_000, loan.repay(99_999), "only what's owed is taken");
        assertTrue(loan.repaid());
        assertEquals(Map.of("minecraft:gold_ingot", 64), loan.releaseCollateral());
        assertTrue(loan.collateral().isEmpty());
    }

    @Test
    void loanRoundTrips() throws Exception {
        Dealer d = dealer();
        Loan loan = Loan.open(Map.of("minecraft:diamond", 15, "minecraft:iron_block", 3), 5_000, 40_000, d, 7);
        loan.accrueTo(9);
        StringWriter w = new StringWriter();
        loan.write(w);
        assertEquals(loan, Loan.read(new StringReader(w.toString())));
        assertNull(Loan.read(new StringReader(Loan.HEADER + "\n")));
        assertThrows(IllegalArgumentException.class, () -> Loan.read(new StringReader("escrow\tminecraft:diamond\t3\n")));
    }
}
