package com.realisticmarkets.equities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What each account paid for the shares it bought at the Stock Exchange, at average cost, so a sale can be compared
 * with its cost (the Beat the Market quest, and later the Records Terminal). Bearer certificates can change hands
 * without the exchange knowing, so this is the account's own buying and selling there, not proof of ownership.
 */
public final class CostBasis {
    public static final String HEADER = "# Realistic Markets cost basis v1";

    private final Map<String, long[]> byKey = new LinkedHashMap<>(); // "account|ticker" -> {shares, cents}

    private static String key(String account, String ticker) {
        return account + "|" + ticker;
    }

    public void bought(String account, String ticker, long shares, long cents) {
        if (shares <= 0) return;
        long[] b = byKey.computeIfAbsent(key(account, ticker), k -> new long[2]);
        b[0] += shares;
        b[1] += cents;
    }

    /**
     * Records a sale and returns the average cost (cents) of the shares sold, or -1 if this account hasn't bought
     * that many here (certificates from elsewhere have no known cost).
     */
    public long sold(String account, String ticker, long shares) {
        long[] b = byKey.get(key(account, ticker));
        if (b == null || shares <= 0 || b[0] < shares) {
            if (b != null && b[0] < shares) byKey.remove(key(account, ticker));
            return -1;
        }
        long cost = Math.round(b[1] * (double) shares / b[0]);
        b[0] -= shares;
        b[1] -= cost;
        if (b[0] == 0) byKey.remove(key(account, ticker));
        return cost;
    }

    public long shares(String account, String ticker) {
        long[] b = byKey.get(key(account, ticker));
        return b == null ? 0 : b[0];
    }

    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        for (Map.Entry<String, long[]> e : byKey.entrySet()) w.write(e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1] + "\n");
        w.flush();
    }

    public static CostBasis read(Reader r) throws IOException {
        CostBasis cb = new CostBasis();
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            cb.byKey.put(c[0], new long[] {Long.parseLong(c[1]), Long.parseLong(c[2])});
        }
        return cb;
    }
}
