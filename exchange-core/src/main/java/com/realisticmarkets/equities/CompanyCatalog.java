package com.realisticmarkets.equities;

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

/** The listed companies, in {@code companies.csv} order. */
public final class CompanyCatalog {
    public static final String DEFAULT_RESOURCE = "/realisticmarkets/companies.csv";
    private final Map<String, Company> byTicker;

    public CompanyCatalog(List<Company> companies) {
        Map<String, Company> m = new LinkedHashMap<>();
        for (Company c : companies) if (m.put(c.ticker(), c) != null) throw new IllegalArgumentException("duplicate " + c.ticker());
        byTicker = Collections.unmodifiableMap(m);
    }

    public static CompanyCatalog loadDefault() {
        try (InputStream in = CompanyCatalog.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + DEFAULT_RESOURCE);
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static CompanyCatalog parse(Reader reader) throws IOException {
        List<Company> out = new ArrayList<>();
        BufferedReader br = new BufferedReader(reader);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("ticker,")) continue;
            String[] c = t.split(",", -1);
            try {
                if (c.length != 11) throw new IllegalArgumentException("expected 11 columns");
                out.add(new Company(c[0], c[1], units(c[2]), Double.parseDouble(c[3]), units(c[4]), Double.parseDouble(c[5]),
                        Double.parseDouble(c[6]), Double.parseDouble(c[7]), Long.parseLong(c[8]), Double.parseDouble(c[9]),
                        effects(c[10])));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("companies line " + n + ": " + e.getMessage(), e);
            }
        }
        return new CompanyCatalog(out);
    }

    private static Map<String, Long> units(String s) {
        Map<String, Long> m = new LinkedHashMap<>();
        if (s.isBlank()) return m;
        for (String part : s.split(";")) {
            String[] kv = part.strip().split("\\*");
            m.put(kv[0], Long.parseLong(kv[1]));
        }
        return m;
    }

    private static Map<String, Double> effects(String s) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (s.isBlank()) return m;
        for (String part : s.split(";")) {
            String[] kv = part.strip().split("\\*");
            m.put(kv[0], Double.parseDouble(kv[1]));
        }
        return m;
    }

    public Company company(String ticker) {
        Company c = byTicker.get(ticker);
        if (c == null) throw new IllegalArgumentException("no company " + ticker);
        return c;
    }

    public List<Company> all() {
        return List.copyOf(byTicker.values());
    }

    /** Every price key any company needs (Dealer items, "fees", "shipping", and the price level "cpi"). */
    public List<String> priceKeys() {
        List<String> keys = new ArrayList<>();
        for (Company c : byTicker.values()) {
            for (String k : c.revenue().keySet()) if (!keys.contains(k)) keys.add(k);
            for (String k : c.inputs().keySet()) if (!keys.contains(k)) keys.add(k);
        }
        if (!keys.contains(Equities.CPI)) keys.add(Equities.CPI);
        return keys;
    }
}
