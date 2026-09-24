package com.realisticmarkets.exchange;

import java.util.Arrays;
import java.util.List;

/**
 * What a printed Price Chart holds: {@link #DAYS} in-game days of one book in quarter-day points (high, low and close
 * of each quarter), ending with the day it was printed. Quarters with no trade carry the last close forward so the
 * line stays unbroken; quarters before the first trade in the window are {@link #NONE}.
 */
public record PriceChart(String item, long toDay, long[] high, long[] low, long[] close) {
    public static final int DAYS = 7;
    public static final int POINTS = DAYS * PriceHistory.PERIODS_PER_DAY;
    public static final long NONE = -1;
    private static final String BLOCKS = "▁▂▃▄▅▆▇█";

    public static PriceChart of(PriceHistory h, String item, long toDay) {
        long[] hi = new long[POINTS], lo = new long[POINTS], cl = new long[POINTS];
        Arrays.fill(hi, NONE);
        Arrays.fill(lo, NONE);
        Arrays.fill(cl, NONE);
        long first = (toDay + 1 - DAYS) * PriceHistory.PERIODS_PER_DAY;
        for (PriceHistory.Bar b : h.periods(item, toDay, DAYS)) {
            int i = (int) (b.period() - first);
            hi[i] = b.high();
            lo[i] = b.low();
            cl[i] = b.close();
        }
        for (int i = 1; i < POINTS; i++) {
            if (cl[i] == NONE && cl[i - 1] != NONE) {
                cl[i] = cl[i - 1];
                hi[i] = cl[i];
                lo[i] = cl[i];
            }
        }
        return new PriceChart(item, toDay, hi, lo, cl);
    }

    /** True if nothing traded in the whole window. */
    public boolean empty() {
        return close[POINTS - 1] == NONE;
    }

    public long min() {
        return Arrays.stream(low).filter(v -> v != NONE).min().orElse(0);
    }

    public long max() {
        return Arrays.stream(high).filter(v -> v != NONE).max().orElse(0);
    }

    /** The last close in the window. */
    public long last() {
        return empty() ? 0 : close[POINTS - 1];
    }

    /** The first known close in the window. */
    public long first() {
        for (long c : close) if (c != NONE) return c;
        return 0;
    }

    /** Daily closes, oldest first ({@link #NONE} before the first trade). */
    public long[] dailyCloses() {
        long[] out = new long[DAYS];
        for (int d = 0; d < DAYS; d++) out[d] = close[(d + 1) * PriceHistory.PERIODS_PER_DAY - 1];
        return out;
    }

    /** One block character per day's close, scaled between the week's lowest and highest close. */
    public String sparkline() {
        long[] c = dailyCloses();
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        for (long v : c) {
            if (v == NONE) continue;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        StringBuilder sb = new StringBuilder();
        for (long v : c) {
            if (v == NONE) sb.append(' ');
            else sb.append(BLOCKS.charAt(hi == lo ? 3 : (int) Math.round((v - lo) * 7.0 / (hi - lo))));
        }
        return sb.toString();
    }

    /** For item data: the three series as one int array (high, then low, then close). */
    public int[] packed() {
        int[] out = new int[POINTS * 3];
        for (int i = 0; i < POINTS; i++) {
            out[i] = (int) high[i];
            out[POINTS + i] = (int) low[i];
            out[2 * POINTS + i] = (int) close[i];
        }
        return out;
    }

    public static PriceChart unpack(String item, long toDay, int[] packed) {
        if (packed.length != POINTS * 3) throw new IllegalArgumentException("chart data has " + packed.length + " values");
        long[] hi = new long[POINTS], lo = new long[POINTS], cl = new long[POINTS];
        for (int i = 0; i < POINTS; i++) {
            hi[i] = packed[i];
            lo[i] = packed[POINTS + i];
            cl[i] = packed[2 * POINTS + i];
        }
        return new PriceChart(item, toDay, hi, lo, cl);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PriceChart p && item.equals(p.item) && toDay == p.toDay && Arrays.equals(high, p.high)
                && Arrays.equals(low, p.low) && Arrays.equals(close, p.close);
    }

    @Override
    public int hashCode() {
        return item.hashCode() * 31 + Long.hashCode(toDay) * 17 + Arrays.hashCode(close);
    }

    @Override
    public String toString() {
        return "PriceChart[" + item + " to day " + toDay + " " + List.of(Arrays.stream(close).boxed().toArray()) + "]";
    }
}
