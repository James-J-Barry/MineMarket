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
    public void orderSlipBlueprintMakesThirtyTwo(GameTestHelper helper) {
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
        check(out.is(ModItems.ORDER_SLIP) && out.getCount() == 64, "2 Ledger Paper make 64 slips, got " + out);
        helper.succeed();
    }

    @GameTest
    public void newsstandShowsTheNewsBeforeTheMarketMoves(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        DealerService dealer = DealerService.forTest(1234L);
        dealer.useEvents(77L);
        ProgressionService prog = ProgressionService.forTest();
        Wallet.give(p, 661_500); // bill clip + price board + vault + loan note + newsstand ($1,000)
        for (String node : new String[] {"bill_clip", "price_board", "bank_vault", "loan_note", "newsstand"}) {
            check(prog.buyNode(p, node).isEmpty(), "buy " + node);
        }
        check(prog.progress(p).hasGuide("news_and_markets"), "the Newsstand grants its guide");
        com.realisticmarkets.dealer.WorldEvents ev = dealer.events();
        long day = 5;
        while (ev.startingOn(day).isEmpty()) day++;
        com.realisticmarkets.dealer.WorldEvents.Event e = ev.startingOn(day).getFirst();
        dealer.shiftDays(day + 0.05 - dealer.day(helper.getLevel().getGameTime())); // just after dawn

        var board = new com.realisticmarkets.mod.menu.NewsstandMenu(1, p.getInventory(), ContainerLevelAccess.NULL, prog, dealer);
        check(board.owner() && board.day() == day, "the owner reads day " + day);
        check(board.storyCount() >= 1 && board.storyType(0) == e.type().index() && board.storyAge(0) == 0,
                "today's story first: " + e.type().id());
        int shown = 0;
        for (long d = day; d > day - 3; d--) shown += ev.startingOn(d).size();
        check(board.storyCount() == Math.min(shown, com.realisticmarkets.mod.menu.NewsstandMenu.MAX_STORIES), "the last 3 days' stories");
        var theirs = new com.realisticmarkets.mod.menu.NewsstandMenu(2, stranger.getInventory(), ContainerLevelAccess.NULL, prog, dealer);
        check(!theirs.owner() && theirs.storyCount() == 0, "no news without the upgrade");

        String item = ev.affects(e.type()).iterator().next();
        double dawn = dealer.dealer().fairValue(item, day + 0.05);
        double beforeHeard = dealer.dealer().fairValue(item, day + e.delay() - 0.02);
        double evening = dealer.dealer().fairValue(item, day + 0.95);
        check(Math.abs(beforeHeard / dawn - 1) < 0.03, "a head start: prices still ignore it " + beforeHeard / dawn);
        check(Math.signum(evening / beforeHeard - 1) == Math.signum(e.type().shock()),
                e.type().id() + " moved " + item + " once the market heard: " + evening / beforeHeard);
        helper.succeed();
    }

    @GameTest
    public void repricingMovesAnOrderWithoutANewSlip(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getInventory().add(new ItemStack(Items.WHEAT, 16));
        p.getInventory().add(new ItemStack(ModItems.ORDER_SLIP));
        Pit pit = pit(helper, p);

        press(pit, p, TradingFloorMenu.BUTTON_SIDE, 1);
        press(pit, p, TradingFloorMenu.BUTTON_PRICE_PLUS_10PCT, 8); // far above the market: rests
        check(pit.menu().clickMenuButton(p, TradingFloorMenu.BUTTON_PLACE), "place the sell");
        check(p.getInventory().countItem(ModItems.ORDER_SLIP) == 0, "the only slip is spent");
        pit.floor().runAuctions(pit.day());
        check(pit.menu().orderCount() == 1 && pit.menu().orderFilled(0) == 0, "resting unfilled");

        press(pit, p, TradingFloorMenu.BUTTON_PRICE_MINUS_10PCT, 12); // well under the bid now
        long price = pit.menu().priceCents();
        check(pit.menu().clickMenuButton(p, TradingFloorMenu.BUTTON_REPRICE_BASE), "reprice without a slip");
        check(pit.menu().orderPrice(0) == price, "order moved to " + price + ", shows " + pit.menu().orderPrice(0));
        auctionUntilDone(pit, p, 10);
        collect(pit, p);
        CompoundTag r = receipt(pit.menu().output());
        check(r.getLongOr("qty", 0) == 16, "the repriced order filled: " + r);
        helper.succeed();
    }

    @GameTest
    public void raisingABidTakesOnlyTheExtraCash(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Wallet.give(p, 10_000);
        p.getInventory().add(new ItemStack(ModItems.ORDER_SLIP));
        Pit pit = pit(helper, p);

        press(pit, p, TradingFloorMenu.BUTTON_QTY_MINUS_1, 6); // 10
        press(pit, p, TradingFloorMenu.BUTTON_PRICE_MINUS_10PCT, 6);
        check(pit.menu().clickMenuButton(p, TradingFloorMenu.BUTTON_PLACE), "place a low bid");
        long low = pit.menu().priceCents(), afterPlace = Wallet.count(p.getInventory());
        press(pit, p, TradingFloorMenu.BUTTON_PRICE_PLUS_1, 5);
        check(pit.menu().clickMenuButton(p, TradingFloorMenu.BUTTON_REPRICE_BASE), "raise the bid by 5c");
        long paid = afterPlace - Wallet.count(p.getInventory());
        check(paid >= 50 && paid < 60, "10 x 5c more escrow (to the dime), paid " + paid);
        check(pit.menu().orderPrice(0) == low + 5, "bid now " + (low + 5));
        helper.succeed();
    }

    @GameTest
    public void tickerTapePrintsASevenDayChart(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        DealerService dealer = DealerService.forTest(1234L);
        dealer.shiftDays(10);
        FloorService floor = FloorService.forTest(dealer, 99L);
        ProgressionService prog = ProgressionService.forTest();
        Wallet.give(p, 711_500); // bill clip, price board, vault, loan note, trading floor, ticker tape
        for (String node : new String[] {"bill_clip", "price_board", "bank_vault", "loan_note", "trading_floor", "ticker_tape"}) {
            check(prog.buyNode(p, node).isEmpty(), "buy " + node);
        }
        check(prog.progress(p).hasGuide("reading_a_chart"), "the Ticker Tape grants Reading a Chart");
        double now = dealer.day(helper.getLevel().getGameTime());
        for (double d = now - 3; d <= now; d += 0.05) floor.runAuctions(d); // three days of trading

        var tape = new com.realisticmarkets.mod.menu.TickerTapeMenu(1, p.getInventory(), ContainerLevelAccess.NULL, floor, prog, dealer);
        check(!tape.clickMenuButton(p, com.realisticmarkets.mod.menu.TickerTapeMenu.BUTTON_PRINT), "no paper, no chart");
        p.getInventory().add(new ItemStack(ModItems.LEDGER_PAPER));
        p.getInventory().add(new ItemStack(ModItems.INK_BOTTLE));
        int wheat = book(WHEAT);
        check(tape.clickMenuButton(p, com.realisticmarkets.mod.menu.TickerTapeMenu.BUTTON_BOOK_BASE + wheat), "pick wheat");
        check(tape.clickMenuButton(p, com.realisticmarkets.mod.menu.TickerTapeMenu.BUTTON_PRINT), "print");
        ItemStack chartStack = tape.output().getItem(0);
        check(chartStack.is(ModItems.PRICE_CHART), "a Price Chart in the output");
        var chart = com.realisticmarkets.mod.item.PriceChartItem.read(chartStack).orElseThrow();
        long today = (long) Math.floor(now);
        check(chart.equals(com.realisticmarkets.exchange.PriceChart.of(floor.floor().history(), WHEAT, today)),
                "the chart is the last 7 days of wheat");
        check(!chart.empty() && chart.close()[com.realisticmarkets.exchange.PriceChart.POINTS - 1] > 0, "with prices in it");
        check(p.getInventory().countItem(ModItems.LEDGER_PAPER) == 0 && p.getInventory().countItem(ModItems.INK_BOTTLE) == 0,
                "1 Ledger Paper + 1 Ink Bottle used");
        String lore = chartStack.get(DataComponents.LORE).lines().getFirst().getString();
        check(lore.equals(chart.sparkline()), "tooltip sparkline: " + lore);
        check(prog.progress(p).hasCompleted("read_the_tape"), "Read the Tape");
        helper.succeed();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
