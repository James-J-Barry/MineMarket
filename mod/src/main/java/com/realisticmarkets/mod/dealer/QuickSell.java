package com.realisticmarkets.mod.dealer;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.mod.fx.Feedback;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.progression.ProgressionEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Selling at the Basic Exchange without its screen: sneak and right-click it holding goods to sell the stack in hand
 * (the bills go to your Bill Clip or inventory); sneak-click again within half a second to sell every stack of that
 * item you carry. Looking at an Exchange with goods in hand shows what the Dealer would pay on the action bar.
 */
public final class QuickSell {
    public static final int DOUBLE_CLICK_TICKS = 10, HINT_TICKS = 10;
    private record Click(long time, long pos, String item) {}

    private static final Map<UUID, Click> lastClick = new HashMap<>();

    private QuickSell() {}

    /** Whether the Dealer would buy this stack here (goods it trades; not bills, papers or other mod items with data). */
    public static boolean sellable(ItemStack s, DealerService dealer) {
        return !s.isEmpty() && ModItems.denominationOf(s) == null && !s.has(DataComponents.CUSTOM_DATA)
                && dealer.dealer().catalog().trades(DealerService.itemId(s));
    }

    /** {@code UseBlockCallback}: sneak-right-click on a Basic Exchange with goods in hand sells them. */
    public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND || !player.isSecondaryUseActive()) return InteractionResult.PASS;
        if (!level.getBlockState(hit.getBlockPos()).is(ModBlocks.BASIC_EXCHANGE)) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        boolean again = recent(player, hit.getBlockPos(), level.getGameTime()); // the hand may be empty after the first sale
        if (!again && (held.isEmpty() || ModItems.denominationOf(held) != null)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        DealerService dealer;
        ProgressionService prog;
        try {
            dealer = DealerService.get();
            prog = ProgressionService.get();
        } catch (IllegalStateException notRunning) {
            return InteractionResult.PASS;
        }
        String msg = sell(player, hit.getBlockPos(), level.getGameTime(), dealer, prog);
        if (player instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal(msg));
        return InteractionResult.SUCCESS;
    }

    private static boolean recent(Player player, BlockPos pos, long gameTime) {
        Click c = lastClick.get(player.getUUID());
        return c != null && gameTime - c.time() <= DOUBLE_CLICK_TICKS && c.pos() == pos.asLong();
    }

    /** Sells the held stack, or on a quick second click every stack of that item. Returns the message to show. */
    public static String sell(Player player, BlockPos pos, long gameTime, DealerService dealer, ProgressionService prog) {
        ItemStack held = player.getMainHandItem();
        boolean all = recent(player, pos, gameTime);
        String id;
        if (all) {
            id = lastClick.remove(player.getUUID()).item();
        } else {
            if (!sellable(held, dealer)) return "The Dealer doesn't buy that";
            id = DealerService.itemId(held);
            lastClick.put(player.getUUID(), new Click(gameTime, pos.asLong(), id));
        }
        double day = dealer.day(gameTime);
        boolean licensed = prog != null && prog.licensed(player);
        Dealer d = dealer.dealer();
        Inventory inv = player.getInventory();
        int qty = 0;
        long cents = 0;
        double before = d.mid(id, day) / d.normalValue(id, day);
        try {
            if (all) {
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack s = inv.getItem(i);
                    if (!sellable(s, dealer) || !DealerService.itemId(s).equals(id)) continue;
                    int n = s.getCount();
                    cents += dealer.sellStack(s, day, licensed);
                    qty += n;
                }
            } else {
                qty = held.getCount();
                cents = dealer.sellStack(held, day, licensed);
            }
        } catch (RejectedException collapsed) {
            if (qty == 0) return "The Dealer won't pay anything for that right now";
        }
        if (qty == 0) return all ? "No more of that to sell" : "Nothing to sell";
        Wallet.give(player, cents);
        Feedback.at(player.level(), pos, Feedback.Cue.SALE);
        if (prog != null) {
            double after = d.mid(id, day) / d.normalValue(id, day);
            String group = d.catalog().spec(id).group();
            prog.emit(player, new ProgressionEvent.Sale(id, group, qty, cents, before, after, (long) Math.floor(day)));
            prog.emitNetWorth(player, dealer, day, 0);
        }
        String name = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.Identifier.parse(id))).getHoverName().getString();
        return "Sold " + qty + " " + name + " for " + Money.format(cents) + (all ? "" : "  (sneak-click again to sell all you carry)");
    }

    /** Every few ticks: players looking at an Exchange with goods in hand see what the Dealer would pay. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % HINT_TICKS != 0) return;
        DealerService dealer;
        ProgressionService prog;
        try {
            dealer = DealerService.get();
            prog = ProgressionService.get();
        } catch (IllegalStateException notRunning) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.containerMenu != p.inventoryMenu) continue; // a screen is open
            ItemStack held = p.getMainHandItem();
            if (!sellable(held, dealer)) continue;
            HitResult hit = p.pick(5.0, 0, false);
            if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) continue;
            if (!p.level().getBlockState(bh.getBlockPos()).is(ModBlocks.BASIC_EXCHANGE)) continue;
            String hint = hint(held, dealer, dealer.day(p.level().getGameTime()), prog.licensed(p));
            if (hint != null) p.sendOverlayMessage(Component.literal(hint));
        }
    }

    /** "64 Wheat: the Dealer pays $25.40 (sneak-click to sell)", or null if it won't. */
    public static String hint(ItemStack held, DealerService dealer, double day, boolean licensed) {
        String id = DealerService.itemId(held);
        try {
            long cents = dealer.dealer().quoteSell(id, held.getCount(), day, licensed).cents();
            return held.getCount() + " " + held.getHoverName().getString() + ": the Dealer pays " + Money.format(cents)
                    + "  (sneak-click to sell)";
        } catch (RejectedException collapsed) {
            return held.getHoverName().getString() + ": the Dealer won't pay anything right now";
        }
    }
}
