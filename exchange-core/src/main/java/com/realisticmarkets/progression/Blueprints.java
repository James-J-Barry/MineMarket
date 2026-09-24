package com.realisticmarkets.progression;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All Drafting Table blueprints.
 * <pre>
 * result,node,material:count,...
 * realisticmarkets:bill_clip,bill_clip,minecraft:leather*2,realisticmarkets:brass_fittings*1
 * </pre>
 * Materials are written {@code id*count} because ids already contain {@code :}. A result may be written the same
 * way ({@code realisticmarkets:order_slip*8}) when one craft makes several.
 */
public final class Blueprints {
    public static final String DEFAULT_RESOURCE = "/realisticmarkets/blueprints.csv";

    private final Map<String, Blueprint> byResult;

    public Blueprints(List<Blueprint> rows) {
        Map<String, Blueprint> m = new LinkedHashMap<>();
        for (Blueprint b : rows) {
            if (m.put(b.result(), b) != null) throw new IllegalArgumentException("duplicate blueprint " + b.result());
        }
        this.byResult = Collections.unmodifiableMap(m);
    }

    public static Blueprints loadDefault() {
        return Csv.load(DEFAULT_RESOURCE, Blueprints::parseCsv);
    }

    public static Blueprints parseCsv(Reader reader) throws IOException {
        List<Blueprint> rows = new ArrayList<>();
        for (Csv.Row r : Csv.read(reader, "result,", -1)) {
            String[] c = r.cols();
            try {
                List<Blueprint.Material> mats = new ArrayList<>();
                for (int i = 2; i < c.length; i++) {
                    int star = c[i].lastIndexOf('*');
                    if (star < 0) throw new IllegalArgumentException("material '" + c[i] + "' needs *count");
                    mats.add(new Blueprint.Material(c[i].substring(0, star), Integer.parseInt(c[i].substring(star + 1))));
                }
                int star = c[0].lastIndexOf('*');
                String result = star < 0 ? c[0] : c[0].substring(0, star);
                int count = star < 0 ? 1 : Integer.parseInt(c[0].substring(star + 1));
                rows.add(new Blueprint(result, c.length > 1 ? c[1] : "", mats, count));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("blueprints line " + r.lineNo() + ": " + e.getMessage(), e);
            }
        }
        return new Blueprints(rows);
    }

    /** Every blueprint must name a real node that grants it. */
    public void validateAgainst(UnlockTree tree) {
        for (Blueprint b : byResult.values()) {
            if (!tree.node(b.node()).grants().contains("blueprint:" + b.result())) {
                throw new IllegalArgumentException(b.result() + ": node " + b.node() + " does not grant it");
            }
        }
    }

    public Blueprint forResult(String result) {
        Blueprint b = byResult.get(result);
        if (b == null) throw new IllegalArgumentException("unknown blueprint " + result);
        return b;
    }

    public Collection<Blueprint> all() {
        return byResult.values();
    }

    /** Components the player may buy: those used by a blueprint they have unlocked, or granted outright. */
    public Set<String> componentsVisibleTo(PlayerProgress progress, Set<String> componentIds) {
        Set<String> visible = new LinkedHashSet<>();
        for (String id : componentIds) if (progress.grants().contains("component:" + id)) visible.add(id);
        for (Blueprint b : byResult.values()) {
            if (!progress.hasBlueprint(b.result())) continue;
            for (Blueprint.Material m : b.materials()) if (componentIds.contains(m.item())) visible.add(m.item());
        }
        return visible;
    }
}
