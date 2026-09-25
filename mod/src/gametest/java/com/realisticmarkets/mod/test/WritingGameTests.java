package com.realisticmarkets.mod.test;

import com.realisticmarkets.mod.block.RecordsTerminalBlockEntity;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.futures.FuturesService;
import com.realisticmarkets.mod.menu.OptionsDeskMenu;
import com.realisticmarkets.mod.menu.RecordsTerminalMenu;
import com.realisticmarkets.mod.menu.VolatilityBoardMenu;
import com.realisticmarkets.mod.options.OptionsService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.records.RecordsService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Denomination;
import com.realisticmarkets.options.OptionDesk;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** M9b: writing options against collateral, the Volatility Board, the Risk Report Module. */
public class WritingGameTests {
    static final String WHEAT = "minecraft:wheat";

    record Setup(ServerPlayer p, DealerService dealer, ProgressionService prog, OptionsService options, OptionsDeskMenu menu) {}

    static Setup setup(GameTestHelper helper, String... nodes) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        DealerService dealer = DealerService.forTestLive(1234L);
        ProgressionService prog = ProgressionService.forTest();
        ForwardGameTests.unlock(p, prog, 6, nodes);
        OptionsService options = OptionsService.forTest(dealer, null, null);
        OptionsDeskMenu m = new OptionsDeskMenu(1, p.getInventory(), ContainerLevelAccess.NULL, options, prog, dealer);
        return new Setup(p, dealer, prog, options, m);
    }

    static void tick(OptionsDeskMenu m) {
        for (int i = 0; i < 20; i++) m.broadcastChanges();
    }

    @GameTest
    public void aCoveredCallEarnsTheFullPremiumAndIsCalledAway(GameTestHelper helper) {
        Setup s = setup(helper, "options_desk");
        OptionsDeskMenu m = s.menu();
        m.clickMenuButton(s.p(), OptionsDeskMenu.TAB_WRITE);
        m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_STRIKE_BASE + 2); // at the money
        for (int i = 0; i < 4; i++) m.collateralSlots().setItem(i, new ItemStack(Items.WHEAT, 64));
        tick(m);
        check(m.writeCovered() == 1.0 && m.writeQuality() == 1.0, "256 wheat covers one wheat call: " + m.writeCovered());
        long premium = m.writePremium();
        OptionDesk.Series series = m.selected();
        double day = s.dealer().day(helper.getLevel().getGameTime());
        long full = (long) Math.floor(s.options().desk().fair(series, day) * (1 - OptionDesk.HALF_SPREAD) / 10) * 10;
        check(Math.abs(premium - full) <= 10, "the full premium: " + premium + " vs " + full);
        check(m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_WRITE), "write it");
        check(Wallet.count(s.p().getInventory()) == premium + 10_000, "the premium is paid in bills (and Covered Call's $100)");
        check(m.collateralSlots().isEmpty(), "the wheat went into escrow");
        check(s.prog().progress(s.p()).hasCompleted("covered_call"), "Covered Call");
        check(s.prog().progress(s.p()).hasGuide("covered_and_naked"), "and its guide");

        s.dealer().dealer().buy(WHEAT, 4_000, day + 0.1, false); // wheat runs up: the call ends in the money
        s.dealer().shiftDays(series.expiry() - Math.floor(day) + 0.2);
        s.options().dawn(series.expiry(), id -> s.p(), s.prog());
        check(s.options().written().open(OptionsService.account(s.p())).isEmpty(), "settled at expiry");
        m.clickMenuButton(s.p(), OptionsDeskMenu.TAB_HOLDINGS);
        check(m.returnCash() >= series.strikeCents() - 10 && m.returnItems() == 0, "called away: the strike, no wheat: " + m.returnCash());
        long before = Wallet.count(s.p().getInventory());
        check(m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_COLLECT_RETURNS), "collect");
        check(Wallet.count(s.p().getInventory()) - before >= series.strikeCents() - 10, "the strike in bills");
        check(s.p().getInventory().countItem(Items.WHEAT) == 0, "the wheat is gone for good");
        helper.succeed();
    }

    @GameTest
    public void aNakedPutPaysLessWithWeakCollateralAndOneExpiringWorthlessKeepsItAll(GameTestHelper helper) {
        Setup s = setup(helper, "options_desk");
        OptionsDeskMenu m = s.menu();
        m.clickMenuButton(s.p(), OptionsDeskMenu.TAB_WRITE);
        m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_PUT);
        m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_STRIKE_BASE + 1); // 90%: likely to expire worthless
        for (int i = 0; i < 9; i++) m.collateralSlots().setItem(i, new ItemStack(ModItems.CURRENCY.get(Denomination.HUNDRED), 10));
        tick(m);
        long cashPremium = m.writePremium();
        check(Math.abs(m.writeQuality() - 0.9) < 1e-3, "cash collateral: quality 0.90");
        m.collateralSlots().clearContent();
        for (int i = 0; i < 9; i++) m.collateralSlots().setItem(i, new ItemStack(Items.OAK_LOG, 64));
        tick(m);
        check(m.writePremium() < cashPremium, "oak logs pay less than cash: " + m.writePremium() + " vs " + cashPremium);
        check(m.writeQuality() < 0.6, "logs are weak collateral: " + m.writeQuality());
        m.collateralSlots().clearContent();
        for (int i = 0; i < 9; i++) m.collateralSlots().setItem(i, new ItemStack(ModItems.CURRENCY.get(Denomination.HUNDRED), 10));
        tick(m);
        OptionDesk.Series series = m.selected();
        check(m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_WRITE), "write a naked put on $9,000 of cash");
        long premium = Wallet.count(s.p().getInventory());
        check(premium > 0, "premium paid");

        double day = s.dealer().day(helper.getLevel().getGameTime());
        s.dealer().dealer().buy(WHEAT, 800, day + 0.1, false); // wheat holds up: the put expires worthless
        s.dealer().shiftDays(series.expiry() - Math.floor(day) + 0.2);
        s.options().dawn(series.expiry(), id -> s.p(), s.prog());
        check(s.prog().progress(s.p()).hasCompleted("premium_collector"), "Premium Collector: it expired worthless");
        var back = s.options().written().waiting(OptionsService.account(s.p()));
        check(back.cashCents() == 900_000, "all the collateral comes back: " + back.cashCents());
        helper.succeed();
    }

    @GameTest
    public void theVolatilityBoardDrawsTheSmile(GameTestHelper helper) {
        Setup s = setup(helper, "options_desk", "volatility_board");
        VolatilityBoardMenu v = new VolatilityBoardMenu(1, s.p().getInventory(), ContainerLevelAccess.NULL, s.options(), s.prog(), s.dealer());
        check(v.owner() && s.prog().progress(s.p()).hasGuide("volatility"), "unlocked, with the Volatility guide");
        int mid = VolatilityBoardMenu.LEVELS.length / 2;
        check(v.smileBp(mid) == v.realizedBp(), "at the money: realized volatility");
        check(v.smileBp(0) > v.smileBp(mid) && v.smileBp(VolatilityBoardMenu.LEVELS.length - 1) > v.smileBp(mid), "a smile");
        check(v.atm(1) > v.atm(0), "more time, more value");
        check(v.clickMenuButton(s.p(), 5), "switch to diamonds");
        check(v.underlying() == 5, "diamonds");
        helper.succeed();
    }

    @GameTest
    public void theRiskTabShowsCoverAndExposure(GameTestHelper helper) {
        Setup s = setup(helper, "digital_record_keeping", "forward_contract", "clearing_house", "risk_report_module");
        FuturesService futures = FuturesService.forTest(s.dealer());
        double day = s.dealer().day(helper.getLevel().getGameTime());
        Wallet.give(s.p(), 500_000);
        futures.depositAll(s.p(), day, s.prog());
        long expiry = com.realisticmarkets.futures.ClearingHouse.expiries((long) Math.floor(day))[1];
        check(futures.trade(s.p(), "WHT", expiry, 2, day, s.prog()).isEmpty(), "long 2 wheat lots");
        BlockPos tPos = new BlockPos(1, 1, 1);
        helper.setBlock(tPos, ModBlocks.RECORDS_TERMINAL);
        RecordsTerminalBlockEntity terminal = helper.getBlockEntity(tPos, RecordsTerminalBlockEntity.class);
        terminal.setOwner(s.p());
        RecordsService records = RecordsService.forTest(new RecordsService.Sources(s.dealer(), s.prog(), null, null, null, null, null,
                futures, s.options()));
        RecordsTerminalMenu menu = new RecordsTerminalMenu(1, s.p().getInventory(), ContainerLevelAccess.NULL, terminal, records);
        check(!menu.riskModule() && !menu.clickMenuButton(s.p(), RecordsTerminalMenu.TAB_RISK), "no module, no Risk tab");
        terminal.installRiskModule();
        check(menu.clickMenuButton(s.p(), RecordsTerminalMenu.TAB_RISK), "fitted: the Risk tab opens");
        check(menu.exposureUnits(0) == 512, "2 wheat lots: like holding 512 wheat: " + menu.exposureUnits(0));
        check(menu.exposureStress(0) < 0, "a 20% fall costs money: " + menu.exposureStress(0));
        check(menu.coverCount() == 1 && menu.coverKind(0) == RecordsTerminalMenu.COVER_FUTURES, "the futures account's cover");
        check(menu.coverRatioPct(0) > 100 && menu.coverCushionPermille(0) > 0, "comfortably covered: " + menu.coverRatioPct(0));
        helper.succeed();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
