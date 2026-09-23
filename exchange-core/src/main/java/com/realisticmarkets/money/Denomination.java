package com.realisticmarkets.money;

/**
 * Physical currency. All amounts in the mod are integer cents; the smallest physical unit is the
 * dime, so every payout must be a multiple of 10 cents.
 */
public enum Denomination {
    HUNDRED(10_000, "hundred_dollar"),
    TEN(1_000, "ten_dollar"),
    ONE(100, "one_dollar"),
    DIME(10, "dime");

    private final long cents;
    private final String itemName;

    Denomination(long cents, String itemName) {
        this.cents = cents;
        this.itemName = itemName;
    }

    public long cents() {
        return cents;
    }

    /** Item path in the mod namespace, e.g. {@code realisticmarkets:ten_dollar}. */
    public String itemName() {
        return itemName;
    }

    public static Denomination byItemName(String name) {
        for (Denomination d : values()) if (d.itemName.equals(name)) return d;
        return null;
    }
}
