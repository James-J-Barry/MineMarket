package com.realisticmarkets.dealer;

import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.money.Money;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The single world Dealer behind the Basic Exchange (design doc: "Basic Exchange and the Dealer").
 *
 * <p>Per base pool it tracks inventory I (units bought minus sold, in base units). Prices:
 * <pre>
 *   m(I)  = V e^{-kI/L}                 mid price
 *   bid   = m (1 - s/2),  ask = m (1 + s/2)
 *   sell n units: V(1-s/2)(L/k) e^{-kI/L} (1 - e^{-kn/L})     (price walks inside the trade)
 *   buy  n units: V(1+s/2)(L/k) e^{-kI/L} (e^{kn/L} - 1)
 *   between trades: I(t) = I0 e^{-t/tau}
 * </pre>
 * Fair value V drifts as a mean-reverting AR(1) on log V, driven by a deterministic per-day shock
 * derived from the world seed, so the same world always has the same price history.
 *
 * <p>Time is passed in as fractional in-game days by the caller; the Dealer never reads a clock.
 * Not thread-safe; call from the server thread.
 */
public final class Dealer {

    public enum Side { SELL_TO_DEALER, BUY_FROM_DEALER }

    /**
     * @param cents          what changes hands, already rounded in the Dealer's favor
     * @param rawCents       unrounded amount (for tests and the Records view)
     * @param unitPriceBefore marginal price per item before the trade, in dollars
     * @param unitPriceAfter  marginal price per item after the trade, in dollars
     */
    public record Quote(String itemId, long items, Side side, long cents, double rawCents,
                        double unitPriceBefore, double unitPriceAfter) {}

    /** Persistable per-pool state. */
    public record PoolState(double inventory, double lastDay, long driftDay, double logDeviation) {}

    private static final class Pool {
        double inventory;
        double lastDay = Double.NaN;
        long driftDay;
        double logDev;
    }

    private DealerCatalog catalog;
    private DealerParams params;
    private final long seed;
    private final Map<String, Pool> pools = new LinkedHashMap<>();

    public Dealer(DealerCatalog catalog, DealerParams params, long seed) {
        this.catalog = catalog;
        this.params = params;
        this.seed = seed;
    }

    public DealerCatalog catalog() { return catalog; }
    public DealerParams params() { return params; }

    /** Swap in edited catalog/params (e.g. /mkt dealer reload). Existing pool state is kept. */
    public void reload(DealerCatalog newCatalog, DealerParams newParams) {
        this.catalog = newCatalog;
        this.params = newParams;
        pools.keySet().retainAll(newCatalog.all().keySet());
    }

    // ------------------------------------------------------------------ quotes

    public Quote quoteSell(String itemId, long items, double day, boolean licensed) {
        return price(itemId, items, day, licensed, Side.SELL_TO_DEALER, false);
    }

    public Quote sell(String itemId, long items, double day, boolean licensed) {
        Quote q = price(itemId, items, day, licensed, Side.SELL_TO_DEALER, true);
        return q;
    }

    public Quote quoteBuy(String itemId, long items, double day, boolean licensed) {
        return price(itemId, items, day, licensed, Side.BUY_FROM_DEALER, false);
    }

    public Quote buy(String itemId, long items, double day, boolean licensed) {
        return price(itemId, items, day, licensed, Side.BUY_FROM_DEALER, true);
    }

    /** Marginal price the Dealer pays for one more item right now, in dollars. */
    public double bid(String itemId, double day, boolean licensed) {
        MarketSpec spec = catalog.spec(itemId);
        MarketSpec base = catalog.pool(itemId);
        Pool p = advance(base, day);
        return mid(base, p) * spec.baseUnits() * (1 - spread(base, licensed) / 2);
    }

    /** Marginal price the Dealer charges for one more item right now, in dollars. */
    public double ask(String itemId, double day, boolean licensed) {
        MarketSpec spec = catalog.spec(itemId);
        MarketSpec base = catalog.pool(itemId);
        Pool p = advance(base, day);
        return mid(base, p) * spec.baseUnits() * (1 + spread(base, licensed) / 2);
    }

    /** The Dealer's current mid price per item (between bid and ask), in dollars. */
    public double mid(String itemId, double day) {
        MarketSpec spec = catalog.spec(itemId);
        MarketSpec base = catalog.pool(itemId);
        return mid(base, advance(base, day)) * spec.baseUnits();
    }

    public double fairValue(String itemId, double day) {
        MarketSpec spec = catalog.spec(itemId);
        MarketSpec base = catalog.pool(itemId);
        return fair(base, advance(base, day)) * spec.baseUnits();
    }

    /** Dealer inventory of the item's base pool, in base units. */
    public double inventory(String itemId, double day) {
        return advance(catalog.pool(itemId), day).inventory;
    }

    // ------------------------------------------------------------------ persistence & debug

    public Map<String, PoolState> snapshot() {
        Map<String, PoolState> out = new LinkedHashMap<>();
        pools.forEach((id, p) -> out.put(id, new PoolState(p.inventory, p.lastDay, p.driftDay, p.logDev)));
        return out;
    }

    public void restore(Map<String, PoolState> states) {
        pools.clear();
        states.forEach((id, s) -> {
            if (!catalog.all().containsKey(id)) return;
            Pool p = new Pool();
            p.inventory = s.inventory();
            p.lastDay = s.lastDay();
            p.driftDay = s.driftDay();
            p.logDev = s.logDeviation();
            pools.put(id, p);
        });
    }

    /** JSON view of every base pool: fair value, inventory, and live quotes for one item. */
    public String stateJson(double day, boolean licensed) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "{\"day\":%.3f,\"pools\":[", day));
        boolean first = true;
        for (MarketSpec base : catalog.basePools()) {
            Pool p = advance(base, day);
            if (!first) sb.append(',');
            first = false;
            sb.append(String.format(Locale.ROOT,
                    "{\"item\":\"%s\",\"fairValue\":%.4f,\"inventory\":%.2f,\"bid\":%.4f,\"ask\":%.4f}",
                    base.itemId(), fair(base, p), p.inventory,
                    bid(base.itemId(), day, licensed), ask(base.itemId(), day, licensed)));
        }
        return sb.append("]}").toString();
    }

    // ------------------------------------------------------------------ internals

    private Quote price(String itemId, long items, double day, boolean licensed, Side side, boolean execute) {
        if (items <= 0) throw new RejectedException("quantity must be positive");
        MarketSpec spec = catalog.spec(itemId);
        MarketSpec base = catalog.pool(itemId);
        Pool p = advance(base, day);

        double units = (double) items * spec.baseUnits();
        double k = params.k();
        double depth = base.depth();
        double s = spread(base, licensed);
        double fv = fair(base, p);
        double level = Math.exp(-k * p.inventory / depth);
        double before = fv * level * spec.baseUnits();

        double rawDollars;
        double invAfter;
        if (side == Side.SELL_TO_DEALER) {
            rawDollars = fv * (1 - s / 2) * (depth / k) * level * (1 - Math.exp(-k * units / depth));
            invAfter = p.inventory + units;
        } else {
            double x = k * units / depth;
            if (x > 50) throw new RejectedException("The Dealer can't supply that many " + itemId);
            rawDollars = fv * (1 + s / 2) * (depth / k) * level * Math.expm1(x);
            invAfter = p.inventory - units;
        }
        double rawCents = rawDollars * 100.0;
        long cents = side == Side.SELL_TO_DEALER ? Money.roundDownToDime(rawCents) : Money.roundUpToDime(rawCents);
        if (side == Side.SELL_TO_DEALER && cents == 0) {
            throw new RejectedException("The Dealer won't pay anything for that right now (price has collapsed; wait for it to recover)");
        }
        double after = fv * Math.exp(-k * invAfter / depth) * spec.baseUnits();
        double sideFactor = side == Side.SELL_TO_DEALER ? (1 - s / 2) : (1 + s / 2);

        if (execute) p.inventory = invAfter;
        return new Quote(spec.itemId(), items, side, cents, rawCents, before * sideFactor, after * sideFactor);
    }

    /**
     * A pool's spread. The Merchant License scales every spread by the same ratio
     * (licensed_spread / spread, 12/20 by default), so a 40% component becomes 24%.
     */
    double spread(MarketSpec base, boolean licensed) {
        if (base.spread() == null) return licensed ? params.licensedSpread() : params.spread();
        if (!licensed) return base.spread();
        return params.spread() == 0 ? base.spread() : base.spread() * params.licensedSpread() / params.spread();
    }

    private double mid(MarketSpec base, Pool p) {
        return fair(base, p) * Math.exp(-params.k() * p.inventory / base.depth());
    }

    private static double fair(MarketSpec base, Pool p) {
        return base.fairValue() * Math.exp(p.logDev);
    }

    /** Brings a pool's inventory decay and fair-value drift up to {@code day}. */
    private Pool advance(MarketSpec base, double day) {
        Pool p = pools.computeIfAbsent(base.itemId(), id -> new Pool());
        if (Double.isNaN(p.lastDay)) p.lastDay = day;
        if (day > p.lastDay) {
            p.inventory *= Math.exp(-(day - p.lastDay) / params.recoveryDays());
            p.lastDay = day;
        }
        long target = (long) Math.floor(day);
        if (target > p.driftDay) {
            double phi = Math.pow(0.5, 1.0 / params.driftHalfLifeDays());
            while (p.driftDay < target) {
                p.driftDay++;
                p.logDev = phi * p.logDev + params.driftSigma() * shock(base.itemId(), p.driftDay);
            }
        }
        return p;
    }

    private double shock(String itemId, long day) {
        long mix = seed ^ (itemId.hashCode() * 0x9E3779B97F4A7C15L) ^ (day * 0xC2B2AE3D27D4EB4FL);
        return new SplittableRandom(mix).nextGaussian();
    }
}
