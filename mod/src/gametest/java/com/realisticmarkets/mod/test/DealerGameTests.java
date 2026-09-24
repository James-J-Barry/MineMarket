package com.realisticmarkets.mod.test;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.menu.BasicExchangeMenu;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Denomination;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * M1 acceptance tests that need real Minecraft items and registries.
 * Pure pricing math is covered by exchange-core's DealerTest; these check the wiring.
 */
public class DealerGameTests {

    @GameTest
    public void sixtyFourWheatPaysTwentyFiveFortyInBills(GameTestHelper helper) {
        DealerService svc = DealerService.forTest(1234L);
        ItemStack wheat = new ItemStack(Items.WHEAT, 64);

        long cents = svc.sellStack(wheat, 0.0, false);

        check(cents == 2540, "expected $25.40, got " + cents + " cents");
        check(wheat.isEmpty(), "the sold stack should be consumed");

        List<ItemStack> bills = Wallet.toStacks(cents);
        check(countOf(bills, Denomination.TEN) == 2, "expected two $10 bills");
        check(countOf(bills, Denomination.ONE) == 5, "expected five $1 bills");
        check(countOf(bills, Denomination.DIME) == 4, "expected four dimes");
        check(countOf(bills, Denomination.HUNDRED) == 0, "expected no $100 bills");
        helper.succeed();
    }

    @GameTest
    public void compressedBlocksShareThePool(GameTestHelper helper) {
        DealerService svc = DealerService.forTest(1234L);
        svc.sellStack(new ItemStack(Items.IRON_BLOCK, 10), 0.0, false);
        double inventory = svc.dealer().inventory("minecraft:iron_ingot", 0.0);
        check(Math.abs(inventory - 90.0) < 1e-9, "10 iron blocks should add 90 ingots to the pool, got " + inventory);
        helper.succeed();
    }

    @GameTest
    public void currencyIsRegistered(GameTestHelper helper) {
        for (Denomination d : Denomination.values()) {
            check(ModItems.CURRENCY.get(d) != null, d + " not registered");
            check(ModItems.denominationOf(new ItemStack(ModItems.CURRENCY.get(d))) == d, d + " not recognized as money");
        }
        check(ModItems.denominationOf(new ItemStack(Items.EMERALD)) == null, "emeralds are not money");
        helper.succeed();
    }


    @GameTest
    public void exchangeScreenSellsIntoDenominationSlots(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BasicExchangeMenu menu = new BasicExchangeMenu(1, player.getInventory(), ContainerLevelAccess.NULL,
                DealerService.forTest(1234L));

        menu.getSlot(BasicExchangeMenu.INPUT).set(new ItemStack(Items.WHEAT, 64));
        menu.broadcastChanges(); // server recomputes and syncs the quote
        check(menu.status() == BasicExchangeMenu.STATUS_OK, "expected OK status, got " + menu.status());
        check(menu.quoteCents() == 2540, "expected a $25.40 quote, got " + menu.quoteCents());

        check(menu.clickMenuButton(player, BasicExchangeMenu.BUTTON_SELL), "Sell should succeed");
        check(menu.getSlot(BasicExchangeMenu.INPUT).getItem().isEmpty(), "input should be consumed");
        check(countIn(menu, 0, Denomination.DIME) == 4, "expected 4 dimes in the first payout slot");
        check(countIn(menu, 1, Denomination.ONE) == 5, "expected five $1 bills in the second payout slot");
        check(countIn(menu, 2, Denomination.TEN) == 2, "expected two $10 bills in the third payout slot");
        check(menu.getSlot(BasicExchangeMenu.OUTPUT_START + 3).getItem().isEmpty(), "no $100 bills expected");

        // Currency can't be sold back, and the Sell button does nothing on an empty table
        menu.getSlot(BasicExchangeMenu.INPUT).set(new ItemStack(ModItems.CURRENCY.get(Denomination.ONE), 3));
        menu.broadcastChanges();
        check(menu.status() == BasicExchangeMenu.STATUS_MONEY, "money should be refused");
        check(!menu.clickMenuButton(player, BasicExchangeMenu.BUTTON_SELL), "selling money must fail");
        helper.succeed();
    }

    @GameTest
    public void exchangeBuyTabBuysWithCashAndGivesChange(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        DealerService svc = DealerService.forTest(1234L);
        Wallet.give(player, 10_000); // $100.00 in bills
        BasicExchangeMenu menu = new BasicExchangeMenu(2, player.getInventory(), ContainerLevelAccess.NULL, svc);

        int wheat = menu.indexOf("minecraft:wheat");
        check(wheat >= 0, "wheat should be in the buy list");
        check(menu.itemCount() > 10, "buy list should show the catalog, got " + menu.itemCount());
        check(menu.clickMenuButton(player, BasicExchangeMenu.BUTTON_TAB_BUY), "switch to Buy tab");
        check(menu.clickMenuButton(player, BasicExchangeMenu.BUTTON_SELECT_BASE + wheat), "select wheat");
        long expected = menu.buyTotalCents(1); // x16
        check(expected > 0 && expected < 10_000, "16 wheat should cost under $100, got " + expected);
        check(menu.buyNormalMills(wheat) == 500, "wheat normal price should be $0.50");
        check(menu.buySellsMills(wheat) == 550, "wheat ask should be $0.55, got " + menu.buySellsMills(wheat));

        check(menu.clickMenuButton(player, BasicExchangeMenu.BUTTON_BUY_FIRST + 1), "buy 16 should succeed");
        check(player.getInventory().countItem(Items.WHEAT) == 16, "should now hold 16 wheat");
        check(Wallet.count(player.getInventory()) == 10_000 - expected, "change should be exact");
        check(menu.buySellsMills(wheat) > 550, "buying should push the ask up");
        helper.succeed();
    }

    @GameTest
    public void everyCatalogItemExistsAndFitsTheBuyList(GameTestHelper helper) {
        DealerService svc = DealerService.forTest(1234L);
        for (String id : svc.dealer().catalog().all().keySet()) {
            check(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)) != Items.AIR, "catalog item " + id + " is not a real item");
        }
        int size = svc.dealer().catalog().all().size();
        check(size <= BasicExchangeMenu.MAX_ITEMS, size + " catalog items exceed the Buy tab limit of " + BasicExchangeMenu.MAX_ITEMS);
        helper.succeed();
    }

    private static int countIn(BasicExchangeMenu menu, int outputIndex, Denomination d) {
        ItemStack s = menu.getSlot(BasicExchangeMenu.OUTPUT_START + outputIndex).getItem();
        return ModItems.denominationOf(s) == d ? s.getCount() : 0;
    }

    private static long countOf(List<ItemStack> stacks, Denomination d) {
        return stacks.stream().filter(s -> ModItems.denominationOf(s) == d).mapToLong(ItemStack::getCount).sum();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
