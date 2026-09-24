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
        if (cashCents < node.costCents()) return Optional.of("Not enough cash");
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
        return node.costCents();
    }

    /** Feeds one event to every open quest; returns the quests it completed (caller pays their cash rewards). */
    public List<Quest> apply(ProgressionEvent event, Quests all) {
        long day = switch (event) {
            case ProgressionEvent.Sale e -> e.day();
            case ProgressionEvent.Purchase e -> e.day();
            case ProgressionEvent.NetWorth e -> e.day();
            case ProgressionEvent.DayRollover e -> e.day();
            case ProgressionEvent.Craft e -> e.day();
        };
        if (day != trackedDay) {
            trackedDay = day;
            dayQty.clear();
            dayGroupCents.clear();
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
        if (event instanceof ProgressionEvent.NetWorth w) {
            return switch (goal) {
                case QuestGoal.NetWorthAtLeast g -> w.netWorthCents() >= g.cents();
                case QuestGoal.HoldCashAtLeast g -> w.cashCents() >= g.cents();
                default -> false;
            };
        }
        return false;
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
                && trackedDay == p.trackedDay && dayQty.equals(p.dayQty) && dayGroupCents.equals(p.dayGroupCents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, quests, grants);
    }
}
