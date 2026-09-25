package com.realisticmarkets.options;

import com.realisticmarkets.collateral.CollateralValuer;
import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.futures.ClearingHouse;
import com.realisticmarkets.money.Money;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Options written (sold) by players to the Options Desk, on the six goods, backed by collateral in escrow.
 *
 * <p>Premium received = fair x (1 - {@link OptionDesk#HALF_SPREAD}) x (1 - {@link #PREMIUM_CUT} x (1 - Q)) per contract,
 * where Q is the collateral's quality ({@link CollateralValuer}). The underlying good itself backing a call counts as
 * Q = 1 for the contracts it covers (a <b>covered call</b> earns the full premium). The uncovered contracts need
 * collateral worth {@link #OPEN_COVER} x what a {@link #STRESS} move against them would cost; each dawn it must still
 * cover {@link #DAWN_COVER} x that, or there's a margin call, and at the next dawn short the desk buys the option back
 * out of the escrow. At expiry an in-the-money covered call is <b>called away</b> (the goods go to the desk, the strike
 * is paid); the rest pay their intrinsic value out of the escrow (cash first, then goods sold to the Dealer). Whatever
 * is left in escrow comes back to the writer to collect.
 */
public final class WrittenBook {
    public static final String HEADER = "# Realistic Markets written options v1";
    public static final double PREMIUM_CUT = 0.3, STRESS = 0.20, OPEN_COVER = 1.25, DAWN_COVER = 1.10;

    /** A written option. {@code coveredUnits} of the underlying in the escrow back it as a covered call. */
    public static final class Written {
        final long id;
        final String account;
        final OptionDesk.Series series;
        final int contracts;
        final long premiumCents;
        final Map<String, Integer> items;
        long cashCents;
        final long coveredUnits;
        long callDay = -1;

        Written(long id, String account, OptionDesk.Series series, int contracts, long premiumCents, Map<String, Integer> items,
                long cashCents, long coveredUnits) {
            this.id = id;
            this.account = account;
            this.series = series;
            this.contracts = contracts;
            this.premiumCents = premiumCents;
            this.items = new LinkedHashMap<>(items);
            this.cashCents = cashCents;
            this.coveredUnits = coveredUnits;
        }

        public long id() { return id; }
        public String account() { return account; }
        public OptionDesk.Series series() { return series; }
        public int contracts() { return contracts; }
        public long premiumCents() { return premiumCents; }
        public Map<String, Integer> items() { return Map.copyOf(items); }
        public long cashCents() { return cashCents; }
        public long coveredUnits() { return coveredUnits; }
        public boolean underCall() { return callDay >= 0; }

        /** Share of the contracts backed by the goods themselves. */
        public double coveredShare() {
            if (coveredUnits <= 0) return 0;
            return Math.min(1, coveredUnits / (double) (contracts * (long) OptionDesk.contractSize(series.underlying())));
        }
    }

    /** What writing would pay and need, before it's done. */
    public record Terms(long premiumCents, double quality, double coveredShare, long collateralCents, long requiredCents,
                        boolean enough) {}

    /** What a written option came to: at expiry, or bought back after an unmet call. */
    public record Settled(long id, String account, OptionDesk.Series series, int contracts, long premiumCents, long paidOutCents,
                          long calledAwayUnits, long strikeReceivedCents, boolean worthless, boolean boughtBack) {}

    /** Escrow coming back to a writer: goods and cash, collected at the desk. */
    public record Return(Map<String, Integer> items, long cashCents) {}

    private final Map<Long, Written> open = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> returnItems = new LinkedHashMap<>();
    private final Map<String, Long> returnCash = new LinkedHashMap<>();
    private long nextId = 1;

    // ------------------------------------------------------------------ terms

    static String item(OptionDesk.Series s) {
        return ClearingHouse.product(s.underlying()).item();
    }

    /** Loss per contract if the underlying moves {@code move} against the writer from forward {@code f}. */
    static double stressLoss(OptionDesk.Series s, double f, double move) {
        return s.call() ? Math.max(0, f * (1 + move) - s.strikeCents()) : Math.max(0, s.strikeCents() - f * (1 - move));
    }

    public static Terms terms(OptionDesk desk, Dealer dealer, OptionDesk.Series s, int contracts, Map<String, Integer> items,
                              long cashCents, double day) {
        if (!OptionDesk.isGood(s.underlying())) throw new RejectedException("Options on shares can't be written here");
        long needUnits = contracts * (long) OptionDesk.contractSize(s.underlying());
        String good = item(s);
        long covered = s.call() ? Math.min(items.getOrDefault(good, 0), needUnits) : 0;
        Map<String, Integer> rest = new LinkedHashMap<>(items);
        if (covered > 0) rest.merge(good, (int) -covered, Integer::sum);
        rest.values().removeIf(n -> n <= 0);
        CollateralValuer.Valuation v = CollateralValuer.value(rest, cashCents, dealer, day);
        double phi = needUnits == 0 ? 0 : covered / (double) needUnits;
        double q = phi + (1 - phi) * (v.marketCents() == 0 ? 0 : v.quality());
        double fair = desk.fair(s, day);
        long premium = Money.roundDownToDime(fair * (1 - OptionDesk.HALF_SPREAD) * (1 - PREMIUM_CUT * (1 - q)) * contracts);
        double f = desk.market().forwardCents(s.underlying(), s.expiry(), day);
        long required = Math.round(OPEN_COVER * (1 - phi) * contracts * stressLoss(s, f, STRESS));
        return new Terms(premium, q, phi, v.valueCents(), required, v.valueCents() >= required);
    }

    /** Writes an option: the collateral goes into escrow; returns the contract (the caller pays the premium). */
    public Written write(String account, OptionDesk desk, Dealer dealer, OptionDesk.Series s, int contracts, Map<String, Integer> items,
                         long cashCents, double day) {
        if (contracts <= 0) throw new RejectedException("Choose how many");
        if (s.expiry() <= Math.floor(day)) throw new RejectedException("That series has expired");
        Terms t = terms(desk, dealer, s, contracts, items, cashCents, day);
        if (!t.enough()) throw new RejectedException("Not enough collateral: it must cover " + Money.format(t.requiredCents()));
        if (t.premiumCents() <= 0) throw new RejectedException("That option is worth nothing to write");
        long covered = Math.round(t.coveredShare() * contracts * OptionDesk.contractSize(s.underlying()));
        Written w = new Written(nextId++, account, s, contracts, t.premiumCents(), items, cashCents, covered);
        open.put(w.id, w);
        return w;
    }

    public List<Written> open(String account) {
        return open.values().stream().filter(w -> w.account.equals(account)).toList();
    }

    public List<Written> all() {
        return List.copyOf(open.values());
    }

    public Optional<Written> get(long id) {
        return Optional.ofNullable(open.get(id));
    }

    /** Collateral value now of a written option's escrow, not counting the goods that cover it. */
    public static long collateralCents(Written w, Dealer dealer, double day) {
        Map<String, Integer> rest = new LinkedHashMap<>(w.items);
        if (w.coveredUnits > 0) rest.merge(item(w.series), (int) -w.coveredUnits, Integer::sum);
        rest.values().removeIf(n -> n <= 0);
        return CollateralValuer.value(rest, w.cashCents, dealer, day).valueCents();
    }

    /** What the uncovered contracts would cost after a {@link #STRESS} move from today's forward. */
    public static long exposureCents(Written w, OptionDesk desk, double day) {
        double f = desk.market().forwardCents(w.series.underlying(), w.series.expiry(), day);
        return Math.round((1 - w.coveredShare()) * w.contracts * stressLoss(w.series, f, STRESS));
    }

    /** Adds bills to a written option's escrow; clears a margin call if it's covered again. */
    public boolean addCash(long id, long cents, OptionDesk desk, Dealer dealer, double day) {
        Written w = open.get(id);
        if (w == null || cents <= 0) return false;
        w.cashCents += cents;
        if (w.underCall() && collateralCents(w, dealer, day) >= DAWN_COVER * exposureCents(w, desk, day)) w.callDay = -1;
        return true;
    }

    // ------------------------------------------------------------------ dawn

    /** A dawn: expired options settle; the rest are checked for cover (a call, then a buy-back at the next dawn). */
    public List<Settled> dawn(long day, OptionDesk desk, Dealer dealer, List<Written> called) {
        List<Settled> out = new ArrayList<>();
        for (Written w : new ArrayList<>(open.values())) {
            var settle = desk.settlement(w.series.underlying(), w.series.expiry());
            if (w.series.expiry() <= day && settle.isPresent()) {
                out.add(settle(w, settle.getAsLong(), dealer, day));
                continue;
            }
            if (w.series.expiry() <= day) continue; // waiting for the desk's settlement price
            boolean short_ = collateralCents(w, dealer, day) < DAWN_COVER * exposureCents(w, desk, day);
            if (!short_) {
                w.callDay = -1;
            } else if (w.callDay < 0) {
                w.callDay = day;
                called.add(w);
            } else if (w.callDay < day) {
                long cost = Math.round(desk.fair(w.series, day) * (1 + OptionDesk.HALF_SPREAD) * w.contracts);
                long paid = pay(w, cost, dealer, day);
                open.remove(w.id);
                release(w);
                out.add(new Settled(w.id, w.account, w.series, w.contracts, w.premiumCents, paid, 0, 0, false, true));
            }
        }
        return out;
    }

    private Settled settle(Written w, long settleCents, Dealer dealer, long day) {
        open.remove(w.id);
        long intrinsic = w.series.intrinsic(settleCents);
        long calledAway = 0, strikeReceived = 0;
        double phi = w.coveredShare();
        if (intrinsic > 0 && w.coveredUnits > 0) {
            // Called away: the covering goods go to the desk (into the Dealer's stock) for the strike.
            calledAway = w.coveredUnits;
            String good = item(w.series);
            w.items.merge(good, (int) -calledAway, Integer::sum);
            if (w.items.get(good) <= 0) w.items.remove(good);
            dealer.absorb(good, calledAway, day);
            strikeReceived = Math.round(w.series.strikeCents() * calledAway / (double) OptionDesk.contractSize(w.series.underlying()));
            w.cashCents += strikeReceived;
        }
        long owed = Math.round(intrinsic * w.contracts * (1 - phi));
        long paid = pay(w, owed, dealer, day);
        release(w);
        return new Settled(w.id, w.account, w.series, w.contracts, w.premiumCents, paid, calledAway, strikeReceived, intrinsic == 0, false);
    }

    /** Pays {@code cents} out of the escrow: cash first, then goods sold to the Dealer. Returns what was paid. */
    private static long pay(Written w, long cents, Dealer dealer, double day) {
        long fromCash = Math.min(cents, w.cashCents);
        w.cashCents -= fromCash;
        long left = cents - fromCash;
        for (String item : new ArrayList<>(w.items.keySet())) {
            if (left <= 0) break;
            int n = w.items.get(item);
            long proceeds;
            try {
                proceeds = dealer.sell(item, n, day, false).cents();
            } catch (RejectedException collapsed) {
                proceeds = 0;
            }
            w.items.remove(item);
            long used = Math.min(left, proceeds);
            left -= used;
            w.cashCents += proceeds - used; // the surplus from the sale goes back to the writer
        }
        return cents - left;
    }

    private void release(Written w) {
        Map<String, Integer> items = returnItems.computeIfAbsent(w.account, k -> new LinkedHashMap<>());
        w.items.forEach((k, v) -> items.merge(k, v, Integer::sum));
        if (items.isEmpty()) returnItems.remove(w.account);
        if (w.cashCents > 0) returnCash.merge(w.account, w.cashCents, Long::sum);
    }

    /** Escrow waiting for {@code account} to collect (empty if none). */
    public Return waiting(String account) {
        return new Return(Map.copyOf(returnItems.getOrDefault(account, Map.of())), returnCash.getOrDefault(account, 0L));
    }

    /** Hands over (and clears) what's waiting for {@code account}. */
    public Return collect(String account) {
        Return r = waiting(account);
        returnItems.remove(account);
        returnCash.remove(account);
        return r;
    }

    // ------------------------------------------------------------------ save format

    public void write(Writer out) throws IOException {
        out.write(HEADER + "\n");
        out.write("next\t" + nextId + "\n");
        for (Written w : open.values()) {
            out.write(String.join("\t", "w", Long.toString(w.id), w.account, w.series.key(), Integer.toString(w.contracts),
                    Long.toString(w.premiumCents), Long.toString(w.cashCents), Long.toString(w.coveredUnits), Long.toString(w.callDay),
                    items(w.items)) + "\n");
        }
        for (String a : returnItems.keySet()) out.write("ri\t" + a + "\t" + items(returnItems.get(a)) + "\n");
        for (Map.Entry<String, Long> e : returnCash.entrySet()) out.write("rc\t" + e.getKey() + "\t" + e.getValue() + "\n");
        out.flush();
    }

    private static String items(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ",").append(k).append('=').append(v));
        return sb.isEmpty() ? "-" : sb.toString();
    }

    private static Map<String, Integer> parseItems(String s) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (s.equals("-")) return m;
        for (String part : s.split(",")) {
            int eq = part.lastIndexOf('=');
            m.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1)));
        }
        return m;
    }

    public static WrittenBook read(Reader r) throws IOException {
        WrittenBook b = new WrittenBook();
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            switch (c[0]) {
                case "next" -> b.nextId = Long.parseLong(c[1]);
                case "w" -> {
                    Written w = new Written(Long.parseLong(c[1]), c[2], OptionDesk.Series.parse(c[3]), Integer.parseInt(c[4]),
                            Long.parseLong(c[5]), parseItems(c[9]), Long.parseLong(c[6]), Long.parseLong(c[7]));
                    w.callDay = Long.parseLong(c[8]);
                    b.open.put(w.id, w);
                    b.nextId = Math.max(b.nextId, w.id + 1);
                }
                case "ri" -> b.returnItems.put(c[1], parseItems(c[2]));
                case "rc" -> b.returnCash.put(c[1], Long.parseLong(c[2]));
                default -> { }
            }
        }
        return b;
    }
}
