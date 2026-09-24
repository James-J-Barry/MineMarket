package com.realisticmarkets.dealer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * World events that move fair values (M5c): a bumper harvest, a drought, a mine collapse. Each event type starts on
 * a day with a fixed chance, decided by the world seed and the day alone, so the schedule needs no save state and
 * a world always has the same history.
 *
 * <p>The news breaks at dawn on the Newsstand, but it takes the rest of the market a while to hear: prices start to
 * move {@link Event#delay()} after dawn (between {@link #MIN_DELAY_DAYS} and {@link #MAX_DELAY_DAYS}, different for
 * every story, and never told to the player). The transient effect then builds over {@link #RAMP_DAYS} and fades
 * with the type's half-life; the smaller permanent effect lands at the next dawn. Each event's size is its type's
 * size times a {@link Event#scale()} between 0.5 and 1.5, so a headline says which way, not how far.
 */
public final class WorldEvents implements Dealer.Shocks {
    public static final String DEFAULT_RESOURCE = "/realisticmarkets/events.csv";
    /** The rest of the market hears a story this long after dawn (varies per story): a Newsstand reader's head start. */
    public static final double MIN_DELAY_DAYS = 0.3, MAX_DELAY_DAYS = 0.7;
    /** Once the market hears, most of a transient shock arrives within about a quarter of a day. */
    public static final double RAMP_DAYS = 0.1;
    /** Transient effects older than this are ignored (under 0.1% of the shock at the longest half-life). */
    static final int LOOKBACK_DAYS = 50;

    public record Type(int index, String id, List<String> targets, double shock, double halfLifeDays, double permanent,
                       double chancePerDay, String headline) {}

    /** One event: a type starting on a day, at {@code scale} times the type's size, heard {@code delay} days after dawn. */
    public record Event(Type type, long day, double scale, double delay) {}

    private final List<Type> types;
    private final long seed;
    private final List<Set<String>> resolved = new ArrayList<>(); // by type index
    private final Map<Long, List<Event>> startsCache = new LinkedHashMap<>();
    private long activeDay = Long.MIN_VALUE;
    private List<Event> active = List.of(); // events within LOOKBACK_DAYS of activeDay

    public WorldEvents(List<Type> types, DealerCatalog catalog, long seed) {
        this.types = List.copyOf(types);
        this.seed = seed;
        for (Type t : types) {
            Set<String> items = new LinkedHashSet<>();
            for (String target : t.targets()) {
                if (target.startsWith("group:")) {
                    String g = target.substring(6);
                    for (MarketSpec m : catalog.basePools()) if (g.equals(m.group())) items.add(m.itemId());
                } else if (catalog.trades(target)) {
                    items.add(catalog.pool(target).itemId());
                }
            }
            resolved.add(items);
        }
    }

    public static WorldEvents loadDefault(DealerCatalog catalog, long seed) {
        return new WorldEvents(loadTypes(), catalog, seed);
    }

    public static List<Type> loadTypes() {
        try (InputStream in = WorldEvents.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + DEFAULT_RESOURCE);
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Type> parse(Reader reader) throws IOException {
        List<Type> out = new ArrayList<>();
        BufferedReader br = new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("id,")) continue;
            String[] c = t.split(",", 7);
            if (c.length != 7) throw new IllegalArgumentException("events.csv: expected 7 columns: " + t);
            double hl = Double.parseDouble(c[3]);
            if (hl <= 0) throw new IllegalArgumentException("events.csv: half-life must be positive: " + t);
            out.add(new Type(out.size(), c[0], List.of(c[1].split(";")), Double.parseDouble(c[2]), hl,
                    Double.parseDouble(c[4]), Double.parseDouble(c[5]), c[6].strip()));
        }
        return out;
    }

    public List<Type> types() { return types; }

    /** Base items an event type moves. */
    public Set<String> affects(Type t) { return resolved.get(t.index()); }

    /** Events that start on {@code day} (at dawn). */
    public List<Event> startingOn(long day) {
        List<Event> cached = startsCache.get(day);
        if (cached != null) return cached;
        if (startsCache.size() > 4 * LOOKBACK_DAYS) startsCache.clear();
        return startsCache.computeIfAbsent(day, d -> {
            List<Event> out = new ArrayList<>();
            for (Type t : types) {
                long mix = seed ^ (t.id().hashCode() * 0x9E3779B97F4A7C15L) ^ (d * 0xD6E8FEB86659FD93L);
                SplittableRandom r = new SplittableRandom(mix);
                if (r.nextDouble() < t.chancePerDay()) {
                    out.add(new Event(t, d, 0.5 + r.nextDouble(), MIN_DELAY_DAYS + (MAX_DELAY_DAYS - MIN_DELAY_DAYS) * r.nextDouble()));
                }
            }
            return List.copyOf(out);
        });
    }

    /** Events still moving prices at {@code day}: started within the last {@code days} days, newest first. */
    public List<Event> recent(double day, int days) {
        List<Event> out = new ArrayList<>();
        long today = (long) Math.floor(day);
        for (long d = today; d > today - days; d--) out.addAll(startingOn(d));
        return out;
    }

    /** Permanent effects land at the dawn after the event: that is, on {@code day} for events of the day before. */
    @Override
    public double permanent(String baseItem, long day) {
        double sum = 0;
        for (Event e : startingOn(day - 1)) {
            if (resolved.get(e.type().index()).contains(baseItem)) sum += e.type().permanent() * e.scale();
        }
        return sum;
    }

    /** One newspaper story: the headline and the goods it should push up or down. */
    public record Story(String headline, boolean up, List<String> items) {}

    /** The Newsstand's paper for {@code day}: every event breaking that morning. */
    public List<Story> edition(long day) {
        List<Story> out = new ArrayList<>();
        for (Event e : startingOn(day)) {
            out.add(new Story(e.type().headline(), e.type().shock() > 0, List.copyOf(resolved.get(e.type().index()))));
        }
        return out;
    }

    @Override
    public double fading(String baseItem, double day) {
        long today = (long) Math.floor(day);
        if (today != activeDay) { // called for every price quote: work out the day's live events once
            active = recent(day, LOOKBACK_DAYS);
            activeDay = today;
        }
        double sum = 0;
        for (Event e : active) {
            if (resolved.get(e.type().index()).contains(baseItem)) sum += e.scale() * transientEffect(e.type(), day - e.day(), e.delay());
        }
        return sum;
    }

    /** Transient log effect of a full-size event {@code age} days after dawn of its day, heard after {@code delay}. */
    public static double transientEffect(Type t, double age, double delay) {
        double heard = age - delay;
        if (heard < 0) return 0;
        return t.shock() * (1 - Math.exp(-heard / RAMP_DAYS)) * Math.pow(0.5, heard / t.halfLifeDays());
    }
}
