package com.realisticmarkets.bonds;

import java.util.Locale;

/**
 * One bond series: every paper with these terms is identical, so they stack. Face value {@value #FACE_CENTS} cents;
 * a coupon every {@link #COUPON_DAYS} days after issue, at {@code couponRate} a day compounded over the period; the
 * face paid back at maturity.
 *
 * @param issuer "TREASURY" or a company ticker
 */
public record Bond(String issuer, long issueDay, long maturityDay, double couponRate) {
    public static final String TREASURY = "TREASURY";
    public static final long FACE_CENTS = 10_000;
    public static final int COUPON_DAYS = 7;
    public static final int[] MATURITY_QUARTERS = {2, 4, 8};

    public Bond {
        if (maturityDay <= issueDay || (maturityDay - issueDay) % COUPON_DAYS != 0) {
            throw new IllegalArgumentException("maturity must be a whole number of quarters after issue");
        }
        if (couponRate < 0) throw new IllegalArgumentException("negative coupon");
    }

    public boolean treasury() {
        return TREASURY.equals(issuer);
    }

    /** Coupon a bond, in cents (rounded down: the issuer's favor, like every payout). */
    public long couponCents() {
        return (long) Math.floor(FACE_CENTS * (Math.pow(1 + couponRate, COUPON_DAYS) - 1));
    }

    public int coupons() {
        return (int) ((maturityDay - issueDay) / COUPON_DAYS);
    }

    /** The day coupon {@code n} (1-based) is due. */
    public long couponDay(int n) {
        return issueDay + (long) n * COUPON_DAYS;
    }

    /** How many coupons have fallen due by {@code day}. */
    public int couponsDueBy(double day) {
        return (int) Math.max(0, Math.min(coupons(), Math.floor((day - issueDay) / COUPON_DAYS)));
    }

    public boolean matured(double day) {
        return day >= maturityDay;
    }

    public double quartersLeft(double day) {
        return Math.max(0, (maturityDay - day) / COUPON_DAYS);
    }

    /** The stacking key: same key, same paper. */
    public String series() {
        return String.format(Locale.ROOT, "%s|%d|%d|%.7f", issuer, issueDay, maturityDay, couponRate);
    }
}
