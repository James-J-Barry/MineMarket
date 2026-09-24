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
            default -> throw new IllegalArgumentException("unknown quest goal '" + s + "'");
        };
    }
}
