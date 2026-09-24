package com.realisticmarkets.equities;

import java.util.Map;

/**
 * One listed company, from {@code companies.csv}. Amounts are dollars and units a quarter (7 in-game days).
 *
 * @param revenue        key to units sold a quarter (a Dealer item, "fees" or "shipping")
 * @param fixedCost      dollars a quarter at the start, growing with the company's expected growth
 * @param inputs         key to units bought a quarter (costs), scaled with output
 * @param growth         expected output growth a quarter
 * @param outputVol      standard deviation of the business's own log-output shock a quarter
 * @param payout         share of positive earnings paid as dividends
 * @param shares         shares outstanding
 * @param requiredReturn what investors want a day for owning it
 * @param events         world event id to the change in that quarter's revenue per occurrence
 */
public record Company(String ticker, String name, Map<String, Long> revenue, double fixedCost, Map<String, Long> inputs,
                      double growth, double outputVol, double payout, long shares, double requiredReturn,
                      Map<String, Double> events) {
    public static final int QUARTER_DAYS = 7;

    public Company {
        revenue = Map.copyOf(revenue);
        inputs = Map.copyOf(inputs);
        events = Map.copyOf(events);
        if (shares <= 0) throw new IllegalArgumentException(ticker + ": shares must be positive");
        if (payout < 0 || payout > 1) throw new IllegalArgumentException(ticker + ": payout out of range");
        // (fields aren't assigned yet inside a compact constructor, so compute from the parameters)
        if (Math.pow(1 + requiredReturn, QUARTER_DAYS) - 1 <= growth) {
            throw new IllegalArgumentException(ticker + ": required return must beat growth");
        }
    }

    /** Required return compounded over a quarter. */
    public double quarterReturn() {
        return Math.pow(1 + requiredReturn, QUARTER_DAYS) - 1;
    }
}
