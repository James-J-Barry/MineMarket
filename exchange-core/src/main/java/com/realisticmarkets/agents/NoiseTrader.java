package com.realisticmarkets.agents;

import com.realisticmarkets.exchange.OrderRequest;
import com.realisticmarkets.exchange.Side;
import java.util.List;

/** Trades about half the time, a random side, at a price scattered around the reference: prices move without news. */
public record NoiseTrader(String accountId, double sigma, long maxQty) implements Agent {
    @Override
    public List<OrderRequest> decide(Context c) {
        if (c.rnd().nextDouble() < 0.5) return List.of();
        boolean buy = c.rnd().nextBoolean();
        long price = Agent.clampPrice(c.refCents() * Math.exp(sigma * c.rnd().nextGaussian()));
        long qty = 1 + c.rnd().nextInt((int) Math.max(1, maxQty));
        if (buy) {
            qty = Math.min(qty, c.account().cash() / price);
            return qty > 0 ? List.of(OrderRequest.ioc(accountId, c.instrument(), Side.BUY, qty, price)) : List.of();
        }
        qty = Math.min(qty, c.stock());
        return qty > 0 ? List.of(OrderRequest.ioc(accountId, c.instrument(), Side.SELL, qty, price)) : List.of();
    }
}
