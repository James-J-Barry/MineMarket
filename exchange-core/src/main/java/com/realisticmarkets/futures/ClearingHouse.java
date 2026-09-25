package com.realisticmarkets.futures;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.exchange.RejectedException;
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
 * The Clearing House: cash-settled futures on six goods in standard lots, two quarterly expiries listed at a time.
 * A future's price is the fair value expected at expiry from what's known at dawn (news priced in at once), plus inflation. The house quotes {@link #HALF_SPREAD}
 * either side, plus {@link #SKEW_PER_LOT} for every lot the account has already traded in that contract today (it
 * reacts to volume), and caps positions at {@link #POSITION_LIMIT} lots.
 *
 * <p>Each account has a margin account in cash. Opening needs {@link #INITIAL_MARGIN} of the positions' value; every
 * dawn the house <b>marks to market</b>: each position's gain or loss since the last mark moves into or out of the cash.
 * Equity under {@link #MAINTENANCE_MARGIN} brings a margin call; if equity isn't back to the initial margin by the next
 * dawn, every position is closed at that dawn's mark. At expiry positions settle at the Dealer's fair value. Cash can
 * go negative (a debt to the house): then nothing new can be opened until it's paid.
 */
public final class ClearingHouse {
    public static final String HEADER = "# Realistic Markets clearing house v1";
    public static final double HALF_SPREAD = 0.0025, SKEW_PER_LOT = 0.001;
    public static final double INITIAL_MARGIN = 0.10, MAINTENANCE_MARGIN = 0.075;
    public static final int POSITION_LIMIT = 20, EXPIRY_DAYS = 7, LISTED = 2;

    /** A future: {@code lot} items of {@code item}, known by a short code. */
    public record Product(String code, String item, int lot, String name) {}

    public static final List<Product> PRODUCTS = List.of(
            new Product("WHT", "minecraft:wheat", 256, "Wheat"),
            new Product("LOG", "minecraft:oak_log", 256, "Oak Log"),
            new Product("IRN", "minecraft:iron_ingot", 64, "Iron Ingot"),
            new Product("GLD", "minecraft:gold_ingot", 64, "Gold Ingot"),
            new Product("RED", "minecraft:redstone", 256, "Redstone"),
            new Product("DIA", "minecraft:diamond", 16, "Diamond"));

    public static Product product(String code) {
        for (Product p : PRODUCTS) if (p.code().equals(code)) return p;
        throw new RejectedException("No such future: " + code);
    }

    /** The expiries listed on {@code today}: the next {@link #LISTED} quarter days after today. */
    public static long[] expiries(long today) {
        long first = Math.floorDiv(today, EXPIRY_DAYS) * EXPIRY_DAYS + EXPIRY_DAYS;
        long[] out = new long[LISTED];
        for (int i = 0; i < LISTED; i++) out[i] = first + (long) i * EXPIRY_DAYS;
        return out;
    }

    public static boolean listed(long expiry, long today) {
        for (long e : expiries(today)) if (e == expiry) return true;
        return false;
    }

    public static String key(String code, long expiry) {
        return code + "@" + expiry;
    }

    /** A position: {@code lots} (negative = short), and the price per lot (cents) its gains are counted from. */
    public record Position(String code, long expiry, long lots, double entryCents) {
        public String key() { return ClearingHouse.key(code, expiry); }
    }

    /** One account's margin cash, positions, margin call and today's traded volume per contract. */
    public static final class Account {
        long cashCents;
        final Map<String, Position> positions = new LinkedHashMap<>();
        long callDay = -1;          // dawn a margin call was made, -1 if none
        long volumeDay = Long.MIN_VALUE;
        final Map<String, Long> netToday = new LinkedHashMap<>();

        public long cashCents() { return cashCents; }
        public List<Position> positions() { return List.copyOf(positions.values()); }
        public boolean underCall() { return callDay >= 0; }
        public long callDay() { return callDay; }
    }

    /** What a trade did: its average price per lot and any gain or loss realized on lots it closed. */
    public record Trade(String code, long expiry, long lots, double priceCents, long realizedCents) {}

    /** What a dawn did to one account. */
    public record Dawn(String account, long variationCents, long expiredCents, boolean called, boolean callMet, boolean closedOut,
                       long equityCents, long requiredCents) {}

    private final Dealer dealer;
    private final Map<String, Account> accounts = new LinkedHashMap<>();

    public ClearingHouse(Dealer dealer) {
        this.dealer = dealer;
    }

    public Dealer dealer() { return dealer; }

    public Account account(String id) {
        return accounts.computeIfAbsent(id, k -> new Account());
    }

    public Optional<Account> existing(String id) {
        return Optional.ofNullable(accounts.get(id));
    }

    public List<String> accountIds() {
        return List.copyOf(accounts.keySet());
    }

    // ------------------------------------------------------------------ prices

    /**
     * The futures price per lot (cents) of {@code code} for {@code expiry}, at {@code day}: the fair value the market
     * expects at expiry given all the news out by today's dawn (news is priced in at once, so the Newsstand gives no
     * free ride here), with a little intraday noise. At expiry it is the Dealer's fair value.
     */
    public double price(String code, long expiry, double day) {
        Product p = product(code);
        double unit = dealer.expectedFair(p.item(), day, Math.max(expiry, day)) * Math.exp(dealer.intradayNoise(p.item(), day));
        return unit * p.lot() * 100.0;
    }

    /** Average price per lot (cents) for {@code lots} (negative = sell) by {@code account} now. */
    public double quote(String account, String code, long expiry, long lots, double day) {
        if (lots == 0) return price(code, expiry, day);
        Account a = account(account);
        long net = netToday(a, key(code, expiry), (long) Math.floor(day));
        double f = price(code, expiry, day);
        long n = Math.abs(lots);
        double sign = Math.signum(lots);
        // Lot j of the order trades at F x (1 + sign x half spread + skew x (net + sign x j)).
        double avgSkew = SKEW_PER_LOT * (net + sign * (n - 1) / 2.0);
        return f * (1 + sign * HALF_SPREAD + avgSkew);
    }

    private static long netToday(Account a, String key, long today) {
        if (a.volumeDay != today) return 0;
        return a.netToday.getOrDefault(key, 0L);
    }

    // ------------------------------------------------------------------ margin

    /** Cash plus every position's gain or loss at today's price. */
    public long equity(String account, double day) {
        Account a = account(account);
        double eq = a.cashCents;
        for (Position p : a.positions.values()) eq += (price(p.code(), p.expiry(), day) - p.entryCents()) * p.lots();
        return Math.round(eq);
    }

    /** Margin required for the account's positions at today's prices: initial ({@code initial}) or maintenance. */
    public long required(String account, double day, boolean initial) {
        Account a = account(account);
        double sum = 0;
        for (Position p : a.positions.values()) sum += Math.abs(p.lots()) * price(p.code(), p.expiry(), day);
        return Math.round(sum * (initial ? INITIAL_MARGIN : MAINTENANCE_MARGIN));
    }

    public void deposit(String account, long cents) {
        if (cents <= 0) return;
        account(account).cashCents += cents;
    }

    /** What can be withdrawn: cash not needed as initial margin (never more than the cash itself). */
    public long free(String account, double day) {
        Account a = account(account);
        return Math.max(0, Math.min(a.cashCents, equity(account, day) - required(account, day, true)));
    }

    public void withdraw(String account, long cents, double day) {
        if (cents <= 0) return;
        if (cents > free(account, day)) throw new RejectedException("Only " + free(account, day) + " cents are free to withdraw");
        account(account).cashCents -= cents;
    }

    // ------------------------------------------------------------------ trading

    /**
     * Buys ({@code lots} > 0) or sells ({@code lots} < 0) futures. Lots that close part of a position realize their gain
     * or loss into the margin cash; new exposure needs the initial margin after the trade, no debt, and stays within the
     * position limit.
     */
    public Trade trade(String account, String code, long expiry, long lots, double day) {
        if (lots == 0) throw new RejectedException("Choose how many lots");
        long today = (long) Math.floor(day);
        if (!listed(expiry, today)) throw new RejectedException("That expiry isn't listed");
        Account a = account(account);
        String k = key(code, expiry);
        Position old = a.positions.get(k);
        long before = old == null ? 0 : old.lots();
        long after = before + lots;
        boolean adds = Math.abs(after) > Math.abs(before);
        if (adds && Math.abs(after) > POSITION_LIMIT) throw new RejectedException("Position limit: " + POSITION_LIMIT + " lots a contract");
        if (adds && a.cashCents < 0) throw new RejectedException("Pay your debt to the Clearing House first");
        double price = quote(account, code, expiry, lots, day);

        long realized = 0;
        Position next;
        if (before == 0 || Long.signum(before) == Long.signum(lots)) {
            double entry = before == 0 ? price : (old.entryCents() * before + price * lots) / after;
            next = new Position(code, expiry, after, entry);
        } else {
            long closing = Math.min(Math.abs(lots), Math.abs(before));
            realized = Math.round((price - old.entryCents()) * closing * Long.signum(before));
            next = after == 0 ? null : new Position(code, expiry, after,
                    Long.signum(after) == Long.signum(before) ? old.entryCents() : price);
        }
        // Check the margin with the trade applied; undo if short.
        Map<String, Position> saved = new LinkedHashMap<>(a.positions);
        long savedCash = a.cashCents;
        a.cashCents += realized;
        if (next == null) a.positions.remove(k);
        else a.positions.put(k, next);
        if (adds && equity(account, day) < required(account, day, true)) {
            a.positions.clear();
            a.positions.putAll(saved);
            a.cashCents = savedCash;
            throw new RejectedException("Not enough margin: deposit more");
        }
        if (a.volumeDay != today) {
            a.volumeDay = today;
            a.netToday.clear();
        }
        a.netToday.merge(k, lots, Long::sum);
        if (a.underCall() && equity(account, day) >= required(account, day, true)) a.callDay = -1;
        return new Trade(code, expiry, lots, price, realized);
    }

    /** True if a deposit (or trade) just brought an account under a margin call back to its initial margin. */
    public boolean callMet(String account, double day) {
        Account a = account(account);
        if (!a.underCall()) return false;
        if (equity(account, day) >= required(account, day, true)) {
            a.callDay = -1;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ dawn

    /**
     * The dawn of {@code day}: contracts expiring today settle at the Dealer's fair value; the rest are marked to
     * today's price (their gain or loss since the last mark moves into the cash); a margin call made at an earlier
     * dawn and still not met closes every position; equity under maintenance makes a new call.
     */
    public List<Dawn> dawn(long day) {
        List<Dawn> out = new ArrayList<>();
        for (Map.Entry<String, Account> e : accounts.entrySet()) {
            Account a = e.getValue();
            if (a.positions.isEmpty() && !a.underCall()) continue;
            long variation = 0, expired = 0;
            for (Position p : new ArrayList<>(a.positions.values())) {
                double settle = price(p.code(), p.expiry(), day);
                long pnl = Math.round((settle - p.entryCents()) * p.lots());
                a.cashCents += pnl;
                if (p.expiry() <= day) {
                    expired += pnl;
                    a.positions.remove(p.key());
                } else {
                    variation += pnl;
                    a.positions.put(p.key(), new Position(p.code(), p.expiry(), p.lots(), settle));
                }
            }
            long equity = equity(e.getKey(), day), initial = required(e.getKey(), day, true);
            boolean called = false, met = false, closed = false;
            if (a.underCall() && a.callDay < day) {
                if (equity >= initial) {
                    met = true;
                } else {
                    closed = !a.positions.isEmpty();
                    a.positions.clear(); // already marked at today's price: closing costs nothing more
                }
                a.callDay = -1;
            }
            if (!a.underCall() && !a.positions.isEmpty() && equity < required(e.getKey(), day, false)) {
                a.callDay = day;
                called = true;
            }
            out.add(new Dawn(e.getKey(), variation, expired, called, met, closed, equity, required(e.getKey(), day, true)));
        }
        return out;
    }

    // ------------------------------------------------------------------ save format

    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        for (Map.Entry<String, Account> e : accounts.entrySet()) {
            Account a = e.getValue();
            w.write("acct\t" + e.getKey() + "\t" + a.cashCents + "\t" + a.callDay + "\n");
            for (Position p : a.positions.values()) {
                w.write("pos\t" + e.getKey() + "\t" + p.code() + "\t" + p.expiry() + "\t" + p.lots() + "\t" + p.entryCents() + "\n");
            }
        }
        w.flush();
    }

    public static ClearingHouse read(Reader r, Dealer dealer) throws IOException {
        ClearingHouse h = new ClearingHouse(dealer);
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            switch (c[0]) {
                case "acct" -> {
                    Account a = h.account(c[1]);
                    a.cashCents = Long.parseLong(c[2]);
                    a.callDay = Long.parseLong(c[3]);
                }
                case "pos" -> {
                    Position p = new Position(c[2], Long.parseLong(c[3]), Long.parseLong(c[4]), Double.parseDouble(c[5]));
                    h.account(c[1]).positions.put(p.key(), p);
                }
                default -> { }
            }
        }
        return h;
    }
}
