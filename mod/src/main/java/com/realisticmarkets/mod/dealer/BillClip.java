package com.realisticmarkets.mod.dealer;

import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Denomination;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** The Bill Clip's contents: up to {@link #SLOTS} stacks of currency, stored on the item stack itself. */
public final class BillClip {
    public static final int SLOTS = 9;

    private BillClip() {}

    public static boolean isClip(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.BILL_CLIP);
    }

    /** A mutable copy of the clip's slots (always {@link #SLOTS} long). */
    public static NonNullList<ItemStack> contents(ItemStack clip) {
        NonNullList<ItemStack> slots = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        clip.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(slots);
        return slots;
    }

    public static void setContents(ItemStack clip, List<ItemStack> slots) {
        clip.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(new ArrayList<>(slots)));
    }

    public static long cents(ItemStack clip) {
        long cents = 0;
        for (ItemStack s : contents(clip)) {
            Denomination d = ModItems.denominationOf(s);
            if (d != null) cents += d.cents() * s.getCount();
        }
        return cents;
    }

    /** Empties the clip and returns what it held. */
    public static long takeAll(ItemStack clip) {
        long cents = cents(clip);
        setContents(clip, NonNullList.withSize(SLOTS, ItemStack.EMPTY));
        return cents;
    }

    /** Adds money to the clip, topping up matching stacks first; returns what didn't fit. */
    public static ItemStack insert(ItemStack clip, ItemStack money) {
        if (ModItems.denominationOf(money) == null) return money;
        NonNullList<ItemStack> slots = contents(clip);
        ItemStack left = money.copy();
        for (ItemStack s : slots) {
            if (left.isEmpty()) break;
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, left)) {
                int n = Math.min(left.getCount(), s.getMaxStackSize() - s.getCount());
                s.grow(n);
                left.shrink(n);
            }
        }
        for (int i = 0; i < SLOTS && !left.isEmpty(); i++) {
            if (slots.get(i).isEmpty()) {
                slots.set(i, left.copy());
                left = ItemStack.EMPTY;
            }
        }
        setContents(clip, slots);
        return left;
    }
}
