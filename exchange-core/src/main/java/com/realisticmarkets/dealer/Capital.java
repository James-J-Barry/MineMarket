package com.realisticmarkets.dealer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Capital: a second, independent Dealer reached by Trade Route Crate. Its catalog is derived from the local
 * Dealer's: same items and depths, fair value times a per-group multiplier. Loaded from {@code capital_catalog.csv}.
 */
public final class Capital {
    public static final String DEFAULT_RESOURCE = "/realisticmarkets/capital_catalog.csv";

    public record Config(Map<String, Double> groupMultipliers, double spread, double freight, double transitDays) {
        public Config {
            groupMultipliers = Collections.unmodifiableMap(new LinkedHashMap<>(groupMultipliers));
            if (spread < 0 || spread >= 2) throw new IllegalArgumentException("capital spread out of range");
            if (freight < 0 || freight >= 1) throw new IllegalArgumentException("freight must be in [0, 1)");
            if (transitDays <= 0) throw new IllegalArgumentException("transit_days must be positive");
            for (Map.Entry<String, Double> e : groupMultipliers.entrySet()) {
                if (e.getValue() <= 0) throw new IllegalArgumentException(e.getKey() + ": multiplier must be positive");
            }
        }
    }

    private Capital() {}

    public static Config loadDefault() {
        try (InputStream in = Capital.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + DEFAULT_RESOURCE);
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Config parse(Reader reader) throws IOException {
        Map<String, Double> groups = new LinkedHashMap<>();
        double spread = 0.15, freight = 0.05, transit = 1.0;
        BufferedReader br = new BufferedReader(reader);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("key,")) continue;
            String[] c = t.split(",");
            if (c.length != 2) throw new IllegalArgumentException("capital line " + n + ": expected key,value");
            double v;
            try {
                v = Double.parseDouble(c[1].strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("capital line " + n + ": bad number '" + c[1] + "'");
            }
            switch (c[0].strip()) {
                case "spread" -> spread = v;
                case "freight" -> freight = v;
                case "transit_days" -> transit = v;
                default -> groups.put(c[0].strip(), v);
            }
        }
        return new Config(groups, spread, freight, transit);
    }

    /** The Capital's catalog: listed groups only, fair values scaled, linked items kept linked. */
    public static DealerCatalog catalog(DealerCatalog local, Config cfg) {
        List<MarketSpec> rows = new ArrayList<>();
        for (MarketSpec s : local.all().values()) {
            Double m = cfg.groupMultipliers().get(s.group());
            if (m == null) continue;
            if (s.isLinked()) {
                MarketSpec base = local.spec(s.baseItem());
                if (!cfg.groupMultipliers().containsKey(base.group())) continue;
                rows.add(MarketSpec.linked(s.itemId(), s.baseItem(), s.baseUnits(), s.group()));
            } else {
                rows.add(MarketSpec.base(s.itemId(), s.fairValue() * m, s.depth(), s.group()));
            }
        }
        return new DealerCatalog(rows);
    }

    /** Local params with the Capital's spread. The license rate equals it: the license doesn't apply here. */
    public static DealerParams params(DealerParams local, Config cfg) {
        return new DealerParams(cfg.spread(), cfg.spread(), local.k(), local.recoveryDays(), local.driftSigma(),
                local.driftHalfLifeDays());
    }

    /** A ready Capital Dealer. Pass a different seed from the local Dealer so the two drift independently. */
    public static Dealer dealer(DealerCatalog local, DealerParams localParams, Config cfg, long seed) {
        return new Dealer(catalog(local, cfg), params(localParams, cfg), seed);
    }
}
