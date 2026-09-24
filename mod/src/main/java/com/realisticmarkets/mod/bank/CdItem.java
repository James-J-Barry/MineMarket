package com.realisticmarkets.mod.bank;

import com.realisticmarkets.contracts.Cd;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Money;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Certificate of Deposit papers. The item carries only its serial (custom data) and a printed description (lore);
 * the security registry holds the real terms, so an edited or copied paper can't pay more than it was issued for.
 */
public final class CdItem {
    private CdItem() {}

    public static ItemStack create(UUID serial, Cd cd) {
        ItemStack stack = new ItemStack(ModItems.CERTIFICATE_OF_DEPOSIT);
        CompoundTag tag = new CompoundTag();
        tag.putString("serial", serial.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                line(Money.format(cd.principalCents()) + " for " + cd.termDays() + " days at "
                        + String.format(Locale.ROOT, "%.2f%%", cd.dailyRate() * 100) + "/day", ChatFormatting.GRAY),
                line("Matures day " + cd.maturityDay() + ": pays " + Money.format(cd.valueAtMaturityCents()), ChatFormatting.DARK_GREEN),
                line("Redeemed early: principal only", ChatFormatting.GRAY),
                line("Bearer paper: whoever holds it can redeem it", ChatFormatting.DARK_GRAY),
                line("Serial " + serial.toString().substring(0, 8), ChatFormatting.DARK_GRAY))));
        return stack;
    }

    public static Optional<UUID> serial(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(ModItems.CERTIFICATE_OF_DEPOSIT)) return Optional.empty();
        String s = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("serial", "");
        try {
            return s.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isVoid(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBooleanOr("void", false);
    }

    public static void markVoid(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putBoolean("void", true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("VOID Certificate").withStyle(ChatFormatting.RED));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                line("Already redeemed, or not genuine", ChatFormatting.RED))));
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(s -> s.withColor(color).withItalic(false));
    }
}
