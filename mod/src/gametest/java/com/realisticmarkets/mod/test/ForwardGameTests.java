package com.realisticmarkets.mod.test;

import com.realisticmarkets.contracts.ForwardBook;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.forwards.ForwardService;
import com.realisticmarkets.mod.menu.BasicExchangeMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.progression.UnlockNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** M8a: Forward Contracts at the Basic Exchange. */
public class ForwardGameTests {
    static final String WHEAT = "minecraft:wheat";

    /** Buys every node of Tiers 1..{@code tier} the gates allow (with plenty of cash), then {@code nodes}. */
    static void unlock(ServerPlayer p, ProgressionService prog, int tier, String... nodes) {
        for (int t = 1; t <= tier; t++) for (UnlockNode n : prog.tree().tier(t)) buy(p, prog, n.id());
        for (String n : nodes) {
            var why = buy(p, prog, n);
            check(why.isEmpty() || prog.progress(p).hasNode(n), "buy " + n + ": " + why.orElse(""));
        }
    }

    /** Buys one node with exactly its cost in hand (a wallet of $500k wouldn't fit in an inventory). */
    static java.util.Optional<String> buy(ServerPlayer p, ProgressionService prog, String node) {
        Wallet.takeAll(p);
        Wallet.give(p, prog.tree().node(node).costCents());
        var why = prog.buyNode(p, node);
        Wallet.takeAll(p);
        return why;
    }

    record Setup(ServerPlayer p, DealerService dealer, ProgressionService prog, ForwardService forwards, BasicExchangeMenu menu) {}

    static Setup setup(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        DealerService dealer = DealerService.forTest(1234L);
        ProgressionService prog = ProgressionService.forTest();
        unlock(p, prog, 5, "forward_contract");
        ForwardService fs = ForwardService.forTest(dealer);
        BasicExchangeMenu m = new BasicExchangeMenu(1, p.getInventory(), ContainerLevelAccess.NULL, dealer, prog);
        m.testForwards = fs;
        return new Setup(p, dealer, prog, fs, m);
    }

    static void tick(BasicExchangeMenu m) {
        for (int i = 0; i < 10; i++) m.broadcastChanges();
    }

    @GameTest
    public void signingTakesTheDepositAndASecurityPaper(GameTestHelper helper) {
        Setup s = setup(helper);
        check(s.prog().progress(s.p()).hasGuide("hedging"), "the node grants Hedging");
        s.menu().getSlot(BasicExchangeMenu.INPUT).set(new ItemStack(Items.WHEAT, 64));
        check(s.menu().clickMenuButton(s.p(), BasicExchangeMenu.BUTTON_TAB_FORWARD), "open the Forward tab");
        s.menu().clickMenuButton(s.p(), BasicExchangeMenu.BUTTON_FWD_QTY_BASE + 3); // +64: 128
        tick(s.menu());
        check(s.menu().forwardQuantity() == 128 && s.menu().forwardTerm() == 7, "128 wheat in 7 days");
        long price = s.menu().forwardPriceCents(), deposit = s.menu().forwardDepositCents();
        check(s.menu().forwardStatus() == BasicExchangeMenu.FWD_OK && price > 0, "a price: " + price);
        check(!s.menu().clickMenuButton(s.p(), BasicExchangeMenu.BUTTON_FWD_SIGN), "no Security Paper, no deal");
        s.p().getInventory().add(new ItemStack(ModItems.SECURITY_PAPER, 2));
        Wallet.give(s.p(), deposit + 1_000);
        check(s.menu().clickMenuButton(s.p(), BasicExchangeMenu.BUTTON_FWD_SIGN), "sign");
        var f0 = s.forwards().open(s.p()).get(0);
        check(f0.priceCents() == price && f0.depositCents() == deposit, "terms " + f0 + " vs shown " + price + "/" + deposit);
        check(Wallet.count(s.p().getInventory()) == 1_000, "the deposit was taken: " + Wallet.count(s.p().getInventory()));
        check(s.p().getInventory().countItem(ModItems.SECURITY_PAPER) == 1, "one Security Paper used");
        check(s.p().getInventory().countItem(ModItems.FORWARD_CONTRACT) == 1, "a Forward Contract paper printed");
        var f = s.forwards().open(s.p()).get(0);
        check(f.quantity() == 128 && f.priceCents() == price && f.depositCents() == deposit, "the terms shown");
        check(s.menu().getSlot(BasicExchangeMenu.INPUT).getItem().getCount() == 64, "the wheat in the slot only picked the good");

        // The Records Terminal counts the deposit and puts the delivery on its calendar, linked blocks or not.
        var records = com.realisticmarkets.mod.records.RecordsService.forTest(new com.realisticmarkets.mod.records.RecordsService.Sources(
                s.dealer(), s.prog(), null, null, null, null, s.forwards(), null, null));
        net.minecraft.core.BlockPos tPos = new net.minecraft.core.BlockPos(1, 1, 1);
        helper.setBlock(tPos, com.realisticmarkets.mod.registry.ModBlocks.RECORDS_TERMINAL);
        var terminal = helper.getBlockEntity(tPos, com.realisticmarkets.mod.block.RecordsTerminalBlockEntity.class);
        terminal.setOwner(s.p());
        var view = records.view(terminal, s.p().getUUID(), s.dealer().day(helper.getLevel().getGameTime()));
        check(view.netWorth().total() == deposit, "the deposit counts: " + view.netWorth().total());
        check(view.calendar().upcoming(f.signedDay(), 9).stream().anyMatch(e -> e.kind()
                == com.realisticmarkets.records.Calendar.Kind.FORWARD_DELIVERY && e.day() == f.deliveryDay() && e.cents() == price),
                "the delivery is on the calendar");
        helper.succeed();
    }

    @GameTest
    public void deliveringPaysThePriceEvenAfterACrashAndIsHedged(GameTestHelper helper) {
        Setup s = setup(helper);
        double day = s.dealer().day(helper.getLevel().getGameTime());
        s.p().getInventory().add(new ItemStack(ModItems.SECURITY_PAPER, 1));
        Wallet.give(s.p(), 100_000);
        check(s.forwards().sign(s.p(), WHEAT, 64, 3, day, false).isEmpty(), "sign 64 wheat for 3 days");
        var f = s.forwards().open(s.p()).get(0);
        Wallet.takeAll(s.p());
        check(s.forwards().deliver(s.p(), f.id(), day, false, s.prog()).orElse("").startsWith("Not due"), "not due yet");
        s.dealer().shiftDays(3);
        double later = s.dealer().day(helper.getLevel().getGameTime());
        s.dealer().dealer().sell(WHEAT, 1_000, later, false); // someone floods the wheat market
        check(s.forwards().deliver(s.p(), f.id(), later, false, s.prog()).orElse("").startsWith("Bring 64"), "needs the wheat");
        s.p().getInventory().add(new ItemStack(Items.WHEAT, 64));
        long spot;
        try {
            spot = s.dealer().dealer().quoteSell(WHEAT, 64, later, false).cents();
        } catch (com.realisticmarkets.exchange.RejectedException collapsed) {
            spot = 0;
        }
        check(s.forwards().deliver(s.p(), f.id(), later, false, s.prog()).isEmpty(), "deliver");
        long paid = Wallet.count(s.p().getInventory());
        check(paid == f.priceCents() + f.depositCents() + 6_000, "the agreed price, the deposit back and Hedged's $60: " + paid);
        check(f.priceCents() > spot, "more than the crashed market pays: " + f.priceCents() + " vs " + spot);
        check(s.p().getInventory().countItem(Items.WHEAT) == 0, "the wheat was delivered");
        check(s.forwards().open(s.p()).isEmpty(), "and the forward closed");
        check(s.prog().progress(s.p()).hasCompleted("hedged"), "Hedged");
        helper.succeed();
    }

    @GameTest
    public void anUndeliveredForwardDefaultsAndLosesItsDeposit(GameTestHelper helper) {
        Setup s = setup(helper);
        long today = (long) Math.floor(s.dealer().day(helper.getLevel().getGameTime()));
        s.p().getInventory().add(new ItemStack(ModItems.SECURITY_PAPER, 1));
        Wallet.give(s.p(), 100_000);
        check(s.forwards().sign(s.p(), WHEAT, 256, 7, today, false).isEmpty(), "sign");
        long cash = Wallet.count(s.p().getInventory());
        check(s.forwards().dawn(today + 8, id -> s.p(), s.prog()).isEmpty(), "still open the day after delivery day");
        var gone = s.forwards().dawn(today + 9, id -> s.p(), s.prog());
        check(gone.size() == 1 && s.forwards().open(s.p()).isEmpty(), "defaulted at dawn two days after");
        check(Wallet.count(s.p().getInventory()) == cash, "no deposit back");
        check(s.forwards().book().pendingBaseUnits(s.dealer().dealer(), WHEAT) == 0, "and it no longer weighs on new forwards");
        check(ForwardBook.deposit(gone.get(0).priceCents()) == gone.get(0).depositCents(), "the deposit was 20%");
        helper.succeed();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
