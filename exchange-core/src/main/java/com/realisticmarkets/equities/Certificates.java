package com.realisticmarkets.equities;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Share Certificate arithmetic. A certificate is a bearer paper for {@link #DENOMINATIONS} shares of one company,
 * carrying the last quarter its dividends were paid through. Certificates are fungible (no serial), like bills.
 */
public final class Certificates {
    /** Largest first. */
    public static final int[] DENOMINATIONS = {100, 10, 1};

    private Certificates() {}

    public static boolean valid(int denomination) {
        for (int d : DENOMINATIONS) if (d == denomination) return true;
        return false;
    }

    /** The fewest certificates for {@code shares}: denomination to count, largest first. */
    public static Map<Integer, Long> fewest(long shares) {
        if (shares < 0) throw new IllegalArgumentException("negative shares");
        Map<Integer, Long> out = new LinkedHashMap<>();
        long left = shares;
        for (int d : DENOMINATIONS) {
            if (left >= d) out.put(d, left / d);
            left %= d;
        }
        return out;
    }

    /** One step smaller: a 100 splits into ten 10s, a 10 into ten 1s. Zero for a 1. */
    public static int splitInto(int denomination) {
        return switch (denomination) {
            case 100 -> 10;
            case 10 -> 1;
            default -> 0;
        };
    }

    /** Dividends (cents) owed on {@code shares} shares paid through {@code paidThrough}. */
    public static long owed(Equities eq, String ticker, long shares, long paidThrough) {
        return shares * eq.dividendsSince(ticker, paidThrough);
    }
}
