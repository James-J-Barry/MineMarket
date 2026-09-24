package com.realisticmarkets.mod.test;

import com.realisticmarkets.mod.block.PriceBoardBlock;
import com.realisticmarkets.mod.block.PriceBoardBlockEntity;
import com.realisticmarkets.mod.block.TradeRouteCrateBlockEntity;
import com.realisticmarkets.mod.dealer.BillClip;
import com.realisticmarkets.mod.dealer.CapitalService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.menu.BasicExchangeMenu;
import com.realisticmarkets.mod.menu.BillClipMenu;
import com.realisticmarkets.mod.menu.TradeRouteCrateMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Denomination;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** M3 acceptance tests: Bill Clip, Price Board, Trade Route Crate. */
public class Tier1GameTests {

    // ------------------------------------------------------------------ Bill Clip

    @GameTest
    public void walletCountsLooseBillsAndClips(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ItemStack clip = new ItemStack(ModItems.BILL_CLIP);
        BillClip.insert(clip, bill(Denomination.HUNDRED, 2));
        player.getInventory().add(clip);
        player.getInventory().add(bill(Denomination.ONE, 5));
        check(Wallet.count(player.getInventory()) == 20_500, "expected $205, got " + Wallet.count(player.getInventory()));
        helper.succeed();
    }

    @GameTest
    public void clipPaysAndTakesTheChange(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ItemStack clip = new ItemStack(ModItems.BILL_CLIP);
        BillClip.insert(clip, bill(Denomination.HUNDRED, 1));
        player.getInventory().add(clip);

        check(Wallet.pay(player, 3_000), "pay $30 from a clip holding $100");
        ItemStack held = findClip(player);
        check(BillClip.cents(held) == 7_000, "change should land in the clip, clip holds " + BillClip.cents(held));
        check(looseCents(player) == 0, "no loose change in the inventory");
        check(!Wallet.pay(player, 7_010), "can't pay more than the clip holds");
        check(BillClip.cents(held) == 7_000, "a refused payment changes nothing");
        helper.succeed();
    }

    @GameTest
    public void payoutsFillTheClipThenTheInventory(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().add(new ItemStack(ModItems.BILL_CLIP));
        Wallet.give(player, 7_000_000); // $70,000 = 700 hundreds = 11 stacks, more than 9 slots hold
        ItemStack clip = findClip(player);
        int used = 0;
        for (ItemStack s : BillClip.contents(clip)) if (!s.isEmpty()) used++;
        check(used == BillClip.SLOTS, "clip should be full, " + used + " slots used");
        check(Wallet.count(player.getInventory()) == 7_000_000, "nothing lost, got " + Wallet.count(player.getInventory()));
        check(looseCents(player) > 0, "overflow goes to the inventory");
        helper.succeed();
    }

    @GameTest
    public void exchangeBuysWithClipMoney(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ItemStack clip = new ItemStack(ModItems.BILL_CLIP);
        BillClip.insert(clip, bill(Denomination.HUNDRED, 1));
        player.getInventory().add(clip);
        BasicExchangeMenu menu = new BasicExchangeMenu(1, player.getInventory(), ContainerLevelAccess.NULL,
                DealerService.forTest(1234L), ProgressionService.forTest());
        check(menu.cashCents() == 10_000, "exchange should see the clip's $100, sees " + menu.cashCents());
        menu.clickMenuButton(player, BasicExchangeMenu.BUTTON_TAB_BUY);
        menu.clickMenuButton(player, BasicExchangeMenu.BUTTON_SELECT_BASE + menu.indexOf("minecraft:wheat"));
        long cost = menu.buyTotalCents(1);
        check(menu.clickMenuButton(player, BasicExchangeMenu.BUTTON_BUY_FIRST + 1), "buy 16 wheat");
        check(BillClip.cents(findClip(player)) == 10_000 - cost, "change back in the clip");
        check(player.getInventory().countItem(Items.WHEAT) == 16, "goods in the inventory");
        helper.succeed();
    }

    @GameTest
    public void clipScreenAcceptsOnlyMoneyAndLocksTheOpenClip(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ItemStack clip = new ItemStack(ModItems.BILL_CLIP);
        player.getInventory().setItem(player.getInventory().getSelectedSlot(), clip);
        BillClipMenu menu = new BillClipMenu(1, player.getInventory(), InteractionHand.MAIN_HAND, clip);

        check(!menu.getSlot(0).mayPlace(new ItemStack(Items.DIRT)), "dirt refused");
        check(!menu.getSlot(0).mayPlace(new ItemStack(ModItems.BILL_CLIP)), "no clip inside a clip");
        check(menu.getSlot(0).mayPlace(bill(Denomination.TEN, 1)), "money accepted");
        int hotbarSlot = BillClipMenu.CLIP_END + 27 + player.getInventory().getSelectedSlot();
        check(!menu.getSlot(hotbarSlot).mayPickup(player), "the open clip can't be picked up");

        menu.getSlot(3).set(bill(Denomination.TEN, 4));
        check(BillClip.cents(clip) == 4_000, "edits write through to the item, holds " + BillClip.cents(clip));
        check(menu.stillValid(player), "valid while the clip is carried");
        helper.succeed();
    }

    // ------------------------------------------------------------------ Price Board

    @GameTest
    public void priceBoardQuotesTradedItemsOnly(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ModBlocks.PRICE_BOARD.defaultBlockState().setValue(PriceBoardBlock.FACING, Direction.SOUTH));
        PriceBoardBlockEntity board = helper.getBlockEntity(pos, PriceBoardBlockEntity.class);
        DealerService svc = DealerService.forTest(1234L);
        var catalog = svc.dealer().catalog();

        check(board.tryAdd(new ItemStack(Items.DIRT), catalog) != null, "dirt has no market");
        check(board.tryAdd(bill(Denomination.ONE, 1), catalog) != null, "money has no price");
        check(board.tryAdd(new ItemStack(Items.WHEAT, 64), catalog) == null, "wheat accepted");
        check(board.item(0).getCount() == 1, "the board holds one of the item, not the stack");
        board.refresh(svc.dealer(), 0);
        check(board.bidMills(0) == 450 && board.askMills(0) == 550,
                "board shows the public quote $0.45/$0.55, got " + board.bidMills(0) + "/" + board.askMills(0));
        check(board.fairMills(0) == 500, "Normal is $0.50");

        svc.sellStack(new ItemStack(Items.WHEAT, 64), 0, false);
        board.refresh(svc.dealer(), 0);
        check(board.bidMills(0) == 350, "the bid follows the Dealer: $0.35 after 64 wheat, got " + board.bidMills(0));
        check(board.midMills(0) < board.fairMills(0), "market below normal after the sale");

        check(board.tryAdd(new ItemStack(Items.IRON_INGOT), catalog) == null, "2nd");
        check(board.tryAdd(new ItemStack(Items.COAL), catalog) == null, "3rd");
        check(board.tryAdd(new ItemStack(Items.BONE), catalog) == null, "4th");
        check(board.tryAdd(new ItemStack(Items.STRING), catalog) != null, "a fifth item doesn't fit");
        check(board.removeLast().is(Items.BONE), "take back the last one");
        check(board.count() == 3, "three left");
        helper.succeed();
    }

    // ------------------------------------------------------------------ Trade Route Crate

    record Crate(TradeRouteCrateBlockEntity be, DealerService dealer, CapitalService capital, ProgressionService prog) {}

    static Crate crate(GameTestHelper helper, ServerPlayer owner) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ModBlocks.TRADE_ROUTE_CRATE);
        TradeRouteCrateBlockEntity be = helper.getBlockEntity(pos, TradeRouteCrateBlockEntity.class);
        be.setOwner(owner);
        DealerService dealer = DealerService.forTest(1234L);
        return new Crate(be, dealer, CapitalService.forTest(dealer, 99L), ProgressionService.forTest());
    }

    static TradeRouteCrateMenu menu(Crate c, ServerPlayer player) {
        return new TradeRouteCrateMenu(1, player.getInventory(), c.be(), ContainerLevelAccess.NULL, c.dealer(), c.capital(), c.prog());
    }

    @GameTest
    public void crateRefusesNonOwners(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        stranger.setUUID(UUID.randomUUID());
        Crate c = crate(helper, owner);
        c.be().cargo().setItem(0, new ItemStack(Items.WHEAT, 64));

        check(!c.be().isOwner(stranger), "stranger isn't the owner");
        check(!menu(c, stranger).stillValid(stranger), "a stranger's crate screen closes");
        check(!menu(c, stranger).clickMenuButton(stranger, TradeRouteCrateMenu.BUTTON_SHIP), "stranger can't ship");
        check(c.be().cargo().getItem(0).getCount() == 64, "cargo untouched");
        check(c.capital().inTransitAt(c.be().location()).isEmpty(), "nothing on the road");
        helper.succeed();
    }

    @GameTest
    public void crateShipsAndSettlesIntoTheDrawerAfterOneDay(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Crate c = crate(helper, owner);
        c.be().cargo().setItem(0, new ItemStack(Items.WHEAT, 64));
        c.be().cargo().setItem(4, new ItemStack(Items.WHEAT, 64));
        TradeRouteCrateMenu menu = menu(c, owner);

        check(menu.status() == TradeRouteCrateMenu.STATUS_READY, "ready to ship, status " + menu.status());
        check(menu.localCents() == 4530, "local Dealer would pay $45.30, shows " + menu.localCents());
        check(menu.estimateCents() == 5740, "Capital estimate $57.40 after freight, shows " + menu.estimateCents());
        check(menu.clickMenuButton(owner, TradeRouteCrateMenu.BUTTON_SHIP), "ship");
        check(c.be().cargo().isEmpty(), "cargo left the world");
        check(menu.status() == TradeRouteCrateMenu.STATUS_IN_TRANSIT, "on the road");
        check(menu.minutesLeft() == 20, "arrives in one in-game day = 20 min, shows " + menu.minutesLeft());

        c.be().cargo().setItem(0, new ItemStack(Items.CARROT, 8));
        check(!menu.clickMenuButton(owner, TradeRouteCrateMenu.BUTTON_SHIP), "one shipment per crate at a time");

        double shipped = c.dealer().day(helper.getLevel().getGameTime());
        check(c.capital().settle(loc -> c.be(), id -> owner, c.prog(), shipped + 0.99) == 0, "not there yet");
        check(c.capital().settle(loc -> c.be(), id -> owner, c.prog(), shipped + 1.0) == 1, "arrives after one day");
        check(c.be().drawerCents() == 5740, "payout in the drawer: $57.40, got " + c.be().drawerCents());
        check(c.prog().progress(owner).hasCompleted("two_markets"), "quest 7: beat the local Dealer");
        check(Wallet.count(owner.getInventory()) == 4000, "$40 quest reward, got " + Wallet.count(owner.getInventory()));
        helper.succeed();
    }

    @GameTest
    public void losingShipmentsDontCompleteQuestSeven(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Crate c = crate(helper, owner);
        c.be().cargo().setItem(0, new ItemStack(Items.IRON_INGOT, 16));
        TradeRouteCrateMenu menu = menu(c, owner);
        check(menu.estimateCents() < menu.localCents(), "iron pays less at the Capital");
        check(menu.clickMenuButton(owner, TradeRouteCrateMenu.BUTTON_SHIP), "ship anyway");
        double shipped = c.dealer().day(helper.getLevel().getGameTime());
        c.capital().settle(loc -> c.be(), id -> owner, c.prog(), shipped + 1);
        check(!c.prog().progress(owner).hasCompleted("two_markets"), "a loss isn't arbitrage");
        helper.succeed();
    }

    @GameTest
    public void payoutWaitsForAnOfflineOwnerWhoseCrateIsGone(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Crate c = crate(helper, owner);
        c.be().cargo().setItem(0, new ItemStack(Items.WHEAT, 64));
        check(menu(c, owner).clickMenuButton(owner, TradeRouteCrateMenu.BUTTON_SHIP), "ship");
        double shipped = c.dealer().day(helper.getLevel().getGameTime());

        c.capital().settle(loc -> null, id -> null, c.prog(), shipped + 1); // crate broken, owner offline
        check(Wallet.count(owner.getInventory()) == 0, "nothing delivered while offline");
        check(!c.prog().progress(owner).hasCompleted("two_markets"), "quest waits too");

        c.capital().settle(loc -> null, id -> owner, c.prog(), shipped + 1.1); // owner logs in
        long got = Wallet.count(owner.getInventory());
        check(got > 4000, "payout plus the $40 quest reward in the inventory, got " + got);
        check(c.prog().progress(owner).hasCompleted("two_markets"), "quest completes on delivery");
        helper.succeed();
    }

    // ------------------------------------------------------------------ helpers

    static ItemStack bill(Denomination d, int n) {
        return new ItemStack(ModItems.CURRENCY.get(d), n);
    }

    static ItemStack findClip(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (BillClip.isClip(player.getInventory().getItem(i))) return player.getInventory().getItem(i);
        }
        throw new IllegalStateException("no clip");
    }

    static long looseCents(ServerPlayer player) {
        long c = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var s = player.getInventory().getItem(i);
            var d = ModItems.denominationOf(s);
            if (d != null) c += d.cents() * s.getCount();
        }
        return c;
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
