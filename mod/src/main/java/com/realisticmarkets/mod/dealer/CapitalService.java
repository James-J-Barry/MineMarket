package com.realisticmarkets.mod.dealer;

import com.realisticmarkets.dealer.Capital;
import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.dealer.DealerStateIO;
import com.realisticmarkets.dealer.ShipmentBook;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.block.Locations;
import com.realisticmarkets.mod.block.TradeRouteCrateBlockEntity;
import com.realisticmarkets.mod.progression.ProgressionService;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The Capital and every shipment on the road to it. Shipments settle on the server tick once a day has passed;
 * the payout lands in the crate's drawer, or (crate gone or unloaded, drawer full) goes to the owner's inventory
 * the next time they're online, together with the quest event.
 *
 * <p>Saved in {@code <world>/realisticmarkets/}: {@code capital_state.txt} (Capital prices, DealerStateIO format),
 * {@code shipments.txt} (goods in transit) and {@code capital_pending.txt} (arrived, owner offline).
 */
public final class CapitalService {
    private static final int CHECK_TICKS = 20;
    private static final long SEED_SALT = 0x6361706974616CL; // "capital"

    /** A settled shipment whose owner hasn't collected the cash and quest event yet. */
    record Pending(UUID owner, long cents, long localQuoteCents, long payoutCents, long day) {}

    private static CapitalService instance;

    private final Capital.Config cfg;
    private final Dealer capital;
    private ShipmentBook book = new ShipmentBook();
    private final List<Pending> pending = new ArrayList<>();
    private final Path dir; // null for test instances
    private int ticks;

    private CapitalService(Capital.Config cfg, Dealer capital, Path dir) {
        this.cfg = cfg;
        this.capital = capital;
        this.dir = dir;
    }

    public static void start(MinecraftServer server) {
        Dealer local = DealerService.get().dealer();
        Capital.Config cfg = Capital.loadDefault();
        Dealer capital = Capital.dealer(local.catalog(), local.params(), cfg, server.overworld().getSeed() ^ SEED_SALT);
        CapitalService svc = new CapitalService(cfg, capital,
                server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID));
        svc.load();
        instance = svc;
        RealisticMarkets.LOGGER.info("Capital started: {} markets, {} shipment(s) in transit",
                capital.catalog().all().size(), svc.book.inTransit().size());
    }

    public static void stop() {
        if (instance != null) instance.save();
        instance = null;
    }

    public static CapitalService get() {
        if (instance == null) throw new IllegalStateException("Capital not started (no server running?)");
        return instance;
    }

    /** In-memory instance with no fair-value drift, for GameTests. */
    public static CapitalService forTest(DealerService local, long seed) {
        Capital.Config cfg = Capital.loadDefault();
        return new CapitalService(cfg, Capital.dealer(local.dealer().catalog(), DealerParams.noDrift(), cfg, seed), null);
    }

    public Dealer capital() { return capital; }
    public Capital.Config config() { return cfg; }
    public Optional<ShipmentBook.Shipment> inTransitAt(String location) { return book.inTransitAt(location); }

    // ------------------------------------------------------------------ shipping

    /** Cargo merged by item id, skipping empty slots. */
    public static Map<String, Integer> manifest(TradeRouteCrateBlockEntity crate) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int i = 0; i < TradeRouteCrateBlockEntity.CARGO_SLOTS; i++) {
            ItemStack s = crate.cargo().getItem(i);
            if (!s.isEmpty()) items.merge(DealerService.itemId(s), s.getCount(), Integer::sum);
        }
        return items;
    }

    /** What the local Dealer would pay for the goods right now, at this player's spread. */
    public static long localQuote(DealerService local, Map<String, Integer> items, double day, boolean licensed) {
        long cents = 0;
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            if (!local.dealer().catalog().trades(e.getKey())) continue;
            try {
                cents += local.dealer().quoteSell(e.getKey(), e.getValue(), day, licensed).cents();
            } catch (RejectedException collapsed) {
                // worth nothing locally right now
            }
        }
        return cents;
    }

    public long estimate(Map<String, Integer> items, double day) {
        return ShipmentBook.estimate(capital, items, day, cfg.freight());
    }

    /** Ships the crate's cargo. Returns empty on success, else why not (and nothing moves). */
    public Optional<String> ship(Player player, TradeRouteCrateBlockEntity crate, DealerService local, boolean licensed, double day) {
        if (!crate.isOwner(player)) return Optional.of("This crate belongs to " + crate.ownerName());
        Map<String, Integer> items = manifest(crate);
        for (String id : items.keySet()) {
            if (!capital.catalog().trades(id)) return Optional.of("The Capital doesn't buy " + id);
        }
        try {
            book.ship(player.getUUID().toString(), crate.location(), items, localQuote(local, items, day, licensed),
                    day, capital, cfg.transitDays());
        } catch (RejectedException e) {
            return Optional.of(e.getMessage());
        }
        crate.cargo().clearContent();
        crate.cargo().setChanged();
        save();
        return Optional.empty();
    }

    // ------------------------------------------------------------------ settling and delivery

    public static void tick(MinecraftServer server) {
        if (instance == null || ++instance.ticks % CHECK_TICKS != 0) return;
        double day;
        ProgressionService prog;
        try {
            day = DealerService.get().day(server);
            prog = ProgressionService.get();
        } catch (IllegalStateException notRunning) {
            return;
        }
        Function<UUID, Player> online = id -> server.getPlayerList().getPlayer(id);
        instance.settle(location -> Locations.find(server, location, TradeRouteCrateBlockEntity.class), online, prog, day);
        instance.deliverPending(online, prog);
        if (instance.ticks % DealerService.SAVE_INTERVAL_TICKS == 0) instance.save();
    }

    /** Settles every shipment due by {@code day}. Returns how many settled. */
    public int settle(Function<String, TradeRouteCrateBlockEntity> crateAt, Function<UUID, Player> online,
                      ProgressionService prog, double day) {
        List<ShipmentBook.Settlement> done = book.settleDue(capital, day, cfg.freight());
        for (ShipmentBook.Settlement st : done) {
            ShipmentBook.Shipment s = st.shipment();
            UUID owner = UUID.fromString(s.owner());
            TradeRouteCrateBlockEntity crate = crateAt.apply(s.location());
            long left = st.payoutCents();
            if (crate != null && owner.equals(crate.owner())) left = crate.depositCash(left);
            pending.add(new Pending(owner, left, s.localQuoteCents(), st.payoutCents(), (long) Math.floor(day)));
        }
        deliverPending(online, prog);
        if (!done.isEmpty()) save();
        return done.size();
    }

    private void deliverPending(Function<UUID, Player> online, ProgressionService prog) {
        boolean any = false;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            Player player = online.apply(p.owner());
            if (player == null) continue;
            if (p.cents() > 0) Wallet.give(player, p.cents());
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("Shipment sold at the Capital for " + Money.format(p.payoutCents())
                        + " after freight (the local Dealer would have paid " + Money.format(p.localQuoteCents()) + ")."
                        + (p.cents() > 0 ? " " + Money.format(p.cents()) + " went to your inventory." : "")));
            }
            prog.emit(player, new ProgressionEvent.Shipment(p.localQuoteCents(), p.payoutCents(), p.day()));
            it.remove();
            any = true;
        }
        if (any) save();
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        try {
            Path state = dir.resolve("capital_state.txt");
            if (Files.exists(state)) {
                try (Reader r = Files.newBufferedReader(state, StandardCharsets.UTF_8)) {
                    capital.restore(DealerStateIO.read(r).pools());
                }
            }
            Path ships = dir.resolve("shipments.txt");
            if (Files.exists(ships)) {
                try (Reader r = Files.newBufferedReader(ships, StandardCharsets.UTF_8)) {
                    book = ShipmentBook.read(r);
                }
            }
            Path pend = dir.resolve("capital_pending.txt");
            if (Files.exists(pend)) {
                try (BufferedReader r = Files.newBufferedReader(pend, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.isBlank() || line.startsWith("#")) continue;
                        String[] c = line.split("\t");
                        pending.add(new Pending(UUID.fromString(c[0]), Long.parseLong(c[1]), Long.parseLong(c[2]),
                                Long.parseLong(c[3]), Long.parseLong(c[4])));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            RealisticMarkets.LOGGER.error("Could not read Capital state from {}; shipments may be lost", dir, e);
        }
    }

    public void save() {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            StringWriter state = new StringWriter();
            DealerStateIO.write(new DealerStateIO.Saved(capital.snapshot(), 0), state);
            writeAtomic(dir.resolve("capital_state.txt"), state.toString());
            StringWriter ships = new StringWriter();
            book.write(ships);
            writeAtomic(dir.resolve("shipments.txt"), ships.toString());
            StringBuilder pend = new StringBuilder("# owner\tcents_to_give\tlocal_quote\tpayout\tday\n");
            for (Pending p : pending) {
                pend.append(p.owner()).append('\t').append(p.cents()).append('\t').append(p.localQuoteCents())
                        .append('\t').append(p.payoutCents()).append('\t').append(p.day()).append('\n');
            }
            writeAtomic(dir.resolve("capital_pending.txt"), pend.toString());
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save Capital state to {}", dir, e);
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
