package com.realisticmarkets.collateral;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.exchange.RejectedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The dawn check on a loan (design doc, "Margin calls and liquidation"): charge interest, re-value the collateral,
 * reset the floating rate, then compare coverage (C / owed) with 110%. Below it, a margin call is issued; if it's
 * still below a full day later, the bank sells collateral through the Dealer, largest haircut first (C, then B,
 * then A, cash last), until coverage is restored or the debt is paid. Sales move prices like any other sale.
 */
public final class MarginCheck {
    /** Items are sold in chunks this size, re-checking coverage after each, so only what's needed is dumped. */
    public static final int CHUNK = 16;

    public enum Status { OK, CALL_ISSUED, CALL_PENDING, CALL_CLEARED, LIQUIDATED }

    public record Sale(String item, int qty, long cents) {}

    /**
     * {@code surplusCents} is sale money beyond the debt (to the borrower's balance); {@code shortfallCents} is debt
     * left with no collateral behind it.
     */
    public record Result(Status status, long interestCents, double coverage, List<Sale> sales, long surplusCents,
                         long shortfallCents) {}

    private MarginCheck() {}

    public static Result atDawn(Loan loan, Dealer dealer, long day) {
        long interest = loan.accrueTo(day);
        if (loan.repaid()) return new Result(Status.OK, interest, Double.POSITIVE_INFINITY, List.of(), 0, 0);
        CollateralValuer.Valuation v = loan.value(dealer, day);
        loan.setRate(v.dailyRate());
        double coverage = v.coverage(loan.owedCents());
        if (coverage >= CollateralValuer.MAINTENANCE) {
            boolean had = loan.underMarginCall();
            loan.setCallDay(-1);
            return new Result(had ? Status.CALL_CLEARED : Status.OK, interest, coverage, List.of(), 0, 0);
        }
        if (!loan.underMarginCall()) {
            loan.setCallDay(day);
            return new Result(Status.CALL_ISSUED, interest, coverage, List.of(), 0, 0);
        }
        if (day < loan.callDay() + 1) return new Result(Status.CALL_PENDING, interest, coverage, List.of(), 0, 0);
        return liquidate(loan, dealer, day, interest);
    }

    private static Result liquidate(Loan loan, Dealer dealer, long day, long interest) {
        List<Sale> sales = new ArrayList<>();
        long surplus = 0;
        List<String> order = new ArrayList<>(loan.collateral().keySet());
        order.sort(Comparator.comparingDouble((String id) -> -CollateralValuer.gradeOf(dealer.catalog(), id).haircut));
        for (String item : order) {
            while (loan.collateral().getOrDefault(item, 0) > 0 && !restored(loan, dealer, day)) {
                int qty = Math.min(CHUNK, loan.collateral().get(item));
                long cents;
                try {
                    cents = dealer.sell(item, qty, day, false).cents();
                } catch (RejectedException collapsed) {
                    cents = 0; // worthless right now; the bank takes it anyway
                }
                loan.takeCollateral(item, qty);
                surplus += cents - loan.repay(cents);
                sales.add(new Sale(item, qty, cents));
            }
        }
        if (!restored(loan, dealer, day)) {
            long cash = loan.takeCash(loan.cashCollateralCents());
            surplus += cash - loan.repay(cash);
        }
        if (restored(loan, dealer, day)) loan.setCallDay(-1);
        boolean empty = loan.collateral().isEmpty() && loan.cashCollateralCents() == 0;
        long shortfall = empty ? loan.owedCents() : 0;
        double coverage = loan.value(dealer, day).coverage(loan.owedCents());
        return new Result(Status.LIQUIDATED, interest, coverage, sales, surplus, shortfall);
    }

    private static boolean restored(Loan loan, Dealer dealer, long day) {
        return loan.repaid() || loan.value(dealer, day).coverage(loan.owedCents()) >= CollateralValuer.MAINTENANCE;
    }
}
