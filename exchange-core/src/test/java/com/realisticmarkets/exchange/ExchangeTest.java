package com.realisticmarkets.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExchangeTest {
    static final String DIA = "DIAMOND";
    Exchange ex;

    @BeforeEach
    void setUp() {
        ex = new Exchange();
        ex.listInstrument(DIA);
        for (String a : List.of("alice", "bob", "carol", "dave")) {
            ex.deposit(a, 10_000);
            ex.depositPosition(a, DIA, 100);
        }
    }

    @Test
    void noCrossMeansNoTrade() {
        ex.submit(OrderRequest.limit("alice", DIA, Side.BUY, 5, 90));
        ex.submit(OrderRequest.limit("bob", DIA, Side.SELL, 5, 100));
        AuctionResult r = ex.runAuction(DIA);
        assertFalse(r.traded());
        assertTrue(r.clearingPrice().isEmpty());
        assertEquals(1, ex.book(DIA).bids().size());
        assertEquals(1, ex.book(DIA).asks().size());
    }

    @Test
    void simpleCrossSettlesAtUniformPriceWithRefund() {
        ex.submit(OrderRequest.limit("alice", DIA, Side.BUY, 10, 105));
        ex.submit(OrderRequest.limit("bob", DIA, Side.SELL, 10, 100));
        AuctionResult r = ex.runAuction(DIA);

        assertTrue(r.traded());
        assertEquals(10, r.volume());
        long p = r.clearingPrice().getAsLong();
        assertTrue(p >= 100 && p <= 105, "price inside the spread: " + p);

        // Buyer paid exactly 10 * p and got the rest of the escrow back.
        assertEquals(10_000 - 10 * p, ex.account("alice").cash());
        assertEquals(0, ex.account("alice").lockedCash());
        assertEquals(110, ex.account("alice").position(DIA));
        assertEquals(10_000 + 10 * p, ex.account("bob").cash());
        assertEquals(90, ex.account("bob").position(DIA));
        assertEquals(0, ex.account("bob").lockedPosition(DIA));
        assertTrue(ex.book(DIA).bids().isEmpty());
        assertTrue(ex.book(DIA).asks().isEmpty());
    }

    @Test
    void clearingPriceMaximisesVolume() {
        // Demand: 10 @ 110, 10 @ 100. Supply: 5 @ 95, 10 @ 105.
        ex.submit(OrderRequest.limit("alice", DIA, Side.BUY, 10, 110));
        ex.submit(OrderRequest.limit("bob", DIA, Side.BUY, 10, 100));
        ex.submit(OrderRequest.limit("carol", DIA, Side.SELL, 5, 95));
        ex.submit(OrderRequest.limit("dave", DIA, Side.SELL, 10, 105));
        // At 105: demand 10, supply 15 -> vol 10. At 100: demand 20, supply 5 -> 5. At 110: 10 vs 15 -> 10.
        // Tie between 105 and 110 on volume and imbalance; no reference -> lower price.
        AuctionResult r = ex.runAuction(DIA);
        assertEquals(10, r.volume());
        assertEquals(105, r.clearingPrice().getAsLong());
    }

    @Test
    void referencePriceBreaksTies() {
        // Establish a reference of 104.
        ex.submit(OrderRequest.limit("alice", DIA, Side.BUY, 1, 104));
        ex.submit(OrderRequest.limit("bob", DIA, Side.SELL, 1, 104));
        assertEquals(104, ex.runAuction(DIA).clearingPrice().getAsLong());

        // Book where 100..110 all give the same volume and imbalance.
        ex.submit(OrderRequest.limit("alice", DIA, Side.BUY, 5, 110));
        ex.submit(OrderRequest.limit("bob", DIA, Side.SELL, 5, 100));
        AuctionResult r = ex.runAuction(DIA);
        // Candidates are 100 and 110; 100 is closer to 104.
        assertEquals(100, r.clearingPrice().getAsLong());
    }

    @Test
    void timePriorityDecidesPartialFillsAtSamePrice() {
        Order first = ex.submit(OrderRequest.limit("alice", DIA, Side.BUY, 5, 100));
        Order second = ex.submit(OrderRequest.limit("bob", DIA, Side.BUY, 5, 100));
        ex.submit(OrderRequest.limit("carol", DIA, Side.SELL, 7, 100));
        AuctionResult r = ex.runAuction(DIA);

        assertEquals(7, r.volume());
        assertEquals(0, first.remaining());
        assertEquals(3, second.remaining());
        assertEquals(List.of(second.id()), ex.book(DIA).bids().stream().map(Order::id).toList());
    }

    @Test
    void pricePriorityBeatsTimePriority() {
        Order early = ex.submit(OrderRequest.limit("alice", DIA, Side.BUY, 5, 100));
        Order better = ex.submit(OrderRequest.limit("bob", DIA, Side.BUY, 5, 101));
        ex.submit(OrderRequest.limit("carol", DIA, Side.SELL, 5, 100));
        ex.runAuction(DIA);
        assertEquals(0, better.remaining());
        assertEquals(5, early.remaining());
    }

    @Test
    void iocRemainderIsCancelledAndEscrowReleased() {
        Order ioc = ex.submit(OrderRequest.ioc("alice", DIA, Side.BUY, 10, 100));
        ex.submit(OrderRequest.limit("bob", DIA, Side.SELL, 4, 100));
        AuctionResult r = ex.runAuction(DIA);

        assertEquals(4, r.volume());
        assertEquals(List.of(ioc.id()), r.cancelledIoc());
        assertEquals(0, ex.account("alice").lockedCash());
        assertEquals(10_000 - 400, ex.account("alice").cash());
        assertTrue(ex.openOrders("alice").isEmpty());
    }

    @Test
    void cancelReleasesEscrow() {
        Order buy = ex.submit(OrderRequest.limit("alice", DIA, Side.BUY, 10, 50));
        Order sell = ex.submit(OrderRequest.limit("alice", DIA, Side.SELL, 20, 500));
        assertEquals(500, ex.account("alice").lockedCash());
        assertEquals(20, ex.account("alice").lockedPosition(DIA));

        ex.cancel(buy.id());
        ex.cancel(sell.id());
        assertEquals(10_000, ex.account("alice").cash());
        assertEquals(0, ex.account("alice").lockedCash());
        assertEquals(100, ex.account("alice").position(DIA));
        assertThrows(RejectedException.class, () -> ex.cancel(buy.id()));
    }

    @Test
    void cannotOverspendOrOversell() {
        assertThrows(RejectedException.class,
                () -> ex.submit(OrderRequest.limit("alice", DIA, Side.BUY, 1_000, 11)));
        assertThrows(RejectedException.class,
                () -> ex.submit(OrderRequest.limit("alice", DIA, Side.SELL, 101, 1)));
        // Escrow counts: a second order can't reuse locked funds.
        ex.submit(OrderRequest.limit("alice", DIA, Side.BUY, 100, 100));
        assertThrows(RejectedException.class,
                () -> ex.submit(OrderRequest.limit("alice", DIA, Side.BUY, 1, 1)));
    }

    @Test
    void unknownInstrumentRejected() {
        assertThrows(RejectedException.class,
                () -> ex.submit(OrderRequest.limit("alice", "EMERALD", Side.BUY, 1, 1)));
    }

    @Test
    void clearingPriceHelperHandlesEmptySides() {
        assertEquals(OptionalLong.empty(), ClearingPrice.compute(List.of(), List.of(), OptionalLong.empty()));
    }
}
