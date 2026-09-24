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

/**
 * Physical cash a player carries: loose bills and coins in the inventory plus the contents of every Bill Clip
 * in it. Payments draw from all of it; payouts and change go into Bill Clips first, then the inventory.
 */
public final class Wallet {
    private Wallet() {}

    public static long count(Inventory inv) {
        long cents = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            Denomination d = ModItems.denominationOf(s);
            if (d != null) cents += d.cents() * s.getCount();
            else if (BillClip.isClip(s)) cents += BillClip.cents(s);
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

    /** Puts cash into the player's Bill Clips, then their inventory; anything that doesn't fit drops at their feet. */
    public static void give(Player player, long cents) {
        Inventory inv = player.getInventory();
        for (ItemStack stack : toStacks(cents)) {
            ItemStack left = stack;
            for (int i = 0; i < inv.getContainerSize() && !left.isEmpty(); i++) {
                if (BillClip.isClip(inv.getItem(i))) left = BillClip.insert(inv.getItem(i), left);
            }
            if (!left.isEmpty()) inv.placeItemBackInInventory(left);
        }
    }

    /** Removes every loose bill and empties every Bill Clip; returns the total taken. */
    public static long takeAll(Player player) {
        Inventory inv = player.getInventory();
        long cents = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            Denomination d = ModItems.denominationOf(s);
            if (d != null) {
                cents += d.cents() * s.getCount();
                inv.setItem(i, ItemStack.EMPTY);
            } else if (BillClip.isClip(s)) {
                cents += BillClip.takeAll(s);
            }
        }
        return cents;
    }

    /**
     * Takes {@code cents} from the player's cash and returns change. Simple and always correct:
     * collect every bill and coin, then pay back the difference in the fewest items.
     *
     * @return false (and changes nothing) if the player can't afford it
     */
    public static boolean pay(Player player, long cents) {
        long have = count(player.getInventory());
        if (have < cents) return false;
        give(player, takeAll(player) - cents);
        return true;
    }
}
