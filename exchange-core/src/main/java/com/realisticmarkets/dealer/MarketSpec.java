package com.realisticmarkets.dealer;

/**
 * One row of the Dealer catalog.
 *
 * <p>A base market has its own fair value and depth. A linked market (e.g. iron_block) has no
 * pricing of its own: it trades as {@code baseUnits} units of {@code baseItem}'s pool, which
 * closes the "sell blocks, buy ingots back cheaper" exploit.
 *
 * <p>{@code spread} overrides {@link DealerParams#spread()} for this pool (null = default). Linked markets
 * always use their base pool's spread. {@code collateralClass} (A-D, null = by group) sets the haircut when the
 * item backs a loan; linked markets use their base pool's class.
 */
public record MarketSpec(
        String itemId,
        double fairValue,
        double depth,
        String group,
        String baseItem,
        int baseUnits,
        Double spread,
        String collateralClass) {

    public MarketSpec {
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("itemId required");
        if (baseItem == null) {
            if (fairValue <= 0) throw new IllegalArgumentException(itemId + ": fair value must be positive");
            if (depth <= 0) throw new IllegalArgumentException(itemId + ": depth must be positive");
            baseUnits = 1;
        } else if (baseUnits <= 0) {
            throw new IllegalArgumentException(itemId + ": base_units must be positive");
        }
        if (group == null || group.isBlank()) group = "misc";
        if (spread != null && (spread < 0 || spread >= 2)) throw new IllegalArgumentException(itemId + ": spread out of range");
        if (collateralClass != null && collateralClass.isBlank()) collateralClass = null;
        if (collateralClass != null && !collateralClass.matches("[ABCD]")) {
            throw new IllegalArgumentException(itemId + ": collateral_class must be A, B, C or D");
        }
    }

    public boolean isLinked() {
        return baseItem != null;
    }

    public static MarketSpec base(String itemId, double fairValue, double depth, String group) {
        return new MarketSpec(itemId, fairValue, depth, group, null, 1, null, null);
    }

    public static MarketSpec base(String itemId, double fairValue, double depth, String group, Double spread) {
        return new MarketSpec(itemId, fairValue, depth, group, null, 1, spread, null);
    }

    public static MarketSpec base(String itemId, double fairValue, double depth, String group, Double spread,
                                  String collateralClass) {
        return new MarketSpec(itemId, fairValue, depth, group, null, 1, spread, collateralClass);
    }

    public static MarketSpec linked(String itemId, String baseItem, int baseUnits, String group) {
        return new MarketSpec(itemId, 0, 0, group, baseItem, baseUnits, null, null);
    }
}
