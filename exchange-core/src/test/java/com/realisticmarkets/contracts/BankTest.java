package com.realisticmarkets.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.exchange.RejectedException;
import java.io.StringReader;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class BankTest {
    final BankParams params = BankParams.loadDefault();

    @Test
    void defaultParams() {
        assertEquals(0.003, params.interestRate());
        assertEquals(0.0045, params.term(7).dailyRate());
        assertEquals(0.006, params.term(21).dailyRate());
        assertEquals(10_000, params.cdMinimumCents());
    }

    // ---- account

    @Test
    void compoundsDailyAndCreditsWholeDimes() {
        BankAccount a = new BankAccount(0);
        a.deposit(100_000, 0, BankAccount.Kind.DEPOSIT); // $1,000
        long credited = a.accrueTo(10, params.interestRate());
        double exact = 100_000 * (Math.pow(1.003, 10) - 1); // $30.41
        assertTrue(Math.abs(credited - exact) < 10, "credited " + credited + " vs exact " + exact);
        assertEquals(0, a.balanceCents() % 10, "balance stays in whole dimes");
        assertEquals(credited, a.interestTotalCents());
    }

    @Test
    void smallBalancesStillEarnThroughTheCarry() {
        BankAccount a = new BankAccount(0);
        a.deposit(2_000, 0, BankAccount.Kind.DEPOSIT); // $20 earns 6 cents a day
        a.accrueTo(30, params.interestRate());
        assertTrue(a.interestTotalCents() >= 170, "30 days on $20 is about $1.80, got " + a.interestTotalCents());
    }

    @Test
    void interestIsLazyAndOnlyForFullDays() {
        BankAccount a = new BankAccount(5);
        a.deposit(100_000, 5, BankAccount.Kind.DEPOSIT);
        assertEquals(0, a.accrueTo(5, params.interestRate()), "no full day yet: a paused world earns nothing");
        long one = a.accrueTo(6, params.interestRate());
        assertEquals(300, one, "one day of 0.3% on $1,000");
        assertEquals(0, a.accrueTo(6, params.interestRate()), "no double credit");
        assertEquals(0, a.accrueTo(4, params.interestRate()), "time never runs backwards");
    }

    @Test
    void withdrawalsCantOverdrawAndTheLogIsCapped() {
        BankAccount a = new BankAccount(0);
        a.deposit(5_000, 0, BankAccount.Kind.DEPOSIT);
        assertThrows(RejectedException.class, () -> a.withdraw(5_010, 0, BankAccount.Kind.WITHDRAW));
        a.withdraw(5_000, 0, BankAccount.Kind.WITHDRAW);
        assertTrue(a.isEmpty());
        for (int i = 0; i < 150; i++) a.deposit(10, i, BankAccount.Kind.DEPOSIT);
        assertEquals(BankAccount.LOG_SIZE, a.log().size());
        assertEquals(1500, a.log().getLast().balanceCents());
    }

    @Test
    void accountRoundTrips() throws Exception {
        BankAccount a = new BankAccount(3);
        a.deposit(12_340, 3, BankAccount.Kind.DEPOSIT);
        a.accrueTo(9, params.interestRate());
        a.withdraw(1_000, 9, BankAccount.Kind.WITHDRAW);
        StringWriter w = new StringWriter();
        a.write(w);
        assertEquals(a, BankAccount.read(new StringReader(w.toString())));
        assertThrows(IllegalArgumentException.class, () -> BankAccount.read(new StringReader("log\t1\tNOPE\t1\t1\n")));
    }

    // ---- certificates of deposit

    @Test
    void cdPaysCompoundInterestAtMaturityRoundedDown() {
        Cd seven = Cd.issue(50_000, params.term(7), 10, params);
        assertEquals(17, seven.maturityDay());
        assertEquals(51_590, seven.valueAtMaturityCents(), "$500 for 7 days at 0.45%/day = $515.96 -> $515.90");
        Cd twentyOne = Cd.issue(100_000, params.term(21), 0, params);
        long expected = (long) Math.floor(100_000 * Math.pow(1.006, 21) / 10) * 10;
        assertEquals(expected, twentyOne.valueAtMaturityCents());
        assertTrue(twentyOne.valueAtMaturityCents() - 100_000 > 3 * (seven.valueAtMaturityCents() - 50_000),
                "the long CD pays more per dollar per day");
    }

    @Test
    void earlyRedemptionReturnsPrincipalOnly() {
        Cd cd = Cd.issue(50_000, params.term(7), 10, params);
        assertEquals(50_000, cd.redemptionValueCents(16));
        assertEquals(51_590, cd.redemptionValueCents(17));
        assertEquals(51_590, cd.redemptionValueCents(40), "no extra interest after maturity");
    }

    @Test
    void cdMinimumAndTermsRoundTrip() {
        assertThrows(RejectedException.class, () -> Cd.issue(9_990, params.term(7), 0, params));
        assertThrows(IllegalArgumentException.class, () -> params.term(14));
        Cd cd = Cd.issue(25_000, params.term(21), 4, params);
        assertEquals(cd, Cd.fromTerms(cd.terms()));
    }
}
