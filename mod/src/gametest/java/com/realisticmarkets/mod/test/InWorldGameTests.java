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

    @GameTest
    public void theMarketBoardShowsPricesSharesAndNews(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(new BlockPos(1, 1, 2), net.minecraft.world.level.block.Blocks.STONE);
        helper.setBlock(pos, ModBlocks.MARKET_BOARD);
        var board = helper.getBlockEntity(pos, com.realisticmarkets.mod.block.MarketBoardBlockEntity.class);
        board.setOwner(p);
        board.nextPage(); // shares
        check(board.title().startsWith("Shares") && board.lines().size() == 6, "six companies: " + board.lines());
        check(board.lines().get(0).contains("\t$"), "each with a price: " + board.lines().get(0));
        board.nextPage(); // news
        check(board.title().startsWith("News") && !board.lines().isEmpty(), "the rate and the news: " + board.lines());
        board.nextPage(); // back to the Floor
        check(board.title().startsWith("Trading Floor") && board.lines().size() >= 10, "the Floor's books: " + board.lines().size());
        helper.succeed();
    }

    @GameTest
    public void theLedgerDisplayShowsItsTerminalsNetWorth(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        ForwardGameTests.unlock(p, ProgressionService.get(), 4, "digital_record_keeping"); // the live service: the display asks it
        BlockPos tPos = new BlockPos(1, 1, 1), dPos = new BlockPos(3, 2, 1);
        helper.setBlock(tPos, ModBlocks.RECORDS_TERMINAL);
        helper.setBlock(dPos, ModBlocks.LEDGER_DISPLAY);
        var terminal = helper.getBlockEntity(tPos, com.realisticmarkets.mod.block.RecordsTerminalBlockEntity.class);
        terminal.setOwner(p);
        var display = helper.getBlockEntity(dPos, com.realisticmarkets.mod.block.LedgerDisplayBlockEntity.class);
        ItemStack tool = new ItemStack(com.realisticmarkets.mod.registry.ModItems.RECORD_LINK);
        com.realisticmarkets.mod.records.RecordLinkItem.use(p, helper.getLevel(), helper.absolutePos(tPos), tool);
        String msg = com.realisticmarkets.mod.records.RecordLinkItem.use(p, helper.getLevel(), helper.absolutePos(dPos), tool);
        check(msg.startsWith("Ledger Display shows"), msg);
        check(display.terminal().equals(helper.absolutePos(tPos)), "linked to the terminal");
        check(display.title().startsWith("Ledger, day") && display.lines().get(0).startsWith("Net worth"), display.title() + " " + display.lines());
        helper.succeed();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
