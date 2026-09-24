package com.realisticmarkets.mod.item;

import com.realisticmarkets.exchange.PriceChart;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Money;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;

/** A printed Price Chart: 7 days of one Floor book. Right-click to open it; the tooltip sketches daily closes. */
public class PriceChartItem extends Item {
    /** Set by the client entrypoint: opens the chart screen for a stack. */
    public static Consumer<ItemStack> openChart;

    public PriceChartItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() && openChart != null && read(stack).isPresent()) openChart.accept(stack);
        return InteractionResult.SUCCESS;
    }

    public static ItemStack create(PriceChart chart, String itemName) {
        ItemStack s = new ItemStack(ModItems.PRICE_CHART);
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Price Chart: " + itemName).withStyle(st -> st.withItalic(false)));
        CompoundTag tag = new CompoundTag();
        tag.putString("item", chart.item());
        tag.putString("name", itemName);
        tag.putLong("to_day", chart.toDay());
        tag.putIntArray("points", chart.packed());
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        String days = "Days " + (chart.toDay() - PriceChart.DAYS + 1) + "-" + chart.toDay();
        List<Component> lore = chart.empty()
                ? List.of(line(days + ": no trades", ChatFormatting.GRAY))
                : List.of(line(chart.sparkline(), ChatFormatting.GOLD),
                        line(days + ", last " + Money.format(chart.last()), ChatFormatting.GRAY),
                        line("Low " + Money.format(chart.min()) + ", high " + Money.format(chart.max()), ChatFormatting.DARK_GRAY));
        s.set(DataComponents.LORE, new ItemLore(lore));
        return s;
    }

    public static Optional<PriceChart> read(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        Optional<int[]> points = tag.getIntArray("points");
        if (points.isEmpty() || points.get().length != PriceChart.POINTS * 3) return Optional.empty();
        return Optional.of(PriceChart.unpack(tag.getStringOr("item", ""), tag.getLongOr("to_day", 0), points.get()));
    }

    public static String name(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("name", "");
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(st -> st.withColor(color).withItalic(false));
    }
}
