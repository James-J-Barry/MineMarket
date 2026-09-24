package com.realisticmarkets.agents;

import com.realisticmarkets.exchange.OrderRequest;
import com.realisticmarkets.exchange.Side;
import java.util.List;

/**
 * Buys when the price is more than {@code threshold} below fair value and sells when it's that far above, more the
 * wider the gap. This pulls prices back to fair value: mean reversion, value investing.
 */
public record Fundamentalist(String accountId, double threshold, long baseQty) implements Agent {
    @Override
    public List<OrderRequest> decide(Context c) {
        double gap = (c.fairCents() - c.refCents()) / (double) c.fairCents();
        if (Math.abs(gap) <= threshold) return List.of();
        long qty = Math.max(1, Math.round(baseQty * Math.abs(gap) / threshold));
        if (gap > 0) {
            long limit = Agent.clampPrice(c.fairCents() * (1 - threshold / 2));
            qty = Math.min(qty, c.account().cash() / limit);
            return qty > 0 ? List.of(OrderRequest.ioc(accountId, c.instrument(), Side.BUY, qty, limit)) : List.of();
        }
        long limit = Agent.clampPrice(c.fairCents() * (1 + threshold / 2));
        qty = Math.min(qty, c.stock());
        return qty > 0 ? List.of(OrderRequest.ioc(accountId, c.instrument(), Side.SELL, qty, limit)) : List.of();
    }
}
