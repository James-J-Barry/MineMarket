package com.realisticmarkets.exchange;

/** Thrown when an order or ledger operation is refused (insufficient funds, unknown order, ...). */
public class RejectedException extends RuntimeException {
    public RejectedException(String message) {
        super(message);
    }
}
