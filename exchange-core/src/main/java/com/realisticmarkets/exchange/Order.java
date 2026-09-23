package com.realisticmarkets.exchange;

/**
 * A live order inside the exchange. The id doubles as time priority: lower id = arrived earlier.
 */
public final class Order {
    private final long id;
    private final OrderRequest request;
    private long remaining;

    Order(long id, OrderRequest request) {
        this.id = id;
        this.request = request;
        this.remaining = request.quantity();
    }

    public long id() { return id; }
    public String accountId() { return request.accountId(); }
    public String instrument() { return request.instrument(); }
    public Side side() { return request.side(); }
    public long limitPrice() { return request.limitPrice(); }
    public TimeInForce timeInForce() { return request.timeInForce(); }
    public long originalQuantity() { return request.quantity(); }
    public long remaining() { return remaining; }

    void fill(long qty) {
        if (qty <= 0 || qty > remaining) throw new IllegalStateException("bad fill " + qty + " on " + this);
        remaining -= qty;
    }

    boolean isDone() { return remaining == 0; }

    @Override
    public String toString() {
        return "Order#" + id + "[" + side() + " " + remaining + "/" + originalQuantity() + " " + instrument()
                + " @" + limitPrice() + " " + timeInForce() + " by " + accountId() + "]";
    }
}
