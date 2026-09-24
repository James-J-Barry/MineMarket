package com.realisticmarkets.mod.test;

import com.realisticmarkets.agents.TradingFloor;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.floor.FloorService;
import com.realisticmarkets.mod.menu.DraftingTableMenu;
import com.realisticmarkets.mod.menu.TradingFloorMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Denomination;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** M5a acceptance tests: placing, filling, expiring, cancelling and collecting Trading Floor orders. */
public class FloorGameTests {
    static final String WHEAT = "minecraft:wheat";

    record Pit(FloorService floor, DealerService dealer, ProgressionService prog, TradingFloorMenu menu, double day) {}

    static Pit pit(GameTestHelper helper, ServerPlayer p) {
        DealerService dealer = DealerService.forTest(1234L);
        FloorService floor = FloorService.forTest(dealer, 99L);
        ProgressionService prog = ProgressionService.forTest();
        TradingFloorMenu menu = new TradingFloorMenu(1, p.getInventory(), ContainerLevelAccess.NULL, floor, prog, dealer);
        check(menu.clickMenuButton(p, TradingFloorMenu.BUTTON_BOOK_BASE + book(WHEAT)), "select wheat");
        return new Pit(floor, dealer, prog, menu, dealer.day(helper.getLevel().getGameTime()));
    }

    static int book(String item) {
        for (int i = 0; i < TradingFloorMenu.BOOKS.size(); i++) if (TradingFloorMenu.BOOKS.get(i).item().equals(item)) return i;
        throw new IllegalStateException("no book " + item);
    }

    static void press(Pit pit, ServerPlayer p, int button, int times) {
        for (int i = 0; i < times; i++) pit.menu().clickMenuButton(p, button);
    }

    /** Runs auctions until the player has no open orders (at most {@code max}). */
    static void auctionUntilDone(Pit pit, ServerPlayer p, int max) {
        for (int i = 0; i < max && !pit.floor().floor().openTickets(FloorService.account(p)).isEmpty(); i++) {
            pit.floor().runAuctions(pit.day());
        }
    }

    static void collect(Pit pit, ServerPlayer p) {
        pit.floor().deliver(p, pit.menu().output(), pit.prog());
    }

    static long cash(Container c) {
        long cents = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            Denomination d = ModItems.denominationOf(c.getItem(i));
            if (d != null) cents += d.cents() * c.getItem(i).getCount();
        }
        return cents;
    }

    static int count(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    static CompoundTag receipt(Container c) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (c.getItem(i).is(ModItems.TRADE_RECEIPT)) {
                return c.getItem(i).getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            }
        }
        throw new IllegalStateException("no Trade Receipt in the output");
    }

    @GameTest
    public void sellOrderEscrowsFillsAndPaysIntoTheOutput(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getInventory().add(new ItemStack(Items.WHEAT, 20));
        p.getInventory().add(new ItemStack(ModItems.ORDER_SLIP, 2));
        Pit pit = pit(helper, p);

        press(pit, p, TradingFloorMenu.BUTTON_SIDE, 1);
        press(pit, p, TradingFloorMenu.BUTTON_PRICE_MINUS_10PCT, 2); // well under the bid: should cross
        check(pit.menu().selling() && pit.menu().qty() == 16, "sell 16");
        check(pit.menu().clickMenuButton(p, TradingFloorMenu.BUTTON_PLACE), "place the sell");
        check(p.getInventory().countItem(Items.WHEAT) == 4, "16 wheat escrowed, 4 left");
        check(p.getInventory().countItem(ModItems.ORDER_SLIP) == 1, "one slip spent");
        check(pit.menu().orderCount() == 1, "one open order shown");

        auctionUntilDone(pit, p, 10);
        check(pit.floor().floor().openTickets(FloorService.account(p)).isEmpty(), "sell filled against the NPCs");
        collect(pit, p);
        CompoundTag r = receipt(pit.menu().output());
        long filled = r.getLongOr("cents", 0);
        long got = cash(pit.menu().output());
        check(r.getLongOr("qty", 0) == 16, "receipt: 16 sold, got " + r);
        check(got > 0 && got <= filled && filled - got < 10, "bills in the output: " + got + " of " + filled + " cents");
        check(filled >= 16 * pit.menu().priceCents(), "no worse than the limit");
        check(!pit.floor().hasWaiting(p), "nothing left waiting");
        check(pit.prog().progress(p).hasCompleted("name_your_price"), "a filled limit completes Name Your Price");
        check(pit.prog().progress(p).hasGuide("liquidity_and_market_makers"), "and grants the Liquidity guide");
        helper.succeed();
    }

    @GameTest
    public void lowBuyLimitRestsAndExpiresAtDawnWithFullRefund(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Wallet.give(p, 10_000);
        p.getInventory().add(new ItemStack(ModItems.ORDER_SLIP));
        Pit pit = pit(helper, p);

        press(pit, p, TradingFloorMenu.BUTTON_QTY_MINUS_1, 12); // 4
        press(pit, p, TradingFloorMenu.BUTTON_PRICE_MINUS_10PCT, 6); // about half the market
        check(pit.menu().clickMenuButton(p, TradingFloorMenu.BUTTON_PLACE), "place the buy");
        long escrow = 4 * pit.menu().priceCents();
        long after = Wallet.count(p.getInventory());
        check(10_000 - after >= escrow && 10_000 - after - escrow < 10, "qty x limit escrowed: " + (10_000 - after));

        for (int i = 0; i < 5; i++) pit.floor().runAuctions(pit.day());
        check(pit.floor().floor().openTickets(FloorService.account(p)).size() == 1, "still resting below the market");
        collect(pit, p);
        check(cash(pit.menu().output()) == 0, "nothing to collect while resting");

        pit.floor().dawn((long) Math.floor(pit.day()) + 1);
        check(pit.floor().floor().openTickets(FloorService.account(p)).isEmpty(), "expired at dawn");
        collect(pit, p);
        CompoundTag r = receipt(pit.menu().output());
        check(r.getLongOr("qty", -1) == 0, "receipt: nothing bought");
        long total = after + cash(pit.menu().output());
        check(total > 10_000 - 10 && total <= 10_000, "full refund (to the dime): " + total);
        helper.succeed();
    }

    @GameTest
    public void marketOrderFinishesInOneAuction(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Wallet.give(p, 10_000);
        p.getInventory().add(new ItemStack(ModItems.ORDER_SLIP));
        Pit pit = pit(helper, p);

        press(pit, p, TradingFloorMenu.BUTTON_MARKET, 1);
        press(pit, p, TradingFloorMenu.BUTTON_QTY_MINUS_1, 8); // 8
        check(pit.menu().clickMenuButton(p, TradingFloorMenu.BUTTON_PLACE), "place the market buy");
        pit.floor().runAuctions(pit.day());
        check(pit.floor().floor().openTickets(FloorService.account(p)).isEmpty(), "market order filled or refunded at once");

        collect(pit, p);
        CompoundTag r = receipt(pit.menu().output());
        long bought = r.getLongOr("qty", 0), spent = r.getLongOr("cents", 0);
        check(count(pit.menu().output(), Items.WHEAT) == bought, "the wheat bought is in the output");
        check(bought > 0, "a liquid book fills a small market order");
        long total = Wallet.count(p.getInventory()) + cash(pit.menu().output());
        check(Math.abs(10_000 - spent - total) < 20, "paid the fill, the rest refunded: " + total + " after spending " + spent);
        helper.succeed();
    }

    @GameTest
    public void cancellingReturnsEverything(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getInventory().add(new ItemStack(Items.WHEAT, 16));
        p.getInventory().add(new ItemStack(ModItems.ORDER_SLIP));
        Pit pit = pit(helper, p);

        press(pit, p, TradingFloorMenu.BUTTON_SIDE, 1);
        press(pit, p, TradingFloorMenu.BUTTON_PRICE_PLUS_10PCT, 8); // far above the market
        check(pit.menu().clickMenuButton(p, TradingFloorMenu.BUTTON_PLACE), "place the sell");
        check(!pit.menu().clickMenuButton(p, TradingFloorMenu.BUTTON_PLACE), "second order needs another slip");
        pit.floor().runAuctions(pit.day());
        check(pit.menu().clickMenuButton(p, TradingFloorMenu.BUTTON_CANCEL_BASE), "cancel");
        check(pit.menu().orderCount() == 0, "no open orders");
        CompoundTag r = receipt(pit.menu().output()); // cancel refreshes the menu, which delivers
        check(r.getLongOr("qty", -1) == 0, "receipt: nothing sold");
        check(count(pit.menu().output(), Items.WHEAT) == 16, "all 16 wheat back");
        helper.succeed();
    }

    @GameTest
    public void receiptRecordsTheAveragePrice(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getInventory().add(new ItemStack(Items.WHEAT, 32));
        p.getInventory().add(new ItemStack(ModItems.ORDER_SLIP));
        Pit pit = pit(helper, p);

        press(pit, p, TradingFloorMenu.BUTTON_SIDE, 1);
        press(pit, p, TradingFloorMenu.BUTTON_MARKET, 1);
        press(pit, p, TradingFloorMenu.BUTTON_QTY_PLUS_16, 1); // 32
        check(pit.menu().clickMenuButton(p, TradingFloorMenu.BUTTON_PLACE), "place the market sell");
        pit.floor().runAuctions(pit.day());
        collect(pit, p);
        ItemStack slip = ItemStack.EMPTY;
        for (int i = 0; i < pit.menu().output().getContainerSize(); i++) {
            if (pit.menu().output().getItem(i).is(ModItems.TRADE_RECEIPT)) slip = pit.menu().output().getItem(i);
        }
        CompoundTag r = slip.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        long qty = r.getLongOr("qty", 0), cents = r.getLongOr("cents", 0);
        check(qty > 0, "something sold");
        TradingFloor.Receipt rec = new TradingFloor.Receipt("", WHEAT, com.realisticmarkets.exchange.Side.SELL, true, 32, qty,
                cents, 0, 0, TradingFloor.Ending.FILLED);
        String avg = com.realisticmarkets.money.Money.format(rec.avgMills() / 10);
        String lore = slip.get(DataComponents.LORE).lines().get(1).getString();
        check(lore.startsWith("Average $") && lore.contains(com.realisticmarkets.money.Money.format(cents)),
                "lore shows the average and total: " + lore + " (avg about " + avg + ")");
        helper.succeed();
    }

    @GameTest
    public void orderSlipBlueprintMakesEight(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        ProgressionService prog = ProgressionService.forTest();
        Wallet.give(p, 561_500); // bill clip + price board + vault + loan note + trading floor
        for (String node : new String[] {"bill_clip", "price_board", "bank_vault", "loan_note", "trading_floor"}) {
            check(prog.buyNode(p, node).isEmpty(), "buy " + node);
        }
        p.getInventory().add(new ItemStack(ModItems.LEDGER_PAPER, 2));
        DraftingTableMenu menu = new DraftingTableMenu(1, p.getInventory(), ContainerLevelAccess.NULL, prog);
        int slip = -1;
        for (int i = 0; i < DraftingTableMenu.BLUEPRINTS.size(); i++) {
            if (DraftingTableMenu.BLUEPRINTS.get(i).result().equals("realisticmarkets:order_slip")) slip = i;
        }
        check(slip >= 0 && menu.unlocked(slip), "Order Slip blueprint unlocked");
        check(prog.progress(p).hasGuide("order_books") && prog.progress(p).hasGuide("limit_and_market_orders"),
                "the Trading Floor node grants Order Books and Limit and Market Orders");
        menu.clickMenuButton(p, DraftingTableMenu.BUTTON_SELECT_BASE + slip);
        check(menu.clickMenuButton(p, DraftingTableMenu.BUTTON_CRAFT_MAX), "craft");
        ItemStack out = menu.getSlot(DraftingTableMenu.OUTPUT).getItem();
        check(out.is(ModItems.ORDER_SLIP) && out.getCount() == 16, "2 Ledger Paper make 16 slips, got " + out);
        helper.succeed();
    }

    @GameTest
    public void worldEventsShowOnTheFloorAndMoveTheDealer(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        DealerService dealer = DealerService.forTest(1234L);
        dealer.useEvents(77L);
        FloorService floor = FloorService.forTest(dealer, 99L);
        com.realisticmarkets.dealer.WorldEvents ev = dealer.events();
        long day = 1;
        while (ev.startingOn(day).isEmpty()) day++;
        com.realisticmarkets.dealer.WorldEvents.Event e = ev.startingOn(day).getFirst();
        double now = dealer.day(helper.getLevel().getGameTime());
        dealer.shiftDays(day + 0.5 - now); // the middle of the event's first day
        TradingFloorMenu menu = new TradingFloorMenu(1, p.getInventory(), ContainerLevelAccess.NULL, floor,
                ProgressionService.forTest(), dealer);
        check(menu.newsType(0) == e.type().index() && menu.newsAge(0) == 0, "today's headline is first: " + e.type().id()
                + ", menu shows " + menu.newsType(0));
        String item = ev.affects(e.type()).iterator().next();
        double moved = dealer.dealer().fairValue(item, day + 0.5) / dealer.dealer().fairValue(item, day);
        check(Math.signum(moved - 1) == Math.signum(e.type().shock()) && Math.abs(moved - 1) > 0.1,
                e.type().id() + " moved " + item + " by " + moved);
        helper.succeed();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
