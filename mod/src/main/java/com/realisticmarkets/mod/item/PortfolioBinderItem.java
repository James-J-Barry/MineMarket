package com.realisticmarkets.mod.item;

import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.menu.PortfolioBinderMenu;
import com.realisticmarkets.mod.stocks.StockService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;

/** Holds up to 27 security papers; right-click to open it and see what they're worth together at live prices. */
public class PortfolioBinderItem extends Item {
    public static final int SLOTS = 27;
    private static final Component TITLE = Component.translatable("container.realisticmarkets.portfolio_binder");

    public PortfolioBinderItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) {
            ItemStack binder = player.getItemInHand(hand);
            StockService stocks = null;
            BankService bank = null;
            try {
                stocks = StockService.get();
            } catch (IllegalStateException notRunning) {
                // no exchange: shares count at 0
            }
            try {
                bank = BankService.get();
            } catch (IllegalStateException notRunning) {
                // no bank: CDs count at 0
            }
            StockService s = stocks;
            BankService b = bank;
            long day = b == null ? 0 : BankService.day(((net.minecraft.server.level.ServerLevel) level).getServer());
            player.openMenu(new SimpleMenuProvider((id, inv, p) -> new PortfolioBinderMenu(id, inv, hand, binder, s, b, day), TITLE));
            if (s != null) { // opening your binder is looking at your portfolio: the holding quests notice
                try {
                    s.reportHoldings(player, null, com.realisticmarkets.mod.progression.ProgressionService.get(), day);
                } catch (IllegalStateException notRunning) {
                    // no progression service
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    public static NonNullList<ItemStack> contents(ItemStack binder) {
        NonNullList<ItemStack> slots = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        binder.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(slots);
        return slots;
    }

    public static void setContents(ItemStack binder, List<ItemStack> slots) {
        binder.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(new ArrayList<>(slots)));
    }
}
