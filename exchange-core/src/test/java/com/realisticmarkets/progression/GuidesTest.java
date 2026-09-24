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
                        "interest_and_compounding", "term_and_liquidity"),
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
