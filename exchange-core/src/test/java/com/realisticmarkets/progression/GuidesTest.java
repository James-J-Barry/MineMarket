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
    void fiveGuidesOfTheRightLength() {
        assertEquals(List.of("money_and_dealer", "spread", "price_impact", "recovery", "diversification"),
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
