package com.realisticmarkets.agents;

import com.realisticmarkets.exchange.Account;
import com.realisticmarkets.exchange.OrderRequest;
import java.util.List;
import java.util.Random;

/** An NPC trader: looks at the market before an auction and returns the orders it wants in it. */
public interface Agent {
    String accountId();

    List<OrderRequest> decide(Context c);

    /**
     * What an agent sees. {@code refCents} is the last clearing price (or fair value before the first trade);
     * {@code closes} are recent clearing prices, oldest first.
     */
    record Context(String instrument, long fairCents, long refCents, List<Long> closes, Account account, Random rnd) {
        public long stock() {
            return account.position(instrument);
        }
    }

    static long clampPrice(double p) {
        return Math.max(1, Math.round(p));
    }
}
