package com.realisticmarkets.collateral;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.MarketSpec;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.money.Money;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Values item collateral the way the design doc's "Contracts and item collateral" section does: each pool at its
 * liquidation value P(n) (what the Dealer would pay for all of it right now, price impact included), less the class
 * haircut. Items that share a pool (ingots and blocks) are valued as one sale in base units. Cash in escrow counts
 * at face value less the class A haircut.
 *
 * <pre>
 * C = sum (1 - h_i) P_i(n_i)      Q = C / sum n_i m_i      max loan = C / 1.25
 * daily rate = 0.4% + 1.0% x (1 - Q)                         margin call below C / owed = 110%
 * </pre>
 */
public final class CollateralValuer {
    public static final double ADVANCE_RATE = 1 / 1.25;
    public static final double MAINTENANCE = 1.10;
    public static final double BASE_RATE = 0.004;
    public static final double QUALITY_RATE = 0.010;

    public enum Grade {
        A(0.10), B(0.20), C(0.40), D(Double.NaN);

        public final double haircut;

        Grade(double haircut) {
            this.haircut = haircut;
        }

        public boolean accepted() {
            return this != D;
        }
    }

    /** One pool's line in a valuation: {@code units} in base units, cents throughout. */
    public record Line(String pool, Grade grade, double units, long marketCents, long liquidationCents, long valueCents) {}

    public record Valuation(List<Line> lines, List<String> refused, long cashCents, long marketCents,
                            long liquidationCents, long valueCents) {
        /** Collateral quality Q: haircut liquidation value over naive market value. */
        public double quality() {
            return marketCents == 0 ? 0 : valueCents / (double) marketCents;
        }

        public long maxLoanCents() {
            return Money.roundDownToDime(valueCents * ADVANCE_RATE);
        }

        public double dailyRate() {
            return BASE_RATE + QUALITY_RATE * (1 - quality());
        }

        /** C / owed; infinite when nothing is owed. */
        public double coverage(long owedCents) {
            return owedCents <= 0 ? Double.POSITIVE_INFINITY : valueCents / (double) owedCents;
        }
    }

    private CollateralValuer() {}

    public static Grade gradeOf(DealerCatalog catalog, String itemId) {
        if (!catalog.trades(itemId)) return Grade.D;
        MarketSpec pool = catalog.pool(itemId);
        if (pool.collateralClass() != null) return Grade.valueOf(pool.collateralClass());
        return switch (pool.group()) {
            case "mining" -> Grade.B;
            case "farm", "mobs", "wood_and_stone" -> Grade.C;
            default -> Grade.D;
        };
    }

    /** Values {@code items} (item id to count) plus {@code cashCents} of bills, at the Dealer's prices on {@code day}. */
    public static Valuation value(Map<String, Integer> items, long cashCents, Dealer dealer, double day) {
        DealerCatalog catalog = dealer.catalog();
        Map<String, Double> units = new LinkedHashMap<>();
        List<String> refused = new ArrayList<>();
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (!gradeOf(catalog, e.getKey()).accepted()) {
                refused.add(e.getKey());
                continue;
            }
            MarketSpec spec = catalog.spec(e.getKey());
            units.merge(catalog.pool(e.getKey()).itemId(), (double) e.getValue() * spec.baseUnits(), Double::sum);
        }
        List<Line> lines = new ArrayList<>();
        long market = cashCents, liquidation = cashCents;
        long value = Money.roundDownToDime(cashCents * (1 - Grade.A.haircut));
        for (Map.Entry<String, Double> e : units.entrySet()) {
            String pool = e.getKey();
            long n = Math.round(e.getValue());
            Grade grade = gradeOf(catalog, pool);
            long m = Math.round(dealer.mid(pool, day) * n * 100);
            long p;
            try {
                p = dealer.quoteSell(pool, n, day, false).cents();
            } catch (RejectedException collapsed) {
                p = 0;
            }
            long c = Money.roundDownToDime(p * (1 - grade.haircut));
            lines.add(new Line(pool, grade, n, m, p, c));
            market += m;
            liquidation += p;
            value += c;
        }
        return new Valuation(lines, refused, cashCents, market, liquidation, value);
    }
}
