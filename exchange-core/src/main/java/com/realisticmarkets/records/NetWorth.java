package com.realisticmarkets.records;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * A balance sheet: every holding the Records Terminal can see (vault balances, and bills, papers and goods in linked
 * blocks), each at its mark, minus debts. The mod layer finds the holdings and their marks; this only does the sums.
 */
public final class NetWorth {

    public enum Kind {
        CASH("Cash", false),
        VAULT("Vault", false),
        CDS("CDs", false),
        SHARES("Shares", false),
        BONDS("Bonds", false),
        GOODS("Goods", false),
        DEBTS("Debts", true);

        private final String label;
        private final boolean debt;

        Kind(String label, boolean debt) {
            this.label = label;
            this.debt = debt;
        }

        public String label() { return label; }
        public boolean debt() { return debt; }
    }

    /**
     * One holding: {@code quantity} of {@code label} at {@code location}, each marked at {@code markCents}. {@code
     * costCents} is what the whole quantity cost, or -1 if unknown. A debt is quantity 1 marked at what is owed.
     */
    public record Line(Kind kind, String label, String location, long quantity, long markCents, long costCents) {
        public Line {
            if (quantity < 0 || markCents < 0) throw new IllegalArgumentException("negative holding: " + label);
        }

        public static Line cash(String location, long cents) {
            return new Line(Kind.CASH, "Cash", location, 1, cents, cents);
        }

        public static Line debt(String label, String location, long cents) {
            return new Line(Kind.DEBTS, label, location, 1, cents, -1);
        }

        /** Market value in cents (positive, also for debts). */
        public long value() {
            return Math.multiplyExact(quantity, markCents);
        }

        /** Value minus cost, when the cost is known (never for debts). */
        public OptionalLong profit() {
            return costCents < 0 || kind.debt() ? OptionalLong.empty() : OptionalLong.of(value() - costCents);
        }
    }

    private final List<Line> lines = new ArrayList<>();

    public NetWorth add(Line line) {
        if (line.quantity() > 0 && line.markCents() > 0) lines.add(line);
        return this;
    }

    public List<Line> lines() {
        return Collections.unmodifiableList(lines);
    }

    public long assets() {
        return lines.stream().filter(l -> !l.kind().debt()).mapToLong(Line::value).sum();
    }

    public long debts() {
        return lines.stream().filter(l -> l.kind().debt()).mapToLong(Line::value).sum();
    }

    public long total() {
        return assets() - debts();
    }

    /** Value by kind (debts positive); every kind is present. */
    public Map<Kind, Long> byKind() {
        Map<Kind, Long> out = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) out.put(k, 0L);
        for (Line l : lines) out.merge(l.kind(), l.value(), Long::sum);
        return out;
    }

    /** Unrealized profit summed over the holdings whose cost is known. */
    public long knownProfit() {
        return lines.stream().map(Line::profit).filter(OptionalLong::isPresent).mapToLong(OptionalLong::getAsLong).sum();
    }
}
