package com.realisticmarkets.mod.test;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.menu.StockExchangeMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import com.realisticmarkets.mod.stocks.StockService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

/** M6a acceptance tests: buying and selling shares, dividends on presentation, split and merge, the share quests. */
public class StockGameTests {
    record Desk(StockService stocks, DealerService dealer, ProgressionService prog, StockExchangeMenu menu, double day) {}

    static Desk desk(GameTestHelper helper, ServerPlayer p) {
        DealerService dealer = DealerService.forTest(1234L);
        StockService stocks = StockService.forTest(dealer, 7L);
        ProgressionService prog = ProgressionService.forTest();
        StockExchangeMenu menu = new StockExchangeMenu(1, p.getInventory(), ContainerLevelAccess.NULL, stocks, prog, dealer);
        return new Desk(stocks, dealer, prog, menu, dealer.day(helper.getLevel().getGameTime()));
    }

    static int book(String ticker) {
        for (int i = 0; i < StockExchangeMenu.BOOKS.size(); i++) if (StockExchangeMenu.BOOKS.get(i).item().equals(ticker)) return i;
        throw new IllegalStateException("no book " + ticker);
    }

    static void press(Desk d, ServerPlayer p, int button, int times) {
        for (int i = 0; i < times; i++) d.menu().clickMenuButton(p, button);
    }

    static long shares(ServerPlayer p, net.minecraft.world.Container extra, String ticker) {
        long n = ShareCertificates.holdings(p.getInventory()).getOrDefault(ticker, 0L);
        if (extra != null) n += ShareCertificates.holdings(extra).getOrDefault(ticker, 0L);
        return n;
    }

    @GameTest
    public void buyingSharesPaysOutCertificates(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        Wallet.give(p, 300_000); // a market buy escrows up to 1.5x the price
        p.getInventory().add(new ItemStack(ModItems.ORDER_SLIP));
        Desk d = desk(helper, p);
        press(d, p, StockExchangeMenu.BUTTON_BOOK_BASE + book("DSMC"), 1);
        press(d, p, StockExchangeMenu.BUTTON_MARKET, 1);
        check(d.menu().qty() == 10, "10 shares");
        check(d.menu().clickMenuButton(p, StockExchangeMenu.BUTTON_PLACE), "place a market buy");
        d.stocks().runAuctions(d.day());
        press(d, p, StockExchangeMenu.BUTTON_TAB_TRADE, 1); // any click refreshes and delivers
        check(shares(p, d.menu().output(), "DSMC") == 10, "10 DSMC shares as certificates, got " + shares(p, d.menu().output(), "DSMC"));
        ItemStack cert = d.menu().output().getItem(0);
        var paper = ShareCertificates.read(cert).orElseThrow();
        check(paper.denomination() == 10 && cert.getCount() == 1, "one 10-share certificate: " + cert);
        check(paper.paidThrough() == d.stocks().equities().lastReportedQuarter(), "paid up to date");
        long refund = 0;
        for (int i = 0; i < d.menu().output().getContainerSize(); i++) {
            var den = ModItems.denominationOf(d.menu().output().getItem(i));
            if (den != null) refund += den.cents() * d.menu().output().getItem(i).getCount();
        }
        long spent = 300_000 - Wallet.count(p.getInventory()) - refund; // escrow not used comes back as bills
        check(spent > 10 * 2_000 && spent < 10 * 12_000, "paid about 10 x the share price: " + spent);
        check(d.stocks().costBasis().shares(StockService.account(p), "DSMC") == 10, "cost basis recorded");
        helper.succeed();
    }

    @GameTest
    public void sellingCertificatesPaysBills(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getInventory().add(ShareCertificates.create("OWL", 10, -1, 1));
        p.getInventory().add(ShareCertificates.create("OWL", 1, -1, 5));
        p.getInventory().add(new ItemStack(ModItems.ORDER_SLIP));
        Desk d = desk(helper, p);
        press(d, p, StockExchangeMenu.BUTTON_BOOK_BASE + book("OWL"), 1);
        press(d, p, StockExchangeMenu.BUTTON_SIDE, 1);
        press(d, p, StockExchangeMenu.BUTTON_MARKET, 1);
        press(d, p, StockExchangeMenu.BUTTON_QTY_PLUS_1, 2); // 12 shares
        check(d.menu().clickMenuButton(p, StockExchangeMenu.BUTTON_PLACE), "place a market sell of 12");
        check(shares(p, null, "OWL") == 3, "12 of 15 shares escrowed; 3 given back as change");
        d.stocks().runAuctions(d.day());
        press(d, p, StockExchangeMenu.BUTTON_TAB_TRADE, 1);
        long bills = 0;
        for (int i = 0; i < d.menu().output().getContainerSize(); i++) {
            var den = ModItems.denominationOf(d.menu().output().getItem(i));
            if (den != null) bills += den.cents() * d.menu().output().getItem(i).getCount();
        }
        check(bills > 12 * 2_000, "bills for 12 OWL shares in the output: " + bills);
        helper.succeed();
    }

    @GameTest
    public void dividendsPayOnPresentationOncePerQuarter(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getInventory().add(ShareCertificates.create("OWL", 10, -1, 1));
        Desk d = desk(helper, p);
        d.stocks().observeTo(0);
        d.stocks().observeTo(15); // two quarters reported
        long perShare = d.stocks().equities().dividendsSince("OWL", -1);
        check(perShare > 0, "OWL paid dividends");
        check(d.stocks().dividendsWaiting(p) == 10 * perShare, "waiting: 10 x " + perShare);
        long before = Wallet.count(p.getInventory());
        press(d, p, StockExchangeMenu.BUTTON_TAB_COMPANY, 1);
        check(d.menu().clickMenuButton(p, StockExchangeMenu.BUTTON_COLLECT), "collect");
        long paid = Wallet.count(p.getInventory()) - before;
        check(paid == 10 * perShare / 10 * 10 + 1_500, "paid " + paid + ": the dividend (to the dime) for " + 10 * perShare
                + " plus the $15 Shareholder reward");
        var paper = ShareCertificates.read(p.getInventory().getItem(findCert(p))).orElseThrow();
        check(paper.paidThrough() == 1, "now paid through quarter 1");
        check(!d.menu().clickMenuButton(p, StockExchangeMenu.BUTTON_COLLECT), "nothing more this quarter");
        check(d.prog().progress(p).hasCompleted("shareholder"), "Shareholder");
        check(d.prog().progress(p).hasGuide("risk_and_return"), "and its guide, Risk and Return");
        helper.succeed();
    }

    @GameTest
    public void sharesHeldByTheExchangeStillEarnTheirDividend(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getInventory().add(ShareCertificates.create("OWL", 10, -1, 1));
        p.getInventory().add(new ItemStack(ModItems.ORDER_SLIP));
        Desk d = desk(helper, p);
        d.stocks().observeTo(0);
        press(d, p, StockExchangeMenu.BUTTON_BOOK_BASE + book("OWL"), 1);
        press(d, p, StockExchangeMenu.BUTTON_SIDE, 1);
        press(d, p, StockExchangeMenu.BUTTON_PRICE_PLUS_10PCT, 8); // far above the market: rests
        check(d.menu().clickMenuButton(p, StockExchangeMenu.BUTTON_PLACE), "rest a sell");
        d.stocks().observeTo(7); // day orders expire at the next dawn; the shares wait in custody as the quarter closes
        long owed = 10 * d.stocks().equities().dividendsSince("OWL", -1);
        check(owed > 0, "a dividend was declared");
        press(d, p, StockExchangeMenu.BUTTON_TAB_TRADE, 1); // visit: collect what's waiting
        check(shares(p, d.menu().output(), "OWL") == 10, "shares back");
        long cash = 0;
        for (int i = 0; i < d.menu().output().getContainerSize(); i++) {
            var den = ModItems.denominationOf(d.menu().output().getItem(i));
            if (den != null) cash += den.cents() * d.menu().output().getItem(i).getCount();
        }
        check(cash == owed / 10 * 10, "their dividend came with them: " + cash + " of " + owed);
        helper.succeed();
    }

    @GameTest
    public void splitAndMergeConserveShares(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getInventory().add(ShareCertificates.create("DSMC", 100, -1, 1));
        Desk d = desk(helper, p);
        press(d, p, StockExchangeMenu.BUTTON_BOOK_BASE + book("DSMC"), 1);
        check(d.menu().clickMenuButton(p, StockExchangeMenu.BUTTON_SPLIT), "split the 100");
        check(p.getInventory().countItem(ModItems.SHARE_CERTIFICATE) == 10 && shares(p, null, "DSMC") == 100, "ten 10s");
        check(d.menu().clickMenuButton(p, StockExchangeMenu.BUTTON_SPLIT), "split a 10");
        check(shares(p, null, "DSMC") == 100 && p.getInventory().countItem(ModItems.SHARE_CERTIFICATE) == 19, "nine 10s and ten 1s");
        check(d.menu().clickMenuButton(p, StockExchangeMenu.BUTTON_MERGE), "merge");
        check(shares(p, null, "DSMC") == 100 && p.getInventory().countItem(ModItems.SHARE_CERTIFICATE) == 1, "one 100 again");
        check(d.prog().progress(p).hasCompleted("buy_the_business"), "Buy the Business: 100 shares of one company");
        p.getInventory().clearContent();
        p.getInventory().add(ShareCertificates.create("DSMC", 1, -1, 1));
        check(!d.menu().clickMenuButton(p, StockExchangeMenu.BUTTON_SPLIT), "a 1-share certificate can't split");
        helper.succeed();
    }

    @GameTest
    public void sellingAboveWhatYouPaidBeatsTheMarket(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getInventory().add(ShareCertificates.create("OWL", 10, -1, 1));
        p.getInventory().add(new ItemStack(ModItems.ORDER_SLIP));
        Desk d = desk(helper, p);
        d.stocks().costBasis().bought(StockService.account(p), "OWL", 10, 10_000); // bought at $10 a share
        press(d, p, StockExchangeMenu.BUTTON_BOOK_BASE + book("OWL"), 1);
        press(d, p, StockExchangeMenu.BUTTON_SIDE, 1);
        press(d, p, StockExchangeMenu.BUTTON_MARKET, 1);
        check(d.menu().clickMenuButton(p, StockExchangeMenu.BUTTON_PLACE), "sell 10 at market");
        d.stocks().runAuctions(d.day());
        press(d, p, StockExchangeMenu.BUTTON_TAB_TRADE, 1);
        check(d.prog().progress(p).hasCompleted("beat_the_market"), "sold well above $10: Beat the Market");
        helper.succeed();
    }

    @GameTest
    public void newsfeedShowsCompanyNewsBeforeTheMarketMoves(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        DealerService dealer = DealerService.forTest(1234L);
        StockService stocks = StockService.forTest(dealer, 7L);
        ProgressionService prog = ProgressionService.forTest();
        Wallet.give(p, 5_000_000);
        for (String node : new String[] {"bill_clip", "price_board", "bank_vault", "loan_note", "trading_floor", "newsstand",
                "stock_exchange", "electronic_newsfeed"}) {
            check(prog.buyNode(p, node).isEmpty(), "buy " + node);
        }
        var news = stocks.equities().news();
        long day = 10;
        while (news.startingOn(day).isEmpty() || (day + 1) % 7 == 0) day++;
        var story = news.startingOn(day).getFirst();
        String t = story.type().ticker();
        dealer.shiftDays(day + 0.1 - dealer.day(helper.getLevel().getGameTime()));
        stocks.observeTo(day);
        var feed = new com.realisticmarkets.mod.menu.NewsfeedMenu(1, p.getInventory(), ContainerLevelAccess.NULL, stocks, prog, dealer);
        check(feed.owner() && feed.storyCount() >= 1 && feed.storyType(0) == story.type().index() && feed.storyAge(0) == 0,
                "today's story on the Newsfeed: " + story.type().id());
        var theirs = new com.realisticmarkets.mod.menu.NewsfeedMenu(2, stranger.getInventory(), ContainerLevelAccess.NULL, stocks, prog, dealer);
        check(!theirs.owner() && theirs.storyCount() == 0, "no news without the upgrade");
        // The Dealer here doesn't drift, so overnight the share moves only on the news (and a hair of growth).
        long before = stocks.fairCents(t);
        stocks.observeTo(day + 1);
        double moved = stocks.fairCents(t) / (double) before;
        check(Math.signum(moved - 1) == Math.signum(story.type().effect()), story.type().id() + " moved " + t + " by " + moved
                + " once the market heard");
        helper.succeed();
    }

    static int findCert(ServerPlayer p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(ModItems.SHARE_CERTIFICATE)) return i;
        throw new IllegalStateException("no certificate");
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
