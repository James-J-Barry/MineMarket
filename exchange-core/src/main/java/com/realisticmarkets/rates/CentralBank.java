package com.realisticmarkets.rates;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * The central bank's policy rate (a day), the base for the vault, new CDs, loans and bond yields. It is reviewed at
 * dawn every {@link #REVIEW_DAYS} days: hold, raise or cut by {@link #STEP}, drawn from the world seed with a mild pull
 * toward {@link #LONG_RUN}, within [{@link #MIN}, {@link #MAX}]. The bank applies a decision at once; the rest of the
 * market (bond prices) hears it a while after dawn, the same unstated head start as other news.
 */
public final class CentralBank {
    public static final int REVIEW_DAYS = 7;
    public static final double START = 0.003, LONG_RUN = 0.003, STEP = 0.0005, MIN = 0.0015, MAX = 0.005;
    /** The market hears a decision this long after dawn (varies per decision). */
    public static final double MIN_DELAY_DAYS = 0.3, MAX_DELAY_DAYS = 0.7;
    /** Extra yield a day for each quarter to maturity (a gently upward-sloping curve). */
    public static final double TERM_PREMIUM = 0.00004;

    public enum Move { RAISE, HOLD, CUT }

    /** A review: the move, the rate from that dawn, and when the market hears it. */
    public record Decision(long day, Move move, double rate, double delay) {}

    private final long seed;
    private final List<Decision> decisions = new ArrayList<>(); // index = review number (0 = the start)

    public CentralBank(long seed) {
        this.seed = seed;
        decisions.add(new Decision(0, Move.HOLD, START, 0));
    }

    private Decision review(int n) {
        while (decisions.size() <= n) {
            int k = decisions.size();
            double r = decisions.get(k - 1).rate();
            SplittableRandom rnd = new SplittableRandom(seed ^ (k * 0x9E3779B97F4A7C15L) ^ 0x43656E7472616CL);
            double pull = Math.max(-1, Math.min(1, (LONG_RUN - r) / (2 * STEP)));
            double u = rnd.nextDouble(), raise = 0.25 + 0.2 * pull, cut = 0.25 - 0.2 * pull;
            Move m = u < raise ? Move.RAISE : u > 1 - cut ? Move.CUT : Move.HOLD;
            double next = switch (m) {
                case RAISE -> Math.min(MAX, r + STEP);
                case CUT -> Math.max(MIN, r - STEP);
                case HOLD -> r;
            };
            if (next == r) m = Move.HOLD;
            decisions.add(new Decision((long) k * REVIEW_DAYS, m, next, MIN_DELAY_DAYS + (MAX_DELAY_DAYS - MIN_DELAY_DAYS) * rnd.nextDouble()));
        }
        return decisions.get(n);
    }

    /** The policy rate the bank applies on {@code day} (changes at dawn of each review day). */
    public double rate(double day) {
        return review((int) Math.max(0, Math.floorDiv((long) Math.floor(day), REVIEW_DAYS))).rate();
    }

    /** The rate bond prices reflect at {@code day}: a new decision counts only once the market has heard it. */
    public double marketRate(double day) {
        int n = (int) Math.max(0, Math.floorDiv((long) Math.floor(day), REVIEW_DAYS));
        Decision d = review(n);
        if (n > 0 && day < d.day() + d.delay()) return review(n - 1).rate();
        return d.rate();
    }

    /** The review on {@code day}, if that day is a review day (the Newsstand reports raises and cuts). */
    public Optional<Decision> decisionOn(long day) {
        if (day <= 0 || day % REVIEW_DAYS != 0) return Optional.empty();
        return Optional.of(review((int) (day / REVIEW_DAYS)));
    }

    /** Yield a day for a bond with {@code quartersLeft} quarters to maturity, as the market sees it at {@code day}. */
    public double yield(double day, double quartersLeft) {
        return marketRate(day) + TERM_PREMIUM * Math.max(0, quartersLeft);
    }
}
