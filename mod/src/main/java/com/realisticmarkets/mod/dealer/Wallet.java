package com.realisticmarkets.mod.dealer;

import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Denomination;
import com.realisticmarkets.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Physical cash in a player's inventory: counting it, paying with it, and paying it out. */
public final class Wallet {
    private Wallet() {}

    public static long count(Inventory inv) {
        long cents = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            Denomination d = ModItems.denominationOf(s);
            if (d != null) cents += d.cents() * s.getCount();
        }
        return cents;
    }

    /** Fewest bill/coin stacks for an amount (a multiple of 10 cents). */
    public static List<ItemStack> toStacks(long cents) {
        List<ItemStack> out = new ArrayList<>();
        for (Map.Entry<Denomination, Long> e : Money.makeChange(cents).entrySet()) {
            Item item = ModItems.CURRENCY.get(e.getKey());
            long left = e.getValue();
            while (left > 0) {
                int n = (int) Math.min(left, 64);
                out.add(new ItemStack(item, n));
                left -= n;
            }
        }
        return out;
    }

    /** Puts cash into the player's inventory; anything that doesn't fit drops at their feet. */
    public static void give(Player player, long cents) {
        for (ItemStack stack : toStacks(cents)) {
            player.getInventory().placeItemBackInInventory(stack);
        }
    }

    /**
     * Takes {@code cents} from the player's cash and returns change. Simple and always correct:
     * collect every bill and coin, then pay back the difference in the fewest items.
     *
     * @return false (and changes nothing) if the player can't afford it
     */
    public static boolean pay(Player player, long cents) {
        Inventory inv = player.getInventory();
        long have = count(inv);
        if (have < cents) return false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (ModItems.denominationOf(inv.getItem(i)) != null) inv.setItem(i, ItemStack.EMPTY);
        }
        give(player, have - cents);
        return true;
    }
}
