package com.realisticmarkets.dealer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plain-text persistence for the Dealer, stored in the world save so prices survive restarts.
 * Without it, a player could dump a farm, restart, and sell again at full price.
 *
 * <pre>
 * # Realistic Markets dealer state v1
 * @day_offset	0.0
 * minecraft:wheat	256.0	12.5	12	-0.013
 * </pre>
 * Columns: base item, inventory (base units), last day seen, last drift day, log fair-value deviation.
 * Doubles are written with {@link Double#toString} so they round-trip exactly.
 */
public final class DealerStateIO {
    public static final String HEADER = "# Realistic Markets dealer state v1";

    private DealerStateIO() {}

    public record Saved(Map<String, Dealer.PoolState> pools, double dayOffset) {}

    public static void write(Saved saved, Writer w) throws IOException {
        w.write(HEADER);
        w.write('\n');
        w.write("@day_offset\t" + saved.dayOffset() + "\n");
        for (Map.Entry<String, Dealer.PoolState> e : saved.pools().entrySet()) {
            Dealer.PoolState p = e.getValue();
            w.write(e.getKey() + "\t" + p.inventory() + "\t" + p.lastDay() + "\t" + p.driftDay() + "\t" + p.logDeviation() + "\n");
        }
        w.flush();
    }

    public static Saved read(Reader r) throws IOException {
        Map<String, Dealer.PoolState> pools = new LinkedHashMap<>();
        double offset = 0;
        BufferedReader br = new BufferedReader(r);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            try {
                if (c[0].equals("@day_offset")) {
                    offset = Double.parseDouble(c[1]);
                } else if (c.length == 5) {
                    pools.put(c[0], new Dealer.PoolState(Double.parseDouble(c[1]), Double.parseDouble(c[2]),
                            Long.parseLong(c[3]), Double.parseDouble(c[4])));
                } else {
                    throw new IllegalArgumentException("expected 5 columns");
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("dealer state line " + n + " is malformed: '" + t + "'", e);
            }
        }
        return new Saved(pools, offset);
    }
}
