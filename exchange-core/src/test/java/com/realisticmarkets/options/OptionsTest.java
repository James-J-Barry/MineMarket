package com.realisticmarkets.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class OptionsTest {

    /** A flat market: every contract worth {@code spot} cents, forward = spot, rate 0.3% a day. */
    static OptionDesk.Market flat(double spot) {
        return new OptionDesk.Market() {
            public double spotCents(String u, double day) { return spot; }
            public double forwardCents(String u, long expiry, double day) { return spot; }
            public double rate(double day) { return 0.003; }
        };
    }

    @Test
    void black76MatchesTheTextbookAndPutCallParity() {
        assertEquals(7.9656, OptionMath.price(true, 100, 100, 1, 0.2, 0), 1e-4, "at the money: F(2N(sigma/2)-1)");
        assertEquals(7.9656, OptionMath.price(false, 100, 100, 1, 0.2, 0), 1e-4, "the put too, with no interest");
        for (double k : new double[] {70, 95, 100, 120}) {
            for (double t : new double[] {1, 7, 14}) {
                double c = OptionMath.price(true, 100, k, t, 0.03, 0.003), p = OptionMath.price(false, 100, k, t, 0.03, 0.003);
                double df = Math.exp(-Math.log1p(0.003) * t);
                assertEquals(df * (100 - k), c - p, 1e-6, "call - put = discounted (F - K) at K=" + k + " t=" + t);
            }
        }
        assertEquals(0, OptionMath.price(true, 100, 120, 0, 0.03, 0), 0, "expired out of the money: nothing");
        assertEquals(20, OptionMath.price(false, 100, 120, 0, 0.03, 0), 1e-12, "expired in the money: intrinsic");
    }

    @Test
    void greeksMatchFiniteDifferences() {
        double f = 128, k = 135, t = 9, s = 0.03, r = 0.003, h = 1e-3;
        for (boolean call : new boolean[] {true, false}) {
            var g = OptionMath.greeks(call, f, k, t, s, r);
            double delta = (OptionMath.price(call, f + h, k, t, s, r) - OptionMath.price(call, f - h, k, t, s, r)) / (2 * h);
            double gamma = (OptionMath.price(call, f + h, k, t, s, r) - 2 * g.price() + OptionMath.price(call, f - h, k, t, s, r)) / (h * h);
            double vega = (OptionMath.price(call, f, k, t, s + 1e-5, r) - OptionMath.price(call, f, k, t, s - 1e-5, r)) / 2e-5 * 0.01;
            assertEquals(delta, g.delta(), 1e-5, "delta");
            assertEquals(gamma, g.gamma(), 1e-3, "gamma");
            assertEquals(vega, g.vega(), 1e-5, "vega per point of daily vol");
            assertEquals(g.price() - OptionMath.price(call, f, k, t - 1, s, r), g.theta(), 1e-12, "theta: a day's decay");
            assertTrue(call ? g.delta() > 0 : g.delta() < 0, "calls gain as the price rises, puts lose");
        }
    }

    @Test
    void fiveTidyStrikesAroundTheForward() {
        OptionDesk d = new OptionDesk(flat(12_890));
        List<Long> ks = d.strikes("WHT", 14, 10);
        assertEquals(List.of(10_500L, 11_500L, 13_000L, 14_000L, 15_500L), ks, "a $5 step on a $128.90 lot");
        assertEquals(10, OptionDesk.tidy(3), "a dime at least");
        assertEquals(200, OptionDesk.tidy(180));
        assertEquals(256, OptionDesk.contractSize("WHT"));
        assertEquals(10, OptionDesk.contractSize("OWL"), "10 shares a contract");
    }

    @Test
    void volatilityIsRealizedFromClosesTimesTheSmile() {
        OptionDesk d = new OptionDesk(flat(10_000));
        assertEquals(OptionDesk.LONG_RUN_GOODS_VOL, d.realizedVol("WHT"), 0, "the long-run level until 5 closes");
        assertEquals(OptionDesk.LONG_RUN_SHARE_VOL, d.realizedVol("OWL"), 0);
        double p = 10_000;
        for (int day = 1; day <= 8; day++) {
            d.observe("WHT", day, p);
            p *= Math.exp(day % 2 == 0 ? 0.02 : -0.02);
        }
        assertEquals(0.02, d.realizedVol("WHT"), 1e-9, "a few closes: daily moves, 2% a day up and down");
        for (int day = 9; day <= 28; day++) {
            d.observe("WHT", day, p);
            p *= Math.exp(day % 2 == 0 ? 0.02 : -0.02);
        }
        assertTrue(d.realizedVol("WHT") < 0.01, "on week-long moves the zigzag cancels out: " + d.realizedVol("WHT"));
        OptionDesk walk = new OptionDesk(flat(10_000));
        java.util.Random rnd = new java.util.Random(3);
        double q = 10_000;
        for (int day = 1; day <= 28; day++) {
            walk.observe("GLD", day, q);
            q *= Math.exp(0.02 * rnd.nextGaussian());
        }
        assertEquals(0.02, walk.realizedVol("GLD"), 0.008, "a random walk at 2% a day measures about 2%");
        d = walk;
        double base = Math.sqrt(0.5 * Math.pow(d.realizedVol("GLD"), 2) + 0.5 * Math.pow(OptionDesk.LONG_RUN_GOODS_VOL, 2));
        assertEquals(base, d.impliedVol("GLD", 10_000, 10_000), 1e-12, "at the money: realized blended with the long run");
        assertTrue(base > d.realizedVol("GLD"), "a quiet month doesn't make options nearly free");
        double up = d.impliedVol("GLD", 12_000, 10_000), down = d.impliedVol("GLD", 10_000 * 10_000 / 12_000.0, 10_000);
        assertTrue((up + down) / 2 > base * 1.02, "away from the money: the smile");

        // Mostly quiet weeks with the odd big jump up: surprises skew upward, so high strikes cost more than low ones.
        OptionDesk jumpy = new OptionDesk(flat(10_000));
        double z = 10_000;
        for (int day = 1; day <= 28; day++) {
            jumpy.observe("WHT", day, z);
            z *= Math.exp(day == 18 ? 0.25 : -0.01); // one jump, seen in a third of the week-long windows
        }
        assertTrue(jumpy.skewness("WHT") > 0.5, "upward skew: " + jumpy.skewness("WHT"));
        assertTrue(jumpy.impliedVol("WHT", 12_000, 10_000) > jumpy.impliedVol("WHT", 8_333, 10_000), "high strikes dearer");
    }

    @Test
    void theDeskQuotesASpreadThatLeansWithVolume() {
        OptionDesk d = new OptionDesk(flat(12_800));
        var s = new OptionDesk.Series("WHT", true, 13_000, 14);
        double fair = d.fair(s, 10);
        assertTrue(d.ask("a", s, 10) >= fair * 1.04 && d.ask("a", s, 10) < fair * 1.04 + 10, "ask: fair +4%, rounded up");
        assertTrue(d.bid("a", s, 10) <= fair * 0.96 && d.bid("a", s, 10) > fair * 0.96 - 10, "bid: fair -4%, rounded down");
        long ask = d.ask("a", s, 10);
        d.recordTrade("a", s, 4, 10);
        assertTrue(d.ask("a", s, 10) >= ask + fair * 0.02 - 10, "4 bought today: 2% more for the next (to the dime)");
        assertEquals(ask, d.ask("b", s, 10), "only for the account that bought");
        assertEquals(d.ask("b", s, 11), d.ask("a", s, 11), "a new day, the lean resets");
        var deep = new OptionDesk.Series("WHT", true, 30_000, 14);
        assertTrue(d.ask("a", deep, 10) >= 10 && d.bid("a", deep, 10) == 0, "a worthless strike: a dime to buy, nothing back");
    }

    @Test
    void atExpiryTheDeskPaysIntrinsicValue() {
        OptionDesk d = new OptionDesk(flat(12_800));
        var call = new OptionDesk.Series("WHT", true, 12_000, 14);
        var put = new OptionDesk.Series("WHT", false, 12_000, 14);
        d.settle("WHT", 14, 13_500);
        d.settle("WHT", 14, 1); // the first settlement stands
        assertEquals(1_500, call.intrinsic(d.settlement("WHT", 14).orElseThrow()));
        assertEquals(1_500, d.fair(call, 15), 1e-9, "after expiry: worth its intrinsic value");
        assertEquals(0, d.fair(put, 15), 1e-9, "the put expired worthless");
        assertEquals(call, OptionDesk.Series.parse(call.key()));
    }

    @Test
    void savesAndLoads() throws Exception {
        OptionDesk d = new OptionDesk(flat(10_000));
        for (int day = 1; day <= 8; day++) d.observe("GLD", day, 10_000 + day * 37);
        d.settle("OWL", 21, 4_210);
        StringWriter w = new StringWriter();
        d.write(w);
        OptionDesk back = OptionDesk.read(new StringReader(w.toString()), flat(10_000));
        assertEquals(d.realizedVol("GLD"), back.realizedVol("GLD"), 1e-12);
        assertEquals(4_210, back.settlement("OWL", 21).orElseThrow());
    }
}
