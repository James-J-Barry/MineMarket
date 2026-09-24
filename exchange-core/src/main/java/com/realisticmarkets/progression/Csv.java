package com.realisticmarkets.progression;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal CSV reading for the Almanac data files: comments, blank lines and one header line are skipped. */
final class Csv {
    private Csv() {}

    record Row(int lineNo, String[] cols) {}

    static List<Row> read(Reader reader, String headerPrefix, int limit) throws IOException {
        List<Row> rows = new ArrayList<>();
        BufferedReader br = new BufferedReader(reader);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(headerPrefix)) continue;
            String[] c = t.split(",", limit);
            for (int i = 0; i < c.length; i++) c[i] = c[i].strip();
            rows.add(new Row(n, c));
        }
        return rows;
    }

    static Reader resource(String path) {
        InputStream in = Csv.class.getResourceAsStream(path);
        if (in == null) throw new IllegalStateException("missing " + path);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    static <T> T load(String path, IoFunction<Reader, T> parser) {
        try (Reader r = resource(path)) {
            return parser.apply(r);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** "a;b;c" -> [a, b, c]; blank -> []. */
    static List<String> list(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(";")) if (!p.isBlank()) out.add(p.strip());
        return out;
    }

    interface IoFunction<A, B> {
        B apply(A a) throws IOException;
    }
}
