package com.realisticmarkets.mod.bank;

import com.realisticmarkets.collateral.CollateralValuer;
import com.realisticmarkets.collateral.Loan;
import com.realisticmarkets.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * The borrower's statement. The debt lives on the account (the note isn't needed to repay); the vault rewrites any
 * Loan Note you carry with the current figures each time you visit.
 */
public final class LoanNoteItem {
    private LoanNoteItem() {}

    public static void write(ItemStack note, Loan loan, CollateralValuer.Valuation v, long day) {
        List<Component> lines = new ArrayList<>();
        if (loan.repaid()) {
            lines.add(line("Paid off", ChatFormatting.DARK_GREEN));
        } else {
            lines.add(line("Owe " + Money.format(loan.owedCents()) + " at "
                    + String.format(Locale.ROOT, "%.2f%%", loan.dailyRate() * 100) + " a day", ChatFormatting.GRAY));
            double cov = v.coverage(loan.owedCents());
            lines.add(line("Coverage " + Math.round(cov * 100) + "% (margin call below 110%)",
                    cov < CollateralValuer.MAINTENANCE ? ChatFormatting.RED : ChatFormatting.DARK_GREEN));
            if (loan.underMarginCall()) {
                lines.add(line("MARGIN CALL: add collateral or repay by dawn of day " + (loan.callDay() + 1), ChatFormatting.RED));
            }
        }
        for (var e : loan.collateral().entrySet()) {
            lines.add(line(e.getValue() + " " + e.getKey().substring(e.getKey().indexOf(':') + 1).replace('_', ' '),
                    ChatFormatting.DARK_GRAY));
        }
        if (loan.cashCollateralCents() > 0) lines.add(line(Money.format(loan.cashCollateralCents()) + " cash", ChatFormatting.DARK_GRAY));
        lines.add(line("Statement as of day " + day, ChatFormatting.DARK_GRAY));
        note.set(DataComponents.LORE, new ItemLore(lines));
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(s -> s.withColor(color).withItalic(false));
    }
}
