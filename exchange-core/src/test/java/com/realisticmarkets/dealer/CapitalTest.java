package com.realisticmarkets.dealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.dealer.ShipmentBook.Settlement;
import com.realisticmarkets.dealer.ShipmentBook.Shipment;
import com.realisticmarkets.exchange.RejectedException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CapitalTest {
    static final String WHEAT = "minecraft:wheat";
    static final String OWNER = "11111111-2222-3333-4444-555555555555";
    static final String CRATE = "minecraft:overworld|0|64|0";

    DealerCatalog local;
    Capital.Config cfg;
    Dealer capital;
    ShipmentBook book;

    @BeforeEach
    void setUp() {
        local = DealerCatalog.loadDefault();
        cfg = Capital.loadDefault();
        capital = Capital.dealer(local, DealerParams.noDrift(), cfg, 99L);
        book = new ShipmentBook();
    }

    // ---- catalog derivation

    @Test
    void defaultConfig() {
        assertEquals(0.15, cfg.spread());
        assertEquals(0.05, cfg.freight());
        assertEquals(1.0, cfg.transitDays());
        assertEquals(Map.of("farm", 1.30, "mobs", 1.20, "wood_and_stone", 1.10, "mining", 0.90), cfg.groupMultipliers());
    }

    @Test
    void fairValuesScaleByGroupAndDepthsMatch() {
        DealerCatalog c = capital.catalog();
        assertEquals(0.65, c.spec(WHEAT).fairValue(), 1e-9);
        assertEquals(7.20, c.spec("minecraft:iron_ingot").fairValue(), 1e-9);
        assertEquals(0.60, c.spec("minecraft:bone").fairValue(), 1e-9);
        assertEquals(1.10, c.spec("minecraft:oak_log").fairValue(), 1e-9);
        assertEquals(local.spec(WHEAT).depth(), c.spec(WHEAT).depth());
    }

    @Test
    void componentsAreNotTradedAndLinkedItemsFollowTheirBase() {
        assertFalse(capital.catalog().trades("realisticmarkets:brass_fittings"));
        assertEquals(WHEAT, capital.catalog().pool("minecraft:hay_block").itemId());
        assertEquals(9 * capital.mid(WHEAT, 0), capital.mid("minecraft:hay_block", 0), 1e-9);
    }

    @Test
    void capitalSpreadIsFifteenPercentAndTheLicenseDoesNotApply() {
        assertEquals(0.65 * 0.925, capital.bid(WHEAT, 0, false), 1e-9);
        assertEquals(capital.bid(WHEAT, 0, false), capital.bid(WHEAT, 0, true), 1e-12);
    }

    @Test
    void badConfigIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Capital.parse(new StringReader("freight,1.5\n")));
        assertThrows(IllegalArgumentException.class, () -> Capital.parse(new StringReader("farm,abc\n")));
        assertThrows(IllegalArgumentException.class, () -> Capital.parse(new StringReader("farm,0\n")));
    }

    // ---- shipments

    Shipment ship(Map<String, Integer> items, double day) {
        return book.ship(OWNER, CRATE + day, items, 0, day, capital, cfg.transitDays());
    }

    @Test
    void arrivesAfterExactlyOneDay() {
        ship(Map.of(WHEAT, 128), 2.25);
        assertEquals(List.of(), book.settleDue(capital, 3.2499, cfg.freight()));
        assertEquals(1, book.settleDue(capital, 3.25, cfg.freight()).size());
        assertTrue(book.inTransit().isEmpty());
    }

    @Test
    void freightIsFivePercentRoundedUpToTheDime() {
        ship(Map.of(WHEAT, 128), 0);
        Settlement s = book.settleDue(capital, 1, cfg.freight()).getFirst();
        assertEquals(6050, s.grossCents());   // $60.50
        assertEquals(310, s.freightCents());  // 5% = $3.025 -> $3.10
        assertEquals(5740, s.payoutCents());
        assertEquals(0, s.payoutCents() % 10);
    }

    @Test
    void pricedOnArrivalNotAtShipping() {
        capital.sell(WHEAT, 256, 0, false); // someone flooded the Capital
        Map<String, Integer> cargo = Map.of(WHEAT, 64);
        long estimateAtShipping = ShipmentBook.estimate(capital, cargo, 0, cfg.freight());
        ship(cargo, 0);
        long paid = book.settleDue(capital, 1, cfg.freight()).getFirst().payoutCents();
        assertTrue(paid > estimateAtShipping, "the Capital recovered in transit: " + paid + " vs " + estimateAtShipping);
    }

    @Test
    void earlierArrivalsPushTheCapitalPriceDownForLaterOnes() {
        ship(Map.of(WHEAT, 128), 0.0);
        ship(Map.of(WHEAT, 128), 0.1);
        List<Settlement> both = book.settleDue(capital, 1.1, cfg.freight());
        assertEquals(2, both.size());
        assertTrue(both.get(0).shipment().shippedDay() < both.get(1).shipment().shippedDay(), "settled in arrival order");
        assertTrue(both.get(1).payoutCents() < both.get(0).payoutCents(), "second shipment sells into the first's impact");
    }

    @Test
    void oneShipmentPerCrateAndOnlyCapitalGoods() {
        book.ship(OWNER, CRATE, Map.of(WHEAT, 1), 0, 0, capital, 1);
        assertThrows(RejectedException.class, () -> book.ship(OWNER, CRATE, Map.of(WHEAT, 1), 0, 0, capital, 1));
        assertThrows(RejectedException.class,
                () -> book.ship(OWNER, "elsewhere", Map.of("realisticmarkets:ink_bottle", 1), 0, 0, capital, 1));
        assertThrows(RejectedException.class, () -> book.ship(OWNER, "elsewhere", Map.of(), 0, 0, capital, 1));
        assertTrue(book.inTransitAt(CRATE).isPresent());
        book.settleDue(capital, 1, cfg.freight());
        assertFalse(book.inTransitAt(CRATE).isPresent(), "crate is free again after arrival");
    }

    @Test
    void beatsLocalComparesPayoutWithTheLocalQuoteAtShipping() {
        Dealer localDealer = new Dealer(local, DealerParams.noDrift(), 7L);
        long localQuote = localDealer.quoteSell(WHEAT, 128, 0, false).cents();
        assertEquals(4530, localQuote);
        book.ship(OWNER, CRATE, Map.of(WHEAT, 128), localQuote, 0, capital, 1);
        assertTrue(book.settleDue(capital, 1, cfg.freight()).getFirst().beatsLocal());

        long ironQuote = localDealer.quoteSell("minecraft:iron_ingot", 16, 0, false).cents();
        book.ship(OWNER, CRATE, Map.of("minecraft:iron_ingot", 16), ironQuote, 1, capital, 1);
        assertFalse(book.settleDue(capital, 2, cfg.freight()).getFirst().beatsLocal(), "mining goods lose at the Capital");
    }

    @Test
    void shipmentsRoundTrip() throws Exception {
        book.ship(OWNER, CRATE, Map.of(WHEAT, 128, "minecraft:carrot", 64), 4530, 12.25, capital, 1);
        book.ship(OWNER, "minecraft:the_nether|5|70|-9", Map.of("minecraft:bone", 10), 400, 12.5, capital, 1);
        StringWriter w = new StringWriter();
        book.write(w);
        ShipmentBook back = ShipmentBook.read(new StringReader(w.toString()));
        assertEquals(List.copyOf(book.inTransit()), List.copyOf(back.inTransit()));
        Shipment next = back.ship(OWNER, "new", Map.of(WHEAT, 1), 0, 13, capital, 1);
        assertEquals(3, next.id(), "ids keep counting after a reload");
        assertThrows(IllegalArgumentException.class, () -> ShipmentBook.read(new StringReader("ship\t1\tbroken\n")));
    }
}
