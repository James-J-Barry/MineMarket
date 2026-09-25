package com.realisticmarkets.custody;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.bonds.Bond;
import com.realisticmarkets.bonds.BondDesk;
import com.realisticmarkets.custody.BookEntries.Kind;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.equities.CompanyCatalog;
import com.realisticmarkets.equities.Equities;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.options.OptionDesk;
import com.realisticmarkets.rates.CentralBank;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookEntriesTest {
    static final DealerCatalog DEALER = DealerCatalog.loadDefault();

    static double base(String k) {
        return DEALER.trades(k) ? DEALER.spec(k).fairValue() : 1.0;
    }

    @Test
    void depositsAndWithdrawalsConserveQuantities() {
        BookEntries b = new BookEntries();
        b.deposit("a", Kind.SHARE, "OWL", 110, 2);
        b.deposit("a", Kind.SHARE, "OWL", 5, 2);
        b.deposit("b", Kind.SHARE, "OWL", 7, 2);
        assertEquals(115, b.quantity("a", Kind.SHARE, "OWL"));
        assertThrows(RejectedException.class, () -> b.deposit("a", Kind.SHARE, "OWL", 1, 1), "papers not paid up to the books");
        var out = b.withdraw("a", Kind.SHARE, "OWL", 100);
        assertEquals(100, out.quantity());
        assertEquals(2, out.paidThrough(), "papers come out paid through what the books were");
        assertEquals(15, b.quantity("a", Kind.SHARE, "OWL"));
        assertThrows(RejectedException.class, () -> b.withdraw("a", Kind.SHARE, "OWL", 16), "not more than held");
        b.withdraw("a", Kind.SHARE, "OWL", 15);
        assertTrue(b.entries("a").isEmpty(), "an emptied entry closes");
        assertEquals(7, b.quantity("b", Kind.SHARE, "OWL"), "other accounts untouched");
    }

    @Test
    void dividendsAreCreditedOncePerReport() {
        Equities e = new Equities(CompanyCatalog.loadDefault(), BookEntriesTest::base, 3);
        for (long d = 0; d <= 7; d++) e.observe(d, BookEntriesTest::base, List.of());
        BookEntries b = new BookEntries();
        b.deposit("a", Kind.SHARE, "OWL", 100, e.lastReportedQuarter() - 1);
        long perShare = e.dividendsSince("OWL", e.lastReportedQuarter() - 1);
        var income = b.credit(7.5, e, null, null);
        assertEquals(100 * perShare, b.cash("a"), "last quarter's dividend on 100 shares");
        if (perShare > 0) assertEquals(100 * perShare, income.get("a").dividends());
        b.credit(7.9, e, null, null);
        assertEquals(100 * perShare, b.cash("a"), "not twice");
        assertEquals(e.lastReportedQuarter(), b.entries("a").get(0).paidThrough(), "paid through the latest report");
    }

    @Test
    void couponsThenTheFaceAtMaturity() {
        BondDesk desk = new BondDesk(CentralBank.constant(0.003), null);
        Bond bond = desk.issue(Bond.TREASURY, 2, 0);
        BookEntries b = new BookEntries();
        String key = BookEntries.bondKey(bond);
        assertEquals(bond, BookEntries.bond(key), "the key gives back the same bond");
        b.deposit("a", Kind.BOND, key, 10, 0);
        b.credit(3, null, desk, null);
        assertEquals(0, b.cash("a"), "nothing due yet");
        b.credit(7.2, null, desk, null);
        assertEquals(10 * bond.couponCents(), b.cash("a"), "the first coupon on 10 bonds");
        b.credit(8, null, desk, null);
        assertEquals(10 * bond.couponCents(), b.cash("a"), "once");
        var last = b.credit(14.1, null, desk, null).get("a");
        assertEquals(10 * bond.couponCents(), last.coupons(), "the last coupon");
        assertEquals(10L * Bond.FACE_CENTS, last.other(), "and $100 each at maturity");
        assertTrue(b.entries("a").isEmpty(), "the bonds are gone: repaid");
        assertEquals(20 * bond.couponCents() + 100_000, b.withdrawCash("a", Long.MAX_VALUE), "all of it can be taken out");
        assertEquals(0, b.cash("a"));
    }

    @Test
    void optionsSettleAtExpiry() {
        OptionDesk desk = new OptionDesk(new OptionDesk.Market() {
            public double spotCents(String u, double d) { return 12_800; }
            public double forwardCents(String u, long e, double d) { return 12_800; }
            public double rate(double d) { return 0.003; }
        });
        var call = new OptionDesk.Series("WHT", true, 12_000, 14);
        BookEntries b = new BookEntries();
        b.deposit("a", Kind.OPTION, call.key(), 3, 0);
        b.credit(13, null, null, desk);
        assertEquals(0, b.cash("a"), "not settled yet");
        desk.settle("WHT", 14, 13_000);
        var income = b.credit(14.1, null, null, desk).get("a");
        assertEquals(3_000, income.other(), "3 contracts x $10 in the money");
        assertTrue(b.entries("a").isEmpty());
    }

    @Test
    void savesAndLoads() throws Exception {
        BookEntries b = new BookEntries();
        b.deposit("a", Kind.BOND, "OWL|3|31|0.0034", 5, 1);
        b.deposit("a", Kind.OPTION, "WHT|P|12000|14", 2, 0);
        b.credit(0, null, null, null);
        StringWriter w = new StringWriter();
        b.write(w);
        BookEntries back = BookEntries.read(new StringReader(w.toString()));
        assertEquals(b.entries("a"), back.entries("a"));
    }
}
