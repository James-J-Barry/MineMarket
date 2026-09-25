package com.realisticmarkets.mod.options;

import com.realisticmarkets.futures.ClearingHouse;
import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.item.PortfolioBinderItem;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.stocks.StockService;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.options.OptionDesk;
import com.realisticmarkets.progression.ProgressionEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
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
 * The Options Desk in the world. Prices come from the Dealer (goods, at fair value, in futures lots), the Stock
 * Exchange (shares, 10 a contract) and the central bank (discounting). At each dawn it records every underlying's price
 * for the volatility estimate and, on quarter days, the settlement price. Players buy papers at the ask, sell them
 * back at the bid before expiry, and present them after expiry for their intrinsic value. The book keeps what each
 * account paid per series (for Long Shot and the Records Terminal). Saved to {@code options.txt}.
 */
public final class OptionsService {
    private static OptionsService instance;

    private final DealerService dealer;
    private final StockService stocks; // null: no share options priced (tests)
    private final BankService bank;    // null: a flat 0.3% a day
    private final OptionDesk desk;
    private final com.realisticmarkets.options.WrittenBook written;
    private final Path file; // null in tests
    private final Map<String, long[]> book = new LinkedHashMap<>(); // account|series -> {contracts, cents}
    private long lastDawn = Long.MIN_VALUE;
    private int ticks;

    private OptionsService(DealerService dealer, StockService stocks, BankService bank, Path file, Reader saved) throws IOException {
        this.dealer = dealer;
        this.stocks = stocks;
        this.bank = bank;
        this.file = file;
        OptionDesk.Market market = new OptionDesk.Market() {
            @Override
            public double spotCents(String u, double day) {
                return OptionsService.this.spotCents(u, day);
            }

            @Override
            public double forwardCents(String u, long expiry, double day) {
                double t = Math.max(0, expiry - day);
                if (OptionDesk.isGood(u)) { // like the futures: the fair value expected at expiry, news priced in at dawn
                    var d = dealer.dealer();
                    ClearingHouse.Product p = ClearingHouse.product(u);
                    return d.expectedFair(p.item(), day, Math.max(expiry, day)) * Math.exp(d.intradayNoise(p.item(), day)) * p.lot() * 100.0;
                }
                return spotCents(u, day) * Math.pow(1 + rate(day), t);
            }

            @Override
            public double rate(double day) {
                return bank == null ? 0.003 : bank.centralBank().marketRate(day);
            }
        };
        String text = saved == null ? null : readAll(saved);
        this.desk = text == null ? new OptionDesk(market) : OptionDesk.read(new java.io.StringReader(text), market);
        this.written = text == null ? new com.realisticmarkets.options.WrittenBook()
                : com.realisticmarkets.options.WrittenBook.read(new java.io.StringReader(text));
    }

    private static String readAll(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        return sb.toString();
    }

    public static void start(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID).resolve("options.txt");
        try {
            String text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            OptionsService svc = new OptionsService(DealerService.get(), StockService.get(), BankService.get(), file,
                    text == null ? null : new java.io.StringReader(text));
            if (text != null) svc.readBook(new java.io.StringReader(text));
            instance = svc;
        } catch (IOException | RuntimeException e) {
            RealisticMarkets.LOGGER.error("Could not read the Options Desk {}; starting fresh", file, e);
            try {
                instance = new OptionsService(DealerService.get(), StockService.get(), BankService.get(), file, null);
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    public static void stop() {
        if (instance != null) instance.save();
        instance = null;
    }

    public static OptionsService get() {
        if (instance == null) throw new IllegalStateException("Options Desk not started (no server running?)");
        return instance;
    }

    public static OptionsService getOrNull() {
        return instance;
    }

    public static OptionsService forTest(DealerService dealer, StockService stocks, BankService bank) {
        try {
            return new OptionsService(dealer, stocks, bank, null, null);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public OptionDesk desk() { return desk; }
    public com.realisticmarkets.options.WrittenBook written() { return written; }

    public static String account(Player p) {
        return p.getUUID().toString();
    }

    /** One contract's worth of the underlying now (cents): a good at the Dealer's fair value, a share at its last trade. */
    public double spotCents(String underlying, double day) {
        if (OptionDesk.isGood(underlying)) {
            ClearingHouse.Product p = ClearingHouse.product(underlying);
            return dealer.dealer().fairValue(p.item(), day) * p.lot() * 100.0;
        }
        if (stocks == null) return 0;
        long price = stocks.market().exchange().lastPrice(underlying).orElse(stocks.fairCents(underlying));
        return price * (double) OptionDesk.SHARES_PER_CONTRACT;
    }

    /** Underlyings with a price here (share options need the Stock Exchange). */
    public List<String> underlyings() {
        return stocks == null ? OptionPapers.UNDERLYINGS.stream().filter(OptionDesk::isGood).toList() : OptionPapers.UNDERLYINGS;
    }

    // ------------------------------------------------------------------ dawn

    /** Records closes and settlements, then settles and checks written options; tells their writers if online. */
    public void dawn(long day, java.util.function.Function<java.util.UUID, Player> online, ProgressionService prog) {
        dawn(day);
        List<com.realisticmarkets.options.WrittenBook.Written> called = new ArrayList<>();
        var settled = written.dawn(day, desk, dealer.dealer(), called);
        for (var w : called) {
            Player p = online.apply(java.util.UUID.fromString(w.account()));
            if (p == null) continue;
            long need = Math.max(0, Math.round(com.realisticmarkets.options.WrittenBook.DAWN_COVER
                    * com.realisticmarkets.options.WrittenBook.exposureCents(w, desk, day))
                    - com.realisticmarkets.options.WrittenBook.collateralCents(w, dealer.dealer(), day));
            if (p instanceof net.minecraft.server.level.ServerPlayer sp) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("MARGIN CALL on your written " + OptionPapers.title(w.series())
                        + ": add " + Money.format(need) + " at the Options Desk by dawn, or the desk buys it back out of your collateral.")
                        .withStyle(net.minecraft.ChatFormatting.RED));
            }
            p.getInventory().placeItemBackInInventory(com.realisticmarkets.mod.futures.FuturesService.notice(day, need));
            com.realisticmarkets.mod.fx.Feedback.at(p, com.realisticmarkets.mod.fx.Feedback.Cue.ALARM);
        }
        for (var st : settled) {
            Player p = online.apply(java.util.UUID.fromString(st.account()));
            if (p == null) continue;
            if (p instanceof net.minecraft.server.level.ServerPlayer sp) {
                String what = st.boughtBack() ? "bought back by the desk for " + Money.format(st.paidOutCents())
                        : st.calledAwayUnits() > 0 ? "exercised: your goods were called away for " + Money.format(st.strikeReceivedCents())
                        : st.worthless() ? "expired worthless: the premium is yours" : "expired in the money: paid " + Money.format(st.paidOutCents());
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("Written " + OptionPapers.title(st.series()) + " " + what
                        + ". Collect your collateral at the Options Desk.").withStyle(net.minecraft.ChatFormatting.GOLD));
            }
            com.realisticmarkets.mod.fx.Feedback.at(p, st.paidOutCents() > 0 || st.boughtBack() ? com.realisticmarkets.mod.fx.Feedback.Cue.LOSS
                    : com.realisticmarkets.mod.fx.Feedback.Cue.GAIN);
            if (prog != null) {
                prog.emit(p, new ProgressionEvent.OptionWrittenSettled(st.premiumCents(), st.paidOutCents(), st.strikeReceivedCents(),
                        st.worthless(), day));
            }
        }
        if (!called.isEmpty() || !settled.isEmpty()) save();
    }

    /** Records closes for volatility and, on quarter days (including any missed), settlement prices. */
    public void dawn(long day) {
        // After a restart, look back four weeks: an expiry passed while the world was closed still needs a price
        // (settle() keeps any price already recorded; a late one uses today's).
        long from = lastDawn == Long.MIN_VALUE ? day - 4L * ClearingHouse.EXPIRY_DAYS : lastDawn + 1;
        lastDawn = day;
        for (String u : underlyings()) {
            double spot = spotCents(u, day);
            desk.observe(u, day, spot, OptionDesk.isGood(u) ? desk.market().forwardCents(u, day + OptionDesk.HORIZON, day) : 0);
            for (long d = from; d <= day; d++) {
                if (d > 0 && d % ClearingHouse.EXPIRY_DAYS == 0) desk.settle(u, d, Math.round(spot));
            }
        }
        save();
    }

    public static void tick(MinecraftServer server) {
        if (instance == null || ++instance.ticks % 100 != 0) return;
        long day = (long) Math.floor(instance.dealer.day(server));
        if (day > instance.lastDawn) {
            ProgressionService prog;
            try {
                prog = ProgressionService.get();
            } catch (IllegalStateException notRunning) {
                prog = null;
            }
            instance.dawn(day, id -> server.getPlayerList().getPlayer(id), prog);
        }
    }

    // ------------------------------------------------------------------ trading

    /** The cost (cents) of {@code contracts} more of a series for this account now, contract by contract. */
    public long costToBuy(String account, OptionDesk.Series s, int contracts, double day) {
        long total = 0;
        for (int i = 0; i < contracts; i++) {
            total += desk.ask(account, s, day);
            desk.recordTrade(account, s, 1, day);
        }
        desk.recordTrade(account, s, -contracts, day); // a quote, not a trade: undo the lean
        return total;
    }

    /** Buys contracts of a series at the ask. Returns why not, or empty. */
    public Optional<String> buy(Player player, OptionDesk.Series s, int contracts, double day) {
        if (contracts <= 0) return Optional.of("Choose how many");
        if (s.expiry() <= Math.floor(day)) return Optional.of("That series has expired");
        if (!underlyings().contains(s.underlying())) return Optional.of("No options on that here");
        String a = account(player);
        long cost = 0;
        for (int i = 0; i < contracts; i++) {
            cost += desk.ask(a, s, day);
            desk.recordTrade(a, s, 1, day);
        }
        if (Wallet.count(player.getInventory()) < cost) {
            desk.recordTrade(a, s, -contracts, day);
            return Optional.of("Not enough cash: " + contracts + " cost " + Money.format(cost));
        }
        Wallet.pay(player, cost);
        player.getInventory().placeItemBackInInventory(OptionPapers.create(s, contracts));
        long[] e = book.computeIfAbsent(a + "|" + s.key(), k -> new long[2]);
        e[0] += contracts;
        e[1] += cost;
        save();
        return Optional.empty();
    }

    /** One series the player carries (loose papers only): its value now (the bid, or what it pays once expired). */
    public record Holding(OptionDesk.Series series, long contracts, long valueCents, boolean expired, boolean settled) {}

    public List<Holding> holdings(Player player, double day) {
        Map<String, Holding> by = new LinkedHashMap<>();
        Inventory inv = player.getInventory();
        String a = account(player);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            Optional<OptionDesk.Series> s = OptionPapers.read(inv.getItem(i));
            if (s.isEmpty()) continue;
            Holding h = by.get(s.get().key());
            long n = (h == null ? 0 : h.contracts()) + inv.getItem(i).getCount();
            boolean expired = s.get().expiry() <= Math.floor(day);
            var settle = desk.settlement(s.get().underlying(), s.get().expiry());
            long value = settle.isPresent() ? s.get().intrinsic(settle.getAsLong()) : expired ? 0 : desk.bid(a, s.get(), day);
            by.put(s.get().key(), new Holding(s.get(), n, value, expired, settle.isPresent()));
        }
        return new ArrayList<>(by.values());
    }

    /**
     * Closes every loose paper of a series: before expiry the desk buys them back at the bid (contract by contract);
     * after its settlement it pays their intrinsic value. Returns why not, or empty.
     */
    public Optional<String> close(Player player, OptionDesk.Series s, double day, ProgressionService prog) {
        Inventory inv = player.getInventory();
        String a = account(player);
        boolean expired = s.expiry() <= Math.floor(day);
        var settle = desk.settlement(s.underlying(), s.expiry());
        if (expired && settle.isEmpty()) return Optional.of("Expired: the desk settles it at the next dawn");
        long contracts = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            Optional<OptionDesk.Series> p = OptionPapers.read(inv.getItem(i));
            if (p.isPresent() && p.get().equals(s)) {
                contracts += inv.getItem(i).getCount();
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (contracts == 0) return Optional.of("You don't carry that option");
        long proceeds = 0;
        if (settle.isPresent()) {
            proceeds = s.intrinsic(settle.getAsLong()) * contracts;
        } else {
            for (long i = 0; i < contracts; i++) {
                proceeds += desk.bid(a, s, day);
                desk.recordTrade(a, s, -1, day);
            }
        }
        long paid = Money.roundDownToDime(proceeds);
        if (paid > 0) Wallet.give(player, paid);
        long cost = -1;
        long[] e = book.get(a + "|" + s.key());
        if (e != null && e[0] >= contracts) {
            cost = Math.round(e[1] * (double) contracts / e[0]);
            e[0] -= contracts;
            e[1] -= cost;
            if (e[0] <= 0) book.remove(a + "|" + s.key());
        } else if (e != null) {
            book.remove(a + "|" + s.key());
        }
        if (prog != null) {
            prog.emit(player, new ProgressionEvent.OptionClosed(s.underlying(), s.call(), contracts, paid, cost, settle.isPresent(),
                    (long) Math.floor(day)));
        }
        save();
        return Optional.empty();
    }

    /** Presents every settled paper the player carries (loose or in binders is not needed: loose only). Returns cents paid. */
    public long collectExpired(Player player, double day, ProgressionService prog) {
        long before = Wallet.count(player.getInventory());
        for (Holding h : holdings(player, day)) {
            if (h.settled()) close(player, h.series(), day, prog);
        }
        return Wallet.count(player.getInventory()) - before;
    }

    // ------------------------------------------------------------------ writing

    /** Collateral in a container: bills as cash, everything else as goods by item id. */
    public record Collateral(Map<String, Integer> items, long cashCents, List<String> refused) {}

    public Collateral collateral(net.minecraft.world.Container c) {
        Map<String, Integer> items = new LinkedHashMap<>();
        long cash = 0;
        List<String> refused = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty()) continue;
            var d = ModItems.denominationOf(st);
            if (d != null) {
                cash += d.cents() * st.getCount();
                continue;
            }
            String id = DealerService.itemId(st);
            if (!com.realisticmarkets.collateral.CollateralValuer.gradeOf(dealer.dealer().catalog(), id).accepted()
                    || st.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
                refused.add(st.getHoverName().getString());
                continue;
            }
            items.merge(id, st.getCount(), Integer::sum);
        }
        return new Collateral(items, cash, refused);
    }

    public com.realisticmarkets.options.WrittenBook.Terms writeTerms(OptionDesk.Series s, int contracts, Collateral c, double day) {
        return com.realisticmarkets.options.WrittenBook.terms(desk, dealer.dealer(), s, contracts, c.items(), c.cashCents(), day);
    }

    /** Writes an option against the collateral in {@code slots} (emptied into escrow) and pays the premium. */
    public Optional<String> write(Player player, OptionDesk.Series s, int contracts, net.minecraft.world.Container slots, double day,
                                  ProgressionService prog) {
        if (!OptionDesk.isGood(s.underlying())) return Optional.of("Only options on goods can be written");
        Collateral c = collateral(slots);
        if (!c.refused().isEmpty()) return Optional.of(c.refused().get(0) + " can't back an option");
        com.realisticmarkets.options.WrittenBook.Written w;
        try {
            w = written.write(account(player), desk, dealer.dealer(), s, contracts, c.items(), c.cashCents(), day);
        } catch (com.realisticmarkets.exchange.RejectedException e) {
            return Optional.of(e.getMessage());
        }
        slots.clearContent();
        Wallet.give(player, w.premiumCents());
        if (prog != null) {
            prog.emit(player, new ProgressionEvent.OptionWritten(s.underlying(), s.call(), contracts, w.premiumCents(),
                    w.coveredShare() >= 1, (long) Math.floor(day)));
        }
        save();
        return Optional.empty();
    }

    /** Moves every bill the player carries into a written option's escrow (meeting a margin call). */
    public Optional<String> topUp(Player player, long id, double day) {
        var w = written.get(id).filter(x -> x.account().equals(account(player)));
        if (w.isEmpty()) return Optional.of("No such written option");
        long cash = Wallet.takeAll(player);
        if (cash <= 0) return Optional.of("You carry no cash");
        written.addCash(id, cash, desk, dealer.dealer(), day);
        save();
        return Optional.empty();
    }

    /** Hands back escrow released after settlement. Returns whether there was any. */
    public boolean collectReturns(Player player) {
        var r = written.collect(account(player));
        if (r.items().isEmpty() && r.cashCents() <= 0) return false;
        for (Map.Entry<String, Integer> e : r.items().entrySet()) {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(e.getKey()));
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(left, item.getDefaultMaxStackSize());
                player.getInventory().placeItemBackInInventory(new ItemStack(item, n));
                left -= n;
            }
        }
        long cash = Money.roundDownToDime(r.cashCents());
        if (cash > 0) Wallet.give(player, cash);
        save();
        return true;
    }

    /** Average cost (cents) of one contract of a series this account bought here, or -1. */
    public double averageCost(String account, OptionDesk.Series s) {
        long[] e = book.get(account + "|" + s.key());
        return e == null || e[0] <= 0 ? -1 : e[1] / (double) e[0];
    }

    /** Papers carried in binders count for net worth too; this reads a list of stacks. */
    public static List<ItemStack> papersIn(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : stacks) {
            if (s.is(ModItems.OPTION_CONTRACT)) out.add(s);
            if (s.is(ModItems.PORTFOLIO_BINDER)) out.addAll(papersIn(PortfolioBinderItem.contents(s)));
        }
        return out;
    }

    // ------------------------------------------------------------------ persistence

    private void readBook(Reader r) throws IOException {
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            String[] c = line.strip().split("\t");
            if (c.length == 4 && c[0].equals("book")) book.put(c[1], new long[] {Long.parseLong(c[2]), Long.parseLong(c[3])});
        }
    }

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            StringWriter w = new StringWriter();
            desk.write(w);
            written.write(w);
            for (Map.Entry<String, long[]> e : book.entrySet()) {
                w.write("book\t" + e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1] + "\n");
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                out.write(w.toString());
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save the Options Desk {}", file, e);
        }
    }
}
