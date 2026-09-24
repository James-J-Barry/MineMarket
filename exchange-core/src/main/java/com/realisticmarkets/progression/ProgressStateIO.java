package com.realisticmarkets.progression;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Map;

/**
 * Plain-text save file for one player's {@link PlayerProgress} ({@code players/<uuid>.txt}).
 * <pre>
 * # Realistic Markets player progress v1
 * node	bill_clip
 * quest	first_sale
 * grant	blueprint:realisticmarkets:bill_clip
 * bought	minecraft:wheat
 * lowest	minecraft:wheat	0.64
 * day	12
 * day_qty	minecraft:wheat	200
 * day_group	farm	1500
 * </pre>
 */
public final class ProgressStateIO {
    public static final String HEADER = "# Realistic Markets player progress v1";

    private ProgressStateIO() {}

    public static void write(PlayerProgress p, Writer w) throws IOException {
        w.write(HEADER + "\n");
        for (String s : p.nodes) w.write("node\t" + s + "\n");
        for (String s : p.quests) w.write("quest\t" + s + "\n");
        for (String s : p.grants) w.write("grant\t" + s + "\n");
        for (String s : p.boughtItems) w.write("bought\t" + s + "\n");
        for (Map.Entry<String, Double> e : p.lowestRatio.entrySet()) w.write("lowest\t" + e.getKey() + "\t" + e.getValue() + "\n");
        if (p.trackedDay != Long.MIN_VALUE) w.write("day\t" + p.trackedDay + "\n");
        for (Map.Entry<String, Integer> e : p.dayQty.entrySet()) w.write("day_qty\t" + e.getKey() + "\t" + e.getValue() + "\n");
        for (Map.Entry<String, Long> e : p.dayGroupCents.entrySet()) w.write("day_group\t" + e.getKey() + "\t" + e.getValue() + "\n");
        for (Map.Entry<String, long[]> e : p.dayFloor.entrySet()) {
            w.write("day_floor\t" + e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1] + "\n");
        }
        w.flush();
    }

    public static PlayerProgress read(Reader r) throws IOException {
        PlayerProgress p = new PlayerProgress();
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
                    case "node" -> p.nodes.add(c[1]);
                    case "quest" -> p.quests.add(c[1]);
                    case "grant" -> p.grants.add(c[1]);
                    case "bought" -> p.boughtItems.add(c[1]);
                    case "lowest" -> p.lowestRatio.put(c[1], Double.parseDouble(c[2]));
                    case "day" -> p.trackedDay = Long.parseLong(c[1]);
                    case "day_qty" -> p.dayQty.put(c[1], Integer.parseInt(c[2]));
                    case "day_group" -> p.dayGroupCents.put(c[1], Long.parseLong(c[2]));
                    case "day_floor" -> p.dayFloor.put(c[1], new long[] {Long.parseLong(c[2]), Long.parseLong(c[3])});
                    default -> throw new IllegalArgumentException("unknown key " + c[0]);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("progress line " + n + " is malformed: '" + t + "'", e);
            }
        }
        return p;
    }
}
