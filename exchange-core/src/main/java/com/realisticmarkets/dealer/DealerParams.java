package com.realisticmarkets.dealer;

import java.util.Properties;

/**
 * Global Dealer tuning. Every value here is a balance knob from the design doc's Economy section.
 *
 * @param spread             total bid/ask spread at the Basic Exchange (0.20 = 20%)
 * @param licensedSpread     spread with a Merchant License
 * @param k                  price-impact steepness in m(I) = V e^{-kI/L}
 * @param recoveryDays       tau: the Dealer's inventory decays as e^{-t/tau}
 * @param driftSigma         daily volatility of log fair value (0.02 = 2%)
 * @param anchorHalfLifeDays how slowly fair value is pulled back toward its catalog value (a very weak anchor)
 * @param trendSigma         daily shock to the trend (the drift of log fair value per day)
 * @param trendHalfLifeDays  how long a trend persists
 * @param supplyImpact       permanent change in log fair value per depth's worth sold to (-) or bought from (+)
 *                           the Dealer
 */
public record DealerParams(
        double spread,
        double licensedSpread,
        double k,
        double recoveryDays,
        double driftSigma,
        double anchorHalfLifeDays,
        double trendSigma,
        double trendHalfLifeDays,
        double supplyImpact) {

    public DealerParams {
        if (spread < 0 || spread >= 2) throw new IllegalArgumentException("spread out of range");
        if (licensedSpread < 0 || licensedSpread >= 2) throw new IllegalArgumentException("licensedSpread out of range");
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        if (recoveryDays <= 0) throw new IllegalArgumentException("recoveryDays must be positive");
        if (driftSigma < 0 || trendSigma < 0) throw new IllegalArgumentException("volatilities must be >= 0");
        if (anchorHalfLifeDays <= 0 || trendHalfLifeDays <= 0) throw new IllegalArgumentException("half-lives must be positive");
        if (supplyImpact < 0) throw new IllegalArgumentException("supplyImpact must be >= 0");
    }

    public static DealerParams defaults() {
        return new DealerParams(0.20, 0.12, 1.0, 2.0, 0.02, 60.0, 0.0025, 7.0, 0.01);
    }

    /** Same as defaults but fair value never moves (no drift, trends or supply impact). For exact-number tests. */
    public static DealerParams noDrift() {
        DealerParams d = defaults();
        return new DealerParams(d.spread, d.licensedSpread, d.k, d.recoveryDays, 0.0, d.anchorHalfLifeDays, 0.0,
                d.trendHalfLifeDays, 0.0);
    }

    /** A copy with a different spread (the Capital's). */
    public DealerParams withSpread(double s) {
        return new DealerParams(s, s, k, recoveryDays, driftSigma, anchorHalfLifeDays, trendSigma, trendHalfLifeDays,
                supplyImpact);
    }

    /**
     * Reads {@code dealer_params.properties}-style keys; missing keys keep their defaults. The old
     * {@code drift_half_life_days} (a strong 10-day snap-back) is ignored: it was replaced by
     * {@code anchor_half_life_days} in M5c.
     */
    public static DealerParams fromProperties(Properties p) {
        DealerParams d = defaults();
        return new DealerParams(
                num(p, "spread", d.spread),
                num(p, "licensed_spread", d.licensedSpread),
                num(p, "k", d.k),
                num(p, "recovery_days", d.recoveryDays),
                num(p, "drift_sigma", d.driftSigma),
                num(p, "anchor_half_life_days", d.anchorHalfLifeDays),
                num(p, "trend_sigma", d.trendSigma),
                num(p, "trend_half_life_days", d.trendHalfLifeDays),
                num(p, "supply_impact", d.supplyImpact));
    }

    private static double num(Properties p, String key, double fallback) {
        String v = p.getProperty(key);
        return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim());
    }
}
