package com.realisticmarkets.contracts;

import com.realisticmarkets.money.Money;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Certificate of Deposit: principal locked for {@code termDays} at a daily rate, compounded daily. At or after
 * maturity it pays principal x (1 + r)^term, rounded down to the dime; redeemed early it returns the principal only.
 */
public record Cd(long principalCents, double dailyRate, int termDays, long issueDay) {
    public Cd {
        if (principalCents <= 0) throw new IllegalArgumentException("principal must be positive");
        if (termDays <= 0) throw new IllegalArgumentException("term must be positive");
    }

    public static Cd issue(long principalCents, BankParams.CdTerm term, long day, BankParams params) {
        if (principalCents < params.cdMinimumCents()) {
            throw new com.realisticmarkets.exchange.RejectedException(
                    "The minimum CD is " + Money.format(params.cdMinimumCents()));
        }
        if (principalCents % Money.DIME != 0) throw new IllegalArgumentException("principal must be whole dimes");
        return new Cd(principalCents, term.dailyRate(), term.days(), day);
    }

    public long maturityDay() {
        return issueDay + termDays;
    }

    public boolean maturedBy(long day) {
        return day >= maturityDay();
    }

    public long valueAtMaturityCents() {
        return Money.roundDownToDime(principalCents * Math.pow(1 + dailyRate, termDays));
    }

    public long redemptionValueCents(long day) {
        return maturedBy(day) ? valueAtMaturityCents() : principalCents;
    }

    /** Terms as stored in the security registry. */
    public Map<String, String> terms() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("principal", Long.toString(principalCents));
        m.put("rate", Double.toString(dailyRate));
        m.put("term", Integer.toString(termDays));
        m.put("issued", Long.toString(issueDay));
        return m;
    }

    public static Cd fromTerms(Map<String, String> t) {
        return new Cd(Long.parseLong(t.get("principal")), Double.parseDouble(t.get("rate")),
                Integer.parseInt(t.get("term")), Long.parseLong(t.get("issued")));
    }
}
