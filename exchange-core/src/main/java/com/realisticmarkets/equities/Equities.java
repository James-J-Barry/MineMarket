package com.realisticmarkets.equities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;

/**
 * The companies' businesses, quarter by quarter. Each dawn the caller passes in today's prices (Dealer fair values for
 * items; for {@link #CPI}, "fees" and "shipping", the general price level, with shipping scaled by how busy the trade
 * routes are) and the world events that started today. Fixed costs rise with the price level, so earnings keep up
 * with inflation; required returns are real (above inflation). Every
 * {@link Company#QUARTER_DAYS} days each company reports:
 * <pre>
 *   output   = last output x (1 + growth) x e^(vol x shock)        (the business's own luck, seeded)
 *   revenue  = output x sum(units x the quarter's average price) x (1 + event effect) per event
 *   costs    = fixed cost x (1 + growth)^quarter + output x sum(input units x average price)
 *   earnings = revenue - costs;  dividend a share = payout x earnings / shares (none if earnings are negative)
 * </pre>
 * Fixed costs make earnings swing more than revenue, so a fall in iron hits a miner's earnings harder than its sales.
 * Earnings not paid out stay in the company as cash, reinvested at the required return r (a quarter), so a company
 * that pays no dividend still rewards its owners, through the share price.
 * <p>Fair value a share = (company cash + normalized earnings x (1 + g) / (r - g)) / shares. Normalized earnings
 * average the last 4 reports, with this quarter so far (at its prices and events to date) taking the oldest one's
 * place a seventh at a time as its days pass, so the value moves every day with commodity prices and news, and glides
 * rather than jumps from quarter to quarter. When a quarter closes, its dividend leaves the company and the
 * value drops by it. Nothing here reads a clock.
 */
public final class Equities {
    public static final String HEADER = "# Realistic Markets equities v1";
    /** The general price level (1.0 at the start); every company tracks it for its fixed costs. */
    public static final String CPI = "cpi";
    public static final int NORMALIZE_QUARTERS = 4;
    /** Fair value never drops below this share of the starting value (the company's assets are worth something). */
    public static final double VALUE_FLOOR = 0.1;

    /** One quarter's results. Money in cents; {@code dividend} is per share. */
    public record Report(String ticker, long quarter, long revenue, long costs, long earnings, long dividend) {}

    private static final class State {
        double logOutput;
        double cash; // cents: retained earnings, reinvested at the required return (plus inflation)
        double lastCpi; // the price level averaged over the last reported quarter
        final Map<String, double[]> sums = new LinkedHashMap<>(); // key -> {sum, samples}
        final Map<String, Integer> events = new LinkedHashMap<>();
        final List<Report> reports = new ArrayList<>();
    }

    private final CompanyCatalog catalog;
    private final long seed;
    private final Map<String, Double> baseline = new LinkedHashMap<>(); // key -> price, the starting point
    private final Map<String, Double> lastAverage = new LinkedHashMap<>();
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Long> startValue = new LinkedHashMap<>();
    private long openQuarter = Long.MIN_VALUE;
    private long lastDay = Long.MIN_VALUE;
    private CompanyNews news; // null: no company news
    /** News re-rates a share at once and fades as it shows up in reported earnings (the 4-quarter window). */
    public static final int OUTLOOK_DAYS = NORMALIZE_QUARTERS * Company.QUARTER_DAYS;

    public Equities(CompanyCatalog catalog, ToDoubleFunction<String> baselinePrice, long seed) {
        this.catalog = catalog;
        this.seed = seed;
        for (String k : catalog.priceKeys()) baseline.put(k, baselinePrice.applyAsDouble(k));
        for (Company c : catalog.all()) {
            states.put(c.ticker(), new State());
            startValue.put(c.ticker(), value(c, expectedEarnings(c)));
        }
    }

    public CompanyCatalog catalog() { return catalog; }

    /** Plugs in company news (the Electronic Newsfeed's stories), or null for none. */
    public void setNews(CompanyNews news) { this.news = news; }

    public CompanyNews news() { return news; }

    public static long quarterOf(long day) {
        return Math.floorDiv(day, Company.QUARTER_DAYS);
    }

    /**
     * Records {@code day}'s prices and events. Closing a quarter happens here: the first observation of a new quarter
     * reports the old one. Returns the reports issued (empty most days).
     */
    public List<Report> observe(long day, ToDoubleFunction<String> price, Collection<String> eventsToday) {
        long q = quarterOf(day);
        if (openQuarter == Long.MIN_VALUE) openQuarter = q;
        List<Report> issued = new ArrayList<>();
        while (q > openQuarter) {
            issued.addAll(close(openQuarter));
            openQuarter++;
        }
        if (news != null && lastDay != Long.MIN_VALUE) {
            // Stories the rest of the market hears today (they appeared on the Newsfeed yesterday) change the business.
            for (long d = lastDay + 1; d <= day; d++) {
                for (CompanyNews.Story st : news.startingOn(d - 1)) {
                    State s = states.get(st.type().ticker());
                    if (s != null) s.logOutput += st.logEffect();
                }
            }
        }
        lastDay = Math.max(lastDay, day);
        for (Company c : catalog.all()) {
            State s = states.get(c.ticker());
            for (String k : keys(c)) {
                double[] acc = s.sums.computeIfAbsent(k, x -> new double[2]);
                acc[0] += price.applyAsDouble(k);
                acc[1]++;
            }
            for (String e : eventsToday) if (c.events().containsKey(e)) s.events.merge(e, 1, Integer::sum);
        }
        return issued;
    }

    private static List<String> keys(Company c) {
        List<String> k = new ArrayList<>(c.revenue().keySet());
        for (String i : c.inputs().keySet()) if (!k.contains(i)) k.add(i);
        if (!k.contains(CPI)) k.add(CPI);
        return k;
    }

    private List<Report> close(long quarter) {
        List<Report> out = new ArrayList<>();
        for (Company c : catalog.all()) {
            State s = states.get(c.ticker());
            s.logOutput += Math.log(1 + c.growth()) + c.outputVol() * shock(c.ticker(), quarter);
            for (String k : keys(c)) lastAverage.put(k, average(s, k));
            long[] rc = results(c, s, quarter, Math.exp(s.logOutput));
            long earn = rc[0] - rc[1];
            long div = earn > 0 ? (long) Math.floor(c.payout() * earn / c.shares()) : 0;
            double cpi = average(s, CPI), inflation = s.lastCpi > 0 ? cpi / s.lastCpi : 1;
            s.lastCpi = cpi;
            s.cash = s.cash * (1 + c.quarterReturn()) * inflation + earn - (double) div * c.shares();
            Report r = new Report(c.ticker(), quarter, rc[0], rc[1], earn, div);
            s.reports.add(r);
            s.sums.clear();
            s.events.clear();
            out.add(r);
        }
        return out;
    }

    private double average(State s, String key) {
        double[] acc = s.sums.get(key);
        return acc != null && acc[1] > 0 ? acc[0] / acc[1] : lastAverage.getOrDefault(key, baseline.get(key));
    }

    /** {revenue, costs} in cents for a quarter at the state's average prices and events so far. */
    private long[] results(Company c, State s, long quarter, double scale) {
        double revenue = 0;
        for (Map.Entry<String, Long> e : c.revenue().entrySet()) revenue += e.getValue() * average(s, e.getKey());
        for (Map.Entry<String, Integer> e : s.events.entrySet()) revenue *= Math.pow(1 + c.events().get(e.getKey()), e.getValue());
        revenue *= scale;
        double costs = c.fixedCost() * Math.pow(1 + c.growth(), quarter + 1) * average(s, CPI);
        for (Map.Entry<String, Long> e : c.inputs().entrySet()) costs += scale * e.getValue() * average(s, e.getKey());
        return new long[] {Math.round(revenue * 100), Math.round(costs * 100)};
    }

    private double shock(String ticker, long quarter) {
        long mix = seed ^ (ticker.hashCode() * 0x9E3779B97F4A7C15L) ^ (quarter * 0xC2B2AE3D27D4EB4FL);
        return new SplittableRandom(mix).nextGaussian();
    }

    /** Earnings (cents) a quarter at the starting prices, output and costs: the value before anything has happened. */
    private long expectedEarnings(Company c) {
        double revenue = 0, costs = c.fixedCost();
        for (Map.Entry<String, Long> e : c.revenue().entrySet()) revenue += e.getValue() * baseline.get(e.getKey());
        for (Map.Entry<String, Long> e : c.inputs().entrySet()) costs += e.getValue() * baseline.get(e.getKey());
        return Math.round((revenue - costs) * 100);
    }

    private static long value(Company c, double earningsCents) {
        double r = c.quarterReturn(), g = c.growth();
        return Math.round(earningsCents * (1 + g) / (r - g) / c.shares());
    }

    /**
     * Fair value of one share, in cents: company cash plus normalized earnings capitalized at the required return,
     * never below the floor. Normalized earnings average the last reports with this quarter's results so far.
     */
    public long fairValueCents(String ticker) {
        Company c = catalog.company(ticker);
        State s = states.get(ticker);
        List<Report> rs = s.reports;
        double e;
        if (rs.isEmpty()) {
            e = expectedEarnings(c);
        } else {
            // The last 4 reports, with this quarter so far gradually taking the oldest one's place as its days pass.
            int n = Math.min(NORMALIZE_QUARTERS, rs.size());
            double sum = 0;
            for (int i = rs.size() - n; i < rs.size(); i++) sum += rs.get(i).earnings();
            double[] acc = s.sums.isEmpty() ? null : s.sums.values().iterator().next();
            double f = acc == null ? 0 : Math.min(1, acc[1] / Company.QUARTER_DAYS);
            if (f > 0) {
                long[] rc = results(c, s, openQuarter, Math.exp(s.logOutput) * (1 + c.growth()));
                double oldest = rs.get(rs.size() - n).earnings();
                sum += f * ((rc[0] - rc[1]) - oldest);
            }
            e = sum / n;
        }
        long v = Math.round(s.cash / c.shares()) + Math.round(value(c, e) * outlook(c, s, e));
        return Math.max(Math.round(startValue.get(ticker) * VALUE_FLOOR), v);
    }

    /**
     * How news the market has heard changes what it expects: each story's output change, magnified by the company's
     * operating leverage (revenue / earnings), fading linearly over {@link #OUTLOOK_DAYS} as it shows up in reports.
     */
    private double outlook(Company c, State s, double normalizedEarnings) {
        if (news == null || lastDay == Long.MIN_VALUE) return 1;
        double revenue = s.reports.isEmpty() ? expectedRevenue(c) : s.reports.getLast().revenue();
        double leverage = normalizedEarnings > 0 ? Math.max(1, Math.min(4, revenue / normalizedEarnings)) : 4;
        double sum = 0;
        for (long d = lastDay - 1; d > lastDay - 1 - OUTLOOK_DAYS; d--) {
            for (CompanyNews.Story st : news.startingOn(d)) {
                if (!st.type().ticker().equals(c.ticker())) continue;
                double age = lastDay - st.heardDay();
                sum += st.logEffect() * leverage * Math.max(0, 1 - age / OUTLOOK_DAYS);
            }
        }
        return Math.exp(sum);
    }

    private double expectedRevenue(Company c) {
        double revenue = 0;
        for (Map.Entry<String, Long> e : c.revenue().entrySet()) revenue += e.getValue() * baseline.get(e.getKey());
        return revenue * 100;
    }

    /** Company cash a share (cents): retained earnings plus their return; negative after losses. */
    public long cashPerShareCents(String ticker) {
        return Math.round(states.get(ticker).cash / catalog.company(ticker).shares());
    }

    /** The share's fair value before any quarter was reported. */
    public long startValueCents(String ticker) {
        return startValue.get(ticker);
    }

    /** A company's normal quarterly earnings (cents) at the starting prices: the yardstick for credit stress. */
    public long normalEarnings(String ticker) {
        return expectedEarnings(catalog.company(ticker));
    }

    public List<Report> reports(String ticker) {
        return List.copyOf(states.get(ticker).reports);
    }

    public Optional<Report> latest(String ticker) {
        List<Report> rs = states.get(ticker).reports;
        return rs.isEmpty() ? Optional.empty() : Optional.of(rs.getLast());
    }

    /** The last quarter any report covers, or -1 before the first report. */
    public long lastReportedQuarter() {
        for (State s : states.values()) return s.reports.isEmpty() ? -1 : s.reports.getLast().quarter();
        return -1;
    }

    /** Dividends a share (cents) declared after {@code paidThroughQuarter}, up to the latest report. */
    public long dividendsSince(String ticker, long paidThroughQuarter) {
        long sum = 0;
        for (Report r : states.get(ticker).reports) if (r.quarter() > paidThroughQuarter) sum += r.dividend();
        return sum;
    }

    // ------------------------------------------------------------------ persistence

    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        w.write("open\t" + openQuarter + "\n");
        w.write("day\t" + lastDay + "\n");
        for (Map.Entry<String, Double> e : lastAverage.entrySet()) w.write("avg\t" + e.getKey() + "\t" + e.getValue() + "\n");
        for (Map.Entry<String, State> e : states.entrySet()) {
            State s = e.getValue();
            w.write("company\t" + e.getKey() + "\t" + s.logOutput + "\t" + s.cash + "\t" + s.lastCpi + "\n");
            for (Map.Entry<String, double[]> a : s.sums.entrySet()) {
                w.write("sum\t" + e.getKey() + "\t" + a.getKey() + "\t" + a.getValue()[0] + "\t" + a.getValue()[1] + "\n");
            }
            for (Map.Entry<String, Integer> ev : s.events.entrySet()) w.write("event\t" + e.getKey() + "\t" + ev.getKey() + "\t" + ev.getValue() + "\n");
            for (Report r : s.reports) {
                w.write("report\t" + r.ticker() + "\t" + r.quarter() + "\t" + r.revenue() + "\t" + r.costs() + "\t" + r.earnings()
                        + "\t" + r.dividend() + "\n");
            }
        }
        w.flush();
    }

    /** Loads saved state into {@code into} (built from the same catalog). Unknown companies are skipped. */
    public static Equities read(Reader reader, Equities into) throws IOException {
        BufferedReader br = new BufferedReader(reader);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            try {
                switch (c[0]) {
                    case "open" -> into.openQuarter = Long.parseLong(c[1]);
                    case "day" -> into.lastDay = Long.parseLong(c[1]);
                    case "avg" -> into.lastAverage.put(c[1], Double.parseDouble(c[2]));
                    case "company" -> {
                        State s = into.states.get(c[1]);
                        if (s != null) {
                            s.logOutput = Double.parseDouble(c[2]);
                            s.cash = c.length > 3 ? Double.parseDouble(c[3]) : 0;
                            s.lastCpi = c.length > 4 ? Double.parseDouble(c[4]) : 0;
                        }
                    }
                    case "sum" -> {
                        State s = into.states.get(c[1]);
                        if (s != null) s.sums.put(c[2], new double[] {Double.parseDouble(c[3]), Double.parseDouble(c[4])});
                    }
                    case "event" -> {
                        State s = into.states.get(c[1]);
                        if (s != null) s.events.put(c[2], Integer.parseInt(c[3]));
                    }
                    case "report" -> {
                        State s = into.states.get(c[1]);
                        if (s != null) {
                            s.reports.add(new Report(c[1], Long.parseLong(c[2]), Long.parseLong(c[3]), Long.parseLong(c[4]),
                                    Long.parseLong(c[5]), Long.parseLong(c[6])));
                        }
                    }
                    default -> throw new IllegalArgumentException("unknown line type");
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("equities line " + n + " is malformed: '" + t + "'", e);
            }
        }
        return into;
    }
}
