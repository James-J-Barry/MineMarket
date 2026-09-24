package com.realisticmarkets.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuidesTest {
    final Guides guides = Guides.loadDefault();

    @Test
    void guidesOfTheRightLength() {
        assertEquals(List.of("money_and_dealer", "spread", "price_impact", "recovery", "diversification",
                        "cash_on_hand", "reading_a_quote", "transaction_costs", "two_markets",
                        "interest_and_compounding", "term_and_liquidity", "leverage_and_collateral",
                        "order_books", "limit_and_market_orders", "liquidity_and_market_makers", "news_and_markets", "reading_a_chart", "owning_a_share", "valuing_a_company",
                        "risk_and_return", "custody"),
                guides.all().stream().map(Guides.Guide::id).toList());
        for (Guides.Guide g : guides.all()) {
            int words = g.wordCount();
            assertTrue(words >= 150 && words <= 250, g.id() + " has " + words + " words");
            assertTrue(g.title().length() <= 32, g.id() + ": written-book titles max out at 32 chars");
            assertTrue(g.paragraphs().getLast().startsWith("Real world:"), g.id() + " should end with its real-world parallel");
        }
    }

    @Test
    void everyGrantedGuideExists() {
        for (Quest q : Quests.loadDefault().all()) {
            for (String grant : q.grants()) {
                if (grant.startsWith("guide:")) assertTrue(guides.has(grant.substring(6)), q.id() + " grants " + grant);
            }
        }
        for (UnlockNode n : UnlockTree.loadDefault().all()) {
            for (String grant : n.grants()) {
                if (grant.startsWith("guide:")) assertTrue(guides.has(grant.substring(6)), n.id() + " grants " + grant);
            }
        }
    }

    @Test
    void numbersInThePriceImpactGuideMatchTheDealer() {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1L);
        String text = String.join("\n", guides.guide("price_impact").paragraphs());
        for (int qty : new int[] {64, 256, 1024}) {
            String paid = String.format(java.util.Locale.ROOT, "$%.2f", d.quoteSell("minecraft:wheat", qty, 0, false).rawCents() / 100.0);
            assertTrue(text.contains(paid), "guide should quote " + paid + " for " + qty + " wheat");
        }
        d.sell("minecraft:wheat", 256, 0, false);
        String bid = String.format(java.util.Locale.ROOT, "$%.2f", d.bid("minecraft:wheat", 0, false));
        assertTrue(text.contains("to " + bid), "guide should say the bid falls to " + bid);
    }

    @Test
    void recoveryGuidePercentagesMatchTheDealer() {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1L);
        d.sell("minecraft:wheat", 256, 0, false);
        assertEquals(0.37, d.mid("minecraft:wheat", 0) / 0.50, 0.005);
        assertEquals(0.69, d.mid("minecraft:wheat", 2) / 0.50, 0.005);
        assertEquals(0.87, d.mid("minecraft:wheat", 4) / 0.50, 0.005);
        assertTrue(d.mid("minecraft:wheat", 5) / 0.50 >= 0.90);
    }

    @Test
    void diversificationGuideShares() {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1L);
        double firstBid = d.bid("minecraft:wheat", 0, false);
        double oneItem = d.quoteSell("minecraft:wheat", 192, 0, false).rawCents() / 100.0 / (192 * firstBid);
        double threeItems = d.quoteSell("minecraft:wheat", 64, 0, false).rawCents() / 100.0 / (64 * firstBid);
        assertEquals(0.70, oneItem, 0.01);
        assertEquals(0.88, threeItems, 0.01);
        assertEquals(1.25, threeItems / oneItem, 0.02); // "about a quarter more money"
    }

    String text(String id) {
        return String.join("\n", guides.guide(id).paragraphs());
    }

    static String usd(long cents) {
        return com.realisticmarkets.money.Money.format(cents);
    }

    @Test
    void cashOnHandRounding() {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1L);
        var q = d.quoteSell("minecraft:wheat", 64, 0, false);
        assertTrue(text("cash_on_hand").contains("exact price is " + usd(Math.round(q.rawCents()))));
        assertTrue(text("cash_on_hand").contains("paid " + usd(q.cents())));
    }

    @Test
    void readingAQuoteNumbers() {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1L);
        assertEquals(0.45, d.bid("minecraft:wheat", 0, false), 1e-9);
        d.sell("minecraft:wheat", 64, 0, false);
        String bid = String.format(java.util.Locale.ROOT, "$%.2f", d.bid("minecraft:wheat", 0, false));
        assertTrue(text("reading_a_quote").contains("bid of " + bid), "board bid after 64 wheat should be " + bid);
    }

    @Test
    void transactionCostsNumbers() {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1L);
        String t = text("transaction_costs");
        assertEquals(0.47, d.bid("minecraft:wheat", 0, true), 1e-9);
        assertEquals(0.53, d.ask("minecraft:wheat", 0, true), 1e-9);
        assertTrue(t.contains("paid " + usd(d.quoteSell("minecraft:wheat", 64, 0, true).cents())
                + " instead of " + usd(d.quoteSell("minecraft:wheat", 64, 0, false).cents())));
        double uplift = 0.94 / 0.90 - 1;
        assertEquals(0.044, uplift, 0.0005);
        double breakEvenSales = 150 / (1 - 0.90 / 0.94); // licensed proceeds needed to gain $150
        assertEquals(3500, breakEvenSales, 50);
        assertTrue(t.contains("about $3,500"));
    }

    @Test
    void twoMarketsNumbers() {
        DealerCatalog local = DealerCatalog.loadDefault();
        Dealer home = new Dealer(local, DealerParams.noDrift(), 1L);
        com.realisticmarkets.dealer.Capital.Config cfg = com.realisticmarkets.dealer.Capital.loadDefault();
        Dealer capital = com.realisticmarkets.dealer.Capital.dealer(local, DealerParams.noDrift(), cfg, 2L);
        String t = text("two_markets");

        long wheatHome = home.quoteSell("minecraft:wheat", 128, 0, false).cents();
        long wheatGross = capital.quoteSell("minecraft:wheat", 128, 0, false).cents();
        long wheatNet = com.realisticmarkets.dealer.ShipmentBook.estimate(capital, java.util.Map.of("minecraft:wheat", 128), 0, cfg.freight());
        assertTrue(t.contains("pays " + usd(wheatHome)));
        assertTrue(t.contains("Capital pays " + usd(wheatGross) + ", freight takes " + usd(wheatGross - wheatNet)
                + ", and you receive " + usd(wheatNet)));
        assertEquals(1.25, wheatNet / (double) wheatHome, 0.03); // "about a quarter more"

        long ironHome = home.quoteSell("minecraft:iron_ingot", 16, 0, false).cents();
        long ironNet = com.realisticmarkets.dealer.ShipmentBook.estimate(capital, java.util.Map.of("minecraft:iron_ingot", 16), 0, cfg.freight());
        assertTrue(t.contains("16 ingots pay " + usd(ironHome) + " at home but only " + usd(ironNet)));

        long buy = home.quoteBuy("minecraft:wheat", 64, 0, false).cents();
        long back = com.realisticmarkets.dealer.ShipmentBook.estimate(capital, java.util.Map.of("minecraft:wheat", 64), 0, cfg.freight());
        assertTrue(t.contains("Buying 64 wheat costs " + usd(buy) + " and shipping it returns " + usd(back)));
        assertTrue(back < buy);
    }

    static long vault(long cents, int days) {
        com.realisticmarkets.contracts.BankAccount a = new com.realisticmarkets.contracts.BankAccount(0);
        a.deposit(cents, 0, com.realisticmarkets.contracts.BankAccount.Kind.DEPOSIT);
        a.accrueTo(days, com.realisticmarkets.contracts.BankParams.loadDefault().interestRate());
        return a.balanceCents();
    }

    @Test
    void interestAndCompoundingNumbers() {
        String t = text("interest_and_compounding");
        assertTrue(t.contains("one day you have " + usd(vault(100_000, 1))));
        assertTrue(t.contains("After a week you have " + usd(vault(100_000, 7))));
        assertTrue(t.contains("After 30 days you have " + usd(vault(100_000, 30))));
        assertTrue(t.contains("only $90"), "simple interest on $1,000 for 30 days");
        assertTrue(vault(100_000, 231) < 200_000 && vault(100_000, 232) >= 200_000, "doubles on day 232");
        assertTrue(t.contains("doubles by day 232"));
        assertTrue(t.contains("about $3 a day"));
    }

    @Test
    void termAndLiquidityNumbers() {
        var params = com.realisticmarkets.contracts.BankParams.loadDefault();
        String t = text("term_and_liquidity");
        long cd7 = com.realisticmarkets.contracts.Cd.issue(50_000, params.term(7), 0, params).valueAtMaturityCents();
        long cd21 = com.realisticmarkets.contracts.Cd.issue(100_000, params.term(21), 0, params).valueAtMaturityCents();
        assertTrue(t.contains("grows to " + usd(vault(50_000, 7))));
        assertTrue(t.contains("it pays " + usd(cd7)));
        assertTrue(t.contains("becomes " + usd(cd21) + ", against " + usd(vault(100_000, 21)) + " in the vault"));
        assertTrue(cd7 > vault(50_000, 7) && cd21 > vault(100_000, 21), "CDs beat the vault over their term");
    }

    @Test
    void leverageGuideTableMatchesTheValuer() {
        String t = text("leverage_and_collateral");
        Object[][] rows = {{"minecraft:diamond", 15, "15 diamonds"}, {"minecraft:iron_ingot", 256, "256 iron"},
                {"minecraft:gold_ingot", 64, "64 gold"}, {"minecraft:oak_log", 1_500, "1,500 oak logs"}};
        for (Object[] r : rows) {
            Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1L);
            var v = com.realisticmarkets.collateral.CollateralValuer.value(java.util.Map.of((String) r[0], (Integer) r[1]), 0, d, 0);
            String line = String.format(java.util.Locale.ROOT, "%s (market $%,d): %s at %.2f%% a day", r[2],
                    Math.round(v.marketCents() / 100.0), usd(v.maxLoanCents()), v.dailyRate() * 100);
            assertTrue(t.contains(line), "guide should read: " + line);
        }
    }

    @Test
    void leverageGuideMarginCallThreshold() {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1L);
        var v = com.realisticmarkets.collateral.CollateralValuer.value(java.util.Map.of("minecraft:diamond", 15), 0, d, 0);
        double fall = 1 - 1.10 * 70_000 / v.valueCents(); // C falls in proportion to the diamond price
        assertEquals(0.2, fall, 0.02, "about a fifth");
        assertTrue(text("leverage_and_collateral").contains("Borrow $700 against those diamonds and a fall of about a fifth"));
    }

    @Test
    void orderBooksExampleMatchesTheAuction() {
        var ex = new com.realisticmarkets.exchange.Exchange();
        ex.listInstrument("w");
        for (String a : new String[] {"b1", "b2", "b3", "s1", "s2", "s3"}) {
            ex.deposit(a, 100_000);
            ex.depositPosition(a, "w", 100);
        }
        var buy = com.realisticmarkets.exchange.Side.BUY;
        var sell = com.realisticmarkets.exchange.Side.SELL;
        ex.submit(com.realisticmarkets.exchange.OrderRequest.limit("b1", "w", buy, 20, 52));
        ex.submit(com.realisticmarkets.exchange.OrderRequest.limit("b2", "w", buy, 30, 50));
        ex.submit(com.realisticmarkets.exchange.OrderRequest.limit("b3", "w", buy, 40, 48));
        ex.submit(com.realisticmarkets.exchange.OrderRequest.limit("s1", "w", sell, 25, 47));
        ex.submit(com.realisticmarkets.exchange.OrderRequest.limit("s2", "w", sell, 30, 49));
        ex.submit(com.realisticmarkets.exchange.OrderRequest.limit("s3", "w", sell, 50, 51));
        var r = ex.runAuction("w");
        assertEquals(50, r.clearingPrice().getAsLong());
        assertEquals(50, r.volume());
        assertEquals(120, ex.account("b1").position("w"), "the $0.52 buyer gets all 20");
        assertEquals(100_000 - 20 * 50, ex.account("b1").cash() + ex.account("b1").lockedCash(), "and pays $0.50 each");
        assertEquals(130, ex.account("b2").position("w"));
        assertEquals(75, ex.account("s1").position("w"), "the $0.47 seller sells all 25");
        assertEquals(75, ex.account("s2").position("w") + ex.account("s2").lockedPosition("w"), "s2 sold 25 of 30");
        assertEquals(5, ex.account("s2").lockedPosition("w"), "5 wait for the next auction");
        assertEquals(100, ex.account("b3").position("w"));
        assertEquals(100, ex.account("s3").position("w") + ex.account("s3").lockedPosition("w"));
        String t = text("order_books");
        assertTrue(t.contains("so 50 trade") && t.contains("pays $0.50") && t.contains("sells 25 of their 30"));
    }

    @Test
    void limitAndMarketOrdersNumbers() {
        var floor = new com.realisticmarkets.agents.TradingFloor(new com.realisticmarkets.exchange.Exchange(),
                new com.realisticmarkets.exchange.PriceHistory(), com.realisticmarkets.agents.FloorCatalog.loadDefault(),
                item -> 50, 1L);
        long buy = floor.buyLimit("minecraft:wheat", 50, true, 0);
        long sell = floor.sellLimit("minecraft:wheat", 50, true, 0);
        String t = text("limit_and_market_orders");
        assertTrue(t.contains("16 x " + usd(buy) + " = " + usd(16 * buy)), "market buy escrow at " + usd(buy));
        assertTrue(t.contains("as little as " + usd(sell)));
        assertTrue(t.contains("reach " + Math.round(com.realisticmarkets.agents.TradingFloor.MARKET_REACH * 100) + "%"));
    }

    @Test
    void liquidityGuideQuotesMatchTheMarketMakers() {
        var cat = com.realisticmarkets.agents.FloorCatalog.loadDefault();
        var wheat = cat.book("minecraft:wheat");
        var diamond = cat.book("minecraft:diamond");
        var w = new com.realisticmarkets.agents.MarketMaker("w", wheat.halfSpread(), wheat.depth() / 16, wheat.depth() / 2);
        var d = new com.realisticmarkets.agents.MarketMaker("d", diamond.halfSpread(), diamond.depth() / 16, diamond.depth() / 2);
        String t = text("liquidity_and_market_makers");
        long[] q = w.quotes(50, wheat.depth() / 2);
        assertTrue(t.contains("bids " + usd(q[0]) + " and asks " + usd(q[1])));
        assertEquals(4, Math.round((q[1] - q[0]) * 100.0 / 50), "a 4% spread");
        Dealer dealer = new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1L);
        assertEquals(0.20, (dealer.ask("minecraft:wheat", 0, false) - dealer.bid("minecraft:wheat", 0, false)) / 0.50, 1e-9);
        long[] dq = d.quotes(10_000, diamond.depth() / 2);
        assertTrue(t.contains("Diamonds at $100 get " + usd(dq[0]) + " and " + usd(dq[1])));
        long[] full = w.quotes(50, wheat.depth());
        assertTrue(t.contains("drops its quotes to " + usd(full[0]) + " and " + usd(full[1])));
        long[] empty = w.quotes(50, 0);
        assertTrue(t.contains("raises them to " + usd(empty[0]) + " and " + usd(empty[1])));
        assertTrue(t.contains("quotes " + wheat.depth() / 16 + " at a time") && t.contains("quotes " + diamond.depth() / 16 + " at a time"));
    }

    @Test
    void newsGuideTimingMatchesTheEvents() {
        String t = text("news_and_markets");
        assertTrue(com.realisticmarkets.dealer.WorldEvents.MIN_DELAY_DAYS > 0.2, "there is a head start to act on");
        assertTrue(t.contains("News takes time to spread") && t.contains("The sooner you act"));
        assertTrue(!t.contains("midday") && !t.contains("hours"), "the delay is felt, not stated");
        assertTrue(t.contains("half as much as last time, or half as much again"), "event scale 0.5-1.5");
    }

    @Test
    void chartGuideMatchesTheTickerTape() {
        assertEquals(7, com.realisticmarkets.exchange.PriceChart.DAYS);
        assertEquals(4, com.realisticmarkets.exchange.PriceHistory.PERIODS_PER_DAY);
        assertTrue(text("reading_a_chart").contains("last seven days of trading, four points a day"));
    }

    static long startEarnings(com.realisticmarkets.equities.Company c) {
        DealerCatalog cat = DealerCatalog.loadDefault();
        java.util.function.ToDoubleFunction<String> price = k -> cat.trades(k) ? cat.spec(k).fairValue() : 1.0;
        double rev = 0, cost = c.fixedCost();
        for (var e : c.revenue().entrySet()) rev += e.getValue() * price.applyAsDouble(e.getKey());
        for (var e : c.inputs().entrySet()) cost += e.getValue() * price.applyAsDouble(e.getKey());
        return Math.round((rev - cost) * 100);
    }

    @Test
    void shareGuidesMatchTheCompanies() {
        var companies = com.realisticmarkets.equities.CompanyCatalog.loadDefault();
        DealerCatalog cat = DealerCatalog.loadDefault();
        var eq = new com.realisticmarkets.equities.Equities(companies, k -> cat.trades(k) ? cat.spec(k).fairValue() : 1.0, 1);
        var owl = companies.company("OWL");
        long owlEps = Math.round(startEarnings(owl) / (double) owl.shares());
        long owlDiv = (long) Math.floor(owl.payout() * startEarnings(owl) / owl.shares());
        String own = text("owning_a_share");
        assertTrue(own.contains("earning about " + usd(owlEps) + " a share each quarter and pays " + usd(owlDiv)), own);
        String val = text("valuing_a_company");
        var dsmc = companies.company("DSMC");
        long dsmcE = startEarnings(dsmc);
        double pe = eq.startValueCents("DSMC") / (4.0 * dsmcE / dsmc.shares());
        assertTrue(val.contains(String.format(java.util.Locale.ROOT, "about %.1f times earnings", pe)), "DSMC P/E " + pe);
        double owlPe = eq.startValueCents("OWL") / (4.0 * startEarnings(owl) / owl.shares());
        assertTrue(val.contains(String.format(java.util.Locale.ROOT, "Overworld Utility at about %.0f", owlPe)), "OWL P/E " + owlPe);
        double yield = 4.0 * owlDiv / eq.startValueCents("OWL");
        assertTrue(val.contains(String.format(java.util.Locale.ROOT, "about %.1f%% for the utility", yield * 100)), "yield " + yield);
        double ironSales = 20_000 * 8.0 * 0.2, sales = 20_000 * 8.0 + 4_000 * 15.0 + 400 * 100.0;
        assertEquals(dsmc.revenue().get("minecraft:iron_ingot"), 20_000L);
        double salesDrop = ironSales / sales, earningsDrop = ironSales * 100 / dsmcE;
        assertTrue(val.contains(String.format(java.util.Locale.ROOT, "sales fall about %.0f%% but its earnings fall about %.0f%%",
                salesDrop * 100, earningsDrop * 100)), salesDrop + " / " + earningsDrop);
    }

    @Test
    void riskAndReturnMatchesTheEconomy() {
        String t = text("risk_and_return");
        assertEquals(0.001, DealerParams.defaults().inflation(), 0.0);
        assertEquals(0.003, com.realisticmarkets.contracts.BankParams.loadDefault().interestRate(), 0.0);
        assertTrue(t.contains("about 0.1% a day") && t.contains("The vault pays 0.3% a day, about 0.2% more"));
        double lo = 1, hi = 0;
        for (var c : com.realisticmarkets.equities.CompanyCatalog.loadDefault().all()) {
            lo = Math.min(lo, c.requiredReturn());
            hi = Math.max(hi, c.requiredReturn());
        }
        assertTrue(t.contains(String.format(java.util.Locale.ROOT, "between %.2f%% and %.2f%% a day", lo * 100, hi * 100)));
    }

    @Test
    void pagesRespectTheLimitAndKeepEveryWord() {
        for (Guides.Guide g : guides.all()) {
            List<String> pages = g.pages(200);
            for (String p : pages) assertTrue(p.length() <= 200, g.id() + " page too long: " + p.length());
            String rejoined = String.join(" ", pages).replaceAll("\\s+", " ");
            String original = String.join(" ", g.paragraphs()).replaceAll("\\s+", " ");
            assertEquals(original, rejoined);
        }
    }

    @Test
    void pagesKeepListLineBreaks() throws Exception {
        Guides g = Guides.parse(new StringReader("@x X\nIntro here.\n\none\ntwo\n"));
        assertEquals(List.of("Intro here.\n\none\ntwo"), g.guide("x").pages(200));
        assertEquals(List.of("Intro", "here.", "one\ntwo"), g.guide("x").pages(7));
    }
}
