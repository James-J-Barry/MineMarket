package com.realisticmarkets.options;

/**
 * Black-76: a European option on a forward price {@code F}, strike {@code K}, {@code t} days to expiry, daily
 * volatility {@code sigma} (standard deviation of the log price per day) and daily interest rate {@code r}. Prices
 * are in the same units as F and K. Greeks are per unit of the forward (delta), per point of daily volatility (vega,
 * 0.01) and per day (theta, the value lost over the next day if nothing moves).
 */
public final class OptionMath {
    private OptionMath() {}

    public record Greeks(double price, double delta, double gamma, double vega, double theta) {}

    public static double price(boolean call, double f, double k, double t, double sigma, double r) {
        double df = Math.exp(-Math.log1p(r) * t);
        if (t <= 0 || sigma <= 0) return df * Math.max(0, call ? f - k : k - f);
        double sd = sigma * Math.sqrt(t);
        double d1 = (Math.log(f / k) + sd * sd / 2) / sd, d2 = d1 - sd;
        return call ? df * (f * cdf(d1) - k * cdf(d2)) : df * (k * cdf(-d2) - f * cdf(-d1));
    }

    public static Greeks greeks(boolean call, double f, double k, double t, double sigma, double r) {
        double p = price(call, f, k, t, sigma, r);
        double df = Math.exp(-Math.log1p(r) * t);
        if (t <= 0 || sigma <= 0) {
            double itm = (call ? f > k : k > f) ? df : 0;
            return new Greeks(p, call ? itm : -itm, 0, 0, 0);
        }
        double sd = sigma * Math.sqrt(t);
        double d1 = (Math.log(f / k) + sd * sd / 2) / sd;
        double delta = call ? df * cdf(d1) : -df * cdf(-d1);
        double gamma = df * pdf(d1) / (f * sd);
        double vega = df * f * pdf(d1) * Math.sqrt(t) * 0.01;
        double theta = p - price(call, f, k, Math.max(0, t - 1), sigma, r);
        return new Greeks(p, delta, gamma, vega, theta);
    }

    public static double pdf(double x) {
        return Math.exp(-x * x / 2) / Math.sqrt(2 * Math.PI);
    }

    /** Standard normal CDF, via erfc. */
    public static double cdf(double x) {
        return 0.5 * erfc(-x / Math.sqrt(2));
    }

    private static double erfc(double x) {
        // Numerical Recipes' erfc with Chebyshev fit: relative error below 1.2e-7 everywhere, plenty for prices in cents.
        double z = Math.abs(x);
        double t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? r : 2 - r;
    }
}
