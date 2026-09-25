package com.realisticmarkets.mod.test;

import com.realisticmarkets.futures.ClearingHouse;
import com.realisticmarkets.mod.block.ClearingHouseBlockEntity;
import com.realisticmarkets.mod.block.RecordsTerminalBlockEntity;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.futures.FuturesService;
import com.realisticmarkets.mod.menu.ClearingHouseMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.records.RecordLinkItem;
import com.realisticmarkets.mod.records.RecordsService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.records.NetWorth;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

/** M8b: the Clearing House. Margin account, trading, the dawn mark, margin calls, close-outs, the Records Terminal. */
public class FuturesGameTests {
    static final String WHEAT = "minecraft:wheat";

    record Setup(ServerPlayer p, DealerService dealer, ProgressionService prog, FuturesService futures, long today) {}

    static Setup setup(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        DealerService dealer = DealerService.forTestLive(1234L);
        ProgressionService prog = ProgressionService.forTest();
        ForwardGameTests.unlock(p, prog, 5, "forward_contract", "clearing_house");
        long today = (long) Math.floor(dealer.day(helper.getLevel().getGameTime()));
        return new Setup(p, dealer, prog, FuturesService.forTest(dealer), today);
    }

    static long expiry(Setup s) {
        return ClearingHouse.expiries(s.today())[1]; // the far one, so no test day reaches it
    }

    @GameTest
    public void depositTradeAndMeetAMarginCall(GameTestHelper helper) {
        Setup s = setup(helper);
        check(s.prog().progress(s.p()).hasGuide("futures_and_margin"), "the node grants Futures and Margin");
        ClearingHouseMenu m = new ClearingHouseMenu(1, s.p().getInventory(), ContainerLevelAccess.NULL, s.futures(), s.prog(), s.dealer());
        check(m.owner(), "unlocked");
        long lot = com.realisticmarkets.money.Money.roundUpToDime(m.price(0, 1));
        Wallet.give(s.p(), lot); // enough for about 10 lots at 10% margin
        check(m.clickMenuButton(s.p(), ClearingHouseMenu.BUTTON_DEPOSIT), "deposit");
        check(Wallet.count(s.p().getInventory()) == 0 && m.cash() == lot, "cash moved into the futures account");
        m.clickMenuButton(s.p(), ClearingHouseMenu.BUTTON_EXPIRY_BASE + 1);
        m.clickMenuButton(s.p(), ClearingHouseMenu.BUTTON_LOTS_BASE + 3); // +5: 6 lots
        check(m.clickMenuButton(s.p(), ClearingHouseMenu.BUTTON_BUY), "buy 6 lots of wheat");
        check(m.position(0, 1) == 6, "long 6: " + m.position(0, 1));
        check(!m.clickMenuButton(s.p(), ClearingHouseMenu.BUTTON_BUY) || m.position(0, 1) <= 10,
                "a second 6 would need more margin than there is");

        s.dealer().dealer().sell(WHEAT, 3_000, s.today() + 0.5, false); // a glut: wheat falls hard
        var dawn = s.futures().dawn(s.today() + 1, id -> s.p(), s.prog());
        check(dawn.get(0).called() && dawn.get(0).variationCents() < 0, "a loss and a margin call: " + dawn);
        check(s.p().getInventory().countItem(ModItems.MARGIN_CALL_NOTICE) == 1, "a Margin Call Notice arrives");
        check(s.prog().progress(s.p()).hasCompleted("met_the_call") == false, "not met yet");
        String a = FuturesService.account(s.p());
        double later = s.today() + 1.3;
        long need = s.futures().house().required(a, later, true) - s.futures().house().equity(a, later);
        Wallet.give(s.p(), com.realisticmarkets.money.Money.roundUpToDime(need) + 1_000);
        check(s.futures().depositAll(s.p(), later, s.prog()) > 0, "top up");
        check(!s.futures().house().account(a).underCall(), "the call is met");
        check(s.prog().progress(s.p()).hasCompleted("met_the_call"), "Met the Call");
        helper.succeed();
    }

    @GameTest
    public void aGainingMarkPaysAndClosingAtAProfitIsSpeculator(GameTestHelper helper) {
        Setup s = setup(helper);
        String a = FuturesService.account(s.p());
        Wallet.give(s.p(), 500_000);
        s.futures().depositAll(s.p(), s.today() + 0.2, s.prog());
        check(s.futures().trade(s.p(), "WHT", expiry(s), 2, s.today() + 0.2, s.prog()).isEmpty(), "buy 2 lots");
        s.dealer().dealer().buy(WHEAT, 1_500, s.today() + 0.6, false); // wheat gets scarce: the price rises
        long cash = s.futures().house().account(a).cashCents();
        var dawn = s.futures().dawn(s.today() + 1, id -> s.p(), s.prog());
        check(dawn.get(0).variationCents() > 0, "the mark pays: " + dawn);
        check(s.futures().house().account(a).cashCents() == cash + dawn.get(0).variationCents(), "into the cash");
        check(s.prog().progress(s.p()).hasCompleted("marked_to_market"), "Marked to Market");
        check(s.prog().progress(s.p()).hasGuide("margin_calls"), "and its guide, Margin Calls");
        s.dealer().dealer().buy(WHEAT, 1_500, s.today() + 1.2, false);
        check(s.futures().trade(s.p(), "WHT", expiry(s), -2, s.today() + 1.5, s.prog()).isEmpty(), "sell to close");
        check(s.futures().house().account(a).positions().isEmpty(), "flat");
        check(s.prog().progress(s.p()).hasCompleted("speculator"), "Speculator: closed at a profit");
        long withdrawn = s.futures().withdrawFree(s.p(), s.today() + 1.5);
        check(withdrawn > 500_000, "and the winnings come back out in bills: " + withdrawn);
        helper.succeed();
    }

    @GameTest
    public void anUnmetCallClosesOutAndTheTerminalShowsTheAccount(GameTestHelper helper) {
        Setup s = setup(helper);
        String a = FuturesService.account(s.p());
        long lot = Math.round(s.futures().house().price("WHT", expiry(s), s.today() + 0.2));
        Wallet.give(s.p(), com.realisticmarkets.money.Money.roundUpToDime(lot * 3));
        s.futures().depositAll(s.p(), s.today() + 0.2, s.prog());
        check(s.futures().trade(s.p(), "WHT", expiry(s), 20, s.today() + 0.2, s.prog()).isEmpty(), "20 lots on 15% margin");

        BlockPos tPos = new BlockPos(1, 1, 1), hPos = new BlockPos(3, 1, 1);
        helper.setBlock(tPos, ModBlocks.RECORDS_TERMINAL);
        helper.setBlock(hPos, ModBlocks.CLEARING_HOUSE);
        RecordsTerminalBlockEntity terminal = helper.getBlockEntity(tPos, RecordsTerminalBlockEntity.class);
        terminal.setOwner(s.p());
        helper.getBlockEntity(hPos, ClearingHouseBlockEntity.class).setOwner(s.p());
        ItemStack tool = new ItemStack(ModItems.RECORD_LINK);
        RecordLinkItem.use(s.p(), helper.getLevel(), helper.absolutePos(tPos), tool);
        check(RecordLinkItem.use(s.p(), helper.getLevel(), helper.absolutePos(hPos), tool).startsWith("Linked"), "link the Clearing House");
        RecordsService records = RecordsService.forTest(new RecordsService.Sources(s.dealer(), s.prog(), null, null, null, null, null,
                s.futures(), null));
        var view = records.view(terminal, s.p().getUUID(), s.today() + 0.3);
        check(view.netWorth().byKind().get(NetWorth.Kind.FUTURES) == s.futures().house().equity(a, s.today() + 0.3),
                "the terminal counts the futures account at its equity");

        s.dealer().dealer().sell(WHEAT, 6_000, s.today() + 0.5, false); // a crash
        check(s.futures().dawn(s.today() + 1, id -> s.p(), s.prog()).get(0).called(), "called");
        var second = s.futures().dawn(s.today() + 2, id -> s.p(), s.prog());
        check(second.get(0).closedOut(), "not met: closed out at the next dawn");
        check(s.futures().house().account(a).positions().isEmpty(), "no positions left");
        long cash = s.futures().house().account(a).cashCents();
        if (cash < 0) {
            check(s.futures().trade(s.p(), "WHT", ClearingHouse.expiries(s.today() + 2)[1], 1, s.today() + 2.2, s.prog()).isPresent(),
                    "a debt blocks new positions");
            var debt = records.view(terminal, s.p().getUUID(), s.today() + 2.2);
            check(debt.netWorth().debts() == -cash, "and shows as a debt on the terminal");
        }
        helper.succeed();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
