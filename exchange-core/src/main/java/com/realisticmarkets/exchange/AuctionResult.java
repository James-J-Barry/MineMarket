package com.realisticmarkets.exchange;

import java.util.List;
import java.util.OptionalLong;

/**
 * Outcome of one batch auction for one instrument.
 *
 * @param clearingPrice empty when the book did not cross (no trades)
 * @param cancelledIoc  ids of IOC orders whose unfilled remainder was cancelled
 */
public record AuctionResult(
        String instrument,
        long seq,
        OptionalLong clearingPrice,
        long volume,
        List<Fill> fills,
        List<Long> cancelledIoc) {

    public boolean traded() {
        return volume > 0;
    }
}
