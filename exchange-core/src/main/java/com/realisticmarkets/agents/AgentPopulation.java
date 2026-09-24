package com.realisticmarkets.agents;

import com.realisticmarkets.exchange.Account;
import com.realisticmarkets.exchange.AuctionResult;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.OrderRequest;
import com.realisticmarkets.exchange.RejectedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The NPC traders on one Floor book. Each has a baseline of cash and stock (sized from the book's depth at fair
 * value); between auctions their holdings drift back toward that baseline with recovery time
 * {@link #RECOVERY_DAYS}, like the Dealer's. So a big seller drains the buyers and walks the price down, and the
 * book refills over in-game days: the NPCs can't be an unlimited source or sink of money.
 */
public final class AgentPopulation {
    public static final double RECOVERY_DAYS = 2.0;
    public static final int CLOSES_KEPT = 20;

    private final Exchange ex;
    private final FloorCatalog.Book book;
    private final List<Agent> agents = new ArrayList<>();
    private final List<Long> stockBaseline = new ArrayList<>();
    private final List<Double> cashShare = new ArrayList<>(); // share of depth each agent holds, for its cash baseline
    private final List<Long> closes = new ArrayList<>();
    private final Random rnd;

    public AgentPopulation(Exchange ex, FloorCatalog.Book book, long fairCents, long seed) {
        this.ex = ex;
        this.book = book;
        this.rnd = new Random(seed);
        ex.listInstrument(book.item());
        long d = book.depth();
        add(new MarketMaker(id("mm", 0), book.halfSpread(), Math.max(1, d / 16), d / 2), d / 2, 0.5, fairCents);
        for (int i = 0; i < book.noise(); i++) {
            add(new NoiseTrader(id("noise", i), 0.02, Math.max(1, d / 64)), d / 16, 1.0 / 16, fairCents);
        }
        for (int i = 0; i < book.fundamentalists(); i++) {
            add(new Fundamentalist(id("fund", i), 0.02, Math.max(1, d / 64)), d / 8, 1.0 / 8, fairCents);
        }
        for (int i = 0; i < book.momentum(); i++) {
            add(new MomentumTrader(id("mom", i), 3, 0.01, Math.max(1, d / 64)), d / 16, 1.0 / 16, fairCents);
        }
    }

    private String id(String role, int i) {
        return "npc/" + book.item() + "/" + role + i;
    }

    private void add(Agent a, long stock, double share, long fairCents) {
        agents.add(a);
        stockBaseline.add(Math.max(1, stock));
        cashShare.add(share);
        // Existing accounts (after a reload) already hold their stock and cash.
        Account acct = ex.account(a.accountId());
        if (acct.cash() + acct.lockedCash() == 0 && acct.position(book.item()) + acct.lockedPosition(book.item()) == 0) {
            ex.deposit(a.accountId(), cashBaseline(share, fairCents));
            ex.depositPosition(a.accountId(), book.item(), Math.max(1, stock));
        }
    }

    private long cashBaseline(double share, long fairCents) {
        return Math.max(1, Math.round(book.depth() * share * fairCents));
    }

    public List<Agent> agents() { return List.copyOf(agents); }
    public List<Long> closes() { return List.copyOf(closes); }
    public FloorCatalog.Book book() { return book; }

    /** How far the reference may sit from fair value: the NPCs hear the news too, so the book can't lag far behind. */
    public static final double REFERENCE_BAND = 0.08;

    /**
     * Reference price the agents quote around: the last clearing price, kept within {@link #REFERENCE_BAND} of fair
     * value (fair value itself before any trade).
     */
    public long reference(long fairCents) {
        long last = ex.lastPrice(book.item()).orElse(fairCents);
        long lo = (long) Math.floor(fairCents * (1 - REFERENCE_BAND)), hi = (long) Math.ceil(fairCents * (1 + REFERENCE_BAND));
        return Math.max(lo, Math.min(hi, last));
    }

    /** Every agent submits its orders for the coming auction. Returns how many were accepted. */
    public int submitOrders(long fairCents) {
        long ref = reference(fairCents);
        int n = 0;
        for (Agent a : agents) {
            Agent.Context c = new Agent.Context(book.item(), fairCents, ref, closes(), ex.account(a.accountId()), rnd);
            for (OrderRequest r : a.decide(c)) {
                try {
                    ex.submit(r);
                    n++;
                } catch (RejectedException tooPoor) {
                    // an earlier order used the money or stock
                }
            }
        }
        return n;
    }

    public void onAuction(AuctionResult r) {
        if (!r.traded()) return;
        closes.add(r.clearingPrice().getAsLong());
        while (closes.size() > CLOSES_KEPT) closes.removeFirst();
    }

    /** The market maker's current quotes, for the Floor screen: {bid, ask}. */
    public long[] makerQuotes(long fairCents) {
        MarketMaker mm = (MarketMaker) agents.getFirst();
        return mm.quotes(reference(fairCents), ex.account(mm.accountId()).position(book.item()));
    }

    /** Moves each agent's unlocked cash and stock {@code days}' worth of the way back to its baseline. */
    public void recover(double days, long fairCents) {
        if (days <= 0) return;
        double f = 1 - Math.exp(-days / RECOVERY_DAYS);
        for (int i = 0; i < agents.size(); i++) {
            String id = agents.get(i).accountId();
            Account a = ex.account(id);
            long cashDelta = Math.round((cashBaseline(cashShare.get(i), fairCents) - (a.cash() + a.lockedCash())) * f);
            if (cashDelta > 0) ex.deposit(id, cashDelta);
            else if (cashDelta < 0 && a.cash() > 0) ex.withdrawCash(id, Math.min(-cashDelta, a.cash()));
            long have = a.position(book.item()) + a.lockedPosition(book.item());
            long stockDelta = Math.round((stockBaseline.get(i) - have) * f);
            if (stockDelta > 0) ex.depositPosition(id, book.item(), stockDelta);
            else if (stockDelta < 0 && a.position(book.item()) > 0) {
                ex.withdrawPosition(id, book.item(), Math.min(-stockDelta, a.position(book.item())));
            }
        }
    }
}
