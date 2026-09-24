package com.realisticmarkets.mod.progression;

import com.realisticmarkets.progression.Blueprint;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Blueprint material ids against a real inventory: {@code minecraft:leather}, or {@code #minecraft:planks} for a tag. */
public final class Materials {
    private Materials() {}

    public static Predicate<ItemStack> matcher(String materialId) {
        if (materialId.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, Identifier.parse(materialId.substring(1)));
            return s -> s.is(tag);
        }
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(materialId));
        return s -> s.is(item);
    }

    public static int count(Inventory inv, String materialId) {
        Predicate<ItemStack> m = matcher(materialId);
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && m.test(s)) n += s.getCount();
        }
        return n;
    }

    public static Map<String, Integer> counts(Inventory inv, Blueprint bp) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Blueprint.Material m : bp.materials()) out.put(m.item(), count(inv, m.item()));
        return out;
    }

    /** Removes {@code amount} matching items; the caller has already checked they exist. */
    public static void remove(Inventory inv, String materialId, int amount) {
        Predicate<ItemStack> m = matcher(materialId);
        for (int i = 0; i < inv.getContainerSize() && amount > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !m.test(s)) continue;
            int take = Math.min(amount, s.getCount());
            s.shrink(take);
            amount -= take;
        }
        inv.setChanged();
    }
}
