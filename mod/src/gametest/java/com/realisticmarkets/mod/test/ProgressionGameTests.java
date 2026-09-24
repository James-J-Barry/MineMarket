package com.realisticmarkets.mod.test;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.menu.AlmanacMenu;
import com.realisticmarkets.mod.menu.BasicExchangeMenu;
import com.realisticmarkets.mod.menu.DraftingTableMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.progression.ProgressionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** M2 acceptance tests: Almanac purchases, Drafting Table crafting, component visibility, quests, license. */
public class ProgressionGameTests {

    @GameTest
    public void almanacBuysBillClipWithBills(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ProgressionService prog = ProgressionService.forTest();
        Wallet.give(player, 5_000);
        AlmanacMenu menu = new AlmanacMenu(1, player.getInventory(), ContainerLevelAccess.NULL, prog, null);

        int clip = nodeIndex("bill_clip");
        check(menu.nodeState(clip) == AlmanacMenu.AVAILABLE, "Bill Clip should be buyable with $50");
        check(menu.clickMenuButton(player, AlmanacMenu.BUTTON_SELECT_BASE + clip), "select Bill Clip");
        check(menu.clickMenuButton(player, AlmanacMenu.BUTTON_BUY), "buy should succeed");
        check(Wallet.count(player.getInventory()) == 1_000, "$40 should be deducted, left " + Wallet.count(player.getInventory()));
        check(prog.progress(player).hasBlueprint("realisticmarkets:bill_clip"), "blueprint should be unlocked");
        check(menu.nodeState(clip) == AlmanacMenu.OWNED, "node should show as owned");
        check(!menu.clickMenuButton(player, AlmanacMenu.BUTTON_BUY), "can't buy twice");
        check(Wallet.count(player.getInventory()) == 1_000, "second click must not charge");

        int license = nodeIndex("merchant_license");
        check(menu.nodeState(license) == AlmanacMenu.NEEDS_QUEST, "license needs Meet the Spread");
        helper.succeed();
    }

    @GameTest
    public void draftingTableRefusesLockedBlueprint(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ProgressionService prog = ProgressionService.forTest();
        giveClipMaterials(player);
        DraftingTableMenu menu = new DraftingTableMenu(1, player.getInventory(), ContainerLevelAccess.NULL, prog);

        int clip = blueprintIndex("realisticmarkets:bill_clip");
        check(!menu.unlocked(clip), "blueprint should start locked");
        menu.clickMenuButton(player, DraftingTableMenu.BUTTON_SELECT_BASE + clip);
        check(!menu.clickMenuButton(player, DraftingTableMenu.BUTTON_CRAFT_ONE), "locked blueprint must not craft");
        check(menu.getSlot(DraftingTableMenu.OUTPUT).getItem().isEmpty(), "no output");
        check(player.getInventory().countItem(Items.LEATHER) == 2, "materials untouched");
        check(player.getInventory().countItem(ModItems.BRASS_FITTINGS) == 1, "materials untouched");
        helper.succeed();
    }

    @GameTest
    public void draftingTableCraftsBillClipAfterUnlock(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ProgressionService prog = ProgressionService.forTest();
        Wallet.give(player, 4_000);
        check(prog.buyNode(player, "bill_clip").isEmpty(), "buy Bill Clip node");
        giveClipMaterials(player);
        player.getInventory().add(new ItemStack(Items.LEATHER, 1)); // 3 leather: only one clip's worth of brass
        DraftingTableMenu menu = new DraftingTableMenu(1, player.getInventory(), ContainerLevelAccess.NULL, prog);

        int clip = blueprintIndex("realisticmarkets:bill_clip");
        check(menu.unlocked(clip), "blueprint should be unlocked");
        menu.clickMenuButton(player, DraftingTableMenu.BUTTON_SELECT_BASE + clip);
        check(menu.clickMenuButton(player, DraftingTableMenu.BUTTON_CRAFT_MAX), "craft should succeed");
        ItemStack out = menu.getSlot(DraftingTableMenu.OUTPUT).getItem();
        check(out.is(ModItems.BILL_CLIP) && out.getCount() == 1, "expected one Bill Clip, got " + out);
        check(player.getInventory().countItem(Items.LEATHER) == 1, "2 leather consumed, 1 left");
        check(player.getInventory().countItem(ModItems.BRASS_FITTINGS) == 0, "brass consumed");
        check(!menu.clickMenuButton(player, DraftingTableMenu.BUTTON_CRAFT_ONE), "no materials left");
        helper.succeed();
    }

    @GameTest
    public void brassFittingsAppearInBuyTabAfterUnlock(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ProgressionService prog = ProgressionService.forTest();
        BasicExchangeMenu menu = new BasicExchangeMenu(1, player.getInventory(), ContainerLevelAccess.NULL,
                DealerService.forTest(1234L), prog);

        check(menu.indexOf("realisticmarkets:brass_fittings") < 0, "brass hidden before any unlock");
        check(menu.indexOf("minecraft:wheat") >= 0, "normal items still listed");
        Wallet.give(player, 4_000);
        check(prog.buyNode(player, "bill_clip").isEmpty(), "buy Bill Clip node");
        menu.clickMenuButton(player, BasicExchangeMenu.BUTTON_TAB_BUY); // any action refreshes the list
        int brass = menu.indexOf("realisticmarkets:brass_fittings");
        check(brass >= 0, "brass visible after unlocking Bill Clip");
        check(menu.buyGroup(brass) == BasicExchangeMenu.GROUPS.length - 1, "brass sits under Components");
        check(menu.indexOf("realisticmarkets:ink_bottle") < 0, "ink stays hidden (Price Board not unlocked)");
        check(menu.buySellsMills(brass) == 12_000, "brass ask should be $12.00 (40% spread), got " + menu.buySellsMills(brass));
        helper.succeed();
    }

    @GameTest
    public void firstSaleCompletesQuestOneAndPaysFiveDollars(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ProgressionService prog = ProgressionService.forTest();
        BasicExchangeMenu menu = new BasicExchangeMenu(1, player.getInventory(), ContainerLevelAccess.NULL,
                DealerService.forTest(1234L), prog);

        menu.getSlot(BasicExchangeMenu.INPUT).set(new ItemStack(Items.WHEAT, 64));
        menu.broadcastChanges();
        check(menu.clickMenuButton(player, BasicExchangeMenu.BUTTON_SELL), "sell should succeed");
        check(prog.progress(player).hasCompleted("first_sale"), "quest 1 should complete");
        check(Wallet.count(player.getInventory()) == 500, "$5 reward in inventory, got " + Wallet.count(player.getInventory()));
        check(!prog.progress(player).hasCompleted("meet_the_spread"), "quest 2 needs a purchase first");
        helper.succeed();
    }

    @GameTest
    public void merchantLicensePaysMoreForSixtyFourWheat(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ProgressionService prog = ProgressionService.forTest();
        prog.emit(player, new ProgressionEvent.Purchase("minecraft:wheat", "farm", 1, 60, 0));
        prog.emit(player, new ProgressionEvent.Sale("minecraft:wheat", "farm", 1, 40, 1.0, 1.0, 0));
        check(prog.progress(player).hasCompleted("meet_the_spread"), "quest 2 should be done");
        Wallet.give(player, 15_000);
        check(prog.buyNode(player, "merchant_license").isEmpty(), "buy Merchant License");
        check(prog.licensed(player), "perk should be active");

        BasicExchangeMenu menu = new BasicExchangeMenu(1, player.getInventory(), ContainerLevelAccess.NULL,
                DealerService.forTest(1234L), prog);
        menu.getSlot(BasicExchangeMenu.INPUT).set(new ItemStack(Items.WHEAT, 64));
        menu.broadcastChanges();
        check(menu.quoteCents() > 2540, "licensed quote should beat $25.40, got " + menu.quoteCents());
        long quote = menu.quoteCents();
        check(menu.clickMenuButton(player, BasicExchangeMenu.BUTTON_SELL), "sell should succeed");
        check(drawer(menu) == quote, "paid the licensed quote");
        helper.succeed();
    }

    @GameTest
    public void guideTearsOutAsAWrittenBookOnlyOnceUnlocked(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ProgressionService prog = ProgressionService.forTest();
        AlmanacMenu menu = new AlmanacMenu(1, player.getInventory(), ContainerLevelAccess.NULL, prog, null);
        int spread = guideIndex("spread");

        check(!menu.guideUnlocked(spread), "Spread guide starts locked");
        check(!menu.clickMenuButton(player, AlmanacMenu.BUTTON_TEAR_OUT_BASE + spread), "locked guide can't be torn out");
        check(player.getInventory().countItem(Items.WRITTEN_BOOK) == 0, "no book yet");

        prog.emit(player, new ProgressionEvent.Purchase("minecraft:wheat", "farm", 1, 60, 0));
        prog.emit(player, new ProgressionEvent.Sale("minecraft:wheat", "farm", 1, 40, 1.0, 1.0, 0));
        check(menu.clickMenuButton(player, AlmanacMenu.BUTTON_TEAR_OUT_BASE + spread), "tear out after Meet the Spread");
        check(menu.guideUnlocked(spread), "guide shows as unlocked");

        ItemStack book = ItemStack.EMPTY;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(Items.WRITTEN_BOOK)) book = player.getInventory().getItem(i);
        }
        var content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        check(content != null, "book should carry written content");
        check(content.title().raw().equals("The Spread"), "title should be The Spread, got " + content.title().raw());
        check(content.pages().size() >= 3, "a ~220-word guide spans several pages, got " + content.pages().size());
        String all = String.join(" ", content.pages().stream().map(p -> p.raw().getString()).toList());
        check(all.contains("$0.45") && all.contains("Real world:"), "book should contain the guide text");
        helper.succeed();
    }

    @GameTest
    public void progressSurvivesAServiceRestart(GameTestHelper helper) throws Exception {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Path dir = Files.createTempDirectory("rm-progress");
        ProgressionService before = ProgressionService.forTest(dir);
        Wallet.give(player, 4_000);
        check(before.buyNode(player, "bill_clip").isEmpty(), "buy Bill Clip node");
        before.emit(player, new ProgressionEvent.Sale("minecraft:wheat", "farm", 200, 6000, 1.0, 0.5, 3));
        Path file = dir.resolve(player.getUUID() + ".txt");
        check(Files.exists(file), "save file written on change: " + file);

        ProgressionService after = ProgressionService.forTest(dir);
        var p = after.progress(player);
        check(p.hasBlueprint("realisticmarkets:bill_clip"), "blueprint survives");
        check(p.hasCompleted("first_sale"), "quest survives");
        check(p.hasGuide("money_and_dealer"), "guide survives");
        // Same-day history survives too: 56 more wheat completes Flooding the Market.
        var done = after.emit(player, new ProgressionEvent.Sale("minecraft:wheat", "farm", 56, 900, 0.5, 0.45, 3));
        check(done.stream().anyMatch(q -> q.id().equals("flooding_the_market")), "day history survives, got " + done);
        helper.succeed();
    }

    @GameTest
    public void oldSavesGainGuidesAddedToOwnedNodes(GameTestHelper helper) throws Exception {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Path dir = Files.createTempDirectory("rm-progress");
        Path file = dir.resolve(player.getUUID() + ".txt");
        Files.writeString(file, "# Realistic Markets player progress v1\nnode\tbill_clip\n"
                + "grant\tblueprint:realisticmarkets:bill_clip\n"); // written before Bill Clip granted a guide

        ProgressionService svc = ProgressionService.forTest(dir);
        check(svc.progress(player).hasGuide("cash_on_hand"), "Cash on Hand granted on load");
        check(Files.readString(file).contains("grant\tguide:cash_on_hand"), "and saved back");
        helper.succeed();
    }

    @GameTest
    public void unreadableSaveIsMovedAsideNotOverwritten(GameTestHelper helper) throws Exception {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Path dir = Files.createTempDirectory("rm-progress");
        Path file = dir.resolve(player.getUUID() + ".txt");
        Files.writeString(file, "garbage line\n");

        ProgressionService svc = ProgressionService.forTest(dir);
        check(svc.progress(player).nodes().isEmpty(), "starts fresh");
        try (var files = Files.list(dir)) {
            check(files.anyMatch(f -> f.getFileName().toString().startsWith(player.getUUID() + ".broken-")),
                    "the unreadable file should be kept aside");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ helpers

    private static int guideIndex(String id) {
        for (int i = 0; i < AlmanacMenu.GUIDES.size(); i++) if (AlmanacMenu.GUIDES.get(i).id().equals(id)) return i;
        throw new IllegalStateException("no guide " + id);
    }

    private static void giveClipMaterials(ServerPlayer player) {
        player.getInventory().add(new ItemStack(Items.LEATHER, 2));
        player.getInventory().add(new ItemStack(ModItems.BRASS_FITTINGS, 1));
    }

    private static long drawer(BasicExchangeMenu menu) {
        long cents = 0;
        for (int i = 0; i < BasicExchangeMenu.OUTPUT_COUNT; i++) {
            ItemStack s = menu.getSlot(BasicExchangeMenu.OUTPUT_START + i).getItem();
            var d = ModItems.denominationOf(s);
            if (d != null) cents += d.cents() * s.getCount();
        }
        return cents;
    }

    private static int nodeIndex(String id) {
        for (int i = 0; i < AlmanacMenu.NODES.size(); i++) if (AlmanacMenu.NODES.get(i).id().equals(id)) return i;
        throw new IllegalStateException("no node " + id);
    }

    private static int blueprintIndex(String result) {
        for (int i = 0; i < DraftingTableMenu.BLUEPRINTS.size(); i++) {
            if (DraftingTableMenu.BLUEPRINTS.get(i).result().equals(result)) return i;
        }
        throw new IllegalStateException("no blueprint " + result);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
