package com.realisticmarkets.mod.bank;

import com.realisticmarkets.contracts.BankAccount;
import com.realisticmarkets.contracts.BankParams;
import com.realisticmarkets.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;

/**
 * The only place a player sees their bank balance. The vault writes the account onto it (balance in the tooltip,
 * full history as book pages) whenever it sits in the vault's Passbook slot; elsewhere it shows that snapshot.
 */
public class PassbookItem extends Item {
    private static final int ENTRIES_PER_PAGE = 6;

    /** Set by the client entrypoint: opens the book view. Null on a dedicated server. */
    public static Consumer<ItemStack> openBook;

    public PassbookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() && openBook != null && stack.has(DataComponents.WRITTEN_BOOK_CONTENT)) {
            openBook.accept(stack);
        }
        return InteractionResult.SUCCESS;
    }

    public static void write(ItemStack book, String owner, BankAccount a, long day, double dailyRate) {
        List<Filterable<Component>> pages = new ArrayList<>();
        pages.add(page("Passbook\n" + owner + "\n\nBalance\n" + Money.format(a.balanceCents()) + "\nas of day " + day
                + "\n\nInterest " + String.format(Locale.ROOT, "%.2f%%", dailyRate * 100) + " a day today"
                + "\nEarned so far " + Money.format(a.interestTotalCents())));
        List<BankAccount.Entry> log = new ArrayList<>(a.log());
        java.util.Collections.reverse(log); // newest first
        StringBuilder page = new StringBuilder();
        int onPage = 0;
        for (BankAccount.Entry e : log) {
            boolean in = e.kind() == BankAccount.Kind.DEPOSIT || e.kind() == BankAccount.Kind.INTEREST
                    || e.kind() == BankAccount.Kind.CD_REDEEM || e.kind() == BankAccount.Kind.LOAN
                    || e.kind() == BankAccount.Kind.LIQUIDATION;
            page.append("Day ").append(e.day()).append(' ').append(label(e.kind())).append('\n')
                    .append(in ? "+" : "-").append(Money.format(e.amountCents()))
                    .append("  = ").append(Money.format(e.balanceCents())).append("\n");
            if (++onPage == ENTRIES_PER_PAGE) {
                pages.add(page(page.toString()));
                page.setLength(0);
                onPage = 0;
            }
        }
        if (onPage > 0) pages.add(page(page.toString()));
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough("Passbook"), owner, 0, pages, true));
        book.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Balance " + Money.format(a.balanceCents()) + " (day " + day + ")")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_GREEN).withItalic(false)),
                Component.literal(owner).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)))));
    }

    private static String label(BankAccount.Kind kind) {
        return switch (kind) {
            case DEPOSIT -> "deposit";
            case WITHDRAW -> "withdrawal";
            case INTEREST -> "interest";
            case CD_ISSUE -> "CD bought";
            case CD_REDEEM -> "CD redeemed";
            case LOAN -> "loan";
            case LOAN_REPAY -> "loan repaid";
            case LIQUIDATION -> "forced sale";
        };
    }

    private static Filterable<Component> page(String text) {
        return Filterable.passThrough(Component.literal(text));
    }
}
