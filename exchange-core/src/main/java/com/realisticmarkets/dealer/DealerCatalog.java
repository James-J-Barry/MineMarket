package com.realisticmarkets.dealer;

import com.realisticmarkets.exchange.RejectedException;
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
 * The set of items the Dealer trades, loaded from a CSV so balancing never needs a recompile.
 *
 * <pre>
 * # comment lines and blank lines are ignored
 * item,fair_value,depth,group,base_item,base_units,spread
 * minecraft:wheat,0.50,256,farm,,
 * minecraft:hay_block,,,farm,minecraft:wheat,9
 * realisticmarkets:ledger_paper,4.00,64,components,,,0.40
 * </pre>
 * {@code spread} is optional (base markets only); blank means {@link DealerParams#spread()}. An eighth column,
 * {@code collateral_class} (A-D), sets the loan haircut class; blank means by group.
 */
public final class DealerCatalog {
    public static final String DEFAULT_RESOURCE = "/realisticmarkets/dealer_catalog.csv";

    private final Map<String, MarketSpec> specs;

    public DealerCatalog(List<MarketSpec> rows) {
        Map<String, MarketSpec> m = new LinkedHashMap<>();
        for (MarketSpec s : rows) {
            if (m.put(s.itemId(), s) != null) throw new IllegalArgumentException("duplicate item " + s.itemId());
        }
        for (MarketSpec s : m.values()) {
            if (!s.isLinked()) continue;
            MarketSpec base = m.get(s.baseItem());
            if (base == null) throw new IllegalArgumentException(s.itemId() + ": unknown base item " + s.baseItem());
            if (base.isLinked()) throw new IllegalArgumentException(s.itemId() + ": base item " + s.baseItem() + " is itself linked");
        }
        this.specs = Collections.unmodifiableMap(m);
    }

    public static DealerCatalog loadDefault() {
        try (InputStream in = DealerCatalog.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + DEFAULT_RESOURCE);
            return parseCsv(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static DealerCatalog parseCsv(Reader reader) throws IOException {
        List<MarketSpec> rows = new ArrayList<>();
        BufferedReader br = new BufferedReader(reader);
        String line;
        int lineNo = 0;
        boolean headerSeen = false;
        while ((line = br.readLine()) != null) {
            lineNo++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (!headerSeen && t.startsWith("item,")) {
                headerSeen = true;
                continue;
            }
            String[] c = t.split(",", -1);
            if (c.length < 4) throw new IllegalArgumentException("line " + lineNo + ": expected at least 4 columns");
            String item = normalize(c[0]);
            String group = c[3].strip();
            String baseItem = c.length > 4 && !c[4].isBlank() ? normalize(c[4]) : null;
            try {
                if (baseItem == null) {
                    Double spread = c.length > 6 && !c[6].isBlank() ? Double.valueOf(c[6].strip()) : null;
                    String cls = c.length > 7 && !c[7].isBlank() ? c[7].strip().toUpperCase(java.util.Locale.ROOT) : null;
                    rows.add(MarketSpec.base(item, Double.parseDouble(c[1].strip()), Double.parseDouble(c[2].strip()), group,
                            spread, cls));
                } else {
                    if ((c.length > 6 && !c[6].isBlank()) || (c.length > 7 && !c[7].isBlank())) {
                        throw new IllegalArgumentException("line " + lineNo + ": linked item " + item + " can't set a spread or collateral class (it uses " + baseItem + "'s)");
                    }
                    int units = Integer.parseInt(c.length > 5 ? c[5].strip() : "");
                    rows.add(MarketSpec.linked(item, baseItem, units, group));
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("line " + lineNo + ": bad number in '" + t + "'");
            }
        }
        return new DealerCatalog(rows);
    }

    /**
     * The built-in catalog with a config file applied on top: a config row replaces the built-in row for the same
     * item, and items only in the config are added. Built-in items the config doesn't mention stay, so items added
     * by a mod update appear even when an old config copy exists.
     */
    public static DealerCatalog merge(DealerCatalog builtIn, DealerCatalog overrides) {
        Map<String, MarketSpec> m = new LinkedHashMap<>(builtIn.specs);
        m.putAll(overrides.specs);
        return new DealerCatalog(new ArrayList<>(m.values()));
    }

    /** "wheat" -> "minecraft:wheat"; ids with a namespace pass through. */
    public static String normalize(String id) {
        String s = id.strip().toLowerCase(java.util.Locale.ROOT);
        return s.indexOf(':') >= 0 ? s : "minecraft:" + s;
    }

    public boolean trades(String itemId) {
        return specs.containsKey(normalize(itemId));
    }

    public MarketSpec spec(String itemId) {
        MarketSpec s = specs.get(normalize(itemId));
        if (s == null) throw new RejectedException("The Dealer doesn't trade " + itemId + " (no market exists for it yet)");
        return s;
    }

    /** The base-pool spec an item trades through (itself if not linked). */
    public MarketSpec pool(String itemId) {
        MarketSpec s = spec(itemId);
        return s.isLinked() ? specs.get(s.baseItem()) : s;
    }

    public Map<String, MarketSpec> all() {
        return specs;
    }

    public List<MarketSpec> basePools() {
        List<MarketSpec> out = new ArrayList<>();
        for (MarketSpec s : specs.values()) if (!s.isLinked()) out.add(s);
        return out;
    }
}
