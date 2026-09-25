package com.realisticmarkets.mod.stocks;

import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.registry.ModItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/**
 * Security papers (Share Certificates, CDs, Loan Notes) and what a set of them is worth at live prices: shares at the
 * Stock Exchange's last trade (fair value before any trade), CDs at what the bank would pay today, bonds at the Bond Desk's
 * price plus coupons waiting on them. A Loan Note is the
 * borrower's record of a debt, not an asset, so it counts but adds nothing.
 */
public final class Securities {
    private Securities() {}

    public static boolean isSecurity(ItemStack s) {
        return !s.isEmpty() && (s.is(ModItems.SHARE_CERTIFICATE) || s.is(ModItems.CERTIFICATE_OF_DEPOSIT) || s.is(ModItems.LOAN_NOTE)
                || s.is(ModItems.BOND) || s.is(ModItems.OPTION_CONTRACT));
    }

    /** Securities, currency, and the holders of either (Bill Clip, Portfolio Binder): what a Safe Deposit Box takes. */
    public static boolean isPaperOrCash(ItemStack s) {
        return isSecurity(s) || ModItems.denominationOf(s) != null || s.is(ModItems.BILL_CLIP) || s.is(ModItems.PORTFOLIO_BINDER);
    }

    /** One kind of holding: a company's shares, CDs or Loan Notes. {@code cents} is the value (0 when unknown). */
    public record Line(String kind, long quantity, long cents) {}

    public record Valuation(List<Line> lines, long totalCents) {}

    public static final String CDS = "CDs", LOAN_NOTES = "Loan Notes", BONDS = "Bonds", OPTIONS = "Options";

    /** Values {@code stacks} at live prices. {@code stocks} or {@code bank} may be null (then those papers count at 0). */
    public static Valuation value(Iterable<ItemStack> stacks, StockService stocks, BankService bank, long day) {
        Map<String, long[]> byKind = new LinkedHashMap<>();
        for (ItemStack s : stacks) {
            if (s.isEmpty()) continue;
            Optional<ShareCertificates.Paper> share = ShareCertificates.read(s);
            if (share.isPresent()) {
                String t = share.get().ticker();
                long shares = (long) share.get().denomination() * s.getCount();
                long price = stocks == null ? 0 : stocks.market().exchange().lastPrice(t).orElse(stocks.fairCents(t));
                add(byKind, t, shares, shares * price);
            } else if (s.is(ModItems.CERTIFICATE_OF_DEPOSIT)) {
                long v = bank == null ? 0 : Math.max(0, bank.cdValue(s, day));
                add(byKind, CDS, s.getCount(), v * s.getCount());
            } else if (s.is(ModItems.BOND)) {
                var bonds = com.realisticmarkets.mod.bonds.BondService.getOrNull();
                var paper = com.realisticmarkets.mod.bonds.BondPapers.read(s);
                long v = bonds == null || paper.isEmpty() ? 0 : bonds.desk().bid(paper.get().bond(), day)
                        + bonds.desk().couponsOwed(paper.get().bond(), paper.get().paidThrough(), day);
                add(byKind, BONDS, s.getCount(), v * s.getCount());
            } else if (s.is(ModItems.OPTION_CONTRACT)) {
                var options = com.realisticmarkets.mod.options.OptionsService.getOrNull();
                var series = com.realisticmarkets.mod.options.OptionPapers.read(s);
                long v = 0;
                if (options != null && series.isPresent()) {
                    var settle = options.desk().settlement(series.get().underlying(), series.get().expiry());
                    v = settle.isPresent() ? series.get().intrinsic(settle.getAsLong())
                            : series.get().expiry() <= day ? 0 : options.desk().bid("", series.get(), day);
                }
                add(byKind, OPTIONS, s.getCount(), v * s.getCount());
            } else if (s.is(ModItems.LOAN_NOTE)) {
                add(byKind, LOAN_NOTES, s.getCount(), 0);
            }
        }
        List<Line> lines = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, long[]> e : byKind.entrySet()) {
            lines.add(new Line(e.getKey(), e.getValue()[0], e.getValue()[1]));
            total += e.getValue()[1];
        }
        return new Valuation(lines, total);
    }

    private static void add(Map<String, long[]> m, String kind, long qty, long cents) {
        long[] a = m.computeIfAbsent(kind, k -> new long[2]);
        a[0] += qty;
        a[1] += cents;
    }
}
