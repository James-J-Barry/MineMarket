package com.realisticmarkets.registry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Every security paper ever issued, keyed by serial. The registry, not the item, holds the real terms, and each
 * serial settles once: a second settle (a duplicated paper) or an unknown serial (a forged one) is refused and the
 * paper should be marked VOID.
 */
public final class SecurityRegistry {
    public static final String HEADER = "# Realistic Markets security registry v1";

    public enum Status { ISSUED, SETTLED }

    public enum SettleResult { OK, ALREADY_SETTLED, UNKNOWN }

    public record Security(UUID serial, String type, long issueDay, Status status, Map<String, String> terms) {
        public Security {
            terms = Collections.unmodifiableMap(new LinkedHashMap<>(terms));
        }
    }

    private final Map<UUID, Security> bySerial = new LinkedHashMap<>();
    private final Supplier<UUID> serials;

    public SecurityRegistry() {
        this(UUID::randomUUID);
    }

    /** For tests: deterministic serials. */
    public SecurityRegistry(Supplier<UUID> serials) {
        this.serials = serials;
    }

    public Security issue(String type, long day, Map<String, String> terms) {
        UUID serial;
        do {
            serial = serials.get();
        } while (bySerial.containsKey(serial));
        Security s = new Security(serial, type, day, Status.ISSUED, terms);
        bySerial.put(serial, s);
        return s;
    }

    public Optional<Security> lookup(UUID serial) {
        return Optional.ofNullable(bySerial.get(serial));
    }

    /** Marks the serial settled if it was outstanding. */
    public SettleResult settle(UUID serial) {
        Security s = bySerial.get(serial);
        if (s == null) return SettleResult.UNKNOWN;
        if (s.status() == Status.SETTLED) return SettleResult.ALREADY_SETTLED;
        bySerial.put(serial, new Security(s.serial(), s.type(), s.issueDay(), Status.SETTLED, s.terms()));
        return SettleResult.OK;
    }

    public int size() {
        return bySerial.size();
    }

    // ------------------------------------------------------------------ persistence

    /**
     * <pre>
     * # Realistic Markets security registry v1
     * sec	6f1c...	CD	12	ISSUED	principal=50000;rate=0.0045;term=7;issued=12
     * </pre>
     */
    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        for (Security s : bySerial.values()) {
            StringBuilder terms = new StringBuilder();
            for (Map.Entry<String, String> e : s.terms().entrySet()) {
                if (!terms.isEmpty()) terms.append(';');
                terms.append(e.getKey()).append('=').append(e.getValue());
            }
            w.write("sec\t" + s.serial() + "\t" + s.type() + "\t" + s.issueDay() + "\t" + s.status() + "\t" + terms + "\n");
        }
        w.flush();
    }

    public static SecurityRegistry read(Reader r) throws IOException {
        SecurityRegistry reg = new SecurityRegistry();
        BufferedReader br = new BufferedReader(r);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t", -1);
            try {
                // A paper with no terms ends in a tab, which strip() removes: 5 columns is fine.
                if (!c[0].equals("sec") || c.length < 5 || c.length > 6) throw new IllegalArgumentException("expected 6 columns");
                Map<String, String> terms = new LinkedHashMap<>();
                if (c.length == 6 && !c[5].isEmpty()) {
                    for (String kv : c[5].split(";")) {
                        int eq = kv.indexOf('=');
                        terms.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                }
                Security s = new Security(UUID.fromString(c[1]), c[2], Long.parseLong(c[3]), Status.valueOf(c[4]), terms);
                reg.bySerial.put(s.serial(), s);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("registry line " + n + " is malformed: '" + t + "'", e);
            }
        }
        return reg;
    }
}
