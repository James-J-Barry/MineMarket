package com.realisticmarkets.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PriceChartTest {
    static final String W = "minecraft:wheat";

    @Test
    void sevenDaysInQuarterDayPointsWithGapsCarriedForward() {
        PriceHistory h = new PriceHistory();
        h.record(W, 1.5, 99, 1); // older than the week: left out
        h.record(W, 4.1, 50, 10);
        h.record(W, 4.2, 48, 5);
        h.record(W, 6.8, 60, 3); // news day: jumps in the afternoon
        h.record(W, 9.0, 55, 2);
        PriceChart c = PriceChart.of(h, W, 10); // days 4..10
        assertEquals(28, c.close().length);
        assertEquals(48, c.close()[0], "day 4, first quarter: last trade 48");
        assertEquals(50, c.high()[0]);
        assertEquals(48, c.close()[5], "carried forward through quiet quarters");
        assertEquals(48, c.close()[2 * 4 + 2], "day 6 before the jump");
        assertEquals(60, c.close()[2 * 4 + 3], "day 6, last quarter: after the jump");
        assertEquals(55, c.last());
        assertEquals(48, c.first());
        assertEquals(48, c.min());
        assertEquals(60, c.max());
        assertEquals(PriceChart.unpack(W, 10, c.packed()), c);
    }

    @Test
    void sparklineOfDailyCloses() {
        PriceHistory h = new PriceHistory();
        long[] closes = {40, 44, 48, 52, 56, 60, 40};
        for (int d = 0; d < 7; d++) h.record(W, 10 + d + 0.5, closes[d], 1);
        PriceChart c = PriceChart.of(h, W, 16);
        assertEquals("\u2581\u2582\u2584\u2585\u2587\u2588\u2581", c.sparkline());
        PriceHistory late = new PriceHistory();
        late.record(W, 15.5, 50, 1);
        PriceChart l = PriceChart.of(late, W, 16);
        assertEquals("     ▄▄", l.sparkline(), "days before the first trade are blank");
        assertFalse(l.empty());
        assertTrue(PriceChart.of(new PriceHistory(), W, 16).empty());
    }
}
