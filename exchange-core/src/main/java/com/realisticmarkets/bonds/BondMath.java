package com.realisticmarkets.bonds;

/**
 * Bond arithmetic. A bond's price is what its remaining payments are worth at today's yield (a day): every coupon not
 * yet due, and the face at maturity, each discounted by (1 + yield)^days. Coupons already due are paid separately when
 * the paper is presented, so they aren't in the price. Higher yield, lower price; the further away the payments, the
 * more the price moves (duration).
 */
public final class BondMath {
    private BondMath() {}

    /** Price of one bond in cents at {@code yield} a day, on {@code day}. */
    public static double price(Bond b, double day, double yield) {
        double pv = 0;
        for (int n = b.couponsDueBy(day) + 1; n <= b.coupons(); n++) {
            pv += b.couponCents() * Math.pow(1 + yield, -(b.couponDay(n) - day));
        }
        if (!b.matured(day)) pv += Bond.FACE_CENTS * Math.pow(1 + yield, -(b.maturityDay() - day));
        return pv;
    }

    /** Macaulay duration in days: the payment-weighted average wait for the bond's money. */
    public static double duration(Bond b, double day, double yield) {
        double pv = 0, weighted = 0;
        for (int n = b.couponsDueBy(day) + 1; n <= b.coupons(); n++) {
            double t = b.couponDay(n) - day, v = b.couponCents() * Math.pow(1 + yield, -t);
            pv += v;
            weighted += t * v;
        }
        if (!b.matured(day)) {
            double t = b.maturityDay() - day, v = Bond.FACE_CENTS * Math.pow(1 + yield, -t);
            pv += v;
            weighted += t * v;
        }
        return pv == 0 ? 0 : weighted / pv;
    }

    /** Coupons (cents a bond) owed on a paper paid through coupon {@code paidThrough}, as of {@code day}. */
    public static long couponsOwed(Bond b, int paidThrough, double day) {
        return Math.max(0, b.couponsDueBy(day) - paidThrough) * b.couponCents();
    }
}
