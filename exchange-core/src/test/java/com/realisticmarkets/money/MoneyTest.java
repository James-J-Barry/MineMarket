package com.realisticmarkets.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void changeUsesFewestItems() {
        Map<Denomination, Long> c = Money.makeChange(2540); // $25.40
        assertEquals(2L, c.get(Denomination.TEN).longValue());
        assertEquals(5L, c.get(Denomination.ONE).longValue());
        assertEquals(4L, c.get(Denomination.DIME).longValue());
        assertEquals(null, c.get(Denomination.HUNDRED));
        assertEquals(2540, Money.total(c));
    }

    @Test
    void changeForLargeAmount() {
        Map<Denomination, Long> c = Money.makeChange(123_450); // $1,234.50
        assertEquals(12L, c.get(Denomination.HUNDRED).longValue());
        assertEquals(3L, c.get(Denomination.TEN).longValue());
        assertEquals(4L, c.get(Denomination.ONE).longValue());
        assertEquals(5L, c.get(Denomination.DIME).longValue());
    }

    @Test
    void zeroNeedsNoItems() {
        assertEquals(0, Money.makeChange(0).size());
    }

    @Test
    void rejectsAmountsBelowADime() {
        assertThrows(IllegalArgumentException.class, () -> Money.makeChange(2545));
        assertThrows(IllegalArgumentException.class, () -> Money.makeChange(-10));
    }

    @Test
    void roundingFavorsTheDealer() {
        assertEquals(2540, Money.roundDownToDime(2548.9));
        assertEquals(2550, Money.roundUpToDime(2540.1));
        assertEquals(2540, Money.roundUpToDime(2540.0));
        assertEquals(0, Money.roundDownToDime(9.99));
        assertEquals(0, Money.roundDownToDime(-5));
    }

    @Test
    void formatting() {
        assertEquals("$25.40", Money.format(2540));
        assertEquals("$1,234.05", Money.format(123_405));
        assertEquals("-$0.10", Money.format(-10));
    }
}
