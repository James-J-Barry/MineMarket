package com.realisticmarkets.exchange;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cash and positions for one participant. "Available" balances can be spent; "locked" balances are
 * escrowed behind open orders. Every order is fully collateralised at placement, so settlement can
 * never fail.
 */
public final class Account {
    private final String id;
    private long cash;
    private long lockedCash;
    private final Map<String, Long> positions = new TreeMap<>();
    private final Map<String, Long> lockedPositions = new TreeMap<>();

    Account(String id) {
        this.id = id;
    }

    public String id() { return id; }
    public long cash() { return cash; }
    public long lockedCash() { return lockedCash; }
    public long position(String instrument) { return positions.getOrDefault(instrument, 0L); }
    public long lockedPosition(String instrument) { return lockedPositions.getOrDefault(instrument, 0L); }
    public Map<String, Long> positions() { return Collections.unmodifiableMap(positions); }
    public Map<String, Long> lockedPositions() { return Collections.unmodifiableMap(lockedPositions); }

    // ---- package-private mutators: only the Exchange moves money ----

    void creditCash(long amount) {
        cash = Math.addExact(cash, amount);
    }

    void debitCash(long amount) {
        if (amount > cash) throw new RejectedException(id + ": insufficient cash (" + cash + " < " + amount + ")");
        cash -= amount;
    }

    void lockCash(long amount) {
        debitCash(amount);
        lockedCash = Math.addExact(lockedCash, amount);
    }

    void releaseLockedCash(long amount) {
        consumeLockedCash(amount);
        cash = Math.addExact(cash, amount);
    }

    void consumeLockedCash(long amount) {
        if (amount > lockedCash) throw new IllegalStateException(id + ": locked cash underflow");
        lockedCash -= amount;
    }

    void creditPosition(String instrument, long qty) {
        positions.merge(instrument, qty, Math::addExact);
    }

    void debitPosition(String instrument, long qty) {
        long have = position(instrument);
        if (qty > have) throw new RejectedException(id + ": insufficient " + instrument + " (" + have + " < " + qty + ")");
        put(positions, instrument, have - qty);
    }

    void lockPosition(String instrument, long qty) {
        debitPosition(instrument, qty);
        lockedPositions.merge(instrument, qty, Math::addExact);
    }

    void releaseLockedPosition(String instrument, long qty) {
        consumeLockedPosition(instrument, qty);
        creditPosition(instrument, qty);
    }

    void consumeLockedPosition(String instrument, long qty) {
        long locked = lockedPosition(instrument);
        if (qty > locked) throw new IllegalStateException(id + ": locked " + instrument + " underflow");
        put(lockedPositions, instrument, locked - qty);
    }

    private static void put(Map<String, Long> map, String key, long value) {
        if (value == 0) map.remove(key);
        else map.put(key, value);
    }
}
