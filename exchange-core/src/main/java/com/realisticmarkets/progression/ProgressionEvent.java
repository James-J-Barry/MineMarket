package com.realisticmarkets.progression;

/** Something a player did that quests react to. Emitted by the mod layer; replayable in tests. */
public sealed interface ProgressionEvent {

    /**
     * A sale to the Dealer. Ratios are bid ÷ fair value for the item: {@code ratioBefore} when the sale started
     * (after any recovery), {@code ratioAfter} once this sale's impact is applied.
     */
    record Sale(String item, String group, int qty, long proceedsCents, double ratioBefore, double ratioAfter, long day)
            implements ProgressionEvent {}

    record Purchase(String item, String group, int qty, long costCents, long day) implements ProgressionEvent {}

    /** Snapshot of the player's cash (bills held) and net worth (cash + goods at Dealer bid). */
    record NetWorth(long cashCents, long netWorthCents, long day) implements ProgressionEvent {}

    record DayRollover(long day) implements ProgressionEvent {}

    record Craft(String result, int times, long day) implements ProgressionEvent {}
}
