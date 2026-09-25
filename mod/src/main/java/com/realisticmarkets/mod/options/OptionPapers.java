package com.realisticmarkets.mod.options;

import com.realisticmarkets.futures.ClearingHouse;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.options.OptionDesk;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Option Contract papers: bearer papers for one contract of a series (underlying, call or put, strike, expiry). Papers
 * of one series are identical and stack. They show their terms; the desk shows their price and Greeks.
 */
public final class OptionPapers {
    private OptionPapers() {}

    /** The six goods (futures codes) and then the six companies (tickers), in the desk's order. */
    public static final List<String> UNDERLYINGS = underlyings();

    private static List<String> underlyings() {
        List<String> out = new ArrayList<>();
        for (ClearingHouse.Product p : ClearingHouse.PRODUCTS) out.add(p.code());
        for (var c : ShareCertificates.COMPANIES.all()) out.add(c.ticker());
        return List.copyOf(out);
    }

    /** "Wheat" or "OWL". */
    public static String shortName(String underlying) {
        return OptionDesk.isGood(underlying) ? ClearingHouse.product(underlying).name() : underlying;
    }

    /** "256 Wheat" or "10 OWL shares". */
    public static String contractName(String underlying) {
        int n = OptionDesk.contractSize(underlying);
        return OptionDesk.isGood(underlying) ? n + " " + ClearingHouse.product(underlying).name() : n + " " + underlying + " shares";
    }

    public static String title(OptionDesk.Series s) {
        return shortName(s.underlying()) + " " + (s.call() ? "Call" : "Put") + " " + Money.format(s.strikeCents()) + ", day " + s.expiry();
    }

    public static ItemStack create(OptionDesk.Series s, int count) {
        ItemStack st = new ItemStack(ModItems.OPTION_CONTRACT, count);
        st.set(DataComponents.CUSTOM_NAME, Component.literal(title(s)).withStyle(x -> x.withItalic(false)));
        st.set(DataComponents.LORE, new ItemLore(List.of(
                line("The right to " + (s.call() ? "buy " : "sell ") + contractName(s.underlying()), ChatFormatting.DARK_AQUA),
                line("for " + Money.format(s.strikeCents()) + " on day " + s.expiry(), ChatFormatting.GRAY),
                line("Settles in cash at the Options Desk after expiry", ChatFormatting.GRAY),
                line("Bearer paper: whoever holds it owns it", ChatFormatting.DARK_GRAY))));
        CompoundTag tag = new CompoundTag();
        tag.putString("series", s.key());
        st.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return st;
    }

    public static Optional<OptionDesk.Series> read(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(ModItems.OPTION_CONTRACT)) return Optional.empty();
        String key = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("series", "");
        if (key.isEmpty()) return Optional.empty();
        try {
            return Optional.of(OptionDesk.Series.parse(key));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(st -> st.withColor(color).withItalic(false));
    }
}
