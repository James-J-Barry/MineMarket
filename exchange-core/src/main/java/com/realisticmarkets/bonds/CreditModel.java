package com.realisticmarkets.bonds;

import com.realisticmarkets.equities.Equities;
import java.util.List;
import java.util.Optional;

/**
 * Credit risk for company bonds. A company's spread over Treasuries starts at its base (steady companies little,
 * risky ones more) and widens as its recent earnings fall short of normal, so its bonds drop in price before any
 * default. A company defaults when two quarterly reports in a row show a loss: its bonds then stop paying coupons and
 * pay back {@link #RECOVERY} of face when presented.
 */
public final class CreditModel {
    /** Share of face value a defaulted bond pays back. */
    public static final double RECOVERY = 0.4;
    /** How fast the spread widens: at zero recent earnings it's this many times the base on top. */
    public static final double STRESS_MULTIPLIER = 4;

    private CreditModel() {}

    /**
     * Spread a day over Treasuries: base x (1 + 4 x stress), where stress is how far the last two quarters' average
     * earnings fall short of {@code normalEarnings} (0 = at or above normal, 1 = zero, up to 2 for deep losses).
     */
    public static double spread(double base, List<Equities.Report> reports, double normalEarnings) {
        if (reports.isEmpty() || normalEarnings <= 0) return base;
        int n = Math.min(2, reports.size());
        double recent = 0;
        for (int i = reports.size() - n; i < reports.size(); i++) recent += reports.get(i).earnings();
        recent /= n;
        double stress = Math.max(0, Math.min(2, 1 - recent / normalEarnings));
        return base * (1 + STRESS_MULTIPLIER * stress);
    }

    /** The quarter a company defaulted in (the second loss of two in a row), if it has. */
    public static Optional<Long> defaultQuarter(List<Equities.Report> reports) {
        for (int i = 1; i < reports.size(); i++) {
            if (reports.get(i - 1).earnings() < 0 && reports.get(i).earnings() < 0) return Optional.of(reports.get(i).quarter());
        }
        return Optional.empty();
    }

    /**
     * Every quarter the company defaulted in: the second of two losing quarters in a row, then again only after a
     * fresh pair (a restructured company starts over).
     */
    public static List<Long> defaultQuarters(List<Equities.Report> reports) {
        List<Long> out = new java.util.ArrayList<>();
        for (int i = 1; i < reports.size(); i++) {
            if (reports.get(i - 1).earnings() < 0 && reports.get(i).earnings() < 0) {
                out.add(reports.get(i).quarter());
                i++; // the next default needs two new losses
            }
        }
        return out;
    }

    /** What one defaulted bond pays back, in cents. */
    public static long recoveryCents() {
        return Math.round(Bond.FACE_CENTS * RECOVERY);
    }
}
