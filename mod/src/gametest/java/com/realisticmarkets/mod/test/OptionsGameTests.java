package com.realisticmarkets.mod.test;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.menu.OptionsDeskMenu;
import com.realisticmarkets.mod.options.OptionPapers;
import com.realisticmarkets.mod.options.OptionsService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.options.OptionDesk;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;

/** M9a: the Options Desk. Buying, selling back, settlement at expiry, Insured and Long Shot. */
public class OptionsGameTests {
    static final String WHEAT = "minecraft:wheat";

    record Setup(ServerPlayer p, DealerService dealer, ProgressionService prog, OptionsService options, OptionsDeskMenu menu) {}

    static Setup setup(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        DealerService dealer = DealerService.forTestLive(1234L);
        ProgressionService prog = ProgressionService.forTest();
        ForwardGameTests.unlock(p, prog, 6, "options_desk");
        OptionsService options = OptionsService.forTest(dealer, null, null);
        OptionsDeskMenu m = new OptionsDeskMenu(1, p.getInventory(), ContainerLevelAccess.NULL, options, prog, dealer);
        return new Setup(p, dealer, prog, options, m);
    }

    static double now(GameTestHelper helper, Setup s) {
        return s.dealer().day(helper.getLevel().getGameTime());
    }

    @GameTest
    public void buyingPaysOutPapersAtTheAskAndSellingBackPaysTheBid(GameTestHelper helper) {
        Setup s = setup(helper);
        OptionsDeskMenu m = s.menu();
        check(m.owner() && s.prog().progress(s.p()).hasGuide("options"), "unlocked, with the Options guide");
        check(m.underlyingCode().equals("WHT") && m.call(), "wheat calls to start");
        m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_QTY_BASE + 2); // +1: 2 contracts
        long cost = m.cost();
        check(cost > 0 && cost >= 2 * m.fair() * 1.04 - 20, "2 at fair +4% at least: " + cost + " vs fair " + m.fair());
        check(!m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_BUY), "no cash, no options");
        Wallet.give(s.p(), cost + 1_000);
        check(m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_BUY), "buy 2 calls");
        check(Wallet.count(s.p().getInventory()) == 1_000, "paid the quoted cost");
        check(s.p().getInventory().countItem(ModItems.OPTION_CONTRACT) == 2, "two Option Contract papers");
        OptionDesk.Series series = OptionPapers.read(s.p().getInventory().getItem(find(s.p()))).orElseThrow();
        check(series.call() && series.underlying().equals("WHT") && series.expiry() == OptionDesk.expiries(m.day())[0], "the terms");

        m.clickMenuButton(s.p(), OptionsDeskMenu.TAB_HOLDINGS);
        check(m.holdingCount() == 1 && m.holdingContracts(0) == 2, "Holdings shows them");
        String a = OptionsService.account(s.p());
        double day = now(helper, s);
        long bids = s.options().desk().bid(a, series, day);
        s.options().desk().recordTrade(a, series, -1, day);
        bids += s.options().desk().bid(a, series, day);
        s.options().desk().recordTrade(a, series, 1, day);
        check(m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_CLOSE_BASE), "sell back");
        check(s.p().getInventory().countItem(ModItems.OPTION_CONTRACT) == 0, "papers handed in");
        check(Wallet.count(s.p().getInventory()) == 1_000 + bids / 10 * 10, "paid the bid for each: " + Wallet.count(s.p().getInventory()));
        check(bids < cost, "a round trip costs the spread");
        helper.succeed();
    }

    @GameTest
    public void atExpiryAnInTheMoneyPutPaysAndIsInsuredAndALongShot(GameTestHelper helper) {
        Setup s = setup(helper);
        OptionsDeskMenu m = s.menu();
        double day = now(helper, s);
        long expiry = OptionDesk.expiries((long) Math.floor(day))[0];
        m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_PUT);
        m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_STRIKE_BASE + 1); // 90% strike: cheap
        Wallet.give(s.p(), m.cost());
        check(m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_BUY), "buy a 90% put");
        m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_CALL);
        Wallet.give(s.p(), m.cost());
        check(m.clickMenuButton(s.p(), OptionsDeskMenu.BUTTON_BUY), "and a 90% call");
        long paidForPut = 0;
        for (var h : s.options().holdings(s.p(), day)) if (!h.series().call()) paidForPut = Math.round(s.options().averageCost(
                OptionsService.account(s.p()), h.series()));

        s.dealer().dealer().sell(WHEAT, 6_000, day + 0.1, false); // a glut: wheat collapses
        s.dealer().shiftDays(expiry - Math.floor(day) + 0.2);
        s.options().dawn(expiry);
        double after = now(helper, s);
        var settle = s.options().desk().settlement("WHT", expiry);
        check(settle.isPresent(), "settled at the expiry dawn");
        m.clickMenuButton(s.p(), OptionsDeskMenu.TAB_HOLDINGS);
        check(m.holdingCount() == 2, "both papers show");
        long before = Wallet.count(s.p().getInventory());
        long collected = s.options().collectExpired(s.p(), after, s.prog());
        check(s.p().getInventory().countItem(ModItems.OPTION_CONTRACT) == 0, "both handed in");
        long payout = collected - rewards(s); // quest rewards land in the same wallet
        check(payout > 0, "the put pays: " + payout);
        check(s.prog().progress(s.p()).hasCompleted("insured"), "Insured");
        check(s.prog().progress(s.p()).hasGuide("the_greeks"), "and its guide, The Greeks");
        check(s.prog().progress(s.p()).hasCompleted("long_shot") == (payout >= 3 * paidForPut),
                "Long Shot exactly when the put paid 3x: " + payout + " for " + paidForPut);
        check(Wallet.count(s.p().getInventory()) - before == collected, "paid in bills");
        helper.succeed();
    }

    static long rewards(Setup s) {
        long r = 0;
        if (s.prog().progress(s.p()).hasCompleted("insured")) r += 10_000;
        if (s.prog().progress(s.p()).hasCompleted("long_shot")) r += 15_000;
        return r;
    }

    static int find(ServerPlayer p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).is(ModItems.OPTION_CONTRACT)) return i;
        }
        throw new IllegalStateException("no option paper");
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
