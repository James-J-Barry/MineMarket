package com.realisticmarkets.progression;

/**
 * What a quest asks for. Parsed from the {@code goal} column of {@code quests.csv}:
 * <pre>
 * any_sale
 * buy_then_sell
 * sell_qty_in_day:256
 * recover_then_sell:0.70:0.90
 * groups_in_day:2000:3        (cents per group, number of groups)
 * net_worth:25000
 * hold_cash:50000
 * ship_beats_local            (a Trade Route Crate payout, after freight, beats the local quote)
 * interest_earned:1000        (lifetime bank interest, cents)
 * cd_matured                  (a CD redeemed at or after maturity)
 * loan_repaid                 (a loan fully repaid by the borrower)
 * limit_filled                (a Trading Floor limit order at least partly filled)
 * beat_dealer                 (a Floor sale averaging more than the Dealer's bid)
 * two_books:<big>:<small>:<n> (in one day, buy one of the pair and sell the other at a profit per base unit)
 * dividend_collected          (a dividend paid on presenting certificates)
 * shares_held:<n>             (hold at least n shares of one company at once)
 * beat_market                 (sell shares at the Stock Exchange for more than you paid there)
 * companies_held:<n>          (hold shares of at least n companies at once)
 * coupon_collected            (a bond coupon paid on presentation)
 * held_to_maturity            (a bond redeemed at face at maturity)
 * rate_watcher                (a bond sold for more than you paid, after the rate was cut)
 * chart_read               (a book's chart read at the Ticker Tape)
 * balance_sheet:<cents>    (a Records Terminal shows at least this net worth)
 * forward_beat_spot        (a forward delivered for more than the goods would fetch at the Dealer then)
 * variation_received       (the Clearing House's dawn mark paid you)
 * margin_call_met          (a margin call met before the next dawn)
 * futures_profit           (futures closed at a profit)
 * put_paid                 (a put paid out at expiry)
 * option_multiple:<n>      (options closed for at least n times what they cost)
 * covered_call_written     (a call written against the goods themselves)
 * written_expired_worthless (a written option expired paying nothing)
 * </pre>
 */
public sealed interface QuestGoal {
    record AnySale() implements QuestGoal {}
    record BuyThenSell() implements QuestGoal {}
    record SellQtyInDay(int minQty) implements QuestGoal {}
    record RecoverThenSell(double pushedBelow, double recoveredTo) implements QuestGoal {}
    record GroupsInDay(long minCentsPerGroup, int minGroups) implements QuestGoal {}
    record NetWorthAtLeast(long cents) implements QuestGoal {}
    record HoldCashAtLeast(long cents) implements QuestGoal {}
    record ShipBeatsLocal() implements QuestGoal {}
    record InterestEarned(long cents) implements QuestGoal {}
    record CdMatured() implements QuestGoal {}
    record LoanRepaid() implements QuestGoal {}
    record LimitFilled() implements QuestGoal {}
    record BeatDealer() implements QuestGoal {}
    record TwoBooks(String big, String small, int ratio) implements QuestGoal {}
    record ChartRead() implements QuestGoal {}
    record DividendCollected() implements QuestGoal {}
    record SharesHeld(long shares) implements QuestGoal {}
    record BeatMarket() implements QuestGoal {}
    record CompaniesHeld(int companies) implements QuestGoal {}
    record CouponCollected() implements QuestGoal {}
    record HeldToMaturity() implements QuestGoal {}
    record RateWatcher() implements QuestGoal {}
    record BalanceSheet(long cents) implements QuestGoal {}
    record ForwardBeatSpot() implements QuestGoal {}
    record VariationReceived() implements QuestGoal {}
    record MarginCallMet() implements QuestGoal {}
    record FuturesProfit() implements QuestGoal {}
    record PutPaid() implements QuestGoal {}
    record OptionMultiple(int times) implements QuestGoal {}
    record CoveredCallWritten() implements QuestGoal {}
    record WrittenExpiredWorthless() implements QuestGoal {}

    static QuestGoal parse(String s) {
        String[] p = s.strip().split(":");
        return switch (p[0]) {
            case "any_sale" -> new AnySale();
            case "buy_then_sell" -> new BuyThenSell();
            case "sell_qty_in_day" -> new SellQtyInDay(Integer.parseInt(p[1]));
            case "recover_then_sell" -> new RecoverThenSell(Double.parseDouble(p[1]), Double.parseDouble(p[2]));
            case "groups_in_day" -> new GroupsInDay(Long.parseLong(p[1]), Integer.parseInt(p[2]));
            case "net_worth" -> new NetWorthAtLeast(Long.parseLong(p[1]));
            case "hold_cash" -> new HoldCashAtLeast(Long.parseLong(p[1]));
            case "ship_beats_local" -> new ShipBeatsLocal();
            case "interest_earned" -> new InterestEarned(Long.parseLong(p[1]));
            case "cd_matured" -> new CdMatured();
            case "loan_repaid" -> new LoanRepaid();
            case "chart_read" -> new ChartRead();
            case "dividend_collected" -> new DividendCollected();
            case "shares_held" -> new SharesHeld(Long.parseLong(p[1]));
            case "beat_market" -> new BeatMarket();
            case "companies_held" -> new CompaniesHeld(Integer.parseInt(p[1]));
            case "coupon_collected" -> new CouponCollected();
            case "held_to_maturity" -> new HeldToMaturity();
            case "rate_watcher" -> new RateWatcher();
            case "balance_sheet" -> new BalanceSheet(Long.parseLong(p[1]));
            case "forward_beat_spot" -> new ForwardBeatSpot();
            case "variation_received" -> new VariationReceived();
            case "margin_call_met" -> new MarginCallMet();
            case "futures_profit" -> new FuturesProfit();
            case "put_paid" -> new PutPaid();
            case "option_multiple" -> new OptionMultiple(Integer.parseInt(p[1]));
            case "covered_call_written" -> new CoveredCallWritten();
            case "written_expired_worthless" -> new WrittenExpiredWorthless();
            case "limit_filled" -> new LimitFilled();
            case "beat_dealer" -> new BeatDealer();
            case "two_books" -> new TwoBooks(p[1] + ":" + p[2], p[3] + ":" + p[4], Integer.parseInt(p[5]));
            default -> throw new IllegalArgumentException("unknown quest goal '" + s + "'");
        };
    }
}
