package com.realisticmarkets.progression;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Drafting Table recipe. Material ids are opaque to the core: item ids, or {@code #tag} ids that the mod
 * layer counts by tag membership.
 */
public record Blueprint(String result, String node, List<Material> materials) {

    public record Material(String item, int count) {
        public Material {
            if (item == null || item.isBlank()) throw new IllegalArgumentException("material item required");
            if (count <= 0) throw new IllegalArgumentException(item + ": count must be positive");
        }
    }

    /** {@code times} actually crafted and the materials that uses up. */
    public record CraftResult(int times, Map<String, Integer> consumed) {}

    public Blueprint {
        if (result == null || result.isBlank()) throw new IllegalArgumentException("blueprint result required");
        if (node == null || node.isBlank()) throw new IllegalArgumentException(result + ": node required");
        if (materials == null || materials.isEmpty()) throw new IllegalArgumentException(result + ": materials required");
        materials = List.copyOf(materials);
    }

    public int maxCraftable(Map<String, Integer> inventory) {
        int max = Integer.MAX_VALUE;
        for (Material m : materials) max = Math.min(max, inventory.getOrDefault(m.item(), 0) / m.count());
        return Math.max(max, 0);
    }

    public CraftResult craft(Map<String, Integer> inventory, int requested) {
        if (requested < 0) throw new IllegalArgumentException("requested must be >= 0");
        int times = Math.min(requested, maxCraftable(inventory));
        Map<String, Integer> consumed = new LinkedHashMap<>();
        for (Material m : materials) consumed.put(m.item(), m.count() * times);
        return new CraftResult(times, consumed);
    }
}
