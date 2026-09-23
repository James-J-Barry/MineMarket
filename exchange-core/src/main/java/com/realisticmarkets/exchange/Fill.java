package com.realisticmarkets.exchange;

public record Fill(
        long auctionSeq,
        String instrument,
        long buyOrderId,
        long sellOrderId,
        String buyer,
        String seller,
        long quantity,
        long price) {
}
