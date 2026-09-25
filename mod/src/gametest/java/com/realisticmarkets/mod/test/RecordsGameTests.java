package com.realisticmarkets.mod.test;

import com.realisticmarkets.bonds.Bond;
import com.realisticmarkets.contracts.BankAccount;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.block.BankVaultBlockEntity;
import com.realisticmarkets.mod.block.RecordsTerminalBlockEntity;
import com.realisticmarkets.mod.block.SafeDepositBoxBlockEntity;
import com.realisticmarkets.mod.block.TradeRouteCrateBlockEntity;
import com.realisticmarkets.mod.bonds.BondPapers;
import com.realisticmarkets.mod.bonds.BondService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.menu.RecordsTerminalMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.records.RecordLinkItem;
import com.realisticmarkets.mod.records.RecordsService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import com.realisticmarkets.mod.stocks.StockService;
import com.realisticmarkets.money.Denomination;
import com.realisticmarkets.progression.ProgressionEvent;
import com.realisticmarkets.progression.UnlockNode;
import com.realisticmarkets.records.Ledger;
import com.realisticmarkets.records.NetWorth;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** M7b: Digital Record Keeping. The Records Terminal, Record Links, the income ledger, Balance Sheet. */
public class RecordsGameTests {

    record Desk(DealerService dealer, ProgressionService prog, BankService bank, StockService stocks, BondService bonds,
                RecordsService records) {}

    static Desk desk() {
        DealerService dealer = DealerService.forTest(1234L);
        ProgressionService prog = ProgressionService.forTest();
        BankService bank = BankService.forTest(null);
        StockService stocks = StockService.forTest(dealer, 7L);
        BondService bonds = BondService.forTest(bank, stocks);
        RecordsService records = RecordsService.forTest(new RecordsService.Sources(dealer, prog, bank, stocks, bonds, null));
        return new Desk(dealer, prog, bank, stocks, bonds, records);
    }

    /** Buys Tiers 1-4 (what the gates allow) and then Digital Record Keeping. */
    static void unlockRecords(ServerPlayer p, ProgressionService prog) {
        Wallet.give(p, 10_000_000);
        for (int tier = 1; tier <= 4; tier++) {
            for (UnlockNode n : prog.tree().tier(tier)) prog.buyNode(p, n.id());
        }
        check(prog.buyNode(p, RecordsService.NODE).isEmpty(), "buy Digital Record Keeping");
        Wallet.takeAll(p);
    }

    static <T extends net.minecraft.world.level.block.entity.BlockEntity> T place(GameTestHelper helper, BlockPos pos, net.minecraft.world.level.block.Block block, Class<T> type, ServerPlayer owner) {
        helper.setBlock(pos, block);
        T be = helper.getBlockEntity(pos, type);
        ((com.realisticmarkets.mod.block.OwnedBlockEntity) be).setOwner(owner);
        return be;
    }

    static String link(GameTestHelper helper, ServerPlayer p, ItemStack tool, BlockPos pos) {
        return RecordLinkItem.use(p, helper.getLevel(), helper.absolutePos(pos), tool);
    }

    @GameTest
    public void netWorthIsTheSumOfLinkedHoldingsAndChestsDontCount(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Desk d = desk();
        double day = d.dealer().day(helper.getLevel().getGameTime());
        long today = (long) Math.floor(day);
        BlockPos tPos = new BlockPos(1, 1, 1), boxPos = new BlockPos(3, 1, 1), cratePos = new BlockPos(5, 1, 1);
        BlockPos vaultPos = new BlockPos(1, 1, 3), chestPos = new BlockPos(3, 1, 3);
        RecordsTerminalBlockEntity terminal = place(helper, tPos, ModBlocks.RECORDS_TERMINAL, RecordsTerminalBlockEntity.class, p);
        SafeDepositBoxBlockEntity box = place(helper, boxPos, ModBlocks.SAFE_DEPOSIT_BOX, SafeDepositBoxBlockEntity.class, p);
        TradeRouteCrateBlockEntity crate = place(helper, cratePos, ModBlocks.TRADE_ROUTE_CRATE, TradeRouteCrateBlockEntity.class, p);
        place(helper, vaultPos, ModBlocks.BANK_VAULT, BankVaultBlockEntity.class, p);
        helper.setBlock(chestPos, Blocks.CHEST.defaultBlockState());

        d.bank().account(p.getUUID(), today).deposit(50_000, today, BankAccount.Kind.DEPOSIT);
        box.contents().setItem(0, new ItemStack(ModItems.CURRENCY.get(Denomination.HUNDRED), 3));
        box.contents().setItem(1, ShareCertificates.create("OWL", 10, -1, 2));
        Bond bond = d.bonds().desk().issue(Bond.TREASURY, 4, today);
        box.contents().setItem(2, BondPapers.create(bond, 0, 5));
        crate.cargo().setItem(0, new ItemStack(Items.WHEAT, 64));
        ChestBlockEntity chest = helper.getBlockEntity(chestPos, ChestBlockEntity.class);
        chest.setItem(0, ShareCertificates.create("GHF", 100, -1, 1)); // in an ordinary chest: off the books

        ItemStack tool = new ItemStack(ModItems.RECORD_LINK);
        check(link(helper, p, tool, boxPos).contains("Records Terminal first"), "pick a terminal first");
        check(link(helper, p, tool, tPos).contains("set to this terminal"), "pick the terminal");
        for (BlockPos pos : new BlockPos[] {boxPos, cratePos, vaultPos}) check(link(helper, p, tool, pos).startsWith("Linked"), "link " + pos);
        check(link(helper, p, tool, chestPos).equals("Nothing to link here"), "a chest can't be linked");
        check(terminal.links().size() == 3, "three links");

        RecordsService.View v = d.records().view(terminal, p.getUUID(), day);
        long owl = d.stocks().market().exchange().lastPrice("OWL").orElse(d.stocks().fairCents("OWL"));
        long bondMark = d.bonds().desk().bid(bond, day);
        long wheat = d.dealer().dealer().quoteSell("minecraft:wheat", 64, day, false).cents() / 64 * 64;
        long expected = 50_000 + 30_000 + 20 * owl + 5 * bondMark + wheat;
        check(v.netWorth().total() == expected, "net worth " + v.netWorth().total() + " = vault + cash + shares + bonds + wheat " + expected);
        check(v.netWorth().byKind().get(NetWorth.Kind.SHARES) == 20 * owl, "only the linked box's shares, not the chest's GHF");
        check(v.rows().stream().noneMatch(r -> r.line().label().equals("GHF")), "no GHF line");
        check(v.rows().get(0).line().value() >= v.rows().get(v.rows().size() - 1).line().value(), "largest holdings first");
        check(v.calendar().upcoming(today, 9).stream().anyMatch(e -> e.kind() == com.realisticmarkets.records.Calendar.Kind.COUPON
                && e.cents() == 5L * bond.couponCents()), "the calendar shows the next coupon on 5 bonds");
        helper.succeed();
    }

    @GameTest
    public void linksBreakWhenABlockMovesAndOnlyYourBlocksLink(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        Desk d = desk();
        BlockPos tPos = new BlockPos(1, 1, 1), boxPos = new BlockPos(3, 1, 1), otherPos = new BlockPos(5, 1, 1);
        RecordsTerminalBlockEntity terminal = place(helper, tPos, ModBlocks.RECORDS_TERMINAL, RecordsTerminalBlockEntity.class, p);
        SafeDepositBoxBlockEntity box = place(helper, boxPos, ModBlocks.SAFE_DEPOSIT_BOX, SafeDepositBoxBlockEntity.class, p);
        box.contents().setItem(0, new ItemStack(ModItems.CURRENCY.get(Denomination.HUNDRED), 1));
        place(helper, otherPos, ModBlocks.SAFE_DEPOSIT_BOX, SafeDepositBoxBlockEntity.class, stranger);
        ItemStack tool = new ItemStack(ModItems.RECORD_LINK);
        link(helper, p, tool, tPos);
        check(link(helper, p, tool, otherPos).startsWith("This belongs to"), "someone else's box can't be linked");
        check(link(helper, p, tool, boxPos).startsWith("Linked"), "link my box");
        double day = d.dealer().day(helper.getLevel().getGameTime());
        check(d.records().view(terminal, p.getUUID(), day).netWorth().total() == 10_000, "$100 in the box");
        check(link(helper, p, tool, boxPos).equals("Unlinked"), "a second click unlinks");
        check(link(helper, p, tool, boxPos).startsWith("Linked"), "and a third links again");

        // Move the box: take it out (its contents with it) and put it down one block over.
        box.contents().clearContent();
        helper.setBlock(boxPos, Blocks.AIR);
        SafeDepositBoxBlockEntity moved = place(helper, boxPos.above(), ModBlocks.SAFE_DEPOSIT_BOX, SafeDepositBoxBlockEntity.class, p);
        moved.contents().setItem(0, new ItemStack(ModItems.CURRENCY.get(Denomination.HUNDRED), 1));
        check(terminal.linkedBlocks().isEmpty() && terminal.links().isEmpty(), "the link broke when the box moved");
        check(d.records().view(terminal, p.getUUID(), day).netWorth().total() == 0, "and its cash is off the books");

        check(terminal.toggle(helper.absolutePos(tPos).offset(80, 0, 0)).startsWith("Too far"), "64 blocks at most");
        for (int i = 0; i < RecordsTerminalBlockEntity.MAX_LINKS; i++) terminal.toggle(helper.absolutePos(tPos).offset(0, 0, 10 + i));
        check(terminal.toggle(helper.absolutePos(tPos).offset(0, 0, 40)).contains("already has 16"), "16 links at most");
        helper.succeed();
    }

    @GameTest
    public void theLedgerRecordsIncomeFromTheUnlockAndBalanceSheetPays(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Desk d = desk();
        long today = (long) Math.floor(d.dealer().day(helper.getLevel().getGameTime()));
        String acct = RecordsService.account(p);
        d.prog().emit(p, new ProgressionEvent.DividendCollected("OWL", 700, today));
        check(!d.records().ledger().isOpen(acct), "nothing is recorded before the unlock");
        unlockRecords(p, d.prog());
        d.prog().emit(p, new ProgressionEvent.DividendCollected("OWL", 800, today));
        d.prog().emit(p, new ProgressionEvent.CouponCollected(2_700, today));
        d.prog().emit(p, new ProgressionEvent.StockSold("OWL", 10, 5_000, 4_000, today));
        check(d.records().ledger().income(acct, Ledger.Source.DIVIDENDS, today, 7) == 800, "only the dividend after the unlock");
        check(d.records().ledger().income(acct, Ledger.Source.COUPONS, today, 7) == 2_700, "coupons");
        check(d.records().ledger().income(acct, Ledger.Source.TRADING_GAINS, today, 7) == 1_000, "a $10 gain on shares");

        BlockPos tPos = new BlockPos(1, 1, 1), boxPos = new BlockPos(3, 1, 1);
        RecordsTerminalBlockEntity terminal = place(helper, tPos, ModBlocks.RECORDS_TERMINAL, RecordsTerminalBlockEntity.class, p);
        SafeDepositBoxBlockEntity box = place(helper, boxPos, ModBlocks.SAFE_DEPOSIT_BOX, SafeDepositBoxBlockEntity.class, p);
        for (int i = 0; i < 8; i++) box.contents().setItem(i, new ItemStack(ModItems.CURRENCY.get(Denomination.HUNDRED), 64));
        ItemStack tool = new ItemStack(ModItems.RECORD_LINK);
        link(helper, p, tool, tPos);
        link(helper, p, tool, boxPos);
        long before = Wallet.count(p.getInventory());
        RecordsTerminalMenu menu = new RecordsTerminalMenu(1, p.getInventory(), ContainerLevelAccess.NULL, terminal, d.records());
        check(menu.owner() && menu.total() == 8 * 64 * 10_000, "the terminal shows $51,200: " + menu.total());
        check(menu.income(Ledger.Source.COUPONS, false) == 2_700 && menu.ledgerOpen(), "the Income tab");
        check(d.prog().progress(p).hasCompleted("balance_sheet"), "Balance Sheet: $50,000 on a terminal");
        check(Wallet.count(p.getInventory()) - before == 5_000, "pays $50");
        check(d.records().ledger().netWorthOn(acct, today).orElse(-1) == 8 * 64 * 10_000, "and starts the 30-day line");
        check(d.prog().progress(p).hasGuide("net_worth"), "the node grants Net Worth");
        helper.succeed();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
