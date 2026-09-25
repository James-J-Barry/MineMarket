package com.realisticmarkets.sim;

import com.realisticmarkets.agents.FloorCatalog;
import com.realisticmarkets.agents.TradingFloor;
import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.dealer.WorldEvents;
import com.realisticmarkets.equities.Company;
import com.realisticmarkets.equities.CompanyCatalog;
import com.realisticmarkets.equities.Equities;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.PriceHistory;
import com.realisticmarkets.money.Money;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M6 balance check: the six companies over {@link #QUARTERS} quarters in {@link #SEEDS} worlds, with the Dealer's
 * prices trending and reacting to world events, earnings reported every 7 days, and shares trading on the Stock
 * Exchange's books (the Trading Floor engine, NPC traders anchored on each company's fair value). No player.
 * Reports each company's total return (price plus dividends) a day, volatility, worst week (a quarter) and worst 4
 * quarters (28 days), and an
 * even portfolio's, against the Bank Vault's 0.3% a day.
 */
public final class EquitySim {
    static final int QUARTERS = 20;
    static final int DAYS = QUARTERS * Company.QUARTER_DAYS;
    static final int AUCTIONS_PER_DAY = 120;
    static final int SEEDS = 8;
    static final double VAULT = 0.003;
    static final String GOODS = "goods", CPI = "prices", TREAS = "treas", CORP = "corp", VAULT_ROW = "vault";
    static final List<String> BENCHMARKS = List.of(GOODS, CPI, TREAS, CORP, VAULT_ROW);

    record Path(double[] close, double[] dividend, double[] fair) {} // per day, dollars a share

    static Map<String, Path> run(long seed) {
        Dealer dealer = new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), seed);
        WorldEvents events = WorldEvents.loadDefault(dealer.catalog(), seed ^ 0x4576656E7473L);
        dealer.setShocks(events);
        CompanyCatalog companies = CompanyCatalog.loadDefault();
        DealerCatalog cat = dealer.catalog();
        Equities eq = new Equities(companies, k -> cat.trades(k) ? cat.spec(k).fairValue() : 1.0, seed);
        TradingFloor market = new TradingFloor(new Exchange(), new PriceHistory(), FloorCatalog.loadStocks(), eq::fairValueCents, seed);
        Map<String, Path> paths = new LinkedHashMap<>();
        for (Company c : companies.all()) paths.put(c.ticker(), new Path(new double[DAYS + 1], new double[DAYS + 1], new double[DAYS + 1]));
        // Benchmarks: the Floor's 14 goods held at fair value (no trading costs), and the price level (cash in a chest
        // loses what it rises).
        paths.put(GOODS, new Path(new double[DAYS + 1], new double[DAYS + 1], new double[DAYS + 1]));
        paths.put(CPI, new Path(new double[DAYS + 1], new double[DAYS + 1], new double[DAYS + 1]));
        // Bonds: ladders reinvested every quarter into new 4-quarter bonds, held to maturity; and the vault, all on the
        // same seeded central bank.
        for (String k : List.of(TREAS, CORP, VAULT_ROW)) paths.put(k, new Path(new double[DAYS + 1], new double[DAYS + 1], new double[DAYS + 1]));
        com.realisticmarkets.rates.CentralBank central = new com.realisticmarkets.rates.CentralBank(seed ^ 0x52617465L);
        com.realisticmarkets.bonds.BondDesk desk = new com.realisticmarkets.bonds.BondDesk(central, eq);
        Ladder treasuries = new Ladder(List.of(com.realisticmarkets.bonds.Bond.TREASURY));
        List<String> tickers = new ArrayList<>();
        for (Company c : companies.all()) tickers.add(c.ticker());
        Ladder corporates = new Ladder(tickers);
        double vault = 10_000_00;
        List<String> goods = new ArrayList<>();
        Map<String, Double> start = new LinkedHashMap<>();
        for (FloorCatalog.Book b : FloorCatalog.loadDefault().all()) {
            goods.add(b.item());
            start.put(b.item(), dealer.fairValue(b.item(), 0));
        }
        for (int day = 0; day <= DAYS; day++) {
            final int d = day;
            List<String> today = new ArrayList<>();
            for (WorldEvents.Event e : events.startingOn(day)) today.add(e.type().id());
            for (Equities.Report r : eq.observe(day, k -> cat.trades(k) ? dealer.fairValue(k, d + 0.5) : dealer.priceLevel(d + 0.5), today)) {
                paths.get(r.ticker()).dividend()[day] = r.dividend() / 100.0;
            }
            for (int a = 0; a < AUCTIONS_PER_DAY; a++) {
                double now = day + a / (double) AUCTIONS_PER_DAY;
                for (Company c : companies.all()) {
                    // Re-observe prices intraday isn't needed: fair value moves daily; news moves it through prices.
                    market.auction(c.ticker(), eq.fairValueCents(c.ticker()), 1.0 / AUCTIONS_PER_DAY, now);
                }
            }
            double basket = 0;
            for (String g : goods) basket += dealer.fairValue(g, day + 0.5) / start.get(g) / goods.size();
            paths.get(GOODS).close()[day] = basket;
            paths.get(GOODS).fair()[day] = basket;
            paths.get(CPI).close()[day] = dealer.priceLevel(day + 0.5);
            paths.get(CPI).fair()[day] = dealer.priceLevel(day + 0.5);
            if (day >= 7) {
                treasuries.step(desk, day);
                corporates.step(desk, day);
            }
            paths.get(TREAS).close()[day] = paths.get(TREAS).fair()[day] = treasuries.value(desk, day + 0.5) / 100;
            paths.get(CORP).close()[day] = paths.get(CORP).fair()[day] = corporates.value(desk, day + 0.5) / 100;
            if (day > 0) vault *= 1 + central.rate(day - 1);
            paths.get(VAULT_ROW).close()[day] = paths.get(VAULT_ROW).fair()[day] = vault / 100;
            for (Company c : companies.all()) {
                long last = market.exchange().lastPrice(c.ticker()).orElse(eq.fairValueCents(c.ticker()));
                paths.get(c.ticker()).close()[day] = last / 100.0;
                paths.get(c.ticker()).fair()[day] = eq.fairValueCents(c.ticker()) / 100.0;
            }
        }
        return paths;
    }

    /**
     * A bond ladder: starts with $10,000 at day 7 and, at each quarter, collects coupons and maturities and puts all its
     * cash into new 4-quarter bonds of its issuers, split evenly, at the desk's issue price.
     */
    static final class Ladder {
        final List<String> issuers;
        final List<long[]> held = new ArrayList<>(); // index into bonds, count
        final List<com.realisticmarkets.bonds.Bond> bonds = new ArrayList<>();
        final List<Integer> paid = new ArrayList<>();
        double cash = 10_000_00;

        Ladder(List<String> issuers) {
            this.issuers = issuers;
        }

        void step(com.realisticmarkets.bonds.BondDesk desk, long day) {
            for (int i = 0; i < bonds.size(); i++) {
                var b = bonds.get(i);
                long n = held.get(i)[1];
                if (n == 0) continue;
                cash += desk.couponsOwed(b, paid.get(i), day) * n;
                paid.set(i, b.couponsDueBy(day));
                long r = desk.redemption(b, day);
                if (r > 0) {
                    cash += r * n;
                    held.get(i)[1] = 0;
                }
            }
            if (day % 7 != 0) return;
            double each = cash / issuers.size();
            for (String issuer : issuers) {
                var b = desk.issue(issuer, 4, day);
                long n = (long) Math.floor(each / desk.issuePrice(b));
                if (n <= 0) continue;
                cash -= n * desk.issuePrice(b);
                bonds.add(b);
                held.add(new long[] {bonds.size() - 1, n});
                paid.add(0);
            }
        }

        double value(com.realisticmarkets.bonds.BondDesk desk, double day) {
            double v = cash;
            for (int i = 0; i < bonds.size(); i++) {
                long n = held.get(i)[1];
                if (n == 0) continue;
                var b = bonds.get(i);
                v += n * (desk.price(b, day) + desk.couponsOwed(b, paid.get(i), day));
            }
            return v;
        }
    }

    /** Daily total returns (price change plus that day's dividend), from day 7 on (after the books settle). */
    static double[] returns(Path p) {
        double[] r = new double[DAYS - 7];
        for (int d = 8; d <= DAYS; d++) r[d - 8] = (p.close()[d] + p.dividend()[d]) / p.close()[d - 1] - 1;
        return r;
    }

    static double[] fairReturns(Path p) {
        double[] r = new double[DAYS - 7];
        for (int d = 8; d <= DAYS; d++) r[d - 8] = (p.fair()[d] + p.dividend()[d]) / p.fair()[d - 1] - 1;
        return r;
    }

    /** Compounded (geometric) return a day. */
    static double geo(double[] x) {
        double s = 0;
        for (double v : x) s += Math.log(1 + v);
        return Math.exp(s / x.length) - 1;
    }

    static double mean(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return s / x.length;
    }

    static double sd(double[] x) {
        double m = mean(x), s = 0;
        for (double v : x) s += (v - m) * (v - m);
        return Math.sqrt(s / (x.length - 1));
    }

    /** Worst compounded return over any {@code window} consecutive days. */
    static double worst(double[] r, int window) {
        double w = Double.MAX_VALUE;
        for (int i = 0; i + window <= r.length; i++) {
            double g = 1;
            for (int j = i; j < i + window; j++) g *= 1 + r[j];
            w = Math.min(w, g - 1);
        }
        return w;
    }

    public static void main(String[] args) {
        CompanyCatalog companies = CompanyCatalog.loadDefault();
        System.out.printf(Locale.ROOT, "Stock Exchange: %d companies, %d quarters (%d in-game days), %d worlds; vault %.2f%%/day%n",
                companies.all().size(), QUARTERS, DAYS, SEEDS, VAULT * 100);
        Map<String, List<double[]>> byCompany = new LinkedHashMap<>(), fairBy = new LinkedHashMap<>();
        List<double[]> portfolio = new ArrayList<>();
        Map<String, double[]> startEnd = new LinkedHashMap<>();
        for (long seed = 1; seed <= SEEDS; seed++) {
            Map<String, Path> paths = run(seed);
            double[] port = new double[DAYS - 7];
            for (Map.Entry<String, Path> e : paths.entrySet()) {
                double[] r = returns(e.getValue());
                byCompany.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(r);
                fairBy.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(fairReturns(e.getValue()));
                if (!BENCHMARKS.contains(e.getKey())) {
                    for (int i = 0; i < r.length; i++) port[i] += r[i] / companies.all().size();
                }
                if (seed == 1) startEnd.put(e.getKey(), new double[] {e.getValue().close()[7], e.getValue().close()[DAYS]});
            }
            portfolio.add(port);
        }
        System.out.printf(Locale.ROOT, "%-6s %10s %9s %9s %9s %11s %13s %s%n", "", "compound", "vs vault", "vol/day",
                "fair vol", "worst week*", "worst 4 qtrs", "world 1: price day 7 -> end");
        for (Map.Entry<String, List<double[]>> e : byCompany.entrySet()) {
            print(e.getKey(), e.getValue(), fairBy.get(e.getKey()), startEnd.get(e.getKey()));
        }
        print("even 6", portfolio, null, null);
        System.out.println("vault: the central bank's rate, which moves each quarter. treas / corp: every quarter, all the money");
        System.out.println("  in new 4-quarter Treasuries / an even spread of the six companies' bonds, held to maturity.");
        System.out.println("goods: the Floor's 14 goods held at fair value, no trading costs. prices: the general price level");
        System.out.println("  (cash kept in a chest loses this much a day of buying power).");
        System.out.println("* worst week in the typical (median) world; worst 4 quarters in any world.");
        System.out.println("Targets (M7): treasuries about the vault; corporates a little more with defaults; shares the most.");
        System.out.println("Targets (M6): the even portfolio beats the vault about 1.5-2x on average and has losing weeks;");
        System.out.println("  at least one company loses a third in a bad stretch; OWL steadiest, RSD most volatile.");
    }

    static void print(String name, List<double[]> runs, List<double[]> fair, double[] startEnd) {
        double m = 0, v = 0, fv = 0, wq = Double.MAX_VALUE;
        if (fair != null) for (double[] r : fair) fv += sd(r) / fair.size();
        double[] weeks = new double[runs.size()];
        for (int i = 0; i < runs.size(); i++) {
            double[] r = runs.get(i);
            m += geo(r) / runs.size();
            v += sd(r) / runs.size();
            weeks[i] = worst(r, 7);
            wq = Math.min(wq, worst(r, Company.QUARTER_DAYS * 4));
        }
        java.util.Arrays.sort(weeks);
        double ww = weeks[weeks.length / 2]; // the typical world's worst week
        System.out.printf(Locale.ROOT, "%-6s %9.2f%% %8.1fx %8.2f%% %9s %10.1f%% %12.1f%% %s%n", name, m * 100, m / VAULT,
                v * 100, fair == null ? "-" : String.format(Locale.ROOT, "%.2f%%", fv * 100), ww * 100, wq * 100, startEnd == null ? "" : Money.format(Math.round(startEnd[0] * 100)) + " -> "
                        + Money.format(Math.round(startEnd[1] * 100)));
    }
}
