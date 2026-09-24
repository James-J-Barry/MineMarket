package com.realisticmarkets.progression;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Almanac's upgrade nodes.
 * <pre>
 * id,tier,cost_cents,parents,required_quest,grants,title
 * merchant_license,1,15000,,meet_the_spread,perk:merchant_license,Merchant License
 * </pre>
 * {@code parents} and {@code grants} are {@code ;}-separated. Title is last so it may contain commas.
 */
public final class UnlockTree {
    public static final String DEFAULT_RESOURCE = "/realisticmarkets/almanac/nodes.csv";

    private final Map<String, UnlockNode> nodes;

    public UnlockTree(List<UnlockNode> rows) {
        Map<String, UnlockNode> m = new LinkedHashMap<>();
        for (UnlockNode n : rows) {
            if (m.put(n.id(), n) != null) throw new IllegalArgumentException("duplicate node " + n.id());
        }
        for (UnlockNode n : m.values()) {
            for (String p : n.parents()) {
                if (!m.containsKey(p)) throw new IllegalArgumentException(n.id() + ": unknown parent " + p);
            }
        }
        this.nodes = Collections.unmodifiableMap(m);
    }

    public static UnlockTree loadDefault() {
        return Csv.load(DEFAULT_RESOURCE, UnlockTree::parseCsv);
    }

    public static UnlockTree parseCsv(Reader reader) throws IOException {
        List<UnlockNode> rows = new ArrayList<>();
        for (Csv.Row r : Csv.read(reader, "id,", 7)) {
            String[] c = r.cols();
            if (c.length < 7) throw new IllegalArgumentException("nodes line " + r.lineNo() + ": expected 7 columns");
            try {
                rows.add(new UnlockNode(c[0], c[6], Integer.parseInt(c[1]), Long.parseLong(c[2]), Csv.list(c[3]),
                        c[4], Csv.list(c[5])));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("nodes line " + r.lineNo() + ": " + e.getMessage(), e);
            }
        }
        return new UnlockTree(rows);
    }

    public UnlockNode node(String id) {
        UnlockNode n = nodes.get(id);
        if (n == null) throw new IllegalArgumentException("unknown node " + id);
        return n;
    }

    public boolean has(String id) {
        return nodes.containsKey(id);
    }

    public Collection<UnlockNode> all() {
        return nodes.values();
    }

    public List<UnlockNode> tier(int tier) {
        List<UnlockNode> out = new ArrayList<>();
        for (UnlockNode n : nodes.values()) if (n.tier() == tier) out.add(n);
        return out;
    }
}
