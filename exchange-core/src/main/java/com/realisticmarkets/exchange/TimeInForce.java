package com.realisticmarkets.exchange;

public enum TimeInForce {
    /** Good 'til cancelled: unfilled quantity rests in the book for later auctions. */
    GTC,
    /** Immediate or cancel: participates in the next auction only; any remainder is cancelled. */
    IOC
}
