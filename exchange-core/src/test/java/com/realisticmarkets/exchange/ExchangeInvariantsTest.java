package com.realisticmarkets.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Randomised invariants. If any of these ever fails, the market is leaking or minting money.
 */
class ExchangeInvariantsTest {

    @Test
    void conservationAndNoCrossedBookAcrossManyRandomAuctions() {
        for (long seed = 1; seed <= 50; seed++) {
            runScenario(seed);
        }
    }

    private void runScenario(long seed) {
        Random rnd = new Random(seed);
        Exchange ex = new Exchange();
        List<String> instruments = List.of("DIAMOND", "IRON", "WHEAT");
        instruments.forEach(ex::listInstrument);
        List<String> accounts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String a = "acct" + i;
            accounts.add(a);
            ex.deposit(a, 50_000);
            for (String ins : instruments) ex.depositPosition(a, ins, 200);
        }
        long totalCash = totalCash(ex);

        for (int step = 0; step < 200; step++) {
            for (int k = 0; k < 15; k++) {
                String a = accounts.get(rnd.nextInt(accounts.size()));
                String ins = instruments.get(rnd.nextInt(instruments.size()));
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                long qty = 1 + rnd.nextInt(20);
                long price = 80 + rnd.nextInt(41);
                TimeInForce tif = rnd.nextInt(3) == 0 ? TimeInForce.IOC : TimeInForce.GTC;
                try {
                    ex.submit(new OrderRequest(a, ins, side, qty, price, tif));
                } catch (RejectedException ignored) {
                    // out of funds/inventory is a normal outcome
                }
            }
            if (rnd.nextInt(4) == 0) {
                List<Order> open = ex.openOrders(accounts.get(rnd.nextInt(accounts.size())));
                if (!open.isEmpty()) ex.cancel(open.get(rnd.nextInt(open.size())).id());
            }
            for (AuctionResult r : ex.runAllAuctions()) {
                long filled = r.fills().stream().mapToLong(Fill::quantity).sum();
                assertEquals(r.volume(), filled, "seed " + seed);
                r.fills().forEach(f -> assertEquals(r.clearingPrice().getAsLong(), f.price()));
                assertFalse(ex.book(r.instrument()).isCrossed(), "book crossed after auction, seed " + seed);
            }
            assertEquals(totalCash, totalCash(ex), "cash not conserved, seed " + seed);
            for (String ins : instruments) {
                assertEquals(12 * 200, totalUnits(ex, ins), ins + " not conserved, seed " + seed);
            }
            for (Account acct : ex.accounts()) {
                assertTrue(acct.cash() >= 0 && acct.lockedCash() >= 0);
                long expectedLock = ex.openOrders(acct.id()).stream()
                        .filter(o -> o.side() == Side.BUY)
                        .mapToLong(o -> o.remaining() * o.limitPrice()).sum();
                assertEquals(expectedLock, acct.lockedCash(), "escrow mismatch, seed " + seed);
            }
        }
    }

    private static long totalCash(Exchange ex) {
        return ex.accounts().stream().mapToLong(a -> a.cash() + a.lockedCash()).sum();
    }

    private static long totalUnits(Exchange ex, String ins) {
        return ex.accounts().stream().mapToLong(a -> a.position(ins) + a.lockedPosition(ins)).sum();
    }
}
