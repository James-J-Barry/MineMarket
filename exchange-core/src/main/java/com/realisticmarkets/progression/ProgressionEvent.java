package com.realisticmarkets.progression;

/** Something a player did that quests react to. Emitted by the mod layer; replayable in tests. */
public sealed interface ProgressionEvent {
    /** The in-game day it happened. */
    long day();

    /**
     * A sale to the Dealer. Ratios are the Dealer's market (mid) price ÷ fair value, the "Market" and "Normal"
     * rows on the Basic Exchange: {@code ratioBefore} when the sale started (after any recovery),
     * {@code ratioAfter} once this sale's impact is applied. Mid rather than bid, because an untouched bid
     * already sits half a spread below fair value.
     */
    record Sale(String item, String group, int qty, long proceedsCents, double ratioBefore, double ratioAfter, long day)
            implements ProgressionEvent {}

    record Purchase(String item, String group, int qty, long costCents, long day) implements ProgressionEvent {}

    /** Snapshot of the player's cash (bills held) and net worth (cash + goods at Dealer bid). */
    record NetWorth(long cashCents, long netWorthCents, long day) implements ProgressionEvent {}

    record DayRollover(long day) implements ProgressionEvent {}

    record Craft(String result, int times, long day) implements ProgressionEvent {}

    /**
     * A Trade Route Crate shipment settled at the Capital. {@code localQuoteCents} is what the local Dealer would
     * have paid for the same goods at shipping time; {@code payoutCents} is after freight.
     */
    record Shipment(long localQuoteCents, long payoutCents, long day) implements ProgressionEvent {}

    /** Bank interest credited: {@code lifetimeCents} is the account's total interest so far. */
    record Interest(long creditedCents, long lifetimeCents, long day) implements ProgressionEvent {}

    /** A Certificate of Deposit redeemed; {@code matured} is false for early redemption (principal only). */
    record CdRedeemed(long principalCents, long payoutCents, boolean matured, long day) implements ProgressionEvent {}

    /**
     * A Trading Floor order finished (filled, cancelled or expired). {@code filledCents} is the total paid or
     * received for {@code filledQty}; {@code dealerBidMills} is what the Dealer would have paid per item at the time.
     */
    record FloorOrderDone(String item, boolean buy, boolean market, long filledQty, long filledCents, long dealerBidMills,
                          long day) implements ProgressionEvent {
        public double avgMills() {
            return filledQty == 0 ? 0 : filledCents * 10.0 / filledQty;
        }
    }

    /** A loan paid off by the borrower (not by liquidation). */
    record LoanRepaid(long principalCents, long interestPaidCents, long day) implements ProgressionEvent {}

    /** A book's chart read at the Ticker Tape. */
    record ChartRead(String item, long day) implements ProgressionEvent {}

    /** Dividends paid out on certificates presented at the Stock Exchange. */
    record DividendCollected(String ticker, long cents, long day) implements ProgressionEvent {}

    /** How many shares of a company the player holds right now (certificates in their inventory). */
    record SharesHeld(String ticker, long shares, long day) implements ProgressionEvent {}

    /** Shares sold at the Stock Exchange; {@code costCents} is what the account paid for them there, -1 if unknown. */
    record StockSold(String ticker, long shares, long proceedsCents, long costCents, long day) implements ProgressionEvent {}

    /** How many companies the player holds shares of right now. */
    record CompaniesHeld(int companies, long day) implements ProgressionEvent {}

    /** Bond coupons paid on presentation at the Bond Desk. */
    record CouponCollected(long cents, long day) implements ProgressionEvent {}

    /** Bonds paid back at the desk: at face on maturity, or the recovery after a default. */
    record BondRedeemed(long bonds, long cents, boolean atFace, long day) implements ProgressionEvent {}

    /** Bonds sold back to the desk. {@code costCents} is what the account paid, -1 if unknown. */
    record BondSold(long bonds, long proceedsCents, long costCents, boolean rateCutSince, long day) implements ProgressionEvent {}

    /** Net worth as shown on a Records Terminal (linked blocks only). */
    record RecordsViewed(long netWorthCents, long day) implements ProgressionEvent {}

    /**
     * A forward delivered at the Basic Exchange: {@code priceCents} is what the Dealer paid as agreed, {@code spotCents}
     * what the same goods would have fetched that moment at the player's bid.
     */
    record ForwardDelivered(String item, long quantity, long priceCents, long spotCents, long day) implements ProgressionEvent {}

    /** A forward not delivered in time: its deposit is forfeit. */
    record ForwardDefaulted(String item, long depositCents, long day) implements ProgressionEvent {}

    /** The Clearing House's dawn mark paid ({@code cents} > 0) or took variation margin, expiries included. */
    record FuturesMarked(long cents, long day) implements ProgressionEvent {}

    /** Futures closed by the player: {@code realizedCents} is the gain or loss on the lots closed. */
    record FuturesClosed(String code, long lots, long realizedCents, long day) implements ProgressionEvent {}

    /** A margin call met: the account is back to its initial margin before the next dawn. */
    record MarginCallMet(long day) implements ProgressionEvent {}

    /**
     * Option papers closed: presented after expiry ({@code expired}) or sold back to the desk. {@code costCents} is
     * what the account paid for them at the desk, -1 if unknown.
     */
    record OptionClosed(String underlying, boolean call, long contracts, long proceedsCents, long costCents, boolean expired,
                        long day) implements ProgressionEvent {}

    /** An option written to the Options Desk; {@code covered} when the goods themselves back all of it. */
    record OptionWritten(String underlying, boolean call, long contracts, long premiumCents, boolean covered, long day)
            implements ProgressionEvent {}

    /**
     * A written option finished: at expiry ({@code worthless} if it paid nothing) or bought back after an unmet call.
     * {@code strikeReceivedCents} is what the desk paid for goods called away.
     */
    record OptionWrittenSettled(long premiumCents, long paidOutCents, long strikeReceivedCents, boolean worthless, long day)
            implements ProgressionEvent {}

    /** The account opened away from the vault: at an ATM or with a Pocket ATM. */
    record AtmUsed(long day) implements ProgressionEvent {}

    /** Papers put into book entry at a Brokerage Terminal. */
    record BookEntryDeposited(long papers, long day) implements ProgressionEvent {}

    /** Income credited automatically to a brokerage cash account at dawn (dividends, coupons, maturities, settlements). */
    record BrokerageIncome(long dividendCents, long couponCents, long otherCents, long day) implements ProgressionEvent {}
}
