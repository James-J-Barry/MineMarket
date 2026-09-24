package com.realisticmarkets.mod.test;

import com.realisticmarkets.mod.block.SafeDepositBoxBlock;
import com.realisticmarkets.mod.block.SafeDepositBoxBlockEntity;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.menu.PortfolioBinderMenu;
import com.realisticmarkets.mod.menu.SafeDepositBoxMenu;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import com.realisticmarkets.mod.stocks.StockService;
import com.realisticmarkets.money.Denomination;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** M6b: the Portfolio Binder (securities only, live total) and the Safe Deposit Box (papers and cash, owner-only). */
public class CustodyGameTests {

    @GameTest
    public void binderHoldsPapersAndShowsTheirLiveTotal(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        StockService stocks = StockService.forTest(DealerService.forTest(1234L), 7L);
        ItemStack binder = new ItemStack(ModItems.PORTFOLIO_BINDER);
        p.setItemInHand(InteractionHand.MAIN_HAND, binder);
        PortfolioBinderMenu m = new PortfolioBinderMenu(1, p.getInventory(), InteractionHand.MAIN_HAND, binder, stocks, null, 0);
        check(!m.getSlot(0).mayPlace(new ItemStack(Items.WHEAT)), "no wheat in a binder");
        check(!m.getSlot(0).mayPlace(new ItemStack(ModItems.CURRENCY.get(Denomination.TEN))), "nor bills: papers only");
        check(m.getSlot(0).mayPlace(ShareCertificates.create("DSMC", 10, -1, 1)), "certificates go in");
        m.getSlot(0).set(ShareCertificates.create("DSMC", 10, -1, 2));
        m.getSlot(1).set(ShareCertificates.create("OWL", 1, -1, 5));
        long expected = 20 * stocks.fairCents("DSMC") + 5 * stocks.fairCents("OWL"); // no trades yet: fair value
        check(m.totalCents() == expected, "20 DSMC + 5 OWL at live prices: " + m.totalCents() + " vs " + expected);
        int dsmc = PortfolioBinderMenu.KINDS.indexOf("DSMC");
        check(m.quantity(dsmc) == 20 && m.valueCents(dsmc) == 20 * stocks.fairCents("DSMC"), "a line per company");
        check(ShareCertificates.holdings(new net.minecraft.world.SimpleContainer(
                com.realisticmarkets.mod.item.PortfolioBinderItem.contents(binder).toArray(new ItemStack[0]))).get("DSMC") == 20,
                "the papers are saved on the binder item");
        helper.succeed();
    }

    @GameTest
    public void safeDepositBoxIsForPapersAndCashAndItsOwner(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ModBlocks.SAFE_DEPOSIT_BOX);
        SafeDepositBoxBlockEntity box = helper.getBlockEntity(pos, SafeDepositBoxBlockEntity.class);
        box.setOwner(owner);
        SafeDepositBoxMenu m = new SafeDepositBoxMenu(1, owner.getInventory(), box.contents(), ContainerLevelAccess.NULL);
        check(!m.getSlot(0).mayPlace(new ItemStack(Items.DIAMOND)), "no diamonds");
        check(m.getSlot(0).mayPlace(new ItemStack(ModItems.CURRENCY.get(Denomination.HUNDRED))), "bills");
        check(m.getSlot(0).mayPlace(ShareCertificates.create("GHF", 100, -1, 1)), "certificates");
        check(m.getSlot(0).mayPlace(new ItemStack(ModItems.PORTFOLIO_BINDER)), "a binder of papers");
        check(SafeDepositBoxBlock.mayBreak(box, owner), "empty: its owner can take it down");
        m.getSlot(0).set(ShareCertificates.create("GHF", 100, -1, 1));
        check(!SafeDepositBoxBlock.mayBreak(box, owner), "not while it holds anything");
        check(!SafeDepositBoxBlock.mayBreak(box, stranger), "and never someone else's");
        check(!box.isOwner(stranger), "a stranger can't open it");
        check(helper.getBlockState(pos).getBlock().getExplosionResistance() > 1_000_000, "blast-proof");
        helper.succeed();
    }

    @GameTest
    public void diversifiedCountsPapersInBindersTooAndTheGuideMatches(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        StockService stocks = StockService.forTest(DealerService.forTest(1234L), 7L);
        var prog = com.realisticmarkets.mod.progression.ProgressionService.forTest();
        p.getInventory().add(ShareCertificates.create("DSMC", 1, -1, 1));
        p.getInventory().add(ShareCertificates.create("OWL", 1, -1, 1));
        stocks.reportHoldings(p, null, prog, 1);
        check(!prog.progress(p).hasCompleted("diversified"), "two companies isn't three");
        ItemStack binder = new ItemStack(ModItems.PORTFOLIO_BINDER);
        p.getInventory().add(binder);
        binder = p.getInventory().getItem(findBinder(p));
        var slots = com.realisticmarkets.mod.item.PortfolioBinderItem.contents(binder);
        slots.set(0, ShareCertificates.create("RSD", 10, -1, 1));
        com.realisticmarkets.mod.item.PortfolioBinderItem.setContents(binder, slots);
        stocks.reportHoldings(p, null, prog, 1);
        check(prog.progress(p).hasCompleted("diversified"), "RSD in the binder makes three: Diversified");
        stocks.observeTo(0);
        stocks.observeTo(8); // a quarter reports
        long owed = stocks.dividendsWaiting(p);
        check(owed > 0 && stocks.equities().dividendsSince("RSD", -1) == 0, "dividends waiting (RSD pays none)");
        stocks.collectDividends(p, prog, 8);
        var inside = ShareCertificates.read(com.realisticmarkets.mod.item.PortfolioBinderItem.contents(binder).get(0)).orElseThrow();
        check(inside.paidThrough() == stocks.equities().lastReportedQuarter(), "papers in the binder are presented too");
        check(stocks.dividendsWaiting(p) == 0, "nothing left waiting");
        String custody = String.join("\n", com.realisticmarkets.progression.Guides.loadDefault().guide("custody").paragraphs());
        check(custody.contains("holds " + com.realisticmarkets.mod.item.PortfolioBinderItem.SLOTS + " papers"), "binder size in the guide");
        check(custody.contains("It holds " + SafeDepositBoxBlockEntity.SLOTS + " slots"), "box size in the guide");
        helper.succeed();
    }

    static int findBinder(ServerPlayer p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(ModItems.PORTFOLIO_BINDER)) return i;
        throw new IllegalStateException("no binder");
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
