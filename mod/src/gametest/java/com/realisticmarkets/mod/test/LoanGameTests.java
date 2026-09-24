package com.realisticmarkets.mod.test;

import com.realisticmarkets.collateral.Loan;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.block.BankVaultBlock;
import com.realisticmarkets.mod.block.BankVaultBlockEntity;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.menu.BankVaultMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** M4b acceptance tests: loans against item collateral, margin calls and forced sales. */
public class LoanGameTests {

    record Desk(BankVaultBlockEntity vault, BankService bank, ProgressionService prog, DealerService dealer,
                AtomicLong day, BankVaultMenu menu) {}

    /** A player who owns the Loan Note, at a vault, holding one Security Paper. */
    static Desk desk(GameTestHelper helper, ServerPlayer p) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ModBlocks.BANK_VAULT);
        BankVaultBlockEntity vault = helper.getBlockEntity(pos, BankVaultBlockEntity.class);
        vault.setOwner(p);
        BankService bank = BankService.forTest(null);
        ProgressionService prog = ProgressionService.forTest();
        DealerService dealer = DealerService.forTest(1234L);
        Wallet.give(p, 261_500); // bill clip + price board + vault + loan note = $2,615
        for (String node : new String[] {"bill_clip", "price_board", "bank_vault", "loan_note"}) {
            check(prog.buyNode(p, node).isEmpty(), "buy " + node);
        }
        p.getInventory().add(new ItemStack(ModItems.SECURITY_PAPER));
        AtomicLong day = new AtomicLong(10);
        BankVaultMenu menu = new BankVaultMenu(1, p.getInventory(), vault, ContainerLevelAccess.NULL, bank, prog, dealer, day::get);
        menu.clickMenuButton(p, BankVaultMenu.BUTTON_TAB_LOANS);
        return new Desk(vault, bank, prog, dealer, day, menu);
    }

    static void post(Desk d, ItemStack... stacks) {
        for (int i = 0; i < stacks.length; i++) d.menu().vaultSlots().setItem(BankVaultMenu.COLLATERAL_START + i, stacks[i]);
    }

    static void amount(Desk d, ServerPlayer p, long cents) {
        while (d.menu().loanAmountCents() < cents) d.menu().clickMenuButton(p, BankVaultMenu.BUTTON_LOAN_PLUS_100);
        while (d.menu().loanAmountCents() > cents) d.menu().clickMenuButton(p, BankVaultMenu.BUTTON_LOAN_MINUS_10);
    }

    @GameTest
    public void borrowAgainstDiamondsThenRepayAndGetThemBack(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Desk d = desk(helper, p);
        check(d.prog().progress(p).hasGuide("leverage_and_collateral"), "the Loan Note grants its guide");
        post(d, new ItemStack(Items.DIAMOND, 15));
        d.menu().clickMenuButton(p, BankVaultMenu.BUTTON_TAB_LOANS);
        long expected = com.realisticmarkets.collateral.CollateralValuer.value(
                java.util.Map.of("minecraft:diamond", 15), 0, d.dealer().dealer(), 10).maxLoanCents();
        check(d.menu().maxLoanCents() == expected, "vault shows the valuer's max loan " + expected + ", shows " + d.menu().maxLoanCents());
        check(Math.abs(expected - 77_600) <= 20, "about $776, like the design doc's table: " + expected);
        check(d.menu().qualityPct() == 65, "quality 65%, shows " + d.menu().qualityPct());

        amount(d, p, 70_000);
        check(d.menu().clickMenuButton(p, BankVaultMenu.BUTTON_BORROW), "borrow $700");
        check(d.bank().account(p.getUUID(), 10).balanceCents() == 70_000, "loan paid into the balance");
        check(d.menu().vaultSlots().getItem(BankVaultMenu.COLLATERAL_START).isEmpty(), "diamonds went into escrow");
        check(d.bank().loan(p.getUUID()).orElseThrow().collateral().get("minecraft:diamond") == 15, "escrow holds 15 diamonds");
        check(p.getInventory().countItem(ModItems.LOAN_NOTE) == 1, "got the Loan Note statement");
        check(p.getInventory().countItem(ModItems.SECURITY_PAPER) == 0, "Security Paper used");
        check(!BankVaultBlock.mayBreak(d.bank(), d.vault(), p, 10), "vault locked while a loan is open");

        d.day().set(12);
        Wallet.give(p, 5_000);
        d.menu().clickMenuButton(p, BankVaultMenu.BUTTON_TAB_ACCOUNT);
        d.menu().clickMenuButton(p, BankVaultMenu.BUTTON_DEPOSIT_ALL);
        d.menu().clickMenuButton(p, BankVaultMenu.BUTTON_TAB_LOANS);
        check(d.menu().clickMenuButton(p, BankVaultMenu.BUTTON_REPAY_ALL), "repay everything from the balance");
        check(d.bank().loan(p.getUUID()).isEmpty(), "loan closed");
        check(p.getInventory().countItem(Items.DIAMOND) == 15, "diamonds returned");
        check(d.prog().progress(p).hasCompleted("leverage"), "Leverage quest");
        long left = d.bank().account(p.getUUID(), 12).balanceCents();
        check(left < 5_000 && left > 0, "two days of interest were paid out of the $50 buffer, left " + left);
        helper.succeed();
    }

    @GameTest
    public void classDCollateralIsRefused(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Desk d = desk(helper, p);
        post(d, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIAMOND, 5));
        d.menu().clickMenuButton(p, BankVaultMenu.BUTTON_TAB_LOANS);
        check(d.menu().slotRefused(), "cobblestone flagged");
        amount(d, p, 10_000);
        check(!d.menu().clickMenuButton(p, BankVaultMenu.BUTTON_BORROW), "no loan with class D collateral posted");
        check(d.bank().loan(p.getUUID()).isEmpty(), "nothing opened");
        check(d.menu().vaultSlots().getItem(BankVaultMenu.COLLATERAL_START).getCount() == 64, "cobblestone left in the slot");
        helper.succeed();
    }

    @GameTest
    public void aPriceCrashTriggersAMarginCallThenAForcedSale(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Desk d = desk(helper, p);
        post(d, new ItemStack(Items.DIAMOND, 15));
        amount(d, p, 77_000);
        check(d.menu().clickMenuButton(p, BankVaultMenu.BUTTON_BORROW), "borrow $770 against 15 diamonds");
        Loan loan = d.bank().loan(p.getUUID()).orElseThrow();
        List<Boolean> alarms = new ArrayList<>();

        d.dealer().dealer().sell("minecraft:diamond", 10, 10, false); // someone dumps diamonds
        d.bank().dawn(11, d.dealer().dealer(), id -> p, d.prog(), (owner, on) -> alarms.add(on));
        check(loan.underMarginCall(), "margin call at dawn");
        check(alarms.equals(List.of(true)), "vault light turns red, got " + alarms);

        d.dealer().dealer().sell("minecraft:diamond", 10, 11, false); // and again before the deadline
        long balanceBefore = d.bank().account(p.getUUID(), 12).balanceCents();
        d.bank().dawn(12, d.dealer().dealer(), id -> p, d.prog(), (owner, on) -> alarms.add(on));
        check(d.bank().loan(p.getUUID()).isEmpty(), "forced sale paid the loan off and closed it");
        long surplus = d.bank().account(p.getUUID(), 12).balanceCents() - balanceBefore;
        check(surplus > 0, "sale money beyond the debt returned to the balance, got " + surplus);
        check(p.getInventory().countItem(Items.DIAMOND) == 0, "the diamonds were sold, not returned");
        check(!alarms.getLast(), "light off once settled");
        check(!d.prog().progress(p).hasCompleted("leverage"), "a forced sale isn't repaying");
        helper.succeed();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
