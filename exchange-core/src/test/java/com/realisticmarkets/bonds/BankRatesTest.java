package com.realisticmarkets.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.contracts.BankAccount;
import com.realisticmarkets.contracts.BankParams;
import com.realisticmarkets.contracts.Cd;
import com.realisticmarkets.rates.CentralBank;
import org.junit.jupiter.api.Test;

class BankRatesTest {
    final BankParams params = BankParams.loadDefault();

    @Test
    void atTheStartingRateNothingChanges() {
        assertEquals(CentralBank.START, params.interestRate(), 0.0, "the configured vault rate is the central bank's start");
        assertEquals(params.term(7).dailyRate(), params.term(7, CentralBank.START).dailyRate(), 0.0);
        assertEquals(0.0, params.shift(CentralBank.START), 0.0);
    }

    @Test
    void theVaultPaysEachDaysRate() {
        BankAccount a = new BankAccount(0);
        a.deposit(100_000, 0, BankAccount.Kind.DEPOSIT);
        BankAccount b = new BankAccount(0);
        b.deposit(100_000, 0, BankAccount.Kind.DEPOSIT);
        a.accrueTo(14, 0.003);
        b.accrueTo(14, d -> d < 7 ? 0.003 : 0.0035); // raised after a week
        assertTrue(b.balanceCents() > a.balanceCents(), "a raise pays savers more");
        BankAccount c = new BankAccount(0);
        c.deposit(100_000, 0, BankAccount.Kind.DEPOSIT);
        c.accrueTo(14, d -> 0.003);
        assertEquals(a.balanceCents(), c.balanceCents(), "same rate every day: same as before");
    }

    @Test
    void newCdsFollowTheRateAndOldOnesKeepTheirs() {
        Cd before = Cd.issue(100_000, params.term(21, CentralBank.START), 0, params);
        double raised = CentralBank.START + 2 * CentralBank.STEP;
        Cd after = Cd.issue(100_000, params.term(21, raised), 7, params);
        assertEquals(before.dailyRate() + 2 * CentralBank.STEP, after.dailyRate(), 1e-12, "a new CD keeps its premium over the vault");
        assertTrue(after.valueAtMaturityCents() > before.valueAtMaturityCents());
        assertEquals(params.term(21).dailyRate(), before.dailyRate(), 0.0, "the old one locked in its rate");
    }

    @Test
    void loansFloatWithTheRate() {
        var dealer = new com.realisticmarkets.dealer.Dealer(com.realisticmarkets.dealer.DealerCatalog.loadDefault(),
                com.realisticmarkets.dealer.DealerParams.noDrift(), 1L);
        var items = java.util.Map.of("minecraft:diamond", 15);
        var start = com.realisticmarkets.collateral.Loan.open(items, 0, 50_000, dealer, 0);
        var raised = com.realisticmarkets.collateral.Loan.open(items, 0, 50_000, dealer, 0, CentralBank.STEP);
        assertEquals(start.dailyRate() + CentralBank.STEP, raised.dailyRate(), 1e-12, "opened after a raise: dearer");
        com.realisticmarkets.collateral.MarginCheck.atDawn(start, dealer, 1, 2 * CentralBank.STEP);
        assertEquals(raised.dailyRate() + CentralBank.STEP, start.dailyRate(), 1e-9, "an open loan resets to today's rate at dawn");
    }
}
