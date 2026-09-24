package com.realisticmarkets.dealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpreadColumnTest {
    static final String CSV = """
            item,fair_value,depth,group,base_item,base_units,spread
            minecraft:wheat,0.50,256,farm,,
            minecraft:hay_block,,,farm,minecraft:wheat,9
            realisticmarkets:ledger_paper,4.00,64,components,,,0.40
            realisticmarkets:brass_fittings,10.00,32,components,,,0.40
            """;

    Dealer dealer;

    @BeforeEach
    void setUp() throws Exception {
        dealer = new Dealer(DealerCatalog.parseCsv(new StringReader(CSV)), DealerParams.noDrift(), 1L);
    }

    @Test
    void spreadColumnIsOptional() throws Exception {
        DealerCatalog c = DealerCatalog.parseCsv(new StringReader(CSV));
        assertNull(c.spec("wheat").spread());
        assertNull(c.spec("hay_block").spread());
        assertEquals(0.40, c.spec("realisticmarkets:ledger_paper").spread());
    }

    @Test
    void componentQuotesUseFortyPercent() {
        assertEquals(4.00 * 0.80, dealer.bid("realisticmarkets:ledger_paper", 0, false), 1e-9);
        assertEquals(4.00 * 1.20, dealer.ask("realisticmarkets:ledger_paper", 0, false), 1e-9);
        assertEquals(10.00 * 1.20, dealer.ask("realisticmarkets:brass_fittings", 0, false), 1e-9);
    }

    @Test
    void itemsWithoutTheColumnKeepTwentyPercent() {
        assertEquals(0.45, dealer.bid("wheat", 0, false), 1e-9);
        assertEquals(0.55, dealer.ask("wheat", 0, false), 1e-9);
        assertEquals(9 * 0.45, dealer.bid("hay_block", 0, false), 1e-9);
    }

    @Test
    void merchantLicenseScalesComponentSpreadByTwelveTwentieths() {
        // 40% * 12/20 = 24%
        assertEquals(4.00 * 0.88, dealer.bid("realisticmarkets:ledger_paper", 0, true), 1e-9);
        assertEquals(4.00 * 1.12, dealer.ask("realisticmarkets:ledger_paper", 0, true), 1e-9);
        assertEquals(0.47, dealer.bid("wheat", 0, true), 1e-9);
    }

    @Test
    void wideSpreadAppliesToExecutedTrades() {
        // First unit bought at ~ask; round-trip loses about the full 40%.
        long paid = dealer.buy("realisticmarkets:ledger_paper", 1, 0, false).cents();
        long got = dealer.sell("realisticmarkets:ledger_paper", 1, 0, false).cents();
        assertEquals(480, paid, 10);
        assertEquals(320, got, 10);
    }

    @Test
    void linkedItemsCannotSetTheirOwnSpread() {
        String bad = "minecraft:wheat,0.50,256,farm,,\nminecraft:hay_block,,,farm,minecraft:wheat,9,0.40\n";
        assertThrows(IllegalArgumentException.class, () -> DealerCatalog.parseCsv(new StringReader(bad)));
    }

    @Test
    void configRowsOverrideBuiltInsAndNewBuiltInsStillAppear() throws Exception {
        DealerCatalog builtIn = DealerCatalog.parseCsv(new StringReader(CSV));
        // An old config copy: predates brass fittings, and the player tweaked wheat and added melon.
        DealerCatalog config = DealerCatalog.parseCsv(new StringReader("""
                minecraft:wheat,0.60,256,farm,,
                minecraft:hay_block,,,farm,minecraft:wheat,9
                realisticmarkets:ledger_paper,4.00,64,components,,,0.40
                minecraft:melon_slice,0.15,512,farm,,
                """));
        DealerCatalog merged = DealerCatalog.merge(builtIn, config);
        assertEquals(0.60, merged.spec("wheat").fairValue(), "the player's edit wins");
        assertEquals(10.00, merged.spec("realisticmarkets:brass_fittings").fairValue(), "a new built-in item appears");
        assertEquals(0.15, merged.spec("melon_slice").fairValue(), "config-only items are added");
        assertEquals(5, merged.all().size());
        assertEquals("minecraft:wheat", merged.all().keySet().iterator().next(), "built-in order is kept");
    }

    @Test
    void anOldConfigRowKeepsTheBuiltInCollateralClass() throws Exception {
        DealerCatalog builtIn = DealerCatalog.parseCsv(new StringReader("minecraft:diamond,100,32,mining,,,,A\n"));
        DealerCatalog old = DealerCatalog.parseCsv(new StringReader("minecraft:diamond,120,32,mining,,\n"));
        DealerCatalog merged = DealerCatalog.merge(builtIn, old);
        assertEquals(120, merged.spec("diamond").fairValue(), "the config's price wins");
        assertEquals("A", merged.spec("diamond").collateralClass(), "but a blank class keeps the built-in one");
    }

    @Test
    void collateralClassColumn() throws Exception {
        DealerCatalog c = DealerCatalog.parseCsv(new StringReader("minecraft:diamond,100,32,mining,,,,a\n"));
        assertEquals("A", c.spec("diamond").collateralClass());
        assertThrows(IllegalArgumentException.class,
                () -> DealerCatalog.parseCsv(new StringReader("minecraft:diamond,100,32,mining,,,,E\n")));
        assertThrows(IllegalArgumentException.class, () -> DealerCatalog.parseCsv(new StringReader(
                "minecraft:gold_ingot,15,96,mining,,\nminecraft:gold_block,,,mining,minecraft:gold_ingot,9,,A\n")));
    }

    @Test
    void outOfRangeSpreadIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DealerCatalog.parseCsv(new StringReader("x:y,1.0,10,misc,,,2.5\n")));
    }
}
