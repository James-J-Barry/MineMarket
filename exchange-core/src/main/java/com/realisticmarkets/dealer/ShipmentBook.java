package com.realisticmarkets.dealer;

import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.money.Money;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Goods in transit to the Capital. A shipment is priced when it arrives (settlement risk), pays the Capital's
 * proceeds minus freight (rounded up to the dime, in the Capital's favor), and a crate location holds at most one
 * shipment in transit. Owner and location are opaque strings (a UUID and "dimension|x|y|z" in the mod).
 */
public final class ShipmentBook {
    public static final String HEADER = "# Realistic Markets shipments v1";

    public record Shipment(long id, String owner, String location, Map<String, Integer> items, long localQuoteCents,
                           double shippedDay, double arrivesDay) {
        public Shipment {
            items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        }
    }

    /** {@code grossCents} is the Capital's payment before freight; {@code payoutCents} is what the owner gets. */
    public record Settlement(Shipment shipment, long grossCents, long freightCents, long payoutCents) {
        public boolean beatsLocal() {
            return payoutCents > shipment.localQuoteCents();
        }
    }

    private final Map<Long, Shipment> inTransit = new LinkedHashMap<>();
    private long nextId = 1;

    /**
     * Sends goods. {@code localQuoteCents} is what the local Dealer would have paid for the same goods right now,
     * kept for quest 7 and the crate's comparison line.
     */
    public Shipment ship(String owner, String location, Map<String, Integer> items, long localQuoteCents,
                         double day, Dealer capital, double transitDays) {
        if (items.isEmpty()) throw new RejectedException("Nothing to ship");
        if (inTransitAt(location).isPresent()) throw new RejectedException("This crate already has a shipment on the road");
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            if (e.getValue() <= 0) throw new IllegalArgumentException(e.getKey() + ": quantity must be positive");
            if (!capital.catalog().trades(e.getKey())) {
                throw new RejectedException("The Capital doesn't buy " + e.getKey());
            }
        }
        Shipment s = new Shipment(nextId++, owner, location, items, localQuoteCents, day, day + transitDays);
        inTransit.put(s.id(), s);
        return s;
    }

    public Optional<Shipment> inTransitAt(String location) {
        return inTransit.values().stream().filter(s -> s.location().equals(location)).findFirst();
    }

    public Collection<Shipment> inTransit() {
        return Collections.unmodifiableCollection(inTransit.values());
    }

    /** Sells every shipment due by {@code day} to the Capital, in arrival order, and removes it from transit. */
    public List<Settlement> settleDue(Dealer capital, double day, double freight) {
        List<Shipment> due = new ArrayList<>();
        for (Shipment s : inTransit.values()) if (s.arrivesDay() <= day) due.add(s);
        due.sort(Comparator.comparingDouble(Shipment::arrivesDay).thenComparingLong(Shipment::id));
        List<Settlement> out = new ArrayList<>();
        for (Shipment s : due) {
            long gross = 0;
            for (Map.Entry<String, Integer> e : s.items().entrySet()) {
                try {
                    gross += capital.sell(e.getKey(), e.getValue(), day, false).cents();
                } catch (RejectedException collapsed) {
                    // The Capital pays nothing for a collapsed item; the goods are gone either way.
                }
            }
            long fee = Math.min(gross, Money.roundUpToDime(gross * freight));
            out.add(new Settlement(s, gross, fee, gross - fee));
            inTransit.remove(s.id());
        }
        return out;
    }

    /** What a shipment would pay if it arrived now: the Capital's current quote minus freight. Changes nothing. */
    public static long estimate(Dealer capital, Map<String, Integer> items, double day, double freight) {
        long gross = 0;
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            if (!capital.catalog().trades(e.getKey())) continue;
            try {
                gross += capital.quoteSell(e.getKey(), e.getValue(), day, false).cents();
            } catch (RejectedException collapsed) {
                // worth nothing right now
            }
        }
        return gross - Math.min(gross, Money.roundUpToDime(gross * freight));
    }

    // ------------------------------------------------------------------ persistence

    /**
     * <pre>
     * # Realistic Markets shipments v1
     * @next_id	3
     * ship	2	uuid	minecraft:overworld|10|64|-3	4530	12.25	13.25	minecraft:wheat=128;minecraft:carrot=64
     * </pre>
     */
    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        w.write("@next_id\t" + nextId + "\n");
        for (Shipment s : inTransit.values()) {
            StringBuilder items = new StringBuilder();
            for (Map.Entry<String, Integer> e : s.items().entrySet()) {
                if (!items.isEmpty()) items.append(';');
                items.append(e.getKey()).append('=').append(e.getValue());
            }
            w.write("ship\t" + s.id() + "\t" + s.owner() + "\t" + s.location() + "\t" + s.localQuoteCents() + "\t"
                    + s.shippedDay() + "\t" + s.arrivesDay() + "\t" + items + "\n");
        }
        w.flush();
    }

    public static ShipmentBook read(Reader r) throws IOException {
        ShipmentBook book = new ShipmentBook();
        BufferedReader br = new BufferedReader(r);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            try {
                if (c[0].equals("@next_id")) {
                    book.nextId = Long.parseLong(c[1]);
                } else if (c[0].equals("ship") && c.length == 8) {
                    Map<String, Integer> items = new LinkedHashMap<>();
                    for (String kv : c[7].split(";")) {
                        int eq = kv.lastIndexOf('=');
                        items.put(kv.substring(0, eq), Integer.parseInt(kv.substring(eq + 1)));
                    }
                    Shipment s = new Shipment(Long.parseLong(c[1]), c[2], c[3], items, Long.parseLong(c[4]),
                            Double.parseDouble(c[5]), Double.parseDouble(c[6]));
                    book.inTransit.put(s.id(), s);
                    book.nextId = Math.max(book.nextId, s.id() + 1);
                } else {
                    throw new IllegalArgumentException("unknown line");
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("shipments line " + n + " is malformed: '" + t + "'", e);
            }
        }
        return book;
    }
}
