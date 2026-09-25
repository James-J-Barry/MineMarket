package com.realisticmarkets.contracts;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.MarketSpec;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.money.Money;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Forward contracts with the Dealer: the player agrees today to deliver {@code quantity} items on a delivery day for
 * a fixed price, the Dealer's expected proceeds on that day ({@link Dealer#quoteForward}). Signing takes a deposit of
 * {@link #DEPOSIT_SHARE} of the price, returned on delivery. Delivery is open on the delivery day and the day after;
 * at dawn two days after the delivery day an undelivered forward is cancelled and its deposit forfeit. Contracts are
 * kept by account (the Forward Contract paper is only a statement). No margin and no closing early: that's the
 * difference from futures.
 */
public final class ForwardBook {
    public static final String HEADER = "# Realistic Markets forwards v1";
    public static final int[] TERMS = {3, 7, 14};
    public static final int MIN_QTY = 16, MAX_QTY = 1024;
    public static final double DEPOSIT_SHARE = 0.20;

    /** One forward: {@code priceCents} is paid for the whole quantity on delivery. */
    public record Forward(long id, String account, String item, long quantity, long priceCents, long depositCents,
                          long signedDay, long deliveryDay) {
        /** Deliverable on the delivery day and the next. */
        public boolean dueOn(long day) {
            return day >= deliveryDay && day <= deliveryDay + 1;
        }

        /** The dawn at which it defaults if still open. */
        public long defaultDay() {
            return deliveryDay + 2;
        }

        public double unitCents() {
            return priceCents / (double) quantity;
        }
    }

    private final Map<Long, Forward> open = new LinkedHashMap<>();
    private long nextId = 1;

    /** Checks the terms; returns why not, or empty. */
    public static Optional<String> check(Dealer dealer, String item, long quantity, int termDays) {
        if (!dealer.catalog().trades(item)) return Optional.of("The Dealer doesn't trade that");
        MarketSpec spec = dealer.catalog().spec(item);
        if ("components".equals(spec.group())) return Optional.of("No forwards on components");
        if (quantity < MIN_QTY || quantity > MAX_QTY) return Optional.of("Forwards are for " + MIN_QTY + "-" + MAX_QTY + " items");
        boolean term = false;
        for (int t : TERMS) term |= t == termDays;
        if (!term) return Optional.of("Delivery in 3, 7 or 14 days");
        return Optional.empty();
    }

    /** What the Dealer would agree today for delivery in {@code termDays}, given every open forward on that pool. */
    public Dealer.Quote quote(Dealer dealer, String item, long quantity, int termDays, long day, boolean licensed) {
        return dealer.quoteForward(item, quantity, day, day + termDays, pendingBaseUnits(dealer, item), licensed);
    }

    public static long deposit(long priceCents) {
        return Money.roundUpToDime(priceCents * DEPOSIT_SHARE);
    }

    /** Signs a forward at today's quote (the caller takes the deposit and paper). */
    public Forward sign(String account, Dealer dealer, String item, long quantity, int termDays, long day, boolean licensed) {
        Optional<String> why = check(dealer, item, quantity, termDays);
        if (why.isPresent()) throw new RejectedException(why.get());
        long price = quote(dealer, item, quantity, termDays, day, licensed).cents();
        Forward f = new Forward(nextId++, account, dealer.catalog().spec(item).itemId(), quantity, price, deposit(price), day,
                day + termDays);
        open.put(f.id(), f);
        return f;
    }

    /** Base-pool units promised by open forwards on {@code item}'s pool. */
    public double pendingBaseUnits(Dealer dealer, String item) {
        String pool = dealer.catalog().pool(item).itemId();
        double units = 0;
        for (Forward f : open.values()) {
            if (dealer.catalog().pool(f.item()).itemId().equals(pool)) units += f.quantity() * dealer.catalog().spec(f.item()).baseUnits();
        }
        return units;
    }

    public List<Forward> open(String account) {
        return open.values().stream().filter(f -> f.account().equals(account)).toList();
    }

    public List<Forward> all() {
        return List.copyOf(open.values());
    }

    public Optional<Forward> get(long id) {
        return Optional.ofNullable(open.get(id));
    }

    /**
     * Delivers forward {@code id} on {@code day}: the goods join the Dealer's inventory and the contract closes.
     * Returns the cents to pay the player (price plus deposit). The caller has taken the goods.
     */
    public long deliver(long id, Dealer dealer, double day) {
        Forward f = open.get(id);
        if (f == null) throw new RejectedException("No such forward");
        if (!f.dueOn((long) Math.floor(day))) {
            throw new RejectedException(day < f.deliveryDay() ? "Not due until day " + f.deliveryDay() : "Too late: it has defaulted");
        }
        open.remove(id);
        dealer.absorb(f.item(), f.quantity(), day);
        return f.priceCents() + f.depositCents();
    }

    /** At dawn of {@code day}: cancels forwards still open past their window. Their deposits are forfeit. */
    public List<Forward> dawn(long day) {
        List<Forward> out = new ArrayList<>();
        open.values().removeIf(f -> {
            if (day < f.defaultDay()) return false;
            out.add(f);
            return true;
        });
        return out;
    }

    // ------------------------------------------------------------------ save format

    public void write(Writer w) throws IOException {
        w.write(HEADER + "\n");
        w.write("next\t" + nextId + "\n");
        for (Forward f : open.values()) {
            w.write(String.join("\t", "fwd", Long.toString(f.id()), f.account(), f.item(), Long.toString(f.quantity()),
                    Long.toString(f.priceCents()), Long.toString(f.depositCents()), Long.toString(f.signedDay()),
                    Long.toString(f.deliveryDay())) + "\n");
        }
        w.flush();
    }

    public static ForwardBook read(Reader r) throws IOException {
        ForwardBook b = new ForwardBook();
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            switch (c[0]) {
                case "next" -> b.nextId = Long.parseLong(c[1]);
                case "fwd" -> {
                    Forward f = new Forward(Long.parseLong(c[1]), c[2], c[3], Long.parseLong(c[4]), Long.parseLong(c[5]),
                            Long.parseLong(c[6]), Long.parseLong(c[7]), Long.parseLong(c[8]));
                    b.open.put(f.id(), f);
                    b.nextId = Math.max(b.nextId, f.id() + 1);
                }
                default -> { }
            }
        }
        return b;
    }
}
