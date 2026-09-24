package com.realisticmarkets.equities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Company news for the Electronic Newsfeed: new mines, derailments, contracts, recalls. Each story type starts on a
 * day with a fixed chance, decided by the world seed and the day alone (no save state). A story appears on the
 * Newsfeed at dawn; the rest of the market hears it at the next dawn, when it changes the company's business for
 * good (its output, by the type's effect times a {@link Story#scale()} of 0.5-1.5).
 */
public final class CompanyNews {
    public static final String DEFAULT_RESOURCE = "/realisticmarkets/company_news.csv";

    public record Type(int index, String id, String ticker, double effect, double chancePerDay, String headline) {}

    public record Story(Type type, long day, double scale) {
        /** The day the rest of the market hears it. */
        public long heardDay() {
            return day + 1;
        }

        public double logEffect() {
            return type.effect() * scale;
        }
    }

    private final List<Type> types;
    private final long seed;
    private final Map<Long, List<Story>> cache = new LinkedHashMap<>();

    public CompanyNews(List<Type> types, long seed) {
        this.types = List.copyOf(types);
        this.seed = seed;
    }

    public static CompanyNews loadDefault(long seed) {
        return new CompanyNews(loadTypes(), seed);
    }

    public static List<Type> loadTypes() {
        try (InputStream in = CompanyNews.class.getResourceAsStream(DEFAULT_RESOURCE)) {
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
            String[] c = t.split(",", 5);
            if (c.length != 5) throw new IllegalArgumentException("company_news.csv: expected 5 columns: " + t);
            out.add(new Type(out.size(), c[0], c[1], Double.parseDouble(c[2]), Double.parseDouble(c[3]), c[4].strip()));
        }
        return out;
    }

    public List<Type> types() { return types; }

    /** Stories that appear on {@code day}'s Newsfeed. */
    public List<Story> startingOn(long day) {
        List<Story> cached = cache.get(day);
        if (cached != null) return cached;
        if (cache.size() > 200) cache.clear();
        List<Story> out = new ArrayList<>();
        for (Type t : types) {
            long mix = seed ^ (t.id().hashCode() * 0x9E3779B97F4A7C15L) ^ (day * 0xBF58476D1CE4E5B9L);
            SplittableRandom r = new SplittableRandom(mix);
            if (r.nextDouble() < t.chancePerDay()) out.add(new Story(t, day, 0.5 + r.nextDouble()));
        }
        cache.put(day, List.copyOf(out));
        return cache.get(day);
    }

    /** Stories from the last {@code days} days up to {@code day}, newest first. */
    public List<Story> recent(long day, int days) {
        List<Story> out = new ArrayList<>();
        for (long d = day; d > day - days; d--) out.addAll(startingOn(d));
        return out;
    }
}
