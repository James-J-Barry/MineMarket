package com.realisticmarkets.exchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Bars (open, high, low, close, volume, traded value) of clearing prices per instrument, one per quarter of an
 * in-game day, the data behind the Floor's "today" line and the Ticker Tape's Price Chart. Quarter-day bars show a
 * price reacting within the day, which daily bars would hide. Keeps the last {@link #KEEP_DAYS} days.
 */
public final class PriceHistory {
    public static final String HEADER = "# Realistic Markets price history v2";
    public static final int KEEP_DAYS = 30;
    public static final int PERIODS_PER_DAY = 4;

    /**
     * @param period quarter-days since day 0 (a whole-day bar starts at its day's first period)
     * @param value  sum of price x quantity traded, in cents
     */
    public record Bar(long period, long open, long high, long low, long close, long volume, long value) {
        Bar with(long price, long qty) {
            return new Bar(period, open, Math.max(high, price), Math.min(low, price), price, volume + qty, value + price * qty);
        }

        public long day() {
            return Math.floorDiv(period, PERIODS_PER_DAY);
        }

        /** Volume-weighted average price in cents (the close if nothing traded). */
        public long average() {
            return volume == 0 ? close : Math.round(value / (double) volume);
        }
    }

    private final Map<String, NavigableMap<Long, Bar>> bars = new TreeMap<>();

    public static long period(double day) {
        return (long) Math.floor(day * PERIODS_PER_DAY);
    }

    public void record(String instrument, double day, long price, long qty) {
        long p = period(day);
        NavigableMap<Long, Bar> m = bars.computeIfAbsent(instrument, i -> new TreeMap<>());
        m.merge(p, new Bar(p, price, price, price, price, qty, price * qty), (old, fresh) -> old.with(price, qty));
        while (!m.isEmpty() && m.firstKey() <= p - (long) KEEP_DAYS * PERIODS_PER_DAY) m.pollFirstEntry();
    }

    /** Quarter-day bars for the {@code days} days up to and including {@code toDay}, oldest first; gaps skipped. */
    public List<Bar> periods(String instrument, long toDay, int days) {
        NavigableMap<Long, Bar> m = bars.get(instrument);
        if (m == null) return List.of();
        long end = (toDay + 1) * PERIODS_PER_DAY, start = end - (long) days * PERIODS_PER_DAY;
        return new ArrayList<>(m.subMap(start, true, end, false).values());
    }

    /** Whole-day bars for the {@code days} days up to and including {@code toDay}, oldest first; days with no trades skipped. */
    public List<Bar> lastDays(String instrument, long toDay, int days) {
        List<Bar> out = new ArrayList<>();
        for (Bar b : periods(instrument, toDay, days)) {
            Bar last = out.isEmpty() ? null : out.getLast();
            if (last != null && last.day() == b.day()) {
                out.set(out.size() - 1, new Bar(last.period(), last.open(), Math.max(last.high(), b.high()),
                        Math.min(last.low(), b.low()), b.close(), last.volume() + b.volume(), last.value() + b.value()));
            } else {
                out.add(new Bar(b.day() * PERIODS_PER_DAY, b.open(), b.high(), b.low(), b.close(), b.volume(), b.value()));
            }
        }
        return out;
    }

    /** The whole-day bar for {@code day}, if anything traded. */
    public Optional<Bar> day(String instrument, long day) {
        List<Bar> d = lastDays(instrument, day, 1);
        return d.isEmpty() ? Optional.empty() : Optional.of(d.getFirst());
    }

    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        for (Map.Entry<String, NavigableMap<Long, Bar>> e : bars.entrySet()) {
            for (Bar b : e.getValue().values()) {
                w.write("bar\t" + e.getKey() + "\t" + b.period() + "\t" + b.open() + "\t" + b.high() + "\t" + b.low() + "\t"
                        + b.close() + "\t" + b.volume() + "\t" + b.value() + "\n");
            }
        }
        w.flush();
    }

    /** Reads v2 (quarter-day bars) and v1 (daily bars: kept as the day's first quarter, value = close x volume). */
    public static PriceHistory read(Reader r) throws IOException {
        PriceHistory h = new PriceHistory();
        BufferedReader br = new BufferedReader(r);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            try {
                if (!c[0].equals("bar") || (c.length != 8 && c.length != 9)) throw new IllegalArgumentException("expected 8 or 9 columns");
                boolean v1 = c.length == 8;
                long close = Long.parseLong(c[6]), volume = Long.parseLong(c[7]);
                long period = v1 ? Long.parseLong(c[2]) * PERIODS_PER_DAY : Long.parseLong(c[2]);
                Bar b = new Bar(period, Long.parseLong(c[3]), Long.parseLong(c[4]), Long.parseLong(c[5]), close, volume,
                        v1 ? close * volume : Long.parseLong(c[8]));
                h.bars.computeIfAbsent(c[1], i -> new TreeMap<>()).put(b.period(), b);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("history line " + n + " is malformed: '" + t + "'", e);
            }
        }
        return h;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PriceHistory p && bars.equals(p.bars);
    }

    @Override
    public int hashCode() {
        return bars.hashCode();
    }
}
