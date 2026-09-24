package com.realisticmarkets.mod.floor;

import com.realisticmarkets.agents.TradingFloor;
import com.realisticmarkets.exchange.Side;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Money;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/** One Trade Receipt per finished Floor order: what, how many, the average price, and how it ended. */
public final class TradeReceiptItem {
    private TradeReceiptItem() {}

    public static ItemStack create(TradingFloor.Receipt r) {
        ItemStack s = new ItemStack(ModItems.TRADE_RECEIPT);
        String name = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(r.item()))).getHoverName().getString();
        String verb = r.side() == Side.BUY ? "Bought" : "Sold";
        String ending = switch (r.ending()) {
            case FILLED -> "Filled";
            case CANCELLED -> r.market() ? "Market order: rest cancelled" : "Cancelled";
            case EXPIRED -> "Expired at dawn";
        };
        s.set(DataComponents.LORE, new ItemLore(List.of(
                line(verb + " " + r.filledQty() + " of " + r.qty() + " " + name, ChatFormatting.GRAY),
                line(r.filledQty() == 0 ? "Nothing traded" : "Average " + mills(r.avgMills()) + " each, "
                        + Money.format(r.filledCents()) + " in all", ChatFormatting.DARK_GREEN),
                line((r.market() ? "Market" : "Limit") + " order · " + ending + " · day " + r.day(), ChatFormatting.DARK_GRAY))));
        CompoundTag tag = new CompoundTag();
        tag.putString("item", r.item());
        tag.putString("side", r.side().name());
        tag.putLong("qty", r.filledQty());
        tag.putLong("cents", r.filledCents());
        tag.putLong("day", r.day());
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    static String mills(long mills) {
        if (mills % 10 == 0) return Money.format(mills / 10);
        return String.format(Locale.ROOT, "$%.3f", mills / 1000.0);
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(st -> st.withColor(color).withItalic(false));
    }
}
