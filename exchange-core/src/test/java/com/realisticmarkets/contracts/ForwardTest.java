package com.realisticmarkets.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.exchange.RejectedException;
import java.io.StringReader;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class ForwardTest {
    static final String WHEAT = "minecraft:wheat";

    static Dealer calm() {
        return new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), 1234L);
    }

    @Test
    void theForwardPriceIsTheDealersProceedsOnDeliveryDay() {
        Dealer a = calm(), b = calm();
        a.sell(WHEAT, 512, 0, false); // the Dealer is full of wheat today
        b.sell(WHEAT, 512, 0, false);
        long forward = a.quoteForward(WHEAT, 256, 0, 7, 0, false).cents();
        long thenSold = b.quoteSell(WHEAT, 256, 7, false).cents();
        assertEquals(thenSold, forward, 10, "what a sale on day 7 would pay, if nothing changes");
        assertTrue(forward > a.quoteSell(WHEAT, 256, 0, false).cents(), "more than dumping today: the Dealer's stock clears by then");
    }

    @Test
    void theForwardPriceGrowsWithInflation() {
        Dealer d = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), 1234L);
        double today = d.quoteForward(WHEAT, 64, 0, 0, 0, false).rawCents();
        double week = d.quoteForward(WHEAT, 64, 0, 7, 0, false).rawCents(); // day 0: no drift or trend yet to project
        assertEquals(Math.exp(0.001 * 7), week / today, 1e-9, "0.1% a day of inflation to the delivery day");
    }

    @Test
    void openForwardsLowerTheNextOnesPrice() {
        Dealer d = calm();
        ForwardBook book = new ForwardBook();
        var first = book.sign("a", d, WHEAT, 512, 7, 0, false);
        long second = book.quote(d, WHEAT, 512, 7, 0, false).cents();
        assertTrue(second < first.priceCents() * 0.8, "the second 512 comes after the first: " + second + " vs " + first.priceCents());
        long both = new ForwardBook().quote(calm(), WHEAT, 1024, 7, 0, false).cents(); // one big forward instead
        assertEquals(both, first.priceCents() + second, 20, "two forwards cost the same impact as one big one");
        var hay = book.quote(d, "minecraft:hay_block", 16, 7, 0, false).cents();
        assertTrue(hay < new ForwardBook().quote(calm(), "minecraft:hay_block", 16, 7, 0, false).cents(),
                "hay bales share wheat's pool");
    }

    @Test
    void signingDeliveringAndDefaulting() {
        Dealer d = calm();
        ForwardBook book = new ForwardBook();
        var f = book.sign("a", d, WHEAT, 256, 3, 10, false);
        assertEquals(13, f.deliveryDay());
        assertEquals(Math.round(Math.ceil(f.priceCents() * 0.2 / 10.0) * 10), f.depositCents(), "20% deposit, rounded up");
        assertThrows(RejectedException.class, () -> book.deliver(f.id(), d, 12.5), "not due yet");
        double invBefore = d.inventory(WHEAT, 13.2);
        assertEquals(f.priceCents() + f.depositCents(), book.deliver(f.id(), d, 13.2), "price plus the deposit back");
        assertEquals(invBefore + 256, d.inventory(WHEAT, 13.2), 1e-6, "the wheat joins the Dealer's stock like a sale");
        assertTrue(book.open("a").isEmpty());

        var late = book.sign("a", d, WHEAT, 64, 3, 20, false);
        assertTrue(late.dueOn(23) && late.dueOn(24) && !late.dueOn(25), "two days to deliver");
        assertTrue(book.dawn(24).isEmpty(), "still open on day 24");
        assertEquals(late, book.dawn(25).get(0), "defaults at dawn of day 25: the deposit is forfeit");
        assertTrue(book.open("a").isEmpty());
    }

    @Test
    void termsAreChecked() {
        Dealer d = calm();
        assertTrue(ForwardBook.check(d, WHEAT, 8, 7).isPresent(), "at least 16");
        assertTrue(ForwardBook.check(d, WHEAT, 2000, 7).isPresent(), "at most 1,024");
        assertTrue(ForwardBook.check(d, WHEAT, 64, 5).isPresent(), "3, 7 or 14 days");
        assertTrue(ForwardBook.check(d, "realisticmarkets:ledger_paper", 64, 7).isPresent(), "no components");
        assertTrue(ForwardBook.check(d, "minecraft:dirt_block_of_nothing", 64, 7).isPresent(), "only what the Dealer trades");
        assertTrue(ForwardBook.check(d, WHEAT, 64, 14).isEmpty());
    }

    @Test
    void savesAndLoads() throws Exception {
        Dealer d = calm();
        ForwardBook book = new ForwardBook();
        var f = book.sign("a", d, WHEAT, 128, 7, 4, true);
        StringWriter w = new StringWriter();
        book.write(w);
        ForwardBook back = ForwardBook.read(new StringReader(w.toString()));
        assertEquals(f, back.get(f.id()).orElseThrow());
        assertTrue(back.sign("b", d, WHEAT, 16, 3, 4, false).id() > f.id(), "ids keep counting");
    }
}
