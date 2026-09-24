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
 * The Almanac's quests, in display order.
 * <pre>
 * id,goal,reward_cents,grants,title
 * first_sale,any_sale,500,guide:money_and_dealer,First Sale
 * </pre>
 */
public final class Quests {
    public static final String DEFAULT_RESOURCE = "/realisticmarkets/almanac/quests.csv";

    private final Map<String, Quest> byId;

    public Quests(List<Quest> rows) {
        Map<String, Quest> m = new LinkedHashMap<>();
        for (Quest q : rows) {
            if (m.put(q.id(), q) != null) throw new IllegalArgumentException("duplicate quest " + q.id());
        }
        this.byId = Collections.unmodifiableMap(m);
    }

    public static Quests loadDefault() {
        return Csv.load(DEFAULT_RESOURCE, Quests::parseCsv);
    }

    public static Quests parseCsv(Reader reader) throws IOException {
        List<Quest> rows = new ArrayList<>();
        for (Csv.Row r : Csv.read(reader, "id,", 5)) {
            String[] c = r.cols();
            if (c.length < 5) throw new IllegalArgumentException("quests line " + r.lineNo() + ": expected 5 columns");
            try {
                rows.add(new Quest(c[0], c[4], QuestGoal.parse(c[1]), Long.parseLong(c[2]), Csv.list(c[3])));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("quests line " + r.lineNo() + ": " + e.getMessage(), e);
            }
        }
        return new Quests(rows);
    }

    public Quest quest(String id) {
        Quest q = byId.get(id);
        if (q == null) throw new IllegalArgumentException("unknown quest " + id);
        return q;
    }

    public boolean has(String id) {
        return byId.containsKey(id);
    }

    public Collection<Quest> all() {
        return byId.values();
    }
}
