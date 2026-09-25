package com.realisticmarkets.mod.bonds;

import com.realisticmarkets.bonds.Bond;
import com.realisticmarkets.bonds.BondDesk;
import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.item.PortfolioBinderItem;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.stocks.StockService;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.progression.ProgressionEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The Bond Desk in the world: issues new Treasury and company bonds at par, buys old ones back at today's price, and
 * pays coupons, face at maturity and recoveries after defaults when papers are presented. Rates come from the bank's
 * central bank, company credit from the Stock Exchange's companies. It keeps, per account and series, what was paid and
 * the central rate at the time (for Rate Watcher and, later, the Records Terminal), in {@code bond_book.txt}.
 */
public final class BondService {
    private static BondService instance;

    private final BondDesk desk;
    private final Path file; // null in tests
    private final Map<String, double[]> book = new LinkedHashMap<>(); // "account|series" -> {bonds, cents, rate at purchase}

    private BondService(BondDesk desk, Path file) {
        this.desk = desk;
        this.file = file;
    }

    public static void start(MinecraftServer server) {
        BondDesk desk = new BondDesk(BankService.get().centralBank(), StockService.get().equities());
        BondService svc = new BondService(desk, server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID).resolve("bond_book.txt"));
        svc.load();
        instance = svc;
    }

    public static void stop() {
        if (instance != null) instance.save();
        instance = null;
    }

    public static BondService get() {
        if (instance == null) throw new IllegalStateException("Bond Desk not started (no server running?)");
        return instance;
    }

    /** The running instance, or null (no server, or tests). */
    public static BondService getOrNull() {
        return instance;
    }

    public static BondService forTest(BankService bank, StockService stocks) {
        return new BondService(new BondDesk(bank.centralBank(), stocks == null ? null : stocks.equities()), null);
    }

    public BondDesk desk() { return desk; }

    public static String account(Player p) {
        return p.getUUID().toString();
    }

    // ------------------------------------------------------------------ buying

    /** Buys {@code count} new bonds of {@code issuer} maturing in {@code quarters}. Returns empty on success, else why not. */
    public Optional<String> buy(Player player, String issuer, int quarters, int count, long day) {
        if (count <= 0) return Optional.of("Choose how many");
        Bond b = desk.issue(issuer, quarters, day);
        long cost = Money.roundUpToDime(desk.issuePrice(b) * count);
        if (Wallet.count(player.getInventory()) < cost) return Optional.of("Not enough cash: " + count + " bonds cost " + Money.format(cost));
        Wallet.pay(player, cost);
        int left = count;
        while (left > 0) {
            int n = Math.min(64, left);
            player.getInventory().placeItemBackInInventory(BondPapers.create(b, 0, n));
            left -= n;
        }
        double[] e = book.computeIfAbsent(account(player) + "|" + b.series(), k -> new double[3]);
        e[0] += count;
        e[1] += cost;
        e[2] = desk.central().rate(day);
        save();
        return Optional.empty();
    }

    // ------------------------------------------------------------------ presenting

    /** Coupons and redemptions the bonds the player carries (loose or in binders) would pay now, in cents. */
    public long waiting(Player player, double day) {
        long sum = 0;
        for (ItemStack s : carried(player.getInventory())) {
            Optional<BondPapers.Paper> p = BondPapers.read(s);
            if (p.isEmpty()) continue;
            sum += (desk.couponsOwed(p.get().bond(), p.get().paidThrough(), day) + desk.redemption(p.get().bond(), day)) * s.getCount();
        }
        return sum;
    }

    private static List<ItemStack> carried(Inventory inv) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            out.add(s);
            if (s.is(ModItems.PORTFOLIO_BINDER)) out.addAll(PortfolioBinderItem.contents(s));
        }
        return out;
    }

    /**
     * Presents every bond the player carries: pays coupons due, the face of matured bonds and the recovery of
     * defaulted ones (those papers are handed in), marks the rest paid up. Returns cents paid.
     */
    public long present(Player player, ProgressionService prog, long day) {
        Inventory inv = player.getInventory();
        long[] totals = new long[4]; // coupons, redeemed at face (bonds), recovered (bonds), redemption cents
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(ModItems.PORTFOLIO_BINDER)) {
                var inside = PortfolioBinderItem.contents(s);
                for (int j = 0; j < inside.size(); j++) inside.set(j, present(inside.get(j), day, totals));
                PortfolioBinderItem.setContents(s, inside);
            } else {
                inv.setItem(i, present(s, day, totals));
            }
        }
        long paid = totals[0] + totals[3];
        long cash = paid / 10 * 10;
        if (cash > 0) Wallet.give(player, cash);
        if (prog != null) {
            if (totals[0] > 0) prog.emit(player, new ProgressionEvent.CouponCollected(totals[0], day));
            if (totals[1] > 0) prog.emit(player, new ProgressionEvent.BondRedeemed(totals[1], totals[1] * Bond.FACE_CENTS, true, day));
            if (totals[2] > 0) prog.emit(player, new ProgressionEvent.BondRedeemed(totals[2], totals[3] - totals[1] * Bond.FACE_CENTS, false, day));
        }
        return cash;
    }

    private ItemStack present(ItemStack s, long day, long[] totals) {
        Optional<BondPapers.Paper> p = BondPapers.read(s);
        if (p.isEmpty()) return s;
        Bond b = p.get().bond();
        int n = s.getCount();
        totals[0] += desk.couponsOwed(b, p.get().paidThrough(), day) * n;
        long redemption = desk.redemption(b, day);
        if (redemption > 0) {
            totals[3] += redemption * n;
            if (desk.defaulted(b, day)) totals[2] += n;
            else totals[1] += n;
            return ItemStack.EMPTY; // handed in
        }
        int due = b.couponsDueBy(day);
        return due > p.get().paidThrough() ? BondPapers.create(b, due, n) : s;
    }

    // ------------------------------------------------------------------ selling

    /** One series the player holds (loose papers only: the ones they could hand over). */
    public record Holding(Bond bond, long count, long bidCents, boolean defaulted) {}

    public List<Holding> holdings(Player player, double day) {
        Map<String, Holding> by = new LinkedHashMap<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            Optional<BondPapers.Paper> p = BondPapers.read(s);
            if (p.isEmpty()) continue;
            Bond b = p.get().bond();
            Holding h = by.get(b.series());
            long count = (h == null ? 0 : h.count()) + s.getCount();
            by.put(b.series(), new Holding(b, count, desk.bid(b, day), desk.defaulted(b, day)));
        }
        return new ArrayList<>(by.values());
    }

    /** Sells every loose paper of {@code series} to the desk (coupons due are paid first). Returns empty on success. */
    public Optional<String> sell(Player player, String series, ProgressionService prog, long day) {
        present(player, prog, day);
        Inventory inv = player.getInventory();
        long count = 0;
        Bond bond = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            Optional<BondPapers.Paper> p = BondPapers.read(inv.getItem(i));
            if (p.isPresent() && p.get().bond().series().equals(series)) {
                bond = p.get().bond();
                count += inv.getItem(i).getCount();
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (bond == null) return Optional.of("You don't carry that bond (it may have matured or been redeemed)");
        long proceeds = desk.bid(bond, day) * count / 10 * 10;
        if (proceeds > 0) Wallet.give(player, proceeds);
        String key = account(player) + "|" + series;
        double[] e = book.get(key);
        long cost = -1;
        boolean cut = false;
        if (e != null && e[0] >= count) {
            cost = Math.round(e[1] * count / e[0]);
            cut = desk.central().rate(day) < e[2];
            e[0] -= count;
            e[1] -= cost;
            if (e[0] <= 0) book.remove(key);
        } else if (e != null) {
            book.remove(key);
        }
        if (prog != null) prog.emit(player, new ProgressionEvent.BondSold(count, proceeds, cost, cut, day));
        save();
        return Optional.empty();
    }

    /** Average cost (cents) of one bond of {@code series} this account bought at the desk, or -1 if unknown. */
    public double averageCost(String account, String series) {
        double[] e = book.get(account + "|" + series);
        return e == null || e[0] <= 0 ? -1 : e[1] / e[0];
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        if (file == null || Files.notExists(file)) return;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t");
                book.put(c[0], new double[] {Double.parseDouble(c[1]), Double.parseDouble(c[2]), Double.parseDouble(c[3])});
            }
        } catch (IOException | RuntimeException e) {
            RealisticMarkets.LOGGER.error("Could not read the bond book {}", file, e);
        }
    }

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write("# Realistic Markets bond book v1: account|series, bonds, cents paid, central rate at purchase\n");
                for (Map.Entry<String, double[]> e : book.entrySet()) {
                    w.write(e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1] + "\t" + e.getValue()[2] + "\n");
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save the bond book {}", file, e);
        }
    }
}
