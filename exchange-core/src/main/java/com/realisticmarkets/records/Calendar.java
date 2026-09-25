package com.realisticmarkets.records;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Records Terminal's calendar: what falls due on which in-game day (coupons, maturities, earnings reports, rate
 * decisions, margin checks). The mod layer adds entries from linked holdings; this sorts them and keeps the next few.
 */
public final class Calendar {
    public enum Kind {
        COUPON("Coupon"),
        BOND_MATURITY("Bond matures"),
        CD_MATURITY("CD matures"),
        EARNINGS("Earnings"),
        RATE_DECISION("Rate decision"),
        MARGIN_CHECK("Margin check"),
        FORWARD_DELIVERY("Forward due"),
        FUTURES_EXPIRY("Futures expire"),
        OPTION_EXPIRY("Option expires");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() { return label; }
    }

    /** {@code what} names the holding or company ("" for rate decisions); {@code cents} is the amount due, 0 if none. */
    public record Entry(long day, Kind kind, String what, long cents) {}

    private final List<Entry> entries = new ArrayList<>();

    public Calendar add(Entry e) {
        entries.add(e);
        return this;
    }

    public Calendar add(long day, Kind kind, String what, long cents) {
        return add(new Entry(day, kind, what, cents));
    }

    /**
     * Entries on or after {@code today}, soonest first, at most {@code max}. Same-day entries of the same kind and
     * name are merged (their amounts add up), so ten boxes of one bond show as one coupon.
     */
    public List<Entry> upcoming(long today, int max) {
        List<Entry> sorted = new ArrayList<>(entries.stream().filter(e -> e.day() >= today).toList());
        sorted.sort(Comparator.comparingLong(Entry::day).thenComparing(Entry::kind).thenComparing(Entry::what));
        List<Entry> out = new ArrayList<>();
        for (Entry e : sorted) {
            Entry last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.day() == e.day() && last.kind() == e.kind() && last.what().equals(e.what())) {
                out.set(out.size() - 1, new Entry(e.day(), e.kind(), e.what(), last.cents() + e.cents()));
            } else if (out.size() < max) {
                out.add(e);
            }
        }
        return out;
    }

    /** The next dawn strictly after {@code today} that falls on a multiple of {@code period} (reviews, reports). */
    public static long nextEvery(long today, long period) {
        return Math.floorDiv(today, period) * period + period;
    }
}
