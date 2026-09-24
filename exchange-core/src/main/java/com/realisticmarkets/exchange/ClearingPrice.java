package com.realisticmarkets.exchange;

import java.util.List;
import java.util.OptionalLong;
import java.util.TreeSet;

/**
 * Uniform-price call auction clearing, the same idea exchanges use for opening/closing crosses.
 *
 * <p>Among all candidate prices (every limit price in the book), pick the one that:
 * <ol>
 *   <li>maximises executable volume, min(demand(p), supply(p));</li>
 *   <li>then minimises imbalance, |demand(p) - supply(p)|;</li>
 *   <li>then follows market pressure: with more selling than buying, the higher price (the last buyer's limit);
 *       with more buying than selling, the lower price;</li>
 *   <li>then is closest to the reference price (last clearing price), if there is one;</li>
 *   <li>then is the lower price (deterministic final tie-break).</li>
 * </ol>
 * demand(p) = total bid quantity with limit >= p; supply(p) = total ask quantity with limit <= p.
 *
 * <p>Prices are only considered between the lowest bid limit and the highest ask limit. Clearing outside that
 * range can never add volume, and without the bound an aggressive order (a "market" sell priced at 1) that
 * outweighs the other side would drag the price to its own extreme limit: every buyer willing to pay far more
 * would get the goods at 1. With it, the price stops at the last buyer's limit, as in real call auctions.
 *
 * <p>Complexity is O(k * n) for k distinct prices and n orders, which is plenty for game-scale
 * books. A prefix-sum sweep makes it O(n log n) if it ever matters.
 */
public final class ClearingPrice {
    private ClearingPrice() {}

    public record Result(long price, long volume, long demand, long supply) {}

    public static OptionalLong compute(List<Order> bids, List<Order> asks, OptionalLong reference) {
        Result r = computeDetailed(bids, asks, reference);
        return r == null ? OptionalLong.empty() : OptionalLong.of(r.price());
    }

    public static Result computeDetailed(List<Order> bids, List<Order> asks, OptionalLong reference) {
        if (bids.isEmpty() || asks.isEmpty()) return null;

        long lowestBid = Long.MAX_VALUE, highestAsk = 0;
        for (Order o : bids) lowestBid = Math.min(lowestBid, o.limitPrice());
        for (Order o : asks) highestAsk = Math.max(highestAsk, o.limitPrice());
        long lo = Math.min(lowestBid, highestAsk), hi = Math.max(lowestBid, highestAsk);
        TreeSet<Long> candidates = new TreeSet<>();
        for (Order o : bids) if (o.limitPrice() >= lo && o.limitPrice() <= hi) candidates.add(o.limitPrice());
        for (Order o : asks) if (o.limitPrice() >= lo && o.limitPrice() <= hi) candidates.add(o.limitPrice());

        Result best = null;
        for (long p : candidates) {
            long demand = 0, supply = 0;
            for (Order b : bids) if (b.limitPrice() >= p) demand += b.remaining();
            for (Order a : asks) if (a.limitPrice() <= p) supply += a.remaining();
            long volume = Math.min(demand, supply);
            if (volume == 0) continue;
            Result cand = new Result(p, volume, demand, supply);
            if (best == null || better(cand, best, reference)) best = cand;
        }
        return best;
    }

    private static boolean better(Result a, Result b, OptionalLong ref) {
        if (a.volume() != b.volume()) return a.volume() > b.volume();
        long imbA = Math.abs(a.demand() - a.supply());
        long imbB = Math.abs(b.demand() - b.supply());
        if (imbA != imbB) return imbA < imbB;
        long pressure = Long.signum(a.supply() - a.demand());
        if (pressure != 0 && pressure == Long.signum(b.supply() - b.demand()) && a.price() != b.price()) {
            return pressure > 0 ? a.price() > b.price() : a.price() < b.price();
        }
        if (ref.isPresent()) {
            long dA = Math.abs(a.price() - ref.getAsLong());
            long dB = Math.abs(b.price() - ref.getAsLong());
            if (dA != dB) return dA < dB;
        }
        return a.price() < b.price();
    }
}
