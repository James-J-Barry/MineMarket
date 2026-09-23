package com.realisticmarkets.dealer;

import java.util.Properties;

/**
 * Global Dealer tuning. Every value here is a balance knob from the design doc's Economy section.
 *
 * @param spread           total bid/ask spread at the Basic Exchange (0.20 = 20%)
 * @param licensedSpread   spread with a Merchant License
 * @param k                price-impact steepness in m(I) = V e^{-kI/L}
 * @param recoveryDays     tau: inventory decays as e^{-t/tau}
 * @param driftSigma       daily volatility of log fair value (0.02 = 2%)
 * @param driftHalfLifeDays how fast fair value mean-reverts to its base
 */
public record DealerParams(
        double spread,
        double licensedSpread,
        double k,
        double recoveryDays,
        double driftSigma,
        double driftHalfLifeDays) {

    public DealerParams {
        if (spread < 0 || spread >= 2) throw new IllegalArgumentException("spread out of range");
        if (licensedSpread < 0 || licensedSpread >= 2) throw new IllegalArgumentException("licensedSpread out of range");
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        if (recoveryDays <= 0) throw new IllegalArgumentException("recoveryDays must be positive");
        if (driftSigma < 0) throw new IllegalArgumentException("driftSigma must be >= 0");
        if (driftHalfLifeDays <= 0) throw new IllegalArgumentException("driftHalfLifeDays must be positive");
    }

    public static DealerParams defaults() {
        return new DealerParams(0.20, 0.12, 1.0, 2.0, 0.02, 10.0);
    }

    /** Same as defaults but with no fair-value drift. Handy for exact-number tests. */
    public static DealerParams noDrift() {
        DealerParams d = defaults();
        return new DealerParams(d.spread, d.licensedSpread, d.k, d.recoveryDays, 0.0, d.driftHalfLifeDays);
    }

    /** Reads {@code dealer_params.properties}-style keys; missing keys keep their defaults. */
    public static DealerParams fromProperties(Properties p) {
        DealerParams d = defaults();
        return new DealerParams(
                num(p, "spread", d.spread),
                num(p, "licensed_spread", d.licensedSpread),
                num(p, "k", d.k),
                num(p, "recovery_days", d.recoveryDays),
                num(p, "drift_sigma", d.driftSigma),
                num(p, "drift_half_life_days", d.driftHalfLifeDays));
    }

    private static double num(Properties p, String key, double fallback) {
        String v = p.getProperty(key);
        return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim());
    }
}
