package com.realisticmarkets.exchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Daily bars (open, high, low, close, volume) of clearing prices per instrument, the data behind the Ticker Tape's
 * Price Chart. Keeps the last {@link #KEEP_DAYS} days.
 */
public final class PriceHistory {
    public static final String HEADER = "# Realistic Markets price history v1";
    public static final int KEEP_DAYS = 30;

    public record Bar(long day, long open, long high, long low, long close, long volume) {
        Bar with(long price, long qty) {
            return new Bar(day, open, Math.max(high, price), Math.min(low, price), price, volume + qty);
        }
    }

    private final Map<String, NavigableMap<Long, Bar>> bars = new TreeMap<>();

    public void record(String instrument, long day, long price, long qty) {
        NavigableMap<Long, Bar> m = bars.computeIfAbsent(instrument, i -> new TreeMap<>());
        m.merge(day, new Bar(day, price, price, price, price, qty), (old, fresh) -> old.with(price, qty));
        while (m.size() > KEEP_DAYS) m.pollFirstEntry();
    }

    /** Bars for days in {@code (toDay - days, toDay]}, oldest first; days with no trades are skipped. */
    public List<Bar> lastDays(String instrument, long toDay, int days) {
        NavigableMap<Long, Bar> m = bars.get(instrument);
        if (m == null) return List.of();
        return new ArrayList<>(m.subMap(toDay - days, false, toDay, true).values());
    }

    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        for (Map.Entry<String, NavigableMap<Long, Bar>> e : bars.entrySet()) {
            for (Bar b : e.getValue().values()) {
                w.write("bar\t" + e.getKey() + "\t" + b.day() + "\t" + b.open() + "\t" + b.high() + "\t" + b.low() + "\t"
                        + b.close() + "\t" + b.volume() + "\n");
            }
        }
        w.flush();
    }

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
                if (!c[0].equals("bar") || c.length != 8) throw new IllegalArgumentException("expected 8 columns");
                Bar b = new Bar(Long.parseLong(c[2]), Long.parseLong(c[3]), Long.parseLong(c[4]), Long.parseLong(c[5]),
                        Long.parseLong(c[6]), Long.parseLong(c[7]));
                h.bars.computeIfAbsent(c[1], i -> new TreeMap<>()).put(b.day(), b);
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
