package com.realisticmarkets.bonds;

import com.realisticmarkets.equities.Company;
import com.realisticmarkets.equities.Equities;
import com.realisticmarkets.rates.CentralBank;
import java.util.OptionalLong;

/**
 * The Bond Desk's prices. It issues new bonds at par (their coupon is today's yield for the issuer and maturity) and
 * buys and sells old ones at their price at today's yield, with a {@link #HALF_SPREAD} either side. Treasury yields
 * come from the central bank's curve; a company's adds its credit spread, which widens as its earnings fall. A
 * company defaults at the report that shows its second loss in a row: its bonds outstanding then stop paying coupons
 * and are worth the {@link CreditModel#RECOVERY recovery}.
 */
public final class BondDesk {
    public static final double HALF_SPREAD = 0.0025;

    private final CentralBank central;
    private final Equities companies; // null: Treasuries only

    public BondDesk(CentralBank central, Equities companies) {
        this.central = central;
        this.companies = companies;
    }

    public CentralBank central() { return central; }

    /** Yield a day the market wants from {@code issuer} for {@code quartersLeft} quarters, at {@code day}. */
    public double yield(String issuer, double quartersLeft, double day) {
        double y = central.yield(day, quartersLeft);
        if (Bond.TREASURY.equals(issuer) || companies == null) return y;
        Company c = companies.catalog().company(issuer);
        return y + CreditModel.spread(c.creditSpread(), companies.reports(issuer), companies.normalEarnings(issuer));
    }

    /** A new bond issued today: its coupon is today's yield, so it's worth its face. */
    public Bond issue(String issuer, int quarters, long day) {
        return new Bond(issuer, day, day + (long) quarters * Bond.COUPON_DAYS, this.yield(issuer, quarters, day));
    }

    /**
     * The day the bond's issuer defaulted while it was outstanding (after issue, by maturity), if it did. Treasuries
     * never default.
     */
    public OptionalLong defaultDay(Bond b) {
        if (b.treasury() || companies == null) return OptionalLong.empty();
        for (long q : CreditModel.defaultQuarters(companies.reports(b.issuer()))) {
            long d = (q + 1) * Company.QUARTER_DAYS; // reported at the start of the next quarter
            if (d > b.issueDay() && d <= b.maturityDay()) return OptionalLong.of(d);
        }
        return OptionalLong.empty();
    }

    public boolean defaulted(Bond b, double day) {
        OptionalLong d = defaultDay(b);
        return d.isPresent() && day >= d.getAsLong();
    }

    /** Fair price of one bond (cents) at {@code day}, before the desk's spread. */
    public double price(Bond b, double day) {
        if (defaulted(b, day)) return CreditModel.recoveryCents();
        return BondMath.price(b, day, this.yield(b.issuer(), b.quartersLeft(day), day));
    }

    /** What the desk pays for one bond (cents, rounded down). */
    public long bid(Bond b, double day) {
        return (long) Math.floor(price(b, day) * (1 - HALF_SPREAD));
    }

    /** What the desk charges for one new bond (cents, rounded up). */
    public long issuePrice(Bond b) {
        return (long) Math.ceil(Bond.FACE_CENTS * (1 + HALF_SPREAD));
    }

    /**
     * Coupons (cents a bond) owed on a paper paid through coupon {@code paidThrough}, as of {@code day}: none that fell
     * due on or after a default.
     */
    public long couponsOwed(Bond b, int paidThrough, double day) {
        OptionalLong d = defaultDay(b);
        double upTo = d.isPresent() ? Math.min(day, d.getAsLong() - 0.5) : day;
        return BondMath.couponsOwed(b, paidThrough, upTo);
    }

    /** What presenting one bond pays back besides coupons: the face at maturity, the recovery after a default, else 0. */
    public long redemption(Bond b, double day) {
        if (defaulted(b, day)) return CreditModel.recoveryCents();
        return b.matured(day) ? Bond.FACE_CENTS : 0;
    }
}
