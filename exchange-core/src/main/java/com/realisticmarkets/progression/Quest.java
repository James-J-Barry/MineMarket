package com.realisticmarkets.progression;

import java.util.List;

/** A quest: a goal evaluated from {@link ProgressionEvent}s, a cash reward, and grants (guides, perks). */
public record Quest(String id, String title, QuestGoal goal, long rewardCents, List<String> grants) {
    public Quest {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("quest id required");
        if (goal == null) throw new IllegalArgumentException(id + ": goal required");
        if (rewardCents < 0) throw new IllegalArgumentException(id + ": reward must be >= 0");
        grants = List.copyOf(grants);
        grants.forEach(Grants::validate);
    }
}
