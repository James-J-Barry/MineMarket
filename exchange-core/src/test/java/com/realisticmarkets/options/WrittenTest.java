package com.realisticmarkets.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.money.Money;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WrittenTest {
    static final String WHEAT = "minecraft:wheat";

    /** A market whose price per contract the test sets. */
    static final class Stub implements OptionDesk.Market {
        double spot = 12_800;
        public double spotCents(String u, double day) { return spot; }
        public double forwardCents(String u, long expiry, double day) { return spot; }
        public double rate(double day) { return 0.003; }
    }

    static Dealer dealer() {
        return new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 3L);
    }

    static final OptionDesk.Series CALL = new OptionDesk.Series("WHT", true, 13_000, 14);

    @Test
    void aCoveredCallEarnsTheFullPremiumAndANakedOneLessWithWeakCollateral() {
        Stub m = new Stub();
        OptionDesk desk = new OptionDesk(m);
        Dealer d = dealer();
        double fair = desk.fair(CALL, 10);
        var covered = WrittenBook.terms(desk, d, CALL, 1, Map.of(WHEAT, 256), 0, 10);
        assertEquals(1.0, covered.quality(), 1e-12, "the wheat itself: Q = 1");
        assertEquals(Money.roundDownToDime(fair * 0.96), covered.premiumCents(), "the full premium (the desk's side of the spread)");
        assertEquals(0, covered.requiredCents(), "covered: no other collateral needed");

        var cash = WrittenBook.terms(desk, d, CALL, 1, Map.of(), 50_000, 10);
        assertEquals(0.9, cash.quality(), 1e-9, "cash in escrow: class A, 10% haircut");
        assertEquals(Money.roundDownToDime(fair * 0.96 * 0.97), cash.premiumCents(), "3% less");
        var logs = WrittenBook.terms(desk, d, CALL, 1, Map.of("minecraft:oak_log", 1_500), 0, 10);
        assertTrue(logs.quality() < 0.2, "1,500 oak logs are poor collateral: Q " + logs.quality());
        assertTrue(logs.premiumCents() < cash.premiumCents() && logs.premiumCents() >= Math.floor(fair * 0.96 * 0.7) - 10,
                "logs cut the premium by up to 30%: " + logs.premiumCents());
        var half = WrittenBook.terms(desk, d, CALL, 2, Map.of(WHEAT, 256), 50_000, 10);
        assertEquals(0.5, half.coveredShare(), 1e-12, "256 wheat covers one of two contracts");
    }

    @Test
    void theUncoveredPartNeedsCollateralForATwentyPercentMove() {
        Stub m = new Stub();
        OptionDesk desk = new OptionDesk(m);
        Dealer d = dealer();
        var t = WrittenBook.terms(desk, d, CALL, 3, Map.of(), 1_000, 10);
        assertEquals(Math.round(1.25 * 3 * (12_800 * 1.2 - 13_000)), t.requiredCents(), "3 x (1.2F - K) x 1.25");
        assertFalse(t.enough());
        WrittenBook book = new WrittenBook();
        assertThrows(RejectedException.class, () -> book.write("a", desk, d, CALL, 3, Map.of(), 1_000, 10));
        var put = new OptionDesk.Series("WHT", false, 12_000, 14);
        assertEquals(Math.round(1.25 * (12_000 - 12_800 * 0.8)), WrittenBook.terms(desk, d, put, 1, Map.of(), 0, 10).requiredCents(),
                "a put: K - 0.8F");
        assertThrows(RejectedException.class, () -> WrittenBook.terms(desk, d, new OptionDesk.Series("OWL", true, 4_000, 14), 1,
                Map.of(), 100_000, 10), "no writing share options");
    }

    @Test
    void aCoveredCallInTheMoneyIsCalledAway() {
        Stub m = new Stub();
        OptionDesk desk = new OptionDesk(m);
        Dealer d = dealer();
        WrittenBook book = new WrittenBook();
        var w = book.write("a", desk, d, CALL, 1, Map.of(WHEAT, 256), 0, 10);
        assertEquals(256, w.coveredUnits());
        double inv = d.inventory(WHEAT, 14);
        desk.settle("WHT", 14, 15_000);
        var settled = book.dawn(14, desk, d, new ArrayList<>());
        assertEquals(1, settled.size());
        var s = settled.get(0);
        assertEquals(256, s.calledAwayUnits(), "the wheat is called away");
        assertEquals(13_000, s.strikeReceivedCents(), "for the strike");
        assertEquals(0, s.paidOutCents(), "nothing else owed");
        assertEquals(inv + 256, d.inventory(WHEAT, 14), 1e-6, "the wheat joins the Dealer's stock");
        var back = book.collect("a");
        assertEquals(13_000, back.cashCents(), "the strike is there to collect");
        assertTrue(back.items().isEmpty(), "and no wheat");
        assertTrue(book.open("a").isEmpty());
    }

    @Test
    void aNakedPutPaysOutOfTheEscrowAndAWorthlessOneReturnsItAll() {
        Stub m = new Stub();
        OptionDesk desk = new OptionDesk(m);
        Dealer d = dealer();
        WrittenBook book = new WrittenBook();
        var put = new OptionDesk.Series("WHT", false, 13_000, 14);
        book.write("a", desk, d, put, 1, Map.of("minecraft:iron_ingot", 64), 1_000, 10);
        book.write("b", desk, d, new OptionDesk.Series("WHT", false, 12_300, 14), 1, Map.of(), 5_000, 3);
        desk.settle("WHT", 14, 11_500);
        // b's $123 put is in the money too; a third, far below, expires worthless.
        var settled = book.dawn(14, desk, d, new ArrayList<>());
        var a = settled.stream().filter(x -> x.account().equals("a")).findFirst().orElseThrow();
        assertEquals(1_500, a.paidOutCents(), "K - S = $15, from $10 cash and then the iron sold to the Dealer");
        var backA = book.collect("a");
        assertTrue(backA.items().isEmpty(), "the iron was sold");
        assertTrue(backA.cashCents() > 0, "the rest of its sale comes back: " + backA.cashCents());
        var b = settled.stream().filter(x -> x.account().equals("b")).findFirst().orElseThrow();
        assertEquals(800, b.paidOutCents(), "K - S = $8");
        assertEquals(4_200, book.collect("b").cashCents(), "the rest of the escrow comes back");
        var far = new OptionDesk.Series("WHT", false, 12_000, 21);
        book.write("c", desk, d, far, 1, Map.of(), 5_000, 3);
        desk.settle("WHT", 21, 12_500);
        var c = book.dawn(21, desk, d, new ArrayList<>()).get(0);
        assertTrue(c.worthless() && c.paidOutCents() == 0, "above the strike: worthless");
        assertEquals(5_000, book.collect("c").cashCents(), "all the escrow comes back");
    }

    @Test
    void aShortfallAtDawnIsCalledThenBoughtBack() {
        Stub m = new Stub();
        OptionDesk desk = new OptionDesk(m);
        Dealer d = dealer();
        WrittenBook book = new WrittenBook();
        long need = WrittenBook.terms(desk, d, CALL, 1, Map.of(), 0, 10).requiredCents();
        var w = book.write("a", desk, d, CALL, 1, Map.of(), Money.roundUpToDime(need / 0.9) + 10, 10);
        var called = new ArrayList<WrittenBook.Written>();
        assertTrue(book.dawn(11, desk, d, called).isEmpty() && called.isEmpty(), "covered at the first dawn");
        m.spot = 14_500; // wheat runs up
        book.dawn(12, desk, d, called);
        assertEquals(1, called.size(), "short: a margin call");
        assertTrue(w.underCall());
        var out = book.dawn(13, desk, d, new ArrayList<>());
        assertEquals(1, out.size());
        assertTrue(out.get(0).boughtBack(), "not met: the desk buys it back out of the escrow");
        assertTrue(book.open("a").isEmpty());

        var w2 = book.write("b", desk, d, CALL, 1, Map.of(), 400_000, 12.5);
        m.spot = 20_000;
        called.clear();
        book.dawn(14 - 1, desk, d, called);
        book.addCash(w2.id(), 1_000_000, desk, d, 13.2);
        assertFalse(w2.underCall(), "a top-up meets the call");
    }

    @Test
    void savesAndLoads() throws Exception {
        Stub m = new Stub();
        OptionDesk desk = new OptionDesk(m);
        Dealer d = dealer();
        WrittenBook book = new WrittenBook();
        var w = book.write("a", desk, d, CALL, 2, Map.of(WHEAT, 300, "minecraft:gold_ingot", 10), 2_000, 10);
        StringWriter out = new StringWriter();
        book.write(out);
        WrittenBook back = WrittenBook.read(new StringReader(out.toString()));
        var w2 = back.get(w.id()).orElseThrow();
        assertEquals(w.items(), w2.items());
        assertEquals(w.coveredUnits(), w2.coveredUnits());
        assertEquals(w.premiumCents(), w2.premiumCents());
        assertEquals(w.series(), w2.series());
    }
}
