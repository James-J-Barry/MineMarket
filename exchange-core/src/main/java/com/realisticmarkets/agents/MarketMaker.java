package com.realisticmarkets.agents;

import com.realisticmarkets.exchange.OrderRequest;
import com.realisticmarkets.exchange.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * Quotes both sides around a reservation price that leans against its inventory (long: lower both quotes to
 * sell; short: raise them to buy), and widens its spread as inventory strays from target. That's how liquidity
 * gets priced: the more one-sided the flow, the worse the quotes (after Avellaneda and Stoikov).
 */
public record MarketMaker(String accountId, double halfSpread, long quoteQty, long targetStock) implements Agent {

    /** Inventory deviation from target, -1 (empty) to +1 (double). */
    double deviation(long stock) {
        return Math.max(-1, Math.min(1, (stock - targetStock) / (double) Math.max(1, targetStock)));
    }

    public long[] quotes(long refCents, long stock) {
        double dev = deviation(stock);
        double reservation = refCents * (1 - 2 * halfSpread * dev);
        double h = halfSpread * (1 + Math.abs(dev));
        long bid = Agent.clampPrice(Math.floor(reservation * (1 - h)));
        long ask = Math.max(bid + 1, (long) Math.ceil(reservation * (1 + h)));
        return new long[] {bid, ask};
    }

    @Override
    public List<OrderRequest> decide(Context c) {
        long[] q = quotes(c.refCents(), c.stock());
        List<OrderRequest> out = new ArrayList<>();
        long buyQty = Math.min(quoteQty, c.account().cash() / q[0]);
        if (buyQty > 0) out.add(OrderRequest.ioc(accountId, c.instrument(), Side.BUY, buyQty, q[0]));
        long sellQty = Math.min(quoteQty, c.stock());
        if (sellQty > 0) out.add(OrderRequest.ioc(accountId, c.instrument(), Side.SELL, sellQty, q[1]));
        return out;
    }
}
