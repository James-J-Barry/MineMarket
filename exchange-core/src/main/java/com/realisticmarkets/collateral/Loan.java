package com.realisticmarkets.collateral;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.exchange.RejectedException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A loan against item collateral held in escrow. Interest accrues once per full in-game day at the rate set at the
 * last dawn check (it floats with collateral quality); like the bank, exact interest carries and whole dimes are
 * added. A margin call, once issued, gives one day's grace (see {@link MarginCheck}).
 */
public final class Loan {
    public static final String HEADER = "# Realistic Markets loan v1";

    private final Map<String, Integer> collateral = new LinkedHashMap<>();
    private long cashCollateralCents;
    private long principalCents;
    private long owedCents;
    private double carryCents;
    private double dailyRate;
    private long openedDay;
    private long lastDay;
    private long callDay = -1;

    private Loan() {}

    /** Opens a loan of {@code principalCents} against the collateral, if it can back that much. */
    public static Loan open(Map<String, Integer> items, long cashCents, long principalCents, Dealer dealer, long day) {
        CollateralValuer.Valuation v = CollateralValuer.value(items, cashCents, dealer, day);
        if (!v.refused().isEmpty()) throw new RejectedException("The bank won't take " + String.join(", ", v.refused()));
        if (principalCents <= 0) throw new RejectedException("Borrow at least a dime");
        if (principalCents % 10 != 0) throw new IllegalArgumentException("principal must be whole dimes");
        if (principalCents > v.maxLoanCents()) {
            throw new RejectedException("That collateral backs at most " + com.realisticmarkets.money.Money.format(v.maxLoanCents()));
        }
        Loan l = new Loan();
        items.forEach((k, n) -> { if (n > 0) l.collateral.merge(k, n, Integer::sum); });
        l.cashCollateralCents = cashCents;
        l.principalCents = principalCents;
        l.owedCents = principalCents;
        l.dailyRate = v.dailyRate();
        l.openedDay = day;
        l.lastDay = day;
        return l;
    }

    public Map<String, Integer> collateral() { return Collections.unmodifiableMap(collateral); }
    public long cashCollateralCents() { return cashCollateralCents; }
    public long principalCents() { return principalCents; }
    public long owedCents() { return owedCents; }
    public double dailyRate() { return dailyRate; }
    public long openedDay() { return openedDay; }
    public boolean underMarginCall() { return callDay >= 0; }
    public long callDay() { return callDay; }
    public boolean repaid() { return owedCents == 0; }

    public CollateralValuer.Valuation value(Dealer dealer, double day) {
        return CollateralValuer.value(collateral, cashCollateralCents, dealer, day);
    }

    /** Adds interest for every full day since the last update, at the current rate. Returns cents added. */
    public long accrueTo(long day) {
        if (day <= lastDay || owedCents == 0) {
            lastDay = Math.max(lastDay, day);
            return 0;
        }
        long added = 0;
        for (long d = lastDay; d < day; d++) {
            carryCents += owedCents * dailyRate;
            long whole = (long) Math.floor(carryCents / 10.0) * 10;
            owedCents += whole;
            carryCents -= whole;
            added += whole;
        }
        lastDay = day;
        return added;
    }

    /** Applies up to {@code cents}; returns what was applied. Paying it all off clears any margin call. */
    public long repay(long cents) {
        long applied = Math.min(Math.max(cents, 0), owedCents);
        owedCents -= applied;
        if (owedCents == 0) {
            carryCents = 0;
            callDay = -1;
        }
        return applied;
    }

    public void addCollateral(Map<String, Integer> items, long cashCents) {
        items.forEach((k, n) -> { if (n > 0) collateral.merge(k, n, Integer::sum); });
        cashCollateralCents += cashCents;
    }

    /** Hands back everything in escrow; only allowed once the loan is repaid. */
    public Map<String, Integer> releaseCollateral() {
        if (owedCents > 0) throw new RejectedException("Repay the loan first");
        Map<String, Integer> out = new LinkedHashMap<>(collateral);
        collateral.clear();
        return out;
    }

    public long releaseCash() {
        if (owedCents > 0) throw new RejectedException("Repay the loan first");
        long c = cashCollateralCents;
        cashCollateralCents = 0;
        return c;
    }

    // ---- used by MarginCheck

    void setRate(double rate) { dailyRate = rate; }
    void setCallDay(long day) { callDay = day; }

    /** Removes {@code n} of {@code item} from escrow (after selling it). */
    void takeCollateral(String item, int n) {
        int left = collateral.getOrDefault(item, 0) - n;
        if (left < 0) throw new IllegalStateException("escrow holds fewer " + item);
        if (left == 0) collateral.remove(item);
        else collateral.put(item, left);
    }

    long takeCash(long cents) {
        long t = Math.min(cents, cashCollateralCents);
        cashCollateralCents -= t;
        return t;
    }

    // ------------------------------------------------------------------ persistence

    /**
     * <pre>
     * # Realistic Markets loan v1
     * loan	principal	owed	carry	rate	opened	last_day	call_day	cash_collateral
     * escrow	minecraft:diamond	15
     * </pre>
     */
    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        w.write("loan\t" + principalCents + "\t" + owedCents + "\t" + carryCents + "\t" + dailyRate + "\t" + openedDay
                + "\t" + lastDay + "\t" + callDay + "\t" + cashCollateralCents + "\n");
        for (Map.Entry<String, Integer> e : collateral.entrySet()) w.write("escrow\t" + e.getKey() + "\t" + e.getValue() + "\n");
        w.flush();
    }

    /** Reads a loan; returns null for a file with no loan line. */
    public static Loan read(Reader r) throws IOException {
        Loan l = null;
        Map<String, Integer> escrow = new LinkedHashMap<>();
        BufferedReader br = new BufferedReader(r);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            try {
                switch (c[0]) {
                    case "loan" -> {
                        l = new Loan();
                        l.principalCents = Long.parseLong(c[1]);
                        l.owedCents = Long.parseLong(c[2]);
                        l.carryCents = Double.parseDouble(c[3]);
                        l.dailyRate = Double.parseDouble(c[4]);
                        l.openedDay = Long.parseLong(c[5]);
                        l.lastDay = Long.parseLong(c[6]);
                        l.callDay = Long.parseLong(c[7]);
                        l.cashCollateralCents = Long.parseLong(c[8]);
                    }
                    case "escrow" -> escrow.merge(c[1], Integer.parseInt(c[2]), Integer::sum);
                    default -> throw new IllegalArgumentException("unknown key " + c[0]);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("loan line " + n + " is malformed: '" + t + "'", e);
            }
        }
        if (l == null) {
            if (!escrow.isEmpty()) throw new IllegalArgumentException("escrow lines without a loan line");
            return null;
        }
        l.collateral.putAll(escrow);
        return l;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Loan x && principalCents == x.principalCents && owedCents == x.owedCents
                && carryCents == x.carryCents && dailyRate == x.dailyRate && openedDay == x.openedDay
                && lastDay == x.lastDay && callDay == x.callDay && cashCollateralCents == x.cashCollateralCents
                && collateral.equals(x.collateral);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(owedCents) * 31 + collateral.hashCode();
    }
}
