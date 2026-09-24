package com.realisticmarkets.mod.test;

import com.realisticmarkets.bonds.Bond;
import com.realisticmarkets.bonds.CreditModel;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.bonds.BondPapers;
import com.realisticmarkets.mod.bonds.BondService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.menu.BondDeskMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.stocks.StockService;
import com.realisticmarkets.rates.CentralBank;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

/** M7a: the Bond Desk. Buying, coupons, maturity, rate moves, defaults, the bond quests. */
public class BondGameTests {
    record Desk(BondService bonds, StockService stocks, ProgressionService prog) {}

    static Desk desk(CentralBank central) {
        DealerService dealer = DealerService.forTest(1234L);
        StockService stocks = StockService.forTest(dealer, 7L);
        return new Desk(BondService.forTest(BankService.forTest(null, central), stocks), stocks, ProgressionService.forTest());
    }

    static long bonds(ServerPlayer p) {
        return p.getInventory().countItem(ModItems.BOND);
    }

    @GameTest
    public void buyingPaysOutBondPapers(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Wallet.give(p, 500_000);
        Desk d = desk(CentralBank.constant(0.003));
        check(d.bonds().buy(p, Bond.TREASURY, 4, 10, 3).isEmpty(), "buy 10 four-quarter Treasuries");
        check(bonds(p) == 10, "10 bond papers");
        check(Wallet.count(p.getInventory()) == 500_000 - 100_250, "paid $100.25 each: " + Wallet.count(p.getInventory()));
        var paper = BondPapers.read(p.getInventory().getItem(find(p))).orElseThrow();
        check(paper.bond().maturityDay() == 3 + 28 && paper.paidThrough() == 0, "matures in 4 quarters, nothing paid yet");
        check(paper.bond().couponCents() >= 211, "coupon at least today's rate");
        var menu = new BondDeskMenu(1, p.getInventory(), ContainerLevelAccess.NULL, d.bonds(), d.prog(), DealerService.forTest(1234L));
        check(menu.yieldMilliPct(0, 2) > menu.yieldMilliPct(0, 0), "the curve slopes up");
        check(menu.yieldMilliPct(BondDeskMenu.ISSUERS.indexOf("RSD"), 1) > menu.yieldMilliPct(0, 1), "companies pay more than the Treasury");
        helper.succeed();
    }

    @GameTest
    public void couponsPayOncePerQuarterAndTheFaceAtMaturity(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Wallet.give(p, 200_000);
        Desk d = desk(CentralBank.constant(0.003));
        check(d.bonds().buy(p, Bond.TREASURY, 2, 10, 0).isEmpty(), "buy 10 two-quarter bonds");
        long coupon = BondPapers.read(p.getInventory().getItem(find(p))).orElseThrow().bond().couponCents();
        long before = Wallet.count(p.getInventory());
        check(d.bonds().present(p, d.prog(), 7) == 10 * coupon / 10 * 10, "the first coupon on 10 bonds");
        long afterCoupon = Wallet.count(p.getInventory());
        check(afterCoupon - before >= 10 * coupon / 10 * 10, "paid in bills");
        check(d.bonds().present(p, d.prog(), 7) == 0, "nothing more this quarter");
        check(d.prog().progress(p).hasCompleted("coupon_clipper"), "Coupon Clipper");
        long paid = d.bonds().present(p, d.prog(), 14);
        check(paid == (10 * coupon + 10 * Bond.FACE_CENTS) / 10 * 10, "at maturity: the last coupon and $100 each, got " + paid);
        check(bonds(p) == 0, "the papers are handed in");
        check(d.prog().progress(p).hasCompleted("held_to_maturity"), "Held to Maturity");
        helper.succeed();
    }

    @GameTest
    public void aRateRiseLowersWhatTheDeskPays(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Wallet.give(p, 200_000);
        CentralBank central = new CentralBank(11L);
        long q = 1;
        while (central.decisionOn(q * 7).orElseThrow().move() != CentralBank.Move.RAISE) q++;
        var raise = central.decisionOn(q * 7).orElseThrow();
        Desk d = desk(central);
        check(d.bonds().buy(p, Bond.TREASURY, 8, 5, raise.day() - 3).isEmpty(), "buy long bonds before the raise");
        long before = d.bonds().holdings(p, raise.day() + raise.delay() - 0.01).getFirst().bidCents();
        long after = d.bonds().holdings(p, raise.day() + raise.delay() + 0.01).getFirst().bidCents();
        check(after < before - 100, "the desk pays over $1 less a bond once the market hears: " + before + " -> " + after);
        helper.succeed();
    }

    @GameTest
    public void aDefaultedBondPaysTheRecovery(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Wallet.give(p, 200_000);
        Desk d = desk(CentralBank.constant(0.003));
        d.stocks().equities().observe(0, k -> base(k), List.of());
        check(d.bonds().buy(p, "DSMC", 8, 10, 0).isEmpty(), "buy Deepslate Mining bonds");
        long coupon = BondPapers.read(p.getInventory().getItem(find(p))).orElseThrow().bond().couponCents();
        for (long day = 1; day <= 14; day++) d.stocks().equities().observe(day, k -> base(k) * (k.startsWith("minecraft:") ? 0.3 : 1), List.of());
        check(d.bonds().holdings(p, 15).getFirst().defaulted(), "two losing quarters: default");
        long paid = d.bonds().present(p, d.prog(), 15);
        check(paid == (10 * coupon + 10 * CreditModel.recoveryCents()) / 10 * 10,
                "the coupon due before the default and $40 a bond: " + paid);
        check(bonds(p) == 0, "handed in");
        check(!d.prog().progress(p).hasCompleted("held_to_maturity"), "a default isn't holding to maturity");
        helper.succeed();
    }

    @GameTest
    public void sellingAfterACutAtAProfitIsRateWatcher(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Wallet.give(p, 200_000);
        CentralBank central = new CentralBank(11L);
        long q = 1;
        while (central.decisionOn(q * 7).orElseThrow().move() != CentralBank.Move.CUT) q++;
        var cut = central.decisionOn(q * 7).orElseThrow();
        Desk d = desk(central);
        check(d.bonds().buy(p, Bond.TREASURY, 8, 5, cut.day() - 1).isEmpty(), "buy long bonds the day before a cut");
        String series = BondPapers.read(p.getInventory().getItem(find(p))).orElseThrow().bond().series();
        long before = Wallet.count(p.getInventory());
        check(d.bonds().sell(p, series, d.prog(), cut.day() + 1).isEmpty(), "sell after the cut");
        check(bonds(p) == 0, "sold");
        check(Wallet.count(p.getInventory()) - before > 5 * 10_025, "for more than was paid");
        check(d.prog().progress(p).hasCompleted("rate_watcher"), "Rate Watcher");
        helper.succeed();
    }

    static double base(String k) {
        var cat = com.realisticmarkets.dealer.DealerCatalog.loadDefault();
        return cat.trades(k) ? cat.spec(k).fairValue() : 1.0;
    }

    static int find(ServerPlayer p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(ModItems.BOND)) return i;
        throw new IllegalStateException("no bond");
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
