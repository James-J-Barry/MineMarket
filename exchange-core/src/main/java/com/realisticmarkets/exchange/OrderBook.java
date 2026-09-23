package com.realisticmarkets.exchange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Resting orders for a single instrument. Bids are kept best-first (highest price, then earliest);
 * asks best-first (lowest price, then earliest). Plain sorted lists are fine at Minecraft-server
 * order counts; swap for price-level maps if profiling says otherwise.
 */
public final class OrderBook {
    static final Comparator<Order> BID_PRIORITY =
            Comparator.comparingLong(Order::limitPrice).reversed().thenComparingLong(Order::id);
    static final Comparator<Order> ASK_PRIORITY =
            Comparator.comparingLong(Order::limitPrice).thenComparingLong(Order::id);

    private final String instrument;
    private final List<Order> bids = new ArrayList<>();
    private final List<Order> asks = new ArrayList<>();

    OrderBook(String instrument) {
        this.instrument = instrument;
    }

    public String instrument() { return instrument; }

    /** Best-first, read-only view. */
    public List<Order> bids() { return List.copyOf(bids); }

    /** Best-first, read-only view. */
    public List<Order> asks() { return List.copyOf(asks); }

    void add(Order order) {
        List<Order> side = order.side() == Side.BUY ? bids : asks;
        Comparator<Order> cmp = order.side() == Side.BUY ? BID_PRIORITY : ASK_PRIORITY;
        int i = 0;
        while (i < side.size() && cmp.compare(side.get(i), order) < 0) i++;
        side.add(i, order);
    }

    boolean remove(Order order) {
        return (order.side() == Side.BUY ? bids : asks).remove(order);
    }

    List<Order> mutableBids() { return bids; }
    List<Order> mutableAsks() { return asks; }

    public long bestBid() { return bids.isEmpty() ? 0 : bids.get(0).limitPrice(); }
    public long bestAsk() { return asks.isEmpty() ? 0 : asks.get(0).limitPrice(); }
    public boolean isCrossed() { return !bids.isEmpty() && !asks.isEmpty() && bestBid() >= bestAsk(); }
}
