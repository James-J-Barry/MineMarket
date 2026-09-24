package com.realisticmarkets.mod.floor;

import com.realisticmarkets.agents.FloorCatalog;
import com.realisticmarkets.agents.TradingFloor;
import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.PriceHistory;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.exchange.Side;
import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.progression.Materials;
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
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The Trading Floor in the world: runs every book's auction every {@link #AUCTION_TICKS} ticks against the Dealer's
 * live fair values, expires day orders at dawn, and keeps finished orders' receipts until their owner visits a
 * Floor. Saved to {@code floor_exchange.txt}, {@code floor_history.txt}, {@code floor_tickets.txt} and
 * {@code floor_receipts.txt} in the world's realisticmarkets folder.
 */
public final class FloorService {
    public static final int AUCTION_TICKS = 200;
    private static final long SEED_SALT = 0x466C6F6F72L; // "Floor"

    private static FloorService instance;

    private final TradingFloor floor;
    private final DealerService dealer;
    private final Path dir; // null in tests
    private final Map<String, List<TradingFloor.Receipt>> receipts = new LinkedHashMap<>();
    private long ticks;
    private long lastDay = Long.MIN_VALUE;

    private FloorService(TradingFloor floor, DealerService dealer, Path dir) {
        this.floor = floor;
        this.dealer = dealer;
        this.dir = dir;
    }

    public static void start(MinecraftServer server) {
        DealerService d = DealerService.get();
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID);
        Exchange ex = new Exchange();
        PriceHistory hist = new PriceHistory();
        try {
            if (Files.exists(dir.resolve("floor_exchange.txt"))) {
                try (Reader r = Files.newBufferedReader(dir.resolve("floor_exchange.txt"), StandardCharsets.UTF_8)) {
                    ex = Exchange.read(r);
                }
            }
            if (Files.exists(dir.resolve("floor_history.txt"))) {
                try (Reader r = Files.newBufferedReader(dir.resolve("floor_history.txt"), StandardCharsets.UTF_8)) {
                    hist = PriceHistory.read(r);
                }
            }
        } catch (IOException | RuntimeException e) {
            RealisticMarkets.LOGGER.error("Could not read the Trading Floor state; starting a fresh floor", e);
            ex = new Exchange();
            hist = new PriceHistory();
        }
        double day = d.day(server);
        TradingFloor tf = new TradingFloor(ex, hist, FloorCatalog.loadDefault(), item -> fairCents(d.dealer(), item, day),
                server.overworld().getSeed() ^ SEED_SALT);
        FloorService svc = new FloorService(tf, d, dir);
        svc.loadTicketsAndReceipts();
        svc.lastDay = (long) Math.floor(day);
        instance = svc;
    }

    public static void stop() {
        if (instance != null) instance.save();
        instance = null;
    }

    public static FloorService get() {
        if (instance == null) throw new IllegalStateException("Trading Floor not started (no server running?)");
        return instance;
    }

    /** In-memory floor on the given Dealer, for GameTests. */
    public static FloorService forTest(DealerService dealer, long seed) {
        TradingFloor tf = new TradingFloor(new Exchange(), new PriceHistory(), FloorCatalog.loadDefault(),
                item -> fairCents(dealer.dealer(), item, 0), seed);
        return new FloorService(tf, dealer, null);
    }

    public TradingFloor floor() { return floor; }

    public static long fairCents(Dealer d, String item, double day) {
        return Math.max(1, Math.round(d.fairValue(item, day) * 100));
    }

    /** The Floor's fair value for a book: the Dealer's, moved by the book's own basis (iron block vs ingots). */
    public long fairCents(String item, double day) {
        return floor.fairOnFloor(item, fairCents(dealer.dealer(), item, day), day);
    }

    public long ticksToAuction() {
        return AUCTION_TICKS - (ticks % AUCTION_TICKS);
    }

    // ------------------------------------------------------------------ ticking

    public static void tick(MinecraftServer server) {
        if (instance == null) return;
        instance.ticks++;
        double day;
        try {
            day = DealerService.get().day(server);
        } catch (IllegalStateException notRunning) {
            return;
        }
        long whole = (long) Math.floor(day);
        if (whole > instance.lastDay) {
            instance.dawn(whole);
            instance.lastDay = whole;
        }
        if (instance.ticks % AUCTION_TICKS == 0) instance.runAuctions(day);
        if (instance.ticks % DealerService.SAVE_INTERVAL_TICKS == 0) instance.save();
    }

    /** One auction on every book. */
    public void runAuctions(double day) {
        long whole = (long) Math.floor(day);
        for (FloorCatalog.Book b : floor.catalog().all()) {
            for (TradingFloor.Receipt r : floor.auction(b.item(), fairCents(b.item(), day), AUCTION_TICKS / 24_000.0, day)) {
                receipts.computeIfAbsent(r.account(), k -> new ArrayList<>()).add(r);
            }
        }
    }

    public void dawn(long day) {
        for (TradingFloor.Receipt r : floor.dawn(day)) receipts.computeIfAbsent(r.account(), k -> new ArrayList<>()).add(r);
    }

    // ------------------------------------------------------------------ players

    public static String account(Player p) {
        return p.getUUID().toString();
    }

    /**
     * Places an order for {@code player}: takes 1 Order Slip plus the goods (sell) or bills (buy, qty x limit) from
     * them. {@code limitCents} is ignored for market orders. Returns empty on success, else why not.
     */
    public Optional<String> place(Player player, String item, Side side, long qty, long limitCents, boolean market,
                                  double day, boolean licensed) {
        if (!floor.catalog().trades(item)) return Optional.of("The Floor has no book for that");
        if (qty <= 0) return Optional.of("Choose a quantity");
        if (player.getInventory().countItem(ModItems.ORDER_SLIP) < 1) return Optional.of("You need an Order Slip");
        long fair = fairCents(item, day);
        long limit = side == Side.BUY ? floor.buyLimit(item, fair, market, limitCents) : floor.sellLimit(item, fair, market, limitCents);
        if (limit <= 0) return Optional.of("Set a price");
        if (side == Side.SELL) {
            if (Materials.count(player.getInventory(), item) < qty) return Optional.of("You don't have " + qty + " of that");
        }
        // Bills only come in dimes: a buy pays its escrow rounded up, and the extra cents wait in the account.
        long payCents = side == Side.BUY ? Money.roundUpToDime(qty * limit) : 0;
        if (Wallet.count(player.getInventory()) < payCents) {
            return Optional.of("Not enough cash: this order needs " + Money.format(payCents));
        }
        long bidMills = Math.round(dealer.dealer().bid(item, day, licensed) * 1000);
        try {
            floor.place(account(player), item, side, qty, limit, market, bidMills, (long) Math.floor(day));
            if (payCents > qty * limit) floor.exchange().deposit(account(player), payCents - qty * limit);
        } catch (RejectedException e) {
            return Optional.of(e.getMessage());
        }
        Materials.remove(player.getInventory(), BuiltInRegistries.ITEM.getKey(ModItems.ORDER_SLIP).toString(), 1);
        if (side == Side.SELL) Materials.remove(player.getInventory(), item, (int) qty);
        else Wallet.pay(player, payCents);
        save();
        return Optional.empty();
    }

    /**
     * Moves one of the player's open orders to {@code newLimitCents} without a new Order Slip. Raising a bid takes
     * the extra escrow from their bills (rounded up to the dime; spare cents wait in their Floor account).
     */
    public Optional<String> reprice(Player player, long orderId, long newLimitCents, long day) {
        String acct = account(player);
        long extra;
        try {
            extra = Money.roundUpToDime(floor.repriceCost(acct, orderId, newLimitCents));
        } catch (RejectedException | java.util.NoSuchElementException e) {
            return Optional.of("No such order");
        }
        if (Wallet.count(player.getInventory()) < extra) return Optional.of("Not enough cash: raising it needs " + Money.format(extra));
        try {
            floor.reprice(acct, orderId, newLimitCents, extra, day);
        } catch (RejectedException e) {
            return Optional.of(e.getMessage());
        }
        if (extra > 0) Wallet.pay(player, extra);
        save();
        return Optional.empty();
    }

    public Optional<String> cancel(Player player, long orderId, long day) {
        try {
            receipts.computeIfAbsent(account(player), k -> new ArrayList<>()).add(floor.cancel(account(player), orderId, day));
        } catch (RejectedException e) {
            return Optional.of(e.getMessage());
        }
        save();
        return Optional.empty();
    }

    /**
     * Hands the player everything waiting for them: goods, bills and receipts go into {@code output} (overflow to
     * their inventory), and each receipt fires the quest event.
     */
    public void deliver(Player player, Container output, ProgressionService prog) {
        String acct = account(player);
        TradingFloor.Pickup p = floor.available(acct);
        List<TradingFloor.Receipt> mine = receipts.getOrDefault(acct, List.of());
        if (p.isEmpty() && mine.isEmpty()) return;
        floor.collect(acct, p);
        List<ItemStack> stacks = new ArrayList<>();
        for (Map.Entry<String, Long> e : p.items().entrySet()) {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(e.getKey()));
            long left = e.getValue();
            while (left > 0) {
                int n = (int) Math.min(left, item.getDefaultMaxStackSize());
                stacks.add(new ItemStack(item, n));
                left -= n;
            }
        }
        stacks.addAll(Wallet.toStacks(p.cents()));
        for (TradingFloor.Receipt r : mine) {
            stacks.add(TradeReceiptItem.create(r));
            if (prog != null) {
                prog.emit(player, new ProgressionEvent.FloorOrderDone(r.item(), r.side() == Side.BUY, r.market(),
                        r.filledQty(), r.filledCents(), r.dealerBidMills(), r.day()));
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
        save();
    }

    public boolean hasWaiting(Player player) {
        return !floor.available(account(player)).isEmpty() || !receipts.getOrDefault(account(player), List.of()).isEmpty();
    }

    // ------------------------------------------------------------------ persistence

    private void loadTicketsAndReceipts() {
        try {
            Path t = dir.resolve("floor_tickets.txt");
            if (Files.exists(t)) {
                try (Reader r = Files.newBufferedReader(t, StandardCharsets.UTF_8)) {
                    floor.readTickets(r);
                }
            }
            Path rc = dir.resolve("floor_receipts.txt");
            if (Files.exists(rc)) {
                try (BufferedReader br = Files.newBufferedReader(rc, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isBlank() || line.startsWith("#")) continue;
                        String[] c = line.split("\t");
                        receipts.computeIfAbsent(c[0], k -> new ArrayList<>()).add(new TradingFloor.Receipt(c[0], c[1],
                                Side.valueOf(c[2]), Boolean.parseBoolean(c[3]), Long.parseLong(c[4]), Long.parseLong(c[5]),
                                Long.parseLong(c[6]), Long.parseLong(c[7]), Long.parseLong(c[8]),
                                TradingFloor.Ending.valueOf(c[9])));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            RealisticMarkets.LOGGER.error("Could not read Trading Floor orders or receipts", e);
        }
    }

    public void save() {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            StringWriter ex = new StringWriter(), hist = new StringWriter(), tickets = new StringWriter();
            floor.exchange().write(ex);
            floor.history().write(hist);
            floor.writeTickets(tickets);
            StringBuilder rc = new StringBuilder("# account\titem\tside\tmarket\tqty\tfilled\tfilled_cents\tdealer_bid_mills\tday\tending\n");
            for (List<TradingFloor.Receipt> list : receipts.values()) {
                for (TradingFloor.Receipt r : list) {
                    rc.append(r.account()).append('\t').append(r.item()).append('\t').append(r.side()).append('\t')
                            .append(r.market()).append('\t').append(r.qty()).append('\t').append(r.filledQty()).append('\t')
                            .append(r.filledCents()).append('\t').append(r.dealerBidMills()).append('\t').append(r.day())
                            .append('\t').append(r.ending()).append('\n');
                }
            }
            writeAtomic(dir.resolve("floor_exchange.txt"), ex.toString());
            writeAtomic(dir.resolve("floor_history.txt"), hist.toString());
            writeAtomic(dir.resolve("floor_tickets.txt"), tickets.toString());
            writeAtomic(dir.resolve("floor_receipts.txt"), rc.toString());
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save the Trading Floor", e);
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
