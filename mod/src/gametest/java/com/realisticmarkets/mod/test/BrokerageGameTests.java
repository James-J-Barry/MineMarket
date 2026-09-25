package com.realisticmarkets.mod.test;

import com.realisticmarkets.bonds.Bond;
import com.realisticmarkets.custody.BookEntries;
import com.realisticmarkets.custody.BookEntries.Kind;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.block.BrokerageTerminalBlockEntity;
import com.realisticmarkets.mod.block.RecordsTerminalBlockEntity;
import com.realisticmarkets.mod.bonds.BondPapers;
import com.realisticmarkets.mod.bonds.BondService;
import com.realisticmarkets.mod.brokerage.BrokerageService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.menu.BrokerageMenu;
import com.realisticmarkets.mod.options.OptionPapers;
import com.realisticmarkets.mod.options.OptionsService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.records.RecordLinkItem;
import com.realisticmarkets.mod.records.RecordsService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import com.realisticmarkets.mod.stocks.StockService;
import com.realisticmarkets.options.OptionDesk;
import com.realisticmarkets.records.NetWorth;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

/** M10b: the Brokerage Terminal. Paper to book entry and back, income at dawn, the Records Terminal, the Markets tab. */
public class BrokerageGameTests {

    record Setup(ServerPlayer p, DealerService dealer, ProgressionService prog, StockService stocks, BondService bonds,
                 OptionsService options, BrokerageService brokerage, BrokerageMenu menu) {}

    static Setup setup(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        DealerService dealer = DealerService.forTest(1234L);
        ProgressionService prog = ProgressionService.forTest();
        ForwardGameTests.unlock(p, prog, 7, "brokerage_terminal");
        StockService stocks = StockService.forTest(dealer, 7L);
        BondService bonds = BondService.forTest(BankService.forTest(null), stocks);
        OptionsService options = OptionsService.forTest(dealer, stocks, null);
        BrokerageService brokerage = BrokerageService.forTest(dealer, stocks, bonds, options);
        BrokerageMenu m = new BrokerageMenu(1, p.getInventory(), ContainerLevelAccess.NULL, brokerage, prog, dealer);
        return new Setup(p, dealer, prog, stocks, bonds, options, brokerage, m);
    }

    @GameTest
    public void papersConvertToBookEntryAndBack(GameTestHelper helper) {
        Setup s = setup(helper);
        check(s.menu().owner() && s.prog().progress(s.p()).hasGuide("book_entry"), "unlocked, with the Book Entry guide");
        long day = (long) Math.floor(s.dealer().day(helper.getLevel().getGameTime()));
        Bond bond = s.bonds().desk().issue(Bond.TREASURY, 4, day);
        var call = new OptionDesk.Series("WHT", true, 13_000, OptionDesk.expiries(day)[1]);
        s.p().getInventory().add(ShareCertificates.create("OWL", 100, -1, 1));
        s.p().getInventory().add(ShareCertificates.create("OWL", 10, -1, 1));
        s.p().getInventory().add(BondPapers.create(bond, 0, 5));
        s.p().getInventory().add(OptionPapers.create(call, 3));
        check(s.menu().clickMenuButton(s.p(), BrokerageMenu.BUTTON_DEPOSIT), "deposit every paper");
        check(s.p().getInventory().countItem(ModItems.SHARE_CERTIFICATE) == 0 && s.p().getInventory().countItem(ModItems.BOND) == 0
                && s.p().getInventory().countItem(ModItems.OPTION_CONTRACT) == 0, "no papers left");
        String a = BrokerageService.account(s.p());
        check(s.brokerage().books().quantity(a, Kind.SHARE, "OWL") == 110, "110 OWL in book entry");
        check(s.brokerage().books().quantity(a, Kind.BOND, BookEntries.bondKey(bond)) == 5, "5 bonds");
        check(s.brokerage().books().quantity(a, Kind.OPTION, call.key()) == 3, "3 calls");
        check(s.prog().progress(s.p()).hasCompleted("paperless"), "Paperless");
        check(s.menu().rowCount() == 3 && s.menu().total() > 0, "Holdings lists them at value");

        for (int i = 0; i < s.menu().rowCount(); i++) {
            s.menu().clickMenuButton(s.p(), BrokerageMenu.BUTTON_ROW_BASE);
            check(s.menu().clickMenuButton(s.p(), BrokerageMenu.BUTTON_WITHDRAW), "withdraw a holding as papers");
        }
        while (s.menu().rowCount() > 0) {
            s.menu().clickMenuButton(s.p(), BrokerageMenu.BUTTON_ROW_BASE);
            s.menu().clickMenuButton(s.p(), BrokerageMenu.BUTTON_WITHDRAW);
        }
        var shares = ShareCertificates.holdings(s.p().getInventory());
        check(shares.getOrDefault("OWL", 0L) == 110, "110 OWL back as papers: " + shares);
        check(s.p().getInventory().countItem(ModItems.SHARE_CERTIFICATE) == 2, "in the fewest certificates (a 100 and a 10)");
        check(s.p().getInventory().countItem(ModItems.BOND) == 5 && s.p().getInventory().countItem(ModItems.OPTION_CONTRACT) == 3,
                "the bonds and calls too");
        check(BondPapers.read(s.p().getInventory().getItem(find(s.p(), ModItems.BOND))).orElseThrow().bond().equals(bond), "the same bond");
        check(s.brokerage().books().entries(a).isEmpty(), "the books are empty again");
        helper.succeed();
    }

    @GameTest
    public void bookEntryIncomeIsCreditedAtDawnAndTheTerminalCountsIt(GameTestHelper helper) {
        Setup s = setup(helper);
        long day = (long) Math.floor(s.dealer().day(helper.getLevel().getGameTime()));
        Bond bond = s.bonds().desk().issue(Bond.TREASURY, 2, day);
        s.p().getInventory().add(BondPapers.create(bond, 0, 10));
        s.brokerage().depositAll(s.p(), day, s.prog());
        String a = BrokerageService.account(s.p());
        s.brokerage().dawn(day + 1, id -> s.p(), s.prog());
        check(s.brokerage().books().cash(a) == 0, "no coupon yet");
        s.brokerage().dawn(bond.couponDay(1), id -> s.p(), s.prog());
        check(s.brokerage().books().cash(a) == 10 * bond.couponCents(), "the first coupon, automatically: " + s.brokerage().books().cash(a));
        s.brokerage().dawn(bond.couponDay(1) + 1, id -> s.p(), s.prog());
        check(s.brokerage().books().cash(a) == 10 * bond.couponCents(), "once");

        BlockPos tPos = new BlockPos(1, 1, 1), bPos = new BlockPos(3, 1, 1);
        helper.setBlock(tPos, ModBlocks.RECORDS_TERMINAL);
        helper.setBlock(bPos, ModBlocks.BROKERAGE_TERMINAL);
        RecordsTerminalBlockEntity terminal = helper.getBlockEntity(tPos, RecordsTerminalBlockEntity.class);
        terminal.setOwner(s.p());
        helper.getBlockEntity(bPos, BrokerageTerminalBlockEntity.class).setOwner(s.p());
        ItemStack tool = new ItemStack(ModItems.RECORD_LINK);
        RecordLinkItem.use(s.p(), helper.getLevel(), helper.absolutePos(tPos), tool);
        check(RecordLinkItem.use(s.p(), helper.getLevel(), helper.absolutePos(bPos), tool).startsWith("Linked"), "link the brokerage");
        RecordsService records = RecordsService.forTest(new RecordsService.Sources(s.dealer(), s.prog(), null, s.stocks(), s.bonds(), null,
                null, null, s.options(), s.brokerage()));
        var view = records.view(terminal, s.p().getUUID(), bond.couponDay(1) + 0.5);
        check(view.netWorth().byKind().get(NetWorth.Kind.BONDS) > 0, "the terminal sees the book-entry bonds");
        check(view.netWorth().byKind().get(NetWorth.Kind.CASH) == 10 * bond.couponCents(), "and the brokerage cash");

        long before = Wallet.count(s.p().getInventory());
        s.menu().clickMenuButton(s.p(), BrokerageMenu.TAB_CASH);
        check(s.menu().clickMenuButton(s.p(), BrokerageMenu.BUTTON_CASH_OUT), "withdraw the cash");
        check(Wallet.count(s.p().getInventory()) - before == 10 * bond.couponCents() / 10 * 10, "paid in bills");
        helper.succeed();
    }

    static int find(ServerPlayer p, net.minecraft.world.item.Item item) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(item)) return i;
        throw new IllegalStateException("none");
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
