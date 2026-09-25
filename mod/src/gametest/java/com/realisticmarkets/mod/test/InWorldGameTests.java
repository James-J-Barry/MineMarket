package com.realisticmarkets.mod.test;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.QuickSell;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** In-world play: quick-selling at the Basic Exchange, and the wall displays. */
public class InWorldGameTests {

    @GameTest
    public void sneakClickSellsTheStackAndASecondClickSellsTheRest(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        DealerService dealer = DealerService.forTest(1234L);
        ProgressionService prog = ProgressionService.forTest();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.BASIC_EXCHANGE);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 64));
        String hint = QuickSell.hint(p.getMainHandItem(), dealer, 0, false);
        check(hint.contains("the Dealer pays"), hint);
        long t = helper.getLevel().getGameTime();
        String msg = QuickSell.sell(p, pos, t, dealer, prog);
        check(msg.startsWith("Sold 64 Wheat for"), msg);
        check(p.getInventory().countItem(Items.WHEAT) == 0, "the stack in hand is gone");
        long first = Wallet.count(p.getInventory());
        check(first > 2_000, "paid in bills: " + first);
        check(prog.progress(p).hasCompleted("first_sale"), "and it's a sale like any other (First Sale)");

        p.getInventory().add(new ItemStack(Items.WHEAT, 32));
        p.getInventory().add(new ItemStack(Items.WHEAT, 32));
        p.getInventory().add(new ItemStack(Items.CARROT, 10));
        msg = QuickSell.sell(p, pos, t + 5, dealer, prog); // quick second click, empty hand
        check(msg.startsWith("Sold 64 Wheat"), "a quick second click sells all the wheat carried: " + msg);
        check(p.getInventory().countItem(Items.WHEAT) == 0 && p.getInventory().countItem(Items.CARROT) == 10, "only wheat");
        check(QuickSell.sell(p, pos, t + 200, dealer, prog).contains("doesn't buy"), "later, with nothing in hand: nothing");
        helper.succeed();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
