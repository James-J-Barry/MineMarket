package com.realisticmarkets.exchange;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tiny dependency-free JSON views of exchange state. Used by debug commands so that scripts and
 * AI agents can read market state over RCON as structured data.
 */
public final class Snapshots {
    private Snapshots() {}

    /** Aggregated price levels, best-first, up to {@code depth} levels per side. */
    public static String bookJson(Exchange ex, String instrument, int depth) {
        OrderBook book = ex.book(instrument);
        return "{\"instrument\":" + str(instrument)
                + ",\"last\":" + (ex.lastPrice(instrument).isPresent() ? ex.lastPrice(instrument).getAsLong() : "null")
                + ",\"bids\":" + levels(book.bids(), depth, true)
                + ",\"asks\":" + levels(book.asks(), depth, false) + "}";
    }

    public static String accountJson(Exchange ex, String accountId) {
        Account a = ex.account(accountId);
        StringBuilder orders = new StringBuilder("[");
        List<Order> open = ex.openOrders(accountId);
        for (int i = 0; i < open.size(); i++) {
            Order o = open.get(i);
            if (i > 0) orders.append(',');
            orders.append("{\"id\":").append(o.id())
                    .append(",\"instrument\":").append(str(o.instrument()))
                    .append(",\"side\":").append(str(o.side().name()))
                    .append(",\"remaining\":").append(o.remaining())
                    .append(",\"price\":").append(o.limitPrice())
                    .append(",\"tif\":").append(str(o.timeInForce().name())).append('}');
        }
        orders.append(']');
        return "{\"account\":" + str(accountId)
                + ",\"cash\":" + a.cash()
                + ",\"lockedCash\":" + a.lockedCash()
                + ",\"positions\":" + map(a.positions())
                + ",\"lockedPositions\":" + map(a.lockedPositions())
                + ",\"openOrders\":" + orders + "}";
    }

    private static String levels(List<Order> side, int depth, boolean bids) {
        TreeMap<Long, Long> agg = new TreeMap<>(bids ? java.util.Comparator.reverseOrder() : null);
        for (Order o : side) agg.merge(o.limitPrice(), o.remaining(), Long::sum);
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (Map.Entry<Long, Long> e : agg.entrySet()) {
            if (n == depth) break;
            if (n++ > 0) sb.append(',');
            sb.append("{\"price\":").append(e.getKey()).append(",\"qty\":").append(e.getValue()).append('}');
        }
        return sb.append(']').toString();
    }

    private static String map(Map<String, Long> m) {
        StringBuilder sb = new StringBuilder("{");
        int n = 0;
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (n++ > 0) sb.append(',');
            sb.append(str(e.getKey())).append(':').append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static String str(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
