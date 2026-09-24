package com.realisticmarkets.agents;

import com.realisticmarkets.exchange.OrderRequest;
import com.realisticmarkets.exchange.Side;
import java.util.List;

/**
 * Follows the trend: if the price rose more than {@code threshold} over the last {@code lookback} auctions it buys,
 * if it fell that much it sells, paying up to 1% to get in. Momentum traders are how bubbles and overshoots start.
 */
public record MomentumTrader(String accountId, int lookback, double threshold, long qty) implements Agent {
    @Override
    public List<OrderRequest> decide(Context c) {
        List<Long> closes = c.closes();
        if (closes.size() <= lookback) return List.of();
        long then = closes.get(closes.size() - 1 - lookback);
        double trend = (c.refCents() - then) / (double) then;
        if (trend > threshold) {
            long limit = Agent.clampPrice(c.refCents() * 1.01);
            long q = Math.min(qty, c.account().cash() / limit);
            return q > 0 ? List.of(OrderRequest.ioc(accountId, c.instrument(), Side.BUY, q, limit)) : List.of();
        }
        if (trend < -threshold) {
            long q = Math.min(qty, c.stock());
            return q > 0 ? List.of(OrderRequest.ioc(accountId, c.instrument(), Side.SELL, q, Agent.clampPrice(c.refCents() * 0.99))) : List.of();
        }
        return List.of();
    }
}
