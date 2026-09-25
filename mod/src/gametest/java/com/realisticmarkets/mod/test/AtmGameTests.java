package com.realisticmarkets.mod.test;

import com.realisticmarkets.contracts.BankAccount;
import com.realisticmarkets.mod.bank.Atm;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.menu.BankVaultMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;

/** M10a: the ATM, the Bank Card and the Pocket ATM. */
public class AtmGameTests {

    @GameTest
    public void theAtmReachesTheAccountButNotCdsOrLoans(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        DealerService dealer = DealerService.forTest(1234L);
        ProgressionService prog = ProgressionService.forTest();
        BankService bank = BankService.forTest(null);
        long day = 3;
        check(Atm.open(p, ContainerLevelAccess.NULL, bank, prog, dealer, () -> day) != null, "no account, no ATM");
        bank.account(p.getUUID(), day).deposit(50_000, day, BankAccount.Kind.DEPOSIT);
        check(Atm.open(p, ContainerLevelAccess.NULL, bank, prog, dealer, () -> day) == null, "with an account it opens");
        check(prog.progress(p).hasCompleted("cashless"), "Cashless");

        BankVaultMenu m = BankVaultMenu.remote(1, p.getInventory(), ContainerLevelAccess.NULL, bank, prog, dealer, () -> day);
        check(m.isRemote() && m.balanceCents() == 50_000, "the vault's balance, from afar: " + m.isRemote() + " " + m.balanceCents());
        check(m.clickMenuButton(p, BankVaultMenu.BUTTON_WITHDRAW_100), "withdraw $100");
        check(Wallet.count(p.getInventory()) >= 10_000 && bank.account(p.getUUID(), day).balanceCents() == 40_000,
                "the bills arrive and the balance falls");
        check(m.clickMenuButton(p, BankVaultMenu.BUTTON_DEPOSIT_ALL), "deposit it back");
        check(bank.account(p.getUUID(), day).balanceCents() >= 50_000, "back in the account");
        check(!m.clickMenuButton(p, BankVaultMenu.BUTTON_TAB_CDS), "CDs are at the vault");
        check(!m.clickMenuButton(p, BankVaultMenu.BUTTON_TAB_LOANS), "and loans");
        check(m.tab() == BankVaultMenu.TAB_ACCOUNT, "still on the Account tab");
        check(m.stillValid(p), "a Pocket ATM (no block) stays open anywhere");
        helper.succeed();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
