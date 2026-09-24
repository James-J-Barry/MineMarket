package com.realisticmarkets.mod.stocks;

import com.realisticmarkets.agents.FloorCatalog;
import com.realisticmarkets.agents.TradingFloor;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.WorldEvents;
import com.realisticmarkets.equities.CompanyCatalog;
import com.realisticmarkets.equities.CostBasis;
import com.realisticmarkets.equities.Equities;
import com.realisticmarkets.exchange.Account;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.PriceHistory;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.exchange.Side;
import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.floor.TradeReceiptItem;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Money;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The Stock Exchange in the world: the six companies' businesses ({@link Equities}, fed each dawn with the Dealer's
 * prices and the world's events) and one book per company on the Trading Floor's auction engine, NPC traders
 * anchored on each company's fair value. Players trade with Order Slips; bought shares come out as Share
 * Certificates, sold shares go in as certificates. Dividends on certificates pay when they're presented here;
 * dividends on shares the exchange holds for a player (in a resting sell order, or bought but not yet collected) are
 * credited to their account at the quarter's close. Saved to {@code stock_*.txt} and {@code equities.txt}.
 */
public final class StockService {
    public static final int AUCTION_TICKS = 200;
    private static final long SEED_SALT = 0x53746F636BL; // "Stock"
    private static final String NPC = "npc/";

    private static StockService instance;

    private final TradingFloor market;
    private final Equities equities;
    private final CostBasis basis;
    private final DealerService dealer;
    private final Path dir; // null in tests
    private final Map<String, List<TradingFloor.Receipt>> receipts = new LinkedHashMap<>();
    private long ticks;
    private long lastObserved = Long.MIN_VALUE;

    private StockService(TradingFloor market, Equities equities, CostBasis basis, DealerService dealer, Path dir) {
        this.market = market;
        this.equities = equities;
        this.basis = basis;
        this.dealer = dealer;
        this.dir = dir;
    }

    static Equities newEquities(long seed) {
        DealerCatalog cat = DealerCatalog.loadDefault();
        return new Equities(CompanyCatalog.loadDefault(), k -> cat.trades(k) ? cat.spec(k).fairValue() : 1.0, seed);
    }

    public static void start(MinecraftServer server) {
        DealerService d = DealerService.get();
        long seed = server.overworld().getSeed() ^ SEED_SALT;
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID);
        Exchange ex = new Exchange();
        PriceHistory hist = new PriceHistory();
        Equities eq = newEquities(seed);
        CostBasis cb = new CostBasis();
        long observed = Long.MIN_VALUE;
        try {
            if (Files.exists(dir.resolve("stock_exchange.txt"))) {
                try (Reader r = reader(dir.resolve("stock_exchange.txt"))) {
                    ex = Exchange.read(r);
                }
            }
            if (Files.exists(dir.resolve("stock_history.txt"))) {
                try (Reader r = reader(dir.resolve("stock_history.txt"))) {
                    hist = PriceHistory.read(r);
                }
            }
            if (Files.exists(dir.resolve("equities.txt"))) {
                try (BufferedReader r = reader(dir.resolve("equities.txt"))) {
                    String first = r.readLine(); // "# ... observed <day>" written by save()
                    if (first != null && first.startsWith("#observed\t")) observed = Long.parseLong(first.substring(10));
                    Equities.read(r, eq);
                }
            }
            if (Files.exists(dir.resolve("stock_cost_basis.txt"))) {
                try (Reader r = reader(dir.resolve("stock_cost_basis.txt"))) {
                    cb = CostBasis.read(r);
                }
            }
        } catch (IOException | RuntimeException e) {
            RealisticMarkets.LOGGER.error("Could not read the Stock Exchange state; starting fresh", e);
            ex = new Exchange();
            hist = new PriceHistory();
            eq = newEquities(seed);
            cb = new CostBasis();
            observed = Long.MIN_VALUE;
        }
        Equities finalEq = eq;
        TradingFloor tf = new TradingFloor(ex, hist, FloorCatalog.loadStocks(), finalEq::fairValueCents, seed);
        StockService svc = new StockService(tf, eq, cb, d, dir);
        svc.lastObserved = observed;
        svc.loadTicketsAndReceipts();
        svc.observeTo((long) Math.floor(d.day(server)));
        instance = svc;
    }

    public static void stop() {
        if (instance != null) instance.save();
        instance = null;
    }

    public static StockService get() {
        if (instance == null) throw new IllegalStateException("Stock Exchange not started (no server running?)");
        return instance;
    }

    /** In-memory exchange on the given Dealer, for GameTests. */
    public static StockService forTest(DealerService dealer, long seed) {
        Equities eq = newEquities(seed);
        TradingFloor tf = new TradingFloor(new Exchange(), new PriceHistory(), FloorCatalog.loadStocks(), eq::fairValueCents, seed);
        return new StockService(tf, eq, new CostBasis(), dealer, null);
    }

    public TradingFloor market() { return market; }
    public Equities equities() { return equities; }
    public CostBasis costBasis() { return basis; }
    public CompanyCatalog companies() { return equities.catalog(); }

    public long fairCents(String ticker) {
        return equities.fairValueCents(ticker);
    }

    public long ticksToAuction() {
        return AUCTION_TICKS - (ticks % AUCTION_TICKS);
    }

    // ------------------------------------------------------------------ time

    public static void tick(MinecraftServer server) {
        if (instance == null) return;
        instance.ticks++;
        double day;
        try {
            day = DealerService.get().day(server);
        } catch (IllegalStateException notRunning) {
            return;
        }
        instance.observeTo((long) Math.floor(day));
        if (instance.ticks % AUCTION_TICKS == 0) instance.runAuctions(day);
        if (instance.ticks % DealerService.SAVE_INTERVAL_TICKS == 0) instance.save();
    }

    /**
     * Brings the companies up to {@code day}: each new dawn is observed (the Dealer's prices, the day's events), day
     * orders expire, and a quarter's close credits dividends on shares held in custody.
     */
    public void observeTo(long day) {
        if (lastObserved == Long.MIN_VALUE) lastObserved = day - 1;
        while (lastObserved < day) {
            long d = ++lastObserved;
            for (TradingFloor.Receipt r : market.dawn(d)) receipts.computeIfAbsent(r.account(), k -> new ArrayList<>()).add(r);
            List<String> events = new ArrayList<>();
            WorldEvents ev = dealer.events();
            if (ev != null) for (WorldEvents.Event e : ev.startingOn(d)) events.add(e.type().id());
            List<Equities.Report> reports = equities.observe(d, k -> price(k, d), events);
            for (Equities.Report r : reports) creditCustodyDividends(r);
        }
    }

    private double price(String key, long day) {
        // Items at the Dealer's fair value; "cpi", "fees" and "shipping" follow the general price level.
        return dealer.dealer().catalog().trades(key) ? dealer.dealer().fairValue(key, day) : dealer.dealer().priceLevel(day);
    }

    private void creditCustodyDividends(Equities.Report r) {
        if (r.dividend() <= 0) return;
        for (Account a : market.exchange().accounts()) {
            if (a.id().startsWith(NPC)) continue;
            long shares = a.position(r.ticker()) + a.lockedPosition(r.ticker());
            if (shares > 0) market.exchange().deposit(a.id(), shares * r.dividend());
        }
    }

    public void runAuctions(double day) {
        long whole = (long) Math.floor(day);
        for (FloorCatalog.Book b : market.catalog().all()) {
            for (TradingFloor.Receipt r : market.auction(b.item(), fairCents(b.item()), AUCTION_TICKS / 24_000.0, day)) {
                receipts.computeIfAbsent(r.account(), k -> new ArrayList<>()).add(r);
            }
        }
        if (whole > lastObserved) observeTo(whole);
    }

    // ------------------------------------------------------------------ players

    public static String account(Player p) {
        return p.getUUID().toString();
    }

    /**
     * Places an order for {@code shares} of {@code ticker}: one Order Slip, plus certificates (sell; their dividends
     * are paid first) or bills (buy, shares x limit, rounded up to the dime). Returns empty on success, else why not.
     */
    public Optional<String> place(Player player, String ticker, Side side, long shares, long limitCents, boolean marketOrder,
                                  double day, ProgressionService prog) {
        if (!market.catalog().trades(ticker)) return Optional.of("No such company");
        if (shares <= 0) return Optional.of("Choose a number of shares");
        Inventory inv = player.getInventory();
        if (inv.countItem(ModItems.ORDER_SLIP) < 1) return Optional.of("You need an Order Slip");
        long fair = fairCents(ticker);
        long limit = side == Side.BUY ? market.buyLimit(ticker, fair, marketOrder, limitCents) : market.sellLimit(ticker, fair, marketOrder, limitCents);
        if (limit <= 0) return Optional.of("Set a price");
        long payCents = side == Side.BUY ? Money.roundUpToDime(shares * limit) : 0;
        if (side == Side.SELL && ShareCertificates.holdings(inv).getOrDefault(ticker, 0L) < shares) {
            return Optional.of("You don't hold " + shares + " " + ticker + " shares");
        }
        if (side == Side.BUY && Wallet.count(inv) < payCents) return Optional.of("Not enough cash: this order needs " + Money.format(payCents));
        if (side == Side.SELL) collectDividends(player, prog, (long) Math.floor(day));
        try {
            market.place(account(player), ticker, side, shares, limit, marketOrder, 0, (long) Math.floor(day));
            if (payCents > shares * limit) market.exchange().deposit(account(player), payCents - shares * limit);
        } catch (RejectedException e) {
            return Optional.of(e.getMessage());
        }
        removeOne(inv, ModItems.ORDER_SLIP);
        if (side == Side.SELL) takeShares(inv, ticker, shares);
        else Wallet.pay(player, payCents);
        save();
        return Optional.empty();
    }

    private static void removeOne(Inventory inv, net.minecraft.world.item.Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) {
                inv.getItem(i).shrink(1);
                return;
            }
        }
    }

    /** Takes {@code shares} of {@code ticker} from the inventory's certificates, giving change in smaller ones. */
    private void takeShares(Inventory inv, String ticker, long shares) {
        long held = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            Optional<ShareCertificates.Paper> p = ShareCertificates.read(s);
            if (p.isPresent() && p.get().ticker().equals(ticker)) {
                held += (long) p.get().denomination() * s.getCount();
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        for (ItemStack change : ShareCertificates.stacksFor(ticker, held - shares, equities.lastReportedQuarter())) {
            inv.placeItemBackInInventory(change);
        }
    }

    public Optional<String> cancel(Player player, long orderId, long day) {
        try {
            receipts.computeIfAbsent(account(player), k -> new ArrayList<>()).add(market.cancel(account(player), orderId, day));
        } catch (RejectedException e) {
            return Optional.of(e.getMessage());
        }
        save();
        return Optional.empty();
    }

    public Optional<String> reprice(Player player, long orderId, long newLimitCents, long day) {
        String acct = account(player);
        long extra;
        try {
            extra = Money.roundUpToDime(market.repriceCost(acct, orderId, newLimitCents));
        } catch (RejectedException | java.util.NoSuchElementException e) {
            return Optional.of("No such order");
        }
        if (Wallet.count(player.getInventory()) < extra) return Optional.of("Not enough cash: raising it needs " + Money.format(extra));
        try {
            market.reprice(acct, orderId, newLimitCents, extra, day);
        } catch (RejectedException e) {
            return Optional.of(e.getMessage());
        }
        if (extra > 0) Wallet.pay(player, extra);
        save();
        return Optional.empty();
    }

    /**
     * Hands the player what's waiting: bills, certificates for shares bought or returned, and a receipt per finished
     * order. Records cost basis for buys, emits the sale and holding quest events.
     */
    public void deliver(Player player, Container output, ProgressionService prog, long day) {
        String acct = account(player);
        TradingFloor.Pickup p = market.available(acct);
        List<TradingFloor.Receipt> mine = receipts.getOrDefault(acct, List.of());
        if (p.isEmpty() && mine.isEmpty()) return;
        market.collect(acct, p);
        List<ItemStack> stacks = new ArrayList<>();
        for (Map.Entry<String, Long> e : p.items().entrySet()) {
            stacks.addAll(ShareCertificates.stacksFor(e.getKey(), e.getValue(), equities.lastReportedQuarter()));
        }
        stacks.addAll(Wallet.toStacks(p.cents()));
        for (TradingFloor.Receipt r : mine) {
            stacks.add(TradeReceiptItem.create(r, r.item() + " shares"));
            if (r.filledQty() <= 0) continue;
            if (r.side() == Side.BUY) {
                basis.bought(acct, r.item(), r.filledQty(), r.filledCents());
            } else {
                long cost = basis.sold(acct, r.item(), r.filledQty());
                if (prog != null) prog.emit(player, new ProgressionEvent.StockSold(r.item(), r.filledQty(), r.filledCents(), cost, day));
            }
        }
        receipts.remove(acct);
        for (ItemStack s : stacks) {
            ItemStack left = s;
            for (int i = 0; i < output.getContainerSize() && !left.isEmpty(); i++) {
                ItemStack cur = output.getItem(i);
                if (cur.isEmpty()) {
                    output.setItem(i, left);
                    left = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(cur, left) && cur.getCount() < cur.getMaxStackSize()) {
                    int n = Math.min(left.getCount(), cur.getMaxStackSize() - cur.getCount());
                    cur.grow(n);
                    left.shrink(n);
                }
            }
            if (!left.isEmpty()) player.getInventory().placeItemBackInInventory(left);
        }
        output.setChanged();
        reportHoldings(player, output, prog, day);
        save();
    }

    /** Emits the player's current holdings (inventory plus {@code extra}) for the holding quests. */
    public void reportHoldings(Player player, Container extra, ProgressionService prog, long day) {
        if (prog == null) return;
        Map<String, Long> held = new LinkedHashMap<>(ShareCertificates.holdings(player.getInventory()));
        if (extra != null) ShareCertificates.holdings(extra).forEach((k, v) -> held.merge(k, v, Long::sum));
        held.forEach((t, n) -> prog.emit(player, new ProgressionEvent.SharesHeld(t, n, day)));
    }

    public boolean hasWaiting(Player player) {
        return !market.available(account(player)).isEmpty() || !receipts.getOrDefault(account(player), List.of()).isEmpty();
    }

    // ------------------------------------------------------------------ certificates

    /** Dividends (cents) the certificates in the player's inventory would pay if presented now. */
    public long dividendsWaiting(Player player) {
        long sum = 0;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            Optional<ShareCertificates.Paper> p = ShareCertificates.read(s);
            if (p.isPresent()) sum += (long) p.get().denomination() * s.getCount() * equities.dividendsSince(p.get().ticker(), p.get().paidThrough());
        }
        return sum;
    }

    /** Presents every certificate in the inventory: pays their dividends in bills and marks them paid up. */
    public long collectDividends(Player player, ProgressionService prog, long day) {
        Inventory inv = player.getInventory();
        long latest = equities.lastReportedQuarter();
        Map<String, Long> byTicker = new LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            Optional<ShareCertificates.Paper> p = ShareCertificates.read(s);
            if (p.isEmpty() || p.get().paidThrough() >= latest) continue;
            long owed = (long) p.get().denomination() * s.getCount() * equities.dividendsSince(p.get().ticker(), p.get().paidThrough());
            byTicker.merge(p.get().ticker(), owed, Long::sum);
            inv.setItem(i, ShareCertificates.create(p.get().ticker(), p.get().denomination(), latest, s.getCount()));
        }
        long total = 0;
        for (Map.Entry<String, Long> e : byTicker.entrySet()) {
            total += e.getValue();
            if (prog != null) prog.emit(player, new ProgressionEvent.DividendCollected(e.getKey(), e.getValue(), day));
        }
        long paid = total / 10 * 10; // bills come in dimes; the Dealer's-favor rounding, here the company's
        if (paid > 0) Wallet.give(player, paid);
        mergeStacks(inv);
        return paid;
    }

    /** Pays dividends, then swaps every company's certificates for the fewest (100s, 10s, 1s). */
    public void merge(Player player, ProgressionService prog, long day) {
        collectDividends(player, prog, day);
        Inventory inv = player.getInventory();
        Map<String, Long> held = ShareCertificates.holdings(inv);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (ShareCertificates.read(inv.getItem(i)).isPresent()) inv.setItem(i, ItemStack.EMPTY);
        }
        for (Map.Entry<String, Long> e : held.entrySet()) {
            for (ItemStack s : ShareCertificates.stacksFor(e.getKey(), e.getValue(), equities.lastReportedQuarter())) {
                inv.placeItemBackInInventory(s);
            }
        }
        reportHoldings(player, null, prog, day);
    }

    /** Splits one of the largest certificates of {@code ticker} into ten of the next size down. */
    public Optional<String> split(Player player, String ticker) {
        Inventory inv = player.getInventory();
        int best = -1, bestDenom = 1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            Optional<ShareCertificates.Paper> p = ShareCertificates.read(inv.getItem(i));
            if (p.isPresent() && p.get().ticker().equals(ticker) && p.get().denomination() > bestDenom) {
                best = i;
                bestDenom = p.get().denomination();
            }
        }
        if (best < 0) return Optional.of("No " + ticker + " certificate to split");
        ShareCertificates.Paper p = ShareCertificates.read(inv.getItem(best)).orElseThrow();
        inv.getItem(best).shrink(1);
        inv.placeItemBackInInventory(ShareCertificates.create(ticker, com.realisticmarkets.equities.Certificates.splitInto(p.denomination()),
                p.paidThrough(), 10));
        return Optional.empty();
    }

    private static void mergeStacks(Inventory inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack a = inv.getItem(i);
            if (a.isEmpty() || !a.is(ModItems.SHARE_CERTIFICATE)) continue;
            for (int j = i + 1; j < inv.getContainerSize() && a.getCount() < a.getMaxStackSize(); j++) {
                ItemStack b = inv.getItem(j);
                if (ItemStack.isSameItemSameComponents(a, b)) {
                    int n = Math.min(b.getCount(), a.getMaxStackSize() - a.getCount());
                    a.grow(n);
                    b.shrink(n);
                }
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    private static BufferedReader reader(Path p) throws IOException {
        return Files.newBufferedReader(p, StandardCharsets.UTF_8);
    }

    private void loadTicketsAndReceipts() {
        try {
            Path t = dir.resolve("stock_tickets.txt");
            if (Files.exists(t)) {
                try (Reader r = reader(t)) {
                    market.readTickets(r);
                }
            }
            Path rc = dir.resolve("stock_receipts.txt");
            if (Files.exists(rc)) {
                try (BufferedReader br = reader(rc)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isBlank() || line.startsWith("#")) continue;
                        String[] c = line.split("\t");
                        receipts.computeIfAbsent(c[0], k -> new ArrayList<>()).add(new TradingFloor.Receipt(c[0], c[1],
                                Side.valueOf(c[2]), Boolean.parseBoolean(c[3]), Long.parseLong(c[4]), Long.parseLong(c[5]),
                                Long.parseLong(c[6]), Long.parseLong(c[7]), Long.parseLong(c[8]), TradingFloor.Ending.valueOf(c[9])));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            RealisticMarkets.LOGGER.error("Could not read Stock Exchange orders or receipts", e);
        }
    }

    public void save() {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            StringWriter ex = new StringWriter(), hist = new StringWriter(), tickets = new StringWriter(), eq = new StringWriter(),
                    cb = new StringWriter();
            market.exchange().write(ex);
            market.history().write(hist);
            market.writeTickets(tickets);
            eq.write("#observed\t" + lastObserved + "\n");
            equities.write(eq);
            basis.write(cb);
            StringBuilder rc = new StringBuilder("# account\tticker\tside\tmarket\tqty\tfilled\tfilled_cents\tunused\tday\tending\n");
            for (List<TradingFloor.Receipt> list : receipts.values()) {
                for (TradingFloor.Receipt r : list) {
                    rc.append(r.account()).append('\t').append(r.item()).append('\t').append(r.side()).append('\t')
                            .append(r.market()).append('\t').append(r.qty()).append('\t').append(r.filledQty()).append('\t')
                            .append(r.filledCents()).append('\t').append(r.dealerBidMills()).append('\t').append(r.day())
                            .append('\t').append(r.ending()).append('\n');
                }
            }
            writeAtomic(dir.resolve("stock_exchange.txt"), ex.toString());
            writeAtomic(dir.resolve("stock_history.txt"), hist.toString());
            writeAtomic(dir.resolve("stock_tickets.txt"), tickets.toString());
            writeAtomic(dir.resolve("stock_receipts.txt"), rc.toString());
            writeAtomic(dir.resolve("equities.txt"), eq.toString());
            writeAtomic(dir.resolve("stock_cost_basis.txt"), cb.toString());
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save the Stock Exchange", e);
        }
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(content);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
