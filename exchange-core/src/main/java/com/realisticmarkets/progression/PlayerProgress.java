package com.realisticmarkets.progression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One account's Almanac progress: purchased nodes, completed quests, what they granted, and the small amount
 * of history quests need. Cash is not held here; callers pass the wallet balance in and pay/charge the
 * amounts returned.
 */
public final class PlayerProgress {
    final Set<String> nodes = new LinkedHashSet<>();
    final Set<String> quests = new LinkedHashSet<>();
    final Set<String> grants = new LinkedHashSet<>();

    // Quest history. Day-scoped maps hold only the current in-game day.
    final Set<String> boughtItems = new LinkedHashSet<>();
    final Map<String, Double> lowestRatio = new LinkedHashMap<>();
    long trackedDay = Long.MIN_VALUE;
    final Map<String, Integer> dayQty = new LinkedHashMap<>();
    final Map<String, Long> dayGroupCents = new LinkedHashMap<>();
    /** Today's Floor trades: "B|item" or "S|item" to {qty, cents}. */
    final Map<String, long[]> dayFloor = new LinkedHashMap<>();

    public Optional<String> whyCannotBuy(UnlockNode node, UnlockTree tree, long cashCents) {
        if (nodes.contains(node.id())) return Optional.of("Already unlocked");
        for (String p : node.parents()) {
            if (!nodes.contains(p)) return Optional.of("Requires " + tree.node(p).title());
        }
        if (node.requiredQuest() != null && !quests.contains(node.requiredQuest())) {
            return Optional.of("Complete the quest first");
        }
        if (node.tier() > 1) {
            boolean firstInTier = tree.tier(node.tier()).stream().noneMatch(n -> nodes.contains(n.id()));
            List<UnlockNode> prev = tree.tier(node.tier() - 1);
            long owned = prev.stream().filter(n -> nodes.contains(n.id())).count();
            if (firstInTier && owned * 2 < prev.size()) {
                return Optional.of("Unlock half of Tier " + (node.tier() - 1) + " first");
            }
        }
        if (cashCents < costOf(node)) return Optional.of("Not enough cash");
        return Optional.empty();
    }

    public boolean canBuy(UnlockNode node, UnlockTree tree, long cashCents) {
        return whyCannotBuy(node, tree, cashCents).isEmpty();
    }

    /** Unlocks the node and returns the cost the caller must take from the wallet. */
    public long buy(UnlockNode node, UnlockTree tree, long cashCents) {
        Optional<String> why = whyCannotBuy(node, tree, cashCents);
        if (why.isPresent()) throw new IllegalStateException(node.id() + ": " + why.get());
        nodes.add(node.id());
        grants.addAll(node.grants());
        return costOf(node);
    }

    /**
     * The node's price for this player: a perk named {@code <node id>_discount_<percent>} (Save It grants
     * {@code bank_vault_discount_10}) takes that percentage off, rounded up to the dime like every purchase.
     */
    public long costOf(UnlockNode node) {
        int best = 0;
        String prefix = "perk:" + node.id() + "_discount_";
        for (String g : grants) {
            if (!g.startsWith(prefix)) continue;
            try {
                best = Math.max(best, Integer.parseInt(g.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // not a discount perk
            }
        }
        if (best == 0) return node.costCents();
        return com.realisticmarkets.money.Money.roundUpToDime(node.costCents() * (100 - Math.min(best, 100)) / 100.0);
    }

    /** Feeds one event to every open quest; returns the quests it completed (caller pays their cash rewards). */
    public List<Quest> apply(ProgressionEvent event, Quests all) {
        long day = switch (event) {
            case ProgressionEvent.Sale e -> e.day();
            case ProgressionEvent.Purchase e -> e.day();
            case ProgressionEvent.NetWorth e -> e.day();
            case ProgressionEvent.DayRollover e -> e.day();
            case ProgressionEvent.Craft e -> e.day();
            case ProgressionEvent.Shipment e -> e.day();
            case ProgressionEvent.Interest e -> e.day();
            case ProgressionEvent.CdRedeemed e -> e.day();
            case ProgressionEvent.LoanRepaid e -> e.day();
            case ProgressionEvent.FloorOrderDone e -> e.day();
        };
        if (day != trackedDay) {
            trackedDay = day;
            dayQty.clear();
            dayGroupCents.clear();
            dayFloor.clear();
        }
        Double priorLowest = null;
        switch (event) {
            case ProgressionEvent.Sale s -> {
                priorLowest = lowestRatio.get(s.item());
                lowestRatio.merge(s.item(), s.ratioAfter(), Math::min);
                dayQty.merge(s.item(), s.qty(), Integer::sum);
                dayGroupCents.merge(s.group(), s.proceedsCents(), Long::sum);
            }
            case ProgressionEvent.Purchase p -> boughtItems.add(p.item());
            case ProgressionEvent.FloorOrderDone f when f.filledQty() > 0 ->
                    dayFloor.merge((f.buy() ? "B|" : "S|") + f.item(), new long[] {f.filledQty(), f.filledCents()},
                            (a, b) -> new long[] {a[0] + b[0], a[1] + b[1]});
            default -> {}
        }

        List<Quest> done = new ArrayList<>();
        for (Quest q : all.all()) {
            if (quests.contains(q.id()) || !met(q.goal(), event, priorLowest)) continue;
            quests.add(q.id());
            grants.addAll(q.grants());
            done.add(q);
        }
        return done;
    }

    private boolean met(QuestGoal goal, ProgressionEvent event, Double priorLowest) {
        if (event instanceof ProgressionEvent.Sale s) {
            return switch (goal) {
                case QuestGoal.AnySale() -> true;
                case QuestGoal.BuyThenSell() -> boughtItems.contains(s.item());
                case QuestGoal.SellQtyInDay g -> dayQty.getOrDefault(s.item(), 0) >= g.minQty();
                case QuestGoal.RecoverThenSell g ->
                        priorLowest != null && priorLowest < g.pushedBelow() && s.ratioBefore() >= g.recoveredTo();
                case QuestGoal.GroupsInDay g ->
                        dayGroupCents.values().stream().filter(c -> c >= g.minCentsPerGroup()).count() >= g.minGroups();
                default -> false;
            };
        }
        if (event instanceof ProgressionEvent.Interest i) {
            return goal instanceof QuestGoal.InterestEarned g && i.lifetimeCents() >= g.cents();
        }
        if (event instanceof ProgressionEvent.FloorOrderDone f) {
            return switch (goal) {
                case QuestGoal.LimitFilled() -> !f.market() && f.filledQty() > 0;
                case QuestGoal.BeatDealer() -> !f.buy() && f.filledQty() > 0 && f.avgMills() > f.dealerBidMills();
                case QuestGoal.TwoBooks g -> twoBooks(g);
                default -> false;
            };
        }
        if (event instanceof ProgressionEvent.LoanRepaid) {
            return goal instanceof QuestGoal.LoanRepaid;
        }
        if (event instanceof ProgressionEvent.CdRedeemed r) {
            return goal instanceof QuestGoal.CdMatured && r.matured();
        }
        if (event instanceof ProgressionEvent.Shipment s) {
            return goal instanceof QuestGoal.ShipBeatsLocal && s.payoutCents() > s.localQuoteCents();
        }
        if (event instanceof ProgressionEvent.NetWorth w) {
            return switch (goal) {
                case QuestGoal.NetWorthAtLeast g -> w.netWorthCents() >= g.cents();
                case QuestGoal.HoldCashAtLeast g -> w.cashCents() >= g.cents();
                default -> false;
            };
        }
        return false;
    }

    /** Bought one of the pair today and sold the other for more per base unit (one big = {@code ratio} small). */
    private boolean twoBooks(QuestGoal.TwoBooks g) {
        long[] bBig = dayFloor.get("B|" + g.big()), sSmall = dayFloor.get("S|" + g.small());
        long[] bSmall = dayFloor.get("B|" + g.small()), sBig = dayFloor.get("S|" + g.big());
        if (bBig != null && sSmall != null && sSmall[0] >= g.ratio()) {
            if (sSmall[1] / (double) sSmall[0] * g.ratio() > bBig[1] / (double) bBig[0]) return true;
        }
        if (bSmall != null && sBig != null && bSmall[0] >= g.ratio()) {
            if (sBig[1] / (double) sBig[0] > bSmall[1] / (double) bSmall[0] * g.ratio()) return true;
        }
        return false;
    }

    /**
     * Re-applies the grants of every owned node and completed quest, so content added after a purchase (a new
     * guide on an old node) reaches existing players. Returns true if anything was added.
     */
    public boolean syncGrants(UnlockTree tree, Quests all) {
        int before = grants.size();
        for (String id : nodes) if (tree.has(id)) grants.addAll(tree.node(id).grants());
        for (String id : quests) if (all.has(id)) grants.addAll(all.quest(id).grants());
        return grants.size() != before;
    }

    public boolean hasNode(String id) {
        return nodes.contains(id);
    }

    public boolean hasCompleted(String questId) {
        return quests.contains(questId);
    }

    public boolean hasBlueprint(String result) {
        return grants.contains("blueprint:" + result);
    }

    public boolean hasPerk(String id) {
        return grants.contains("perk:" + id);
    }

    public boolean hasGuide(String id) {
        return grants.contains("guide:" + id);
    }

    public Set<String> nodes() {
        return Collections.unmodifiableSet(nodes);
    }

    public Set<String> completedQuests() {
        return Collections.unmodifiableSet(quests);
    }

    public Set<String> grants() {
        return Collections.unmodifiableSet(grants);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlayerProgress p && nodes.equals(p.nodes) && quests.equals(p.quests)
                && grants.equals(p.grants) && boughtItems.equals(p.boughtItems) && lowestRatio.equals(p.lowestRatio)
                && trackedDay == p.trackedDay && dayQty.equals(p.dayQty) && dayGroupCents.equals(p.dayGroupCents)
                && floorEquals(dayFloor, p.dayFloor);
    }

    private static boolean floorEquals(Map<String, long[]> a, Map<String, long[]> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (String k : a.keySet()) if (!java.util.Arrays.equals(a.get(k), b.get(k))) return false;
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, quests, grants);
    }
}
