package com.realisticmarkets.records;

import com.realisticmarkets.progression.ProgressionEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Digital Record Keeping's income ledger: what each account earned, by source and in-game day, from the day it bought
 * the upgrade (nothing earlier is known), plus its net worth as last seen each day for the terminal's 30-day line.
 * Only the last {@link #KEEP_DAYS} days are kept. Trading gains can be negative (a sale below its cost).
 */
public final class Ledger {
    public static final String HEADER = "# Realistic Markets income ledger v1";
    public static final int KEEP_DAYS = 60;
    /** A day with no net worth recorded yet (before the first look). */
    public static final long NO_DATA = Long.MIN_VALUE;

    public enum Source {
        DEALER_SALES("Dealer sales"),
        FLOOR_SALES("Floor sales"),
        INTEREST("Interest"),
        DIVIDENDS("Dividends"),
        COUPONS("Coupons"),
        TRADING_GAINS("Trading gains");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() { return label; }
    }

    private static final class Book {
        final long opened;
        final TreeMap<Long, long[]> income = new TreeMap<>(); // day -> cents by source
        final TreeMap<Long, Long> netWorth = new TreeMap<>(); // day -> cents, last seen that day

        Book(long opened) {
            this.opened = opened;
        }

        void prune(long today) {
            income.headMap(today - KEEP_DAYS, true).clear();
            // Keep one older point so the line can carry it forward.
            Long floor = netWorth.floorKey(today - KEEP_DAYS);
            if (floor != null) netWorth.headMap(floor, false).clear();
        }
    }

    private final Map<String, Book> books = new LinkedHashMap<>();

    /** Starts recording for {@code account} from {@code day} (buying the node). Opening again changes nothing. */
    public void open(String account, long day) {
        books.putIfAbsent(account, new Book(day));
    }

    public boolean isOpen(String account) {
        return books.containsKey(account);
    }

    /** Records {@code cents} earned from {@code source}; ignored before the account opened its ledger. */
    public void record(String account, Source source, double day, long cents) {
        Book b = books.get(account);
        long d = (long) Math.floor(day);
        if (b == null || d < b.opened || cents == 0) return;
        b.income.computeIfAbsent(d, k -> new long[Source.values().length])[source.ordinal()] += cents;
        b.prune(d);
    }

    /**
     * Records whatever income a progression event carries: sales to the Dealer or the Capital, Floor sales, interest
     * (vault interest and a matured CD's gain), dividends, coupons, and gains or losses on shares and bonds sold where
     * their cost is known. Other events carry no income.
     */
    public void record(String account, ProgressionEvent event) {
        switch (event) {
            case ProgressionEvent.Sale s -> record(account, Source.DEALER_SALES, s.day(), s.proceedsCents());
            case ProgressionEvent.Shipment s -> record(account, Source.DEALER_SALES, s.day(), s.payoutCents());
            case ProgressionEvent.ForwardDelivered f -> record(account, Source.DEALER_SALES, f.day(), f.priceCents());
            case ProgressionEvent.ForwardDefaulted f -> record(account, Source.TRADING_GAINS, f.day(), -f.depositCents());
            case ProgressionEvent.FuturesMarked m -> record(account, Source.TRADING_GAINS, m.day(), m.cents());
            case ProgressionEvent.FuturesClosed c -> record(account, Source.TRADING_GAINS, c.day(), c.realizedCents());
            case ProgressionEvent.OptionWrittenSettled w -> {
                record(account, Source.TRADING_GAINS, w.day(), w.premiumCents() - w.paidOutCents());
                record(account, Source.DEALER_SALES, w.day(), w.strikeReceivedCents());
            }
            case ProgressionEvent.OptionClosed o when o.costCents() >= 0 ->
                    record(account, Source.TRADING_GAINS, o.day(), o.proceedsCents() - o.costCents());
            case ProgressionEvent.FloorOrderDone f when !f.buy() -> record(account, Source.FLOOR_SALES, f.day(), f.filledCents());
            case ProgressionEvent.Interest i -> record(account, Source.INTEREST, i.day(), i.creditedCents());
            case ProgressionEvent.CdRedeemed c when c.matured() ->
                    record(account, Source.INTEREST, c.day(), Math.max(0, c.payoutCents() - c.principalCents()));
            case ProgressionEvent.DividendCollected d -> record(account, Source.DIVIDENDS, d.day(), d.cents());
            case ProgressionEvent.CouponCollected c -> record(account, Source.COUPONS, c.day(), c.cents());
            case ProgressionEvent.StockSold s when s.costCents() >= 0 ->
                    record(account, Source.TRADING_GAINS, s.day(), s.proceedsCents() - s.costCents());
            case ProgressionEvent.BondSold b when b.costCents() >= 0 ->
                    record(account, Source.TRADING_GAINS, b.day(), b.proceedsCents() - b.costCents());
            default -> { }
        }
    }

    /** Income from {@code source} over the {@code days} days ending with {@code today} (today included). */
    public long income(String account, Source source, long today, int days) {
        return income(account, today, days).get(source);
    }

    /** Income by source over the {@code days} days ending with {@code today}. Every source is present. */
    public Map<Source, Long> income(String account, long today, int days) {
        Map<Source, Long> out = new EnumMap<>(Source.class);
        for (Source s : Source.values()) out.put(s, 0L);
        Book b = books.get(account);
        if (b == null) return out;
        for (long[] row : b.income.subMap(today - days, false, today, true).values()) {
            for (Source s : Source.values()) out.merge(s, row[s.ordinal()], Long::sum);
        }
        return out;
    }

    public long totalIncome(String account, long today, int days) {
        return income(account, today, days).values().stream().mapToLong(Long::longValue).sum();
    }

    /** Notes the account's net worth as seen at {@code day}; the last note of a day is that day's value. */
    public void noteNetWorth(String account, double day, long cents) {
        Book b = books.get(account);
        long d = (long) Math.floor(day);
        if (b == null || d < b.opened) return;
        b.netWorth.put(d, cents);
        b.prune(d);
    }

    public OptionalLong netWorthOn(String account, long day) {
        Book b = books.get(account);
        Map.Entry<Long, Long> e = b == null ? null : b.netWorth.floorEntry(day);
        return e == null ? OptionalLong.empty() : OptionalLong.of(e.getValue());
    }

    /**
     * Net worth for each of the {@code days} days ending with {@code today}, oldest first. A day with no note carries
     * the last one forward; days before the first note are {@link #NO_DATA}.
     */
    public long[] netWorthLine(String account, long today, int days) {
        long[] out = new long[days];
        for (int i = 0; i < days; i++) {
            OptionalLong v = netWorthOn(account, today - days + 1 + i);
            out[i] = v.isPresent() ? v.getAsLong() : NO_DATA;
        }
        return out;
    }

    // ------------------------------------------------------------------ save format

    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        for (Map.Entry<String, Book> e : books.entrySet()) {
            Book b = e.getValue();
            w.write("open\t" + e.getKey() + "\t" + b.opened + "\n");
            for (Map.Entry<Long, long[]> row : b.income.entrySet()) {
                StringBuilder sb = new StringBuilder("income\t" + e.getKey() + "\t" + row.getKey());
                for (long v : row.getValue()) sb.append('\t').append(v);
                w.write(sb.append('\n').toString());
            }
            for (Map.Entry<Long, Long> nw : b.netWorth.entrySet()) {
                w.write("worth\t" + e.getKey() + "\t" + nw.getKey() + "\t" + nw.getValue() + "\n");
            }
        }
        w.flush();
    }

    public static Ledger read(Reader r) throws IOException {
        Ledger l = new Ledger();
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            switch (c[0]) {
                case "open" -> l.open(c[1], Long.parseLong(c[2]));
                case "income" -> {
                    Book b = l.books.get(c[1]);
                    long[] row = new long[Source.values().length];
                    for (int i = 0; i < row.length && 3 + i < c.length; i++) row[i] = Long.parseLong(c[3 + i]);
                    if (b != null) b.income.put(Long.parseLong(c[2]), row);
                }
                case "worth" -> {
                    Book b = l.books.get(c[1]);
                    if (b != null) b.netWorth.put(Long.parseLong(c[2]), Long.parseLong(c[3]));
                }
                default -> { } // a line from a newer version
            }
        }
        return l;
    }
}
