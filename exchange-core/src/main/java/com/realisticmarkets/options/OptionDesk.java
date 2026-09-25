package com.realisticmarkets.options;

import com.realisticmarkets.futures.ClearingHouse;
import com.realisticmarkets.money.Money;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * The Options Desk's prices. European calls and puts on the six futures goods (in futures lots) and the six companies'
 * shares (10 a contract), expiring on the next two quarter days, five strikes each around the forward price. Priced
 * with Black-76 at a volatility blending the underlying's realized volatility over the last {@link #VOL_DAYS} dawns with
 * its long-run level, times a smile; the desk sells at fair +{@link #HALF_SPREAD} and buys back at fair -{@link #HALF_SPREAD}, a dime at least, and
 * leans {@link #SKEW_PER_CONTRACT} further per contract of the series an account has already traded today. At each
 * expiry dawn it records the settlement price of every underlying; in-the-money papers then pay their intrinsic value.
 */
public final class OptionDesk {
    public static final String HEADER = "# Realistic Markets options desk v1";
    public static final double HALF_SPREAD = 0.04, SKEW_PER_CONTRACT = 0.005, SMILE = 2.0;
    /** Long-run daily volatility (goods measured over 32 worlds x 40 weeks of week-long moves; shares an estimate). */
    public static final double LONG_RUN_GOODS_VOL = 0.055, LONG_RUN_SHARE_VOL = 0.04, MIN_VOL = 0.005, MAX_VOL = 0.25;
    public static final int VOL_DAYS = 28, MIN_CLOSES = 5, WEEKLY_CLOSES = 14, HORIZON = 7, SHARES_PER_CONTRACT = 10;
    public static final double[] MONEYNESS = {0.8, 0.9, 1.0, 1.1, 1.2};

    /** Where the desk gets prices: each underlying's price per contract now, forward to a day, and the day's rate. */
    public interface Market {
        /** Price of one contract's worth of the underlying now (cents). */
        double spotCents(String underlying, double day);

        /** Forward price of one contract's worth for {@code expiry} (cents). */
        double forwardCents(String underlying, long expiry, double day);

        /** The daily interest rate for discounting. */
        double rate(double day);
    }

    /** A series: every paper with these terms is the same. {@code strikeCents} is for the whole contract. */
    public record Series(String underlying, boolean call, long strikeCents, long expiry) {
        public String key() {
            return underlying + "|" + (call ? "C" : "P") + "|" + strikeCents + "|" + expiry;
        }

        public static Series parse(String key) {
            String[] p = key.split("\\|");
            return new Series(p[0], "C".equals(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]));
        }

        /** What one contract pays at a settlement price (cents). */
        public long intrinsic(long settleCents) {
            return Math.max(0, call ? settleCents - strikeCents : strikeCents - settleCents);
        }
    }

    private final Market market;
    private final Map<String, Deque<double[]>> closes = new LinkedHashMap<>(); // underlying -> (day, price) newest last
    private final Map<String, Long> settlements = new LinkedHashMap<>();       // underlying@expiry -> cents
    private final Map<String, Long> volume = new LinkedHashMap<>();            // account|series -> contracts today
    private long volumeDay = Long.MIN_VALUE;

    public OptionDesk(Market market) {
        this.market = market;
    }

    public Market market() { return market; }

    public static boolean isGood(String underlying) {
        for (ClearingHouse.Product p : ClearingHouse.PRODUCTS) if (p.code().equals(underlying)) return true;
        return false;
    }

    /** Units of the underlying in one contract: a futures lot for goods, 10 shares for a company. */
    public static int contractSize(String underlying) {
        return isGood(underlying) ? ClearingHouse.product(underlying).lot() : SHARES_PER_CONTRACT;
    }

    public static long[] expiries(long today) {
        return ClearingHouse.expiries(today);
    }

    /** Five strikes around today's forward: 80-120%, on a tidy step (1, 2 or 5 x a power of ten, about 5% of it). */
    public List<Long> strikes(String underlying, long expiry, double day) {
        double f = market.forwardCents(underlying, expiry, day);
        long step = tidy(f * 0.05);
        List<Long> out = new ArrayList<>();
        for (double m : MONEYNESS) {
            long k = Math.max(step, Math.round(f * m / step) * step);
            if (!out.contains(k)) out.add(k);
        }
        return out;
    }

    static long tidy(double x) {
        if (x <= 10) return 10;
        double p = Math.pow(10, Math.floor(Math.log10(x)));
        double m = x / p;
        double nice = m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10;
        return Math.max(10, Math.round(nice * p / 10) * 10); // whole dimes
    }

    // ------------------------------------------------------------------ volatility

    /** Records a dawn's price for the volatility estimate. */
    public void observe(String underlying, long day, double priceCents) {
        if (priceCents <= 0) return;
        Deque<double[]> d = closes.computeIfAbsent(underlying, k -> new ArrayDeque<>());
        if (!d.isEmpty() && d.peekLast()[0] >= day) return;
        d.addLast(new double[] {day, priceCents});
        while (d.size() > VOL_DAYS + 1) d.removeFirst();
    }

    /**
     * Daily volatility of the log price, or the class default with fewer than {@link #MIN_CLOSES} closes. With at least
     * {@link #WEEKLY_CLOSES} it's measured on overlapping {@link #HORIZON}-day moves (then divided back to a day), because
     * that's how long options run and prices partly reverse from day to day (a news shock fades): daily moves alone would
     * overstate a week's swing. With fewer, on daily moves.
     */
    public double realizedVol(String underlying) {
        Deque<double[]> d = closes.get(underlying);
        if (d == null || d.size() < MIN_CLOSES) return longRunVol(underlying);
        List<double[]> c = new ArrayList<>(d);
        int k = c.size() >= WEEKLY_CLOSES ? HORIZON : 1;
        double sum = 0;
        int n = 0;
        for (int i = k; i < c.size(); i++) {
            double[] from = c.get(i - k), to = c.get(i);
            double r = Math.log(to[1] / from[1]);
            sum += r * r / (to[0] - from[0]);
            n++;
        }
        double var = n == 0 ? 0 : sum / n;
        return Math.max(MIN_VOL, Math.min(MAX_VOL, Math.sqrt(var)));
    }

    public static double longRunVol(String underlying) {
        return isGood(underlying) ? LONG_RUN_GOODS_VOL : LONG_RUN_SHARE_VOL;
    }

    /**
     * The at-the-money volatility the desk prices at: recent realized volatility blended half and half (in variance)
     * with the long-run level, because news can break any day: a quiet month doesn't make a drought impossible.
     */
    public double baseVol(String underlying) {
        double r = realizedVol(underlying), l = longRunVol(underlying);
        return Math.sqrt(0.5 * r * r + 0.5 * l * l);
    }

    /** The volatility the desk prices a strike at: the base, times the smile (away-from-the-money strikes cost more). */
    public double impliedVol(String underlying, double strikeCents, double forwardCents) {
        double m = Math.log(strikeCents / forwardCents);
        return baseVol(underlying) * (1 + SMILE * m * m);
    }

    // ------------------------------------------------------------------ prices

    public OptionMath.Greeks greeks(Series s, double day) {
        double f = market.forwardCents(s.underlying(), s.expiry(), day);
        double t = Math.max(0, s.expiry() - day);
        return OptionMath.greeks(s.call(), f, s.strikeCents(), t, impliedVol(s.underlying(), s.strikeCents(), f), market.rate(day));
    }

    /** Fair value of one contract (cents) before the desk's spread; after expiry, its intrinsic value if settled. */
    public double fair(Series s, double day) {
        OptionalLong settled = settlement(s.underlying(), s.expiry());
        if (settled.isPresent()) return s.intrinsic(settled.getAsLong());
        return greeks(s, day).price();
    }

    /** What the desk charges for one more contract (cents, rounded up to the dime). */
    public long ask(String account, Series s, double day) {
        double f = fair(s, day);
        double skew = SKEW_PER_CONTRACT * Math.max(0, traded(account, s, day));
        return Money.roundUpToDime(Math.max(f * (1 + HALF_SPREAD + skew), f + 10));
    }

    /** What the desk pays for one contract (cents, rounded down to the dime; never negative). */
    public long bid(String account, Series s, double day) {
        double f = fair(s, day);
        double skew = SKEW_PER_CONTRACT * Math.max(0, -traded(account, s, day));
        return Math.max(0, Money.roundDownToDime(Math.min(f * (1 - HALF_SPREAD - skew), f - 10)));
    }

    /** Net contracts {@code account} has bought (+) or sold (-) of the series today. */
    public long traded(String account, Series s, double day) {
        if (volumeDay != (long) Math.floor(day)) return 0;
        return volume.getOrDefault(account + "|" + s.key(), 0L);
    }

    /** Records a trade for the volume lean: {@code contracts} > 0 bought from the desk, < 0 sold to it. */
    public void recordTrade(String account, Series s, long contracts, double day) {
        long today = (long) Math.floor(day);
        if (volumeDay != today) {
            volumeDay = today;
            volume.clear();
        }
        volume.merge(account + "|" + s.key(), contracts, Long::sum);
    }

    // ------------------------------------------------------------------ settlement

    /** At an expiry dawn: records the settlement price (per contract) of an underlying for that expiry. */
    public void settle(String underlying, long expiry, long priceCents) {
        settlements.putIfAbsent(underlying + "@" + expiry, priceCents);
    }

    public OptionalLong settlement(String underlying, long expiry) {
        Long v = settlements.get(underlying + "@" + expiry);
        return v == null ? OptionalLong.empty() : OptionalLong.of(v);
    }

    // ------------------------------------------------------------------ save format

    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        for (Map.Entry<String, Deque<double[]>> e : closes.entrySet()) {
            for (double[] c : e.getValue()) w.write("close\t" + e.getKey() + "\t" + (long) c[0] + "\t" + c[1] + "\n");
        }
        for (Map.Entry<String, Long> e : settlements.entrySet()) w.write("settle\t" + e.getKey() + "\t" + e.getValue() + "\n");
        w.flush();
    }

    public static OptionDesk read(Reader r, Market market) throws IOException {
        OptionDesk d = new OptionDesk(market);
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            switch (c[0]) {
                case "close" -> d.observe(c[1], Long.parseLong(c[2]), Double.parseDouble(c[3]));
                case "settle" -> d.settlements.put(c[1], Long.parseLong(c[2]));
                default -> { }
            }
        }
        return d;
    }
}
