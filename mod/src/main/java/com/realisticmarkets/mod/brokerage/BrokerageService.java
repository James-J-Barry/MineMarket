package com.realisticmarkets.mod.brokerage;

import com.realisticmarkets.bonds.Bond;
import com.realisticmarkets.custody.BookEntries;
import com.realisticmarkets.custody.BookEntries.Kind;
import com.realisticmarkets.equities.Certificates;
import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.bonds.BondPapers;
import com.realisticmarkets.mod.bonds.BondService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.item.PortfolioBinderItem;
import com.realisticmarkets.mod.options.OptionPapers;
import com.realisticmarkets.mod.options.OptionsService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import com.realisticmarkets.mod.stocks.StockService;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.options.OptionDesk;
import com.realisticmarkets.progression.ProgressionEvent;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The Brokerage Terminal's books: shares, bonds and options held as book entries instead of papers, and a cash account
 * that dividends, coupons, maturities and option settlements are credited to at dawn. Papers go in (paid up first)
 * and come back out as papers. Saved to {@code brokerage.txt}.
 */
public final class BrokerageService {
    public static final String NODE = "brokerage_terminal";

    private static BrokerageService instance;

    private final BookEntries books;
    private final DealerService dealer;
    private final StockService stocks;   // any of these may be null in tests
    private final BondService bonds;
    private final OptionsService options;
    private final Path file;
    private long lastDawn = Long.MIN_VALUE;
    private int ticks;

    private BrokerageService(BookEntries books, DealerService dealer, StockService stocks, BondService bonds, OptionsService options,
                             Path file) {
        this.books = books;
        this.dealer = dealer;
        this.stocks = stocks;
        this.bonds = bonds;
        this.options = options;
        this.file = file;
    }

    public static void start(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID).resolve("brokerage.txt");
        BookEntries books = new BookEntries();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                books = BookEntries.read(r);
            } catch (IOException | RuntimeException e) {
                RealisticMarkets.LOGGER.error("Could not read the brokerage books {}", file, e);
            }
        }
        instance = new BrokerageService(books, DealerService.get(), StockService.get(), BondService.get(), OptionsService.get(), file);
    }

    public static void stop() {
        if (instance != null) instance.save();
        instance = null;
    }

    public static BrokerageService get() {
        if (instance == null) throw new IllegalStateException("Brokerage not started (no server running?)");
        return instance;
    }

    public static BrokerageService getOrNull() {
        return instance;
    }

    public static BrokerageService forTest(DealerService dealer, StockService stocks, BondService bonds, OptionsService options) {
        return new BrokerageService(new BookEntries(), dealer, stocks, bonds, options, null);
    }

    public BookEntries books() { return books; }

    public static String account(Player p) {
        return p.getUUID().toString();
    }

    // ------------------------------------------------------------------ income

    /** Credits income on every account's entries as of {@code day} (idempotent: each payment once). */
    public java.util.Map<String, BookEntries.Income> credit(double day) {
        return books.credit(day, stocks == null ? null : stocks.equities(), bonds == null ? null : bonds.desk(),
                options == null ? null : options.desk());
    }

    public void dawn(long day, Function<UUID, Player> online, ProgressionService prog) {
        var income = credit(day);
        for (var e : income.entrySet()) {
            Player p = online.apply(UUID.fromString(e.getKey()));
            if (p == null) continue;
            var i = e.getValue();
            if (p instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("Brokerage: " + Money.format(i.total()) + " credited to your cash account"));
            }
            if (prog != null) prog.emit(p, new ProgressionEvent.BrokerageIncome(i.dividends(), i.coupons(), i.other(), day));
        }
        if (!income.isEmpty()) save();
    }

    public static void tick(MinecraftServer server) {
        if (instance == null || ++instance.ticks % 100 != 0) return;
        long day = (long) Math.floor(instance.dealer.day(server));
        if (day > instance.lastDawn) {
            instance.lastDawn = day;
            ProgressionService prog;
            try {
                prog = ProgressionService.get();
            } catch (IllegalStateException notRunning) {
                prog = null;
            }
            instance.dawn(day, id -> server.getPlayerList().getPlayer(id), prog);
        }
    }

    // ------------------------------------------------------------------ papers in and out

    /**
     * Puts every share certificate, bond and option paper the player carries (loose or in binders) into book entry.
     * Dividends and coupons due on them are paid out in bills first, so the books take them paid up. Returns how many
     * papers went in.
     */
    public long depositAll(Player player, double day, ProgressionService prog) {
        long today = (long) Math.floor(day);
        if (stocks != null) stocks.collectDividends(player, prog, today);
        if (bonds != null) bonds.present(player, prog, today);
        credit(day); // the books' entries paid up to today too
        String a = account(player);
        long papers = 0;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(ModItems.PORTFOLIO_BINDER)) {
                NonNullList<ItemStack> inside = PortfolioBinderItem.contents(s);
                for (int j = 0; j < inside.size(); j++) {
                    if (take(a, inside.get(j))) {
                        papers += inside.get(j).getCount();
                        inside.set(j, ItemStack.EMPTY);
                    }
                }
                PortfolioBinderItem.setContents(s, inside);
            } else if (take(a, s)) {
                papers += s.getCount();
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (papers > 0) {
            save();
            if (prog != null) prog.emit(player, new ProgressionEvent.BookEntryDeposited(papers, today));
        }
        return papers;
    }

    /** Books one stack if it's a security; returns whether it was taken. */
    private boolean take(String account, ItemStack s) {
        if (s.isEmpty()) return false;
        var share = ShareCertificates.read(s);
        if (share.isPresent() && stocks != null) {
            long latest = stocks.equities().lastReportedQuarter();
            books.deposit(account, Kind.SHARE, share.get().ticker(), (long) share.get().denomination() * s.getCount(),
                    Math.max(share.get().paidThrough(), latest));
            return true;
        }
        var bond = BondPapers.read(s);
        if (bond.isPresent() && bonds != null) {
            books.deposit(account, Kind.BOND, BookEntries.bondKey(bond.get().bond()), s.getCount(), bond.get().paidThrough());
            return true;
        }
        var option = OptionPapers.read(s);
        if (option.isPresent() && options != null) {
            books.deposit(account, Kind.OPTION, option.get().key(), s.getCount(), 0);
            return true;
        }
        return false;
    }

    /** Prints book entries back out as papers. Returns why not, or empty. */
    public Optional<String> withdraw(Player player, Kind kind, String key, long quantity) {
        BookEntries.Entry e;
        try {
            e = books.withdraw(account(player), kind, key, quantity);
        } catch (com.realisticmarkets.exchange.RejectedException ex) {
            return Optional.of(ex.getMessage());
        }
        Inventory inv = player.getInventory();
        switch (kind) {
            case SHARE -> {
                for (var entry : Certificates.fewest(quantity).entrySet()) {
                    long left = entry.getValue();
                    while (left > 0) {
                        int n = (int) Math.min(64, left);
                        inv.placeItemBackInInventory(ShareCertificates.create(key, entry.getKey(), e.paidThrough(), n));
                        left -= n;
                    }
                }
            }
            case BOND -> give(inv, BondPapers.create(BookEntries.bond(key), (int) e.paidThrough(), 1), quantity);
            case OPTION -> give(inv, OptionPapers.create(OptionDesk.Series.parse(key), 1), quantity);
        }
        save();
        return Optional.empty();
    }

    private static void give(Inventory inv, ItemStack one, long quantity) {
        long left = quantity;
        while (left > 0) {
            int n = (int) Math.min(64, left);
            inv.placeItemBackInInventory(one.copyWithCount(n));
            left -= n;
        }
    }

    /** Pays out the cash account in bills (down to the dime). Returns cents paid. */
    public long withdrawCash(Player player) {
        long cash = Money.roundDownToDime(books.cash(account(player)));
        if (cash <= 0) return 0;
        books.withdrawCash(account(player), cash);
        Wallet.give(player, cash);
        save();
        return cash;
    }

    // ------------------------------------------------------------------ values

    /** Today's value of one unit of an entry (a share, a bond, a contract), in cents. */
    public long unitValue(String account, BookEntries.Entry e, double day) {
        return switch (e.kind()) {
            case SHARE -> stocks == null ? 0 : stocks.market().exchange().lastPrice(e.key()).orElse(stocks.fairCents(e.key()));
            case BOND -> {
                if (bonds == null) yield 0;
                Bond b = BookEntries.bond(e.key());
                long red = bonds.desk().redemption(b, day);
                yield (red > 0 ? red : bonds.desk().bid(b, day)) + bonds.desk().couponsOwed(b, (int) e.paidThrough(), day);
            }
            case OPTION -> {
                if (options == null) yield 0;
                var s = OptionDesk.Series.parse(e.key());
                var settle = options.desk().settlement(s.underlying(), s.expiry());
                yield settle.isPresent() ? s.intrinsic(settle.getAsLong()) : s.expiry() <= Math.floor(day) ? 0 : options.desk().bid(account, s, day);
            }
        };
    }

    /** A paper standing for an entry (for icons and names). */
    public static ItemStack icon(BookEntries.Entry e) {
        return switch (e.kind()) {
            case SHARE -> ShareCertificates.create(e.key(), 1, e.paidThrough(), 1);
            case BOND -> BondPapers.create(BookEntries.bond(e.key()), (int) e.paidThrough(), 1);
            case OPTION -> OptionPapers.create(OptionDesk.Series.parse(e.key()), 1);
        };
    }

    public List<BookEntries.Entry> entries(Player player) {
        return books.entries(account(player));
    }

    // ------------------------------------------------------------------ persistence

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                books.write(w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save the brokerage books {}", file, e);
        }
    }
}
