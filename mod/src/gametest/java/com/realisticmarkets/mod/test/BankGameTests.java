package com.realisticmarkets.mod.test;

import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.bank.CdItem;
import com.realisticmarkets.mod.block.BankVaultBlock;
import com.realisticmarkets.mod.block.BankVaultBlockEntity;
import com.realisticmarkets.mod.dealer.BillClip;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.menu.BankVaultMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Denomination;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

/** M4a acceptance tests: the Bank Vault, interest, the empty-to-break rule, and Certificates of Deposit. */
public class BankGameTests {

    record Bank(BankVaultBlockEntity vault, BankService bank, ProgressionService prog, AtomicLong day, BankVaultMenu menu) {}

    static Bank bank(GameTestHelper helper, ServerPlayer owner) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ModBlocks.BANK_VAULT);
        BankVaultBlockEntity vault = helper.getBlockEntity(pos, BankVaultBlockEntity.class);
        vault.setOwner(owner);
        BankService bank = BankService.forTest(null);
        ProgressionService prog = ProgressionService.forTest();
        AtomicLong day = new AtomicLong(10);
        BankVaultMenu menu = new BankVaultMenu(1, owner.getInventory(), vault, ContainerLevelAccess.NULL, bank, prog, day::get);
        return new Bank(vault, bank, prog, day, menu);
    }

    @GameTest
    public void depositAllThenWithdrawIntoTheClip(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        ItemStack clip = new ItemStack(ModItems.BILL_CLIP);
        BillClip.insert(clip, bill(Denomination.HUNDRED, 1));
        p.getInventory().add(clip);
        p.getInventory().add(bill(Denomination.ONE, 5));
        Bank b = bank(helper, p);

        check(b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_DEPOSIT_ALL), "deposit");
        check(Wallet.count(p.getInventory()) == 0, "all cash deposited, clip included");
        check(b.bank().account(p.getUUID(), 10).balanceCents() == 10_500, "balance $105");
        check(b.menu().balanceCents() == 0, "no Passbook in the slot: balance hidden");

        check(b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_PASSBOOK), "first Passbook is free");
        ItemStack passbook = find(p, ModItems.PASSBOOK);
        check(!passbook.isEmpty(), "got a Passbook");
        b.menu().vaultSlots().setItem(BankVaultMenu.PASSBOOK_SLOT, passbook.copy());
        passbook.shrink(1);
        b.menu().broadcastChanges();
        b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_TAB_ACCOUNT); // refresh now
        check(b.menu().hasPassbook() && b.menu().balanceCents() == 10_500, "Passbook shows $105, got " + b.menu().balanceCents());
        check(!b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_PASSBOOK), "a second Passbook needs Ledger Paper");

        check(b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_WITHDRAW_10), "withdraw $10");
        ItemStack held = find(p, ModItems.BILL_CLIP); // the inventory holds its own copy of the stack
        check(BillClip.cents(held) == 1_000, "the $10 lands in the clip, clip holds " + BillClip.cents(held));
        check(b.menu().balanceCents() == 9_500, "balance $95");
        check(!b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_WITHDRAW_100), "can't withdraw more than the balance");
        helper.succeed();
    }

    @GameTest
    public void interestCompoundsDailyAndCompletesNestEgg(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Wallet.give(p, 100_000);
        Bank b = bank(helper, p);
        b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_DEPOSIT_ALL);

        b.day().set(11);
        check(b.bank().accrue(p, 11, b.prog()) == 300, "one day on $1,000 is $3.00");
        check(!b.prog().progress(p).hasCompleted("nest_egg"), "not $10 yet");
        b.day().set(14);
        b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_TAB_ACCOUNT); // any visit credits interest
        long balance = b.bank().account(p.getUUID(), 14).balanceCents();
        check(balance == 101_200, "four days compounded: $1,012.00 (to the dime), got " + balance);
        check(b.prog().progress(p).hasCompleted("nest_egg"), "Nest Egg after $12 of interest");
        check(Wallet.count(p.getInventory()) == 1_000, "$10 quest reward, got " + Wallet.count(p.getInventory()));
        helper.succeed();
    }

    @GameTest
    public void vaultBreaksOnlyWhenEmptyAndOnlyForItsOwner(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        stranger.setUUID(UUID.randomUUID());
        Wallet.give(p, 5_000);
        Bank b = bank(helper, p);

        check(BankVaultBlock.mayBreak(b.bank(), b.vault(), p, 10), "an empty vault can be broken by its owner");
        check(!BankVaultBlock.mayBreak(b.bank(), b.vault(), stranger, 10), "never by someone else");
        b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_DEPOSIT_ALL);
        check(!BankVaultBlock.mayBreak(b.bank(), b.vault(), p, 10), "locked while it holds money");
        check(b.vault().locked(), "clients see it locked");
        b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_WITHDRAW_ALL);
        check(BankVaultBlock.mayBreak(b.bank(), b.vault(), p, 10), "breakable again once empty");
        check(!b.vault().locked(), "clients see it unlocked");

        check(b.bank().claimVault(p.getUUID(), "here", 10, old -> false), "first vault claimed");
        check(!b.bank().claimVault(p.getUUID(), "there", 10, old -> true), "a second vault is refused while the first stands");
        check(b.bank().claimVault(p.getUUID(), "there", 10, old -> false), "allowed once the first is gone");
        helper.succeed();
    }

    @GameTest
    public void cdIssuedMaturedAndRedeemedOnlyOnce(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Bank b = bank(helper, p);
        Wallet.give(p, 300_000); // $1,815 of upgrades, the rest deposited
        for (String node : new String[] {"bill_clip", "price_board", "bank_vault", "certificate_of_deposit"}) {
            check(b.prog().buyNode(p, node).isEmpty(), "buy " + node);
        }
        b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_DEPOSIT_ALL);
        long before = b.bank().account(p.getUUID(), 10).balanceCents();

        b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_TAB_CDS);
        check(b.menu().hasCdPerk(), "CD perk unlocked");
        for (int i = 0; i < 4; i++) b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_CD_PLUS_100); // $500
        check(b.menu().cdAmountCents() == 50_000, "amount $500");
        check(!b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_CD_ISSUE), "needs a Security Paper");
        p.getInventory().add(new ItemStack(ModItems.SECURITY_PAPER));
        check(b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_CD_ISSUE), "issue a 7-day CD");
        check(p.getInventory().countItem(ModItems.SECURITY_PAPER) == 0, "paper used");
        ItemStack cd = find(p, ModItems.CERTIFICATE_OF_DEPOSIT);
        check(CdItem.serial(cd).isPresent(), "CD has a serial");
        check(b.bank().account(p.getUUID(), 10).balanceCents() == before - 50_000, "principal left the balance");
        ItemStack copy = cd.copy();

        b.day().set(17);
        b.menu().vaultSlots().setItem(BankVaultMenu.CD_SLOT, cd.copy());
        cd.shrink(1);
        b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_TAB_CDS);
        check(b.menu().cdSlotValueCents() == 51_590, "matured value $515.90, shows " + b.menu().cdSlotValueCents());
        check(b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_CD_REDEEM), "redeem at maturity");
        long after = b.bank().account(p.getUUID(), 17).balanceCents();
        check(after >= before - 50_000 + 51_590, "matured value credited (plus interest), got " + after);
        check(b.prog().progress(p).hasCompleted("locked_in"), "Locked In quest");

        b.menu().vaultSlots().setItem(BankVaultMenu.CD_SLOT, copy);
        check(!b.menu().clickMenuButton(p, BankVaultMenu.BUTTON_CD_REDEEM), "a copied CD is refused");
        check(CdItem.isVoid(b.menu().vaultSlots().getItem(BankVaultMenu.CD_SLOT)), "and marked VOID");
        check(b.bank().account(p.getUUID(), 17).balanceCents() == after, "no money for the copy");
        helper.succeed();
    }

    // ------------------------------------------------------------------ helpers

    static ItemStack bill(Denomination d, int n) {
        return new ItemStack(ModItems.CURRENCY.get(d), n);
    }

    static ItemStack find(ServerPlayer p, net.minecraft.world.item.Item item) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).is(item)) return p.getInventory().getItem(i);
        }
        return ItemStack.EMPTY;
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
