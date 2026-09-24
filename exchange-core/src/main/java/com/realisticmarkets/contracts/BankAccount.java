package com.realisticmarkets.contracts;

import com.realisticmarkets.exchange.RejectedException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One player's bank account at their Bank Vault. Interest compounds once per full in-game day on the credited
 * balance; each day's exact interest goes into a carry and whole dimes are credited, so rounding never quietly
 * zeroes interest on a small balance. Keeps the last {@link #LOG_SIZE} transactions for the Passbook.
 */
public final class BankAccount {
    public static final String HEADER = "# Realistic Markets bank account v1";
    public static final int LOG_SIZE = 100;

    public enum Kind { DEPOSIT, WITHDRAW, INTEREST, CD_ISSUE, CD_REDEEM, LOAN, LOAN_REPAY, LIQUIDATION }

    /** {@code day} is the in-game day the entry was made; {@code balanceCents} is the balance after it. */
    public record Entry(long day, Kind kind, long amountCents, long balanceCents) {}

    private long balanceCents;
    private double carryCents;
    private long lastDay;
    private long interestTotalCents;
    private String vaultLocation; // where the owner's one Bank Vault stands; null when none is placed
    private int passbooksIssued;   // the first Passbook is free, later ones cost a Ledger Paper
    private final Deque<Entry> log = new ArrayDeque<>();

    /** A new, empty account whose interest starts counting from {@code day}. */
    public BankAccount(long day) {
        this.lastDay = day;
    }

    public long balanceCents() { return balanceCents; }
    public long interestTotalCents() { return interestTotalCents; }
    public long lastDay() { return lastDay; }
    public List<Entry> log() { return List.copyOf(log); }
    public String vaultLocation() { return vaultLocation; }
    public void setVaultLocation(String location) { vaultLocation = location; }
    public int passbooksIssued() { return passbooksIssued; }
    public void passbookIssued() { passbooksIssued++; }

    /** True when nothing is held (the vault block may be broken). */
    public boolean isEmpty() {
        return balanceCents == 0;
    }

    /**
     * Credits interest for every full day from the last update up to {@code day}. Returns the cents credited now
     * (0 if no full day passed). A single INTEREST entry summarizes the credit.
     */
    public long accrueTo(long day, double dailyRate) {
        if (day <= lastDay) return 0;
        long credited = 0;
        for (long d = lastDay; d < day; d++) {
            carryCents += balanceCents * dailyRate;
            long whole = (long) Math.floor(carryCents / 10.0) * 10;
            balanceCents += whole;
            carryCents -= whole;
            credited += whole;
        }
        lastDay = day;
        if (credited > 0) {
            interestTotalCents += credited;
            record(day, Kind.INTEREST, credited);
        }
        return credited;
    }

    public void deposit(long cents, long day, Kind kind) {
        if (cents <= 0) throw new IllegalArgumentException("deposit must be positive");
        balanceCents += cents;
        record(day, kind, cents);
    }

    public void withdraw(long cents, long day, Kind kind) {
        if (cents <= 0) throw new IllegalArgumentException("withdrawal must be positive");
        if (cents > balanceCents) throw new RejectedException("Not enough in the account");
        balanceCents -= cents;
        record(day, kind, cents);
    }

    private void record(long day, Kind kind, long cents) {
        log.addLast(new Entry(day, kind, cents, balanceCents));
        while (log.size() > LOG_SIZE) log.removeFirst();
    }

    // ------------------------------------------------------------------ persistence

    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        w.write("balance\t" + balanceCents + "\n");
        w.write("carry\t" + carryCents + "\n");
        w.write("last_day\t" + lastDay + "\n");
        w.write("interest_total\t" + interestTotalCents + "\n");
        if (vaultLocation != null) w.write("vault\t" + vaultLocation + "\n");
        if (passbooksIssued > 0) w.write("passbooks\t" + passbooksIssued + "\n");
        for (Entry e : log) w.write("log\t" + e.day() + "\t" + e.kind() + "\t" + e.amountCents() + "\t" + e.balanceCents() + "\n");
        w.flush();
    }

    public static BankAccount read(Reader r) throws IOException {
        BankAccount a = new BankAccount(0);
        BufferedReader br = new BufferedReader(r);
        String line;
        int n = 0;
        List<Entry> entries = new ArrayList<>();
        while ((line = br.readLine()) != null) {
            n++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            try {
                switch (c[0]) {
                    case "balance" -> a.balanceCents = Long.parseLong(c[1]);
                    case "carry" -> a.carryCents = Double.parseDouble(c[1]);
                    case "last_day" -> a.lastDay = Long.parseLong(c[1]);
                    case "interest_total" -> a.interestTotalCents = Long.parseLong(c[1]);
                    case "vault" -> a.vaultLocation = c[1];
                    case "passbooks" -> a.passbooksIssued = Integer.parseInt(c[1]);
                    case "log" -> entries.add(new Entry(Long.parseLong(c[1]), Kind.valueOf(c[2]), Long.parseLong(c[3]),
                            Long.parseLong(c[4])));
                    default -> throw new IllegalArgumentException("unknown key " + c[0]);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("bank line " + n + " is malformed: '" + t + "'", e);
            }
        }
        a.log.addAll(entries);
        return a;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BankAccount b && balanceCents == b.balanceCents && carryCents == b.carryCents
                && lastDay == b.lastDay && interestTotalCents == b.interestTotalCents
                && java.util.Objects.equals(vaultLocation, b.vaultLocation) && passbooksIssued == b.passbooksIssued && List.copyOf(log).equals(List.copyOf(b.log));
    }

    @Override
    public int hashCode() {
        return Long.hashCode(balanceCents) * 31 + Long.hashCode(lastDay);
    }
}
