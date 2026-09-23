package com.realisticmarkets.exchange;

/**
 * What a participant submits. Prices are integer ticks (1 tick = smallest currency unit),
 * never doubles, so settlement is exact.
 */
public record OrderRequest(
        String accountId,
        String instrument,
        Side side,
        long quantity,
        long limitPrice,
        TimeInForce timeInForce) {

    public OrderRequest {
        if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("accountId required");
        if (instrument == null || instrument.isBlank()) throw new IllegalArgumentException("instrument required");
        if (side == null) throw new IllegalArgumentException("side required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (limitPrice <= 0) throw new IllegalArgumentException("limitPrice must be positive");
        if (timeInForce == null) timeInForce = TimeInForce.GTC;
    }

    public static OrderRequest limit(String account, String instrument, Side side, long qty, long price) {
        return new OrderRequest(account, instrument, side, qty, price, TimeInForce.GTC);
    }

    public static OrderRequest ioc(String account, String instrument, Side side, long qty, long price) {
        return new OrderRequest(account, instrument, side, qty, price, TimeInForce.IOC);
    }
}
