package com.realisticmarkets.custody;

import com.realisticmarkets.bonds.Bond;
import com.realisticmarkets.bonds.BondDesk;
import com.realisticmarkets.equities.Equities;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.options.OptionDesk;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A brokerage's books: securities held as entries instead of papers, per account, plus a cash account. Shares are
 * kept by company, bonds and options by series. Each entry remembers what it has been paid through (a quarter for
 * shares, a coupon for bonds), so income is credited exactly once: at each dawn dividends, coupons, maturities,
 * default recoveries and option settlements go into the cash account and finished entries close.
 */
public final class BookEntries {
    public static final String HEADER = "# Realistic Markets book entries v1";

    public enum Kind { SHARE, BOND, OPTION }

    /** One holding: {@code key} is a ticker, a bond series key or an option series key. */
    public record Entry(Kind kind, String key, long quantity, long paidThrough) {}

    /** What a dawn credited to one account, in cents. */
    public record Income(long dividends, long coupons, long other) {
        public long total() { return dividends + coupons + other; }
    }

    private static final class Account {
        final Map<String, Entry> entries = new LinkedHashMap<>();
        long cashCents;
    }

    private final Map<String, Account> accounts = new LinkedHashMap<>();

    private static String id(Kind k, String key) {
        return k + "|" + key;
    }

    private Account account(String id) {
        return accounts.computeIfAbsent(id, k -> new Account());
    }

    /**
     * Adds {@code quantity} of a security, paid through {@code paidThrough}. Papers must come in paid up to the same
     * point as what's held (the caller presents them first); otherwise income would be paid twice or lost.
     */
    public void deposit(String account, Kind kind, String key, long quantity, long paidThrough) {
        if (quantity <= 0) return;
        Account a = account(account);
        Entry old = a.entries.get(id(kind, key));
        if (old != null && old.paidThrough() != paidThrough) {
            throw new RejectedException("Present those papers first: they're paid through " + paidThrough + ", the books " + old.paidThrough());
        }
        long q = (old == null ? 0 : old.quantity()) + quantity;
        a.entries.put(id(kind, key), new Entry(kind, key, q, paidThrough));
    }

    /** Takes {@code quantity} out (to print as papers); returns the entry taken, with its paid-through. */
    public Entry withdraw(String account, Kind kind, String key, long quantity) {
        Account a = accounts.get(account);
        Entry e = a == null ? null : a.entries.get(id(kind, key));
        if (e == null || quantity <= 0 || e.quantity() < quantity) throw new RejectedException("Not that many in book entry");
        if (e.quantity() == quantity) a.entries.remove(id(kind, key));
        else a.entries.put(id(kind, key), new Entry(kind, key, e.quantity() - quantity, e.paidThrough()));
        return new Entry(kind, key, quantity, e.paidThrough());
    }

    public List<Entry> entries(String account) {
        Account a = accounts.get(account);
        return a == null ? List.of() : List.copyOf(a.entries.values());
    }

    public long quantity(String account, Kind kind, String key) {
        Account a = accounts.get(account);
        Entry e = a == null ? null : a.entries.get(id(kind, key));
        return e == null ? 0 : e.quantity();
    }

    public long cash(String account) {
        Account a = accounts.get(account);
        return a == null ? 0 : a.cashCents;
    }

    /** Takes up to {@code cents} of cash out; returns what was taken. */
    public long withdrawCash(String account, long cents) {
        Account a = accounts.get(account);
        if (a == null) return 0;
        long take = Math.max(0, Math.min(cents, a.cashCents));
        a.cashCents -= take;
        return take;
    }

    public boolean has(String account) {
        Account a = accounts.get(account);
        return a != null && (!a.entries.isEmpty() || a.cashCents > 0);
    }

    // ------------------------------------------------------------------ income

    /**
     * Credits every account's income as of {@code day}: dividends declared since each share entry was paid through;
     * bond coupons due, and the face at maturity or the recovery after a default (the entry closes); option
     * settlements (the entry closes). Any of the sources may be null (then those entries wait).
     */
    public Map<String, Income> credit(double day, Equities equities, BondDesk bonds, OptionDesk options) {
        Map<String, Income> out = new LinkedHashMap<>();
        for (Map.Entry<String, Account> acct : accounts.entrySet()) {
            Account a = acct.getValue();
            long div = 0, cpn = 0, other = 0;
            for (Entry e : new ArrayList<>(a.entries.values())) {
                switch (e.kind()) {
                    case SHARE -> {
                        if (equities == null) continue;
                        long latest = equities.lastReportedQuarter();
                        if (latest <= e.paidThrough()) continue;
                        div += e.quantity() * equities.dividendsSince(e.key(), e.paidThrough());
                        a.entries.put(id(e.kind(), e.key()), new Entry(e.kind(), e.key(), e.quantity(), latest));
                    }
                    case BOND -> {
                        if (bonds == null) continue;
                        Bond b = bond(e.key());
                        cpn += e.quantity() * bonds.couponsOwed(b, (int) e.paidThrough(), day);
                        long red = bonds.redemption(b, day);
                        if (red > 0) {
                            other += e.quantity() * red;
                            a.entries.remove(id(e.kind(), e.key()));
                        } else {
                            int due = b.couponsDueBy(day);
                            if (due > e.paidThrough()) a.entries.put(id(e.kind(), e.key()), new Entry(e.kind(), e.key(), e.quantity(), due));
                        }
                    }
                    case OPTION -> {
                        if (options == null) continue;
                        OptionDesk.Series s = OptionDesk.Series.parse(e.key());
                        var settle = options.settlement(s.underlying(), s.expiry());
                        if (settle.isEmpty()) continue;
                        other += e.quantity() * s.intrinsic(settle.getAsLong());
                        a.entries.remove(id(e.kind(), e.key()));
                    }
                }
            }
            long total = div + cpn + other;
            if (total != 0) {
                a.cashCents += total;
                out.put(acct.getKey(), new Income(div, cpn, other));
            }
        }
        return out;
    }

    /** A bond's book-entry key: its terms, the coupon rate exactly (so the bond comes back identical). */
    public static String bondKey(Bond b) {
        return b.issuer() + "|" + b.issueDay() + "|" + b.maturityDay() + "|" + Double.toString(b.couponRate());
    }

    public static Bond bond(String key) {
        String[] p = key.split("\\|");
        return new Bond(p[0], Long.parseLong(p[1]), Long.parseLong(p[2]), Double.parseDouble(p[3]));
    }

    // ------------------------------------------------------------------ save format

    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        for (Map.Entry<String, Account> e : accounts.entrySet()) {
            w.write("cash\t" + e.getKey() + "\t" + e.getValue().cashCents + "\n");
            for (Entry x : e.getValue().entries.values()) {
                w.write("entry\t" + e.getKey() + "\t" + x.kind() + "\t" + x.key() + "\t" + x.quantity() + "\t" + x.paidThrough() + "\n");
            }
        }
        w.flush();
    }

    public static BookEntries read(Reader r) throws IOException {
        BookEntries b = new BookEntries();
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            switch (c[0]) {
                case "cash" -> b.account(c[1]).cashCents = Long.parseLong(c[2]);
                case "entry" -> {
                    Kind k = Kind.valueOf(c[2]);
                    b.account(c[1]).entries.put(id(k, c[3]), new Entry(k, c[3], Long.parseLong(c[4]), Long.parseLong(c[5])));
                }
                default -> { }
            }
        }
        return b;
    }
}
