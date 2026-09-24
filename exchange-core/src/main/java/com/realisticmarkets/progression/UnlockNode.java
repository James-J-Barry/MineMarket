package com.realisticmarkets.progression;

import java.util.List;

/** One purchasable Almanac node. {@code requiredQuest} may be null. */
public record UnlockNode(String id, String title, int tier, long costCents, List<String> parents, String requiredQuest,
                         List<String> grants) {
    public UnlockNode {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("node id required");
        if (tier < 1) throw new IllegalArgumentException(id + ": tier must be >= 1");
        if (costCents < 0) throw new IllegalArgumentException(id + ": cost must be >= 0");
        parents = List.copyOf(parents);
        grants = List.copyOf(grants);
        grants.forEach(Grants::validate);
        if (requiredQuest != null && requiredQuest.isBlank()) requiredQuest = null;
    }
}
