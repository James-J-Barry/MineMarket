package com.realisticmarkets.progression;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Almanac field guides, loaded from {@code almanac/guides.txt}: {@code @<id> <Title>} starts a guide,
 * blank lines separate paragraphs, {@code #} lines are comments.
 */
public final class Guides {
    public static final String DEFAULT_RESOURCE = "/realisticmarkets/almanac/guides.txt";

    public record Guide(String id, String title, List<String> paragraphs) {
        public Guide {
            paragraphs = List.copyOf(paragraphs);
        }

        public int wordCount() {
            int n = 0;
            for (String p : paragraphs) n += p.isBlank() ? 0 : p.strip().split("\\s+").length;
            return n;
        }

        /**
         * Splits the text into book pages of at most {@code maxChars}, breaking between words. Paragraphs are
         * separated by a blank line and line breaks inside a paragraph (lists) are kept, except at a page top.
         */
        public List<String> pages(int maxChars) {
            List<String> pages = new ArrayList<>();
            StringBuilder page = new StringBuilder();
            for (int pi = 0; pi < paragraphs.size(); pi++) {
                String[] lines = paragraphs.get(pi).split("\n");
                for (int li = 0; li < lines.length; li++) {
                    String sep = li > 0 ? "\n" : pi > 0 ? "\n\n" : "";
                    boolean firstWord = true;
                    for (String word : lines[li].strip().split("\\s+")) {
                        String add = page.isEmpty() ? word : (firstWord ? sep : " ") + word;
                        if (page.length() + add.length() > maxChars && !page.isEmpty()) {
                            pages.add(page.toString());
                            page.setLength(0);
                            add = word;
                        }
                        page.append(add);
                        firstWord = false;
                    }
                }
            }
            if (!page.isEmpty()) pages.add(page.toString());
            return pages;
        }
    }

    private final Map<String, Guide> byId;

    public Guides(List<Guide> guides) {
        Map<String, Guide> m = new LinkedHashMap<>();
        for (Guide g : guides) {
            if (m.put(g.id(), g) != null) throw new IllegalArgumentException("duplicate guide " + g.id());
        }
        this.byId = Collections.unmodifiableMap(m);
    }

    public static Guides loadDefault() {
        return Csv.load(DEFAULT_RESOURCE, Guides::parse);
    }

    public static Guides parse(Reader reader) throws IOException {
        List<Guide> out = new ArrayList<>();
        BufferedReader br = new BufferedReader(reader);
        String id = null, title = null;
        List<String> paras = new ArrayList<>();
        StringBuilder para = new StringBuilder();
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.startsWith("#")) continue;
            if (t.startsWith("@")) {
                flushPara(para, paras);
                if (id != null) out.add(new Guide(id, title, paras));
                int sp = t.indexOf(' ');
                if (sp < 0) throw new IllegalArgumentException("guides line " + n + ": expected '@id Title'");
                id = t.substring(1, sp);
                title = t.substring(sp + 1).strip();
                paras = new ArrayList<>();
            } else if (t.isEmpty()) {
                flushPara(para, paras);
            } else {
                if (id == null) throw new IllegalArgumentException("guides line " + n + ": text before the first @id");
                if (!para.isEmpty()) para.append('\n');
                para.append(t);
            }
        }
        flushPara(para, paras);
        if (id != null) out.add(new Guide(id, title, paras));
        return new Guides(out);
    }

    private static void flushPara(StringBuilder para, List<String> paras) {
        if (!para.isEmpty()) paras.add(para.toString());
        para.setLength(0);
    }

    public Guide guide(String id) {
        Guide g = byId.get(id);
        if (g == null) throw new IllegalArgumentException("unknown guide " + id);
        return g;
    }

    public boolean has(String id) {
        return byId.containsKey(id);
    }

    public Collection<Guide> all() {
        return byId.values();
    }
}
