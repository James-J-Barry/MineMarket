package com.realisticmarkets.money;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Cent arithmetic, rounding rules and change-making. */
public final class Money {
    public static final long DIME = 10;

    private Money() {}

    /** Dealer rounding on sales: in the Dealer's favor. */
    public static long roundDownToDime(double cents) {
        if (cents <= 0) return 0;
        return (long) Math.floor(cents / DIME + 1e-9) * DIME;
    }

    /** Dealer rounding on purchases: in the Dealer's favor. */
    public static long roundUpToDime(double cents) {
        if (cents <= 0) return 0;
        return (long) Math.ceil(cents / DIME - 1e-9) * DIME;
    }

    public static long dollarsToCents(double dollars) {
        return Math.round(dollars * 100.0);
    }

    public static String format(long cents) {
        String sign = cents < 0 ? "-" : "";
        long abs = Math.abs(cents);
        return String.format(Locale.ROOT, "%s$%,d.%02d", sign, abs / 100, abs % 100);
    }

    /**
     * Fewest physical items for an amount. The denominations are a canonical coin system
     * (each divides the next), so the greedy algorithm is optimal.
     */
    public static Map<Denomination, Long> makeChange(long cents) {
        if (cents < 0) throw new IllegalArgumentException("negative amount");
        if (cents % DIME != 0) throw new IllegalArgumentException("not a multiple of 10 cents: " + cents);
        Map<Denomination, Long> out = new EnumMap<>(Denomination.class);
        long left = cents;
        for (Denomination d : Denomination.values()) { // declared largest first
            long n = left / d.cents();
            if (n > 0) {
                out.put(d, n);
                left -= n * d.cents();
            }
        }
        return out;
    }

    public static long total(Map<Denomination, Long> items) {
        long sum = 0;
        for (Map.Entry<Denomination, Long> e : items.entrySet()) {
            sum = Math.addExact(sum, Math.multiplyExact(e.getKey().cents(), e.getValue()));
        }
        return sum;
    }
}
