package com.realisticmarkets.mod.stocks;

import com.realisticmarkets.equities.Certificates;
import com.realisticmarkets.equities.CompanyCatalog;
import com.realisticmarkets.mod.registry.ModItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Share Certificates: bearer papers for 1, 10 or 100 shares of one company, carrying the last quarter their dividends
 * were paid through. Identical certificates stack; they show no value (the Portfolio Binder does that).
 */
public final class ShareCertificates {
    public static final CompanyCatalog COMPANIES = CompanyCatalog.loadDefault();

    private ShareCertificates() {}

    public record Paper(String ticker, int denomination, long paidThrough) {}

    public static ItemStack create(String ticker, int denomination, long paidThrough, int count) {
        if (!Certificates.valid(denomination)) throw new IllegalArgumentException("no " + denomination + "-share certificate");
        ItemStack s = new ItemStack(ModItems.SHARE_CERTIFICATE, count);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(ticker + " Share Certificate, " + denomination
                + (denomination == 1 ? " share" : " shares")).withStyle(st -> st.withItalic(false)));
        String name = COMPANIES.company(ticker).name();
        s.set(DataComponents.LORE, new ItemLore(List.of(
                line(name, ChatFormatting.DARK_GREEN),
                line(paidThrough < 0 ? "No dividends paid on it yet" : "Dividends paid through quarter " + paidThrough, ChatFormatting.GRAY),
                line("Bearer paper: whoever holds it owns it", ChatFormatting.DARK_GRAY))));
        CompoundTag tag = new CompoundTag();
        tag.putString("ticker", ticker);
        tag.putInt("shares", denomination);
        tag.putLong("paid_through", paidThrough);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    public static Optional<Paper> read(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(ModItems.SHARE_CERTIFICATE)) return Optional.empty();
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        String ticker = tag.getStringOr("ticker", "");
        int shares = tag.getIntOr("shares", 0);
        if (ticker.isEmpty() || !Certificates.valid(shares)) return Optional.empty();
        return Optional.of(new Paper(ticker, shares, tag.getLongOr("paid_through", -1)));
    }

    /** Shares of each company held in {@code c}, counting certificates loose and inside Portfolio Binders. */
    public static Map<String, Long> holdings(Container c) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            count(s, out);
            if (s.is(ModItems.PORTFOLIO_BINDER)) {
                for (ItemStack inside : com.realisticmarkets.mod.item.PortfolioBinderItem.contents(s)) count(inside, out);
            }
        }
        return out;
    }

    /** Certificates loose in {@code c} only (the ones a player can present or sell). */
    public static Map<String, Long> loose(Container c) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) count(c.getItem(i), out);
        return out;
    }

    private static void count(ItemStack s, Map<String, Long> out) {
        read(s).ifPresent(p -> out.merge(p.ticker(), (long) p.denomination() * s.getCount(), Long::sum));
    }

    /** The fewest certificates for {@code shares} of {@code ticker}, as stacks of at most 64. */
    public static List<ItemStack> stacksFor(String ticker, long shares, long paidThrough) {
        List<ItemStack> out = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : Certificates.fewest(shares).entrySet()) {
            long left = e.getValue();
            while (left > 0) {
                int n = (int) Math.min(64, left);
                out.add(create(ticker, e.getKey(), paidThrough, n));
                left -= n;
            }
        }
        return out;
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(st -> st.withColor(color).withItalic(false));
    }
}
