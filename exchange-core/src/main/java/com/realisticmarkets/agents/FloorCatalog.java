package com.realisticmarkets.agents;

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

/** The Trading Floor's books, loaded from {@code floor_catalog.csv}. */
public final class FloorCatalog {
    public static final String DEFAULT_RESOURCE = "/realisticmarkets/floor_catalog.csv";

    /**
     * @param basis for a book whose Dealer price comes from another item (iron block = 9 ingots): how far its own
     *              supply and demand move it from that price (standard deviation of log basis; 0 = none)
     */
    public record Book(String item, double halfSpread, long depth, int noise, int fundamentalists, int momentum,
                       double basis) {
        public Book(String item, double halfSpread, long depth, int noise, int fundamentalists, int momentum) {
            this(item, halfSpread, depth, noise, fundamentalists, momentum, 0);
        }

        public Book {
            if (basis < 0 || basis > 0.5) throw new IllegalArgumentException(item + ": basis out of range");
            if (halfSpread <= 0 || halfSpread >= 0.5) throw new IllegalArgumentException(item + ": half_spread out of range");
            if (depth <= 0) throw new IllegalArgumentException(item + ": depth must be positive");
            if (noise < 0 || fundamentalists < 0 || momentum < 0) throw new IllegalArgumentException(item + ": negative trader count");
        }
    }

    private final Map<String, Book> books;

    public FloorCatalog(List<Book> rows) {
        Map<String, Book> m = new LinkedHashMap<>();
        for (Book b : rows) if (m.put(b.item(), b) != null) throw new IllegalArgumentException("duplicate book " + b.item());
        books = Collections.unmodifiableMap(m);
    }

    /** The Stock Exchange's books: one per company, keyed by ticker. */
    public static final String STOCK_RESOURCE = "/realisticmarkets/stock_catalog.csv";

    public static FloorCatalog loadDefault() {
        return load(DEFAULT_RESOURCE);
    }

    public static FloorCatalog loadStocks() {
        return load(STOCK_RESOURCE);
    }

    public static FloorCatalog load(String resource) {
        try (InputStream in = FloorCatalog.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing " + resource);
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static FloorCatalog parse(Reader reader) throws IOException {
        List<Book> rows = new ArrayList<>();
        BufferedReader br = new BufferedReader(reader);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("item,")) continue;
            String[] c = t.split(",");
            try {
                rows.add(new Book(c[0].strip(), Double.parseDouble(c[1]), Long.parseLong(c[2].strip()), Integer.parseInt(c[3].strip()),
                        Integer.parseInt(c[4].strip()), Integer.parseInt(c[5].strip()),
                        c.length > 6 && !c[6].isBlank() ? Double.parseDouble(c[6]) : 0));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("floor line " + n + ": " + e.getMessage(), e);
            }
        }
        return new FloorCatalog(rows);
    }

    public boolean trades(String item) { return books.containsKey(item); }
    public Book book(String item) {
        Book b = books.get(item);
        if (b == null) throw new IllegalArgumentException("no Floor book for " + item);
        return b;
    }
    public List<Book> all() { return List.copyOf(books.values()); }
}
