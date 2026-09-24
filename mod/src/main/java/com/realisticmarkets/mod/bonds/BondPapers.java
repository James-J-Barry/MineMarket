package com.realisticmarkets.mod.bonds;

import com.realisticmarkets.bonds.Bond;
import com.realisticmarkets.equities.CompanyCatalog;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Money;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Bond papers: bearer papers for one bond of a series ($100 face). Papers of the same series paid through the same
 * coupon are identical and stack. They show their terms, not a price (the desk and the Portfolio Binder do that).
 */
public final class BondPapers {
    public static final CompanyCatalog COMPANIES = CompanyCatalog.loadDefault();

    private BondPapers() {}

    public record Paper(Bond bond, int paidThrough) {}

    public static String issuerName(String issuer) {
        return Bond.TREASURY.equals(issuer) ? "Treasury" : issuer;
    }

    public static ItemStack create(Bond b, int paidThrough, int count) {
        ItemStack s = new ItemStack(ModItems.BOND, count);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(issuerName(b.issuer()) + " Bond, matures day " + b.maturityDay())
                .withStyle(st -> st.withItalic(false)));
        String who = b.treasury() ? "Issued by the Treasury" : "Issued by " + COMPANIES.company(b.issuer()).name();
        s.set(DataComponents.LORE, new ItemLore(List.of(
                line(who, ChatFormatting.DARK_AQUA),
                line("$100 face, coupon " + Money.format(b.couponCents()) + " a quarter"
                        + String.format(Locale.ROOT, " (%.3f%% a day)", b.couponRate() * 100), ChatFormatting.GRAY),
                line("Coupons paid: " + paidThrough + " of " + b.coupons(), ChatFormatting.GRAY),
                line("Bearer paper: whoever holds it owns it", ChatFormatting.DARK_GRAY))));
        CompoundTag tag = new CompoundTag();
        tag.putString("issuer", b.issuer());
        tag.putLong("issue_day", b.issueDay());
        tag.putLong("maturity_day", b.maturityDay());
        tag.putDouble("coupon_rate", b.couponRate());
        tag.putInt("paid_through", paidThrough);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    public static Optional<Paper> read(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(ModItems.BOND)) return Optional.empty();
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        String issuer = tag.getStringOr("issuer", "");
        if (issuer.isEmpty()) return Optional.empty();
        try {
            Bond b = new Bond(issuer, tag.getLongOr("issue_day", 0), tag.getLongOr("maturity_day", 0), tag.getDoubleOr("coupon_rate", 0));
            return Optional.of(new Paper(b, tag.getIntOr("paid_through", 0)));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(st -> st.withColor(color).withItalic(false));
    }
}
