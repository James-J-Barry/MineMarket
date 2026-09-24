package com.realisticmarkets.mod.dealer;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.dealer.DealerStateIO;
import com.realisticmarkets.mod.RealisticMarkets;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.item.ItemStack;

/**
 * Owns the world's Dealer and translates Minecraft interactions into Dealer calls.
 *
 * <p>Config lives in {@code config/realisticmarkets/}: {@code dealer_catalog.csv} and
 * {@code dealer_params.properties}, copied from the defaults on first start and re-read by
 * {@code /mkt dealer reload}.
 *
 * <p>Dealer state (inventories, fair-value drift, dev time-shift) is saved to
 * {@code <world>/realisticmarkets/dealer_state.txt} every {@link #SAVE_INTERVAL_TICKS} ticks and when
 * the server stops, so dumping a farm and restarting doesn't reset prices.
 */
public final class DealerService {
    public static final long TICKS_PER_DAY = 24_000L;
    public static final int SAVE_INTERVAL_TICKS = 6_000; // 5 minutes

    private static DealerService instance;

    private final Dealer dealer;
    private final Path configDir;
    private final Path stateFile; // null for test instances
    private double dayOffset; // dev time-shift for testing recovery without waiting
    private int ticksSinceSave;

    private DealerService(Dealer dealer, Path configDir, Path stateFile) {
        this.dealer = dealer;
        this.configDir = configDir;
        this.stateFile = stateFile;
    }

    // ------------------------------------------------------------------ lifecycle

    public static void start(MinecraftServer server) {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(RealisticMarkets.MOD_ID);
        long seed = server.overworld().getSeed();
        Path state = server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID).resolve("dealer_state.txt");
        DealerService svc = new DealerService(new Dealer(DealerCatalog.loadDefault(), DealerParams.defaults(), seed), dir, state);
        String msg = svc.reload();
        String loaded = svc.load();
        instance = svc;
        RealisticMarkets.LOGGER.info("Dealer started: {}; {}", msg, loaded);
    }

    /** Called when the server is stopping: save, then forget the instance. */
    public static void stop() {
        if (instance != null) instance.save();
        instance = null;
    }

    /** Periodic autosave from the server tick. */
    public static void tick(MinecraftServer server) {
        if (instance != null && ++instance.ticksSinceSave >= SAVE_INTERVAL_TICKS) instance.save();
    }

    /** Loads saved state if the world has any. Returns a short status for the log. */
    private String load() {
        if (stateFile == null || Files.notExists(stateFile)) return "no saved dealer state (fresh world)";
        try (Reader r = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
            DealerStateIO.Saved saved = DealerStateIO.read(r);
            dealer.restore(saved.pools());
            dayOffset = saved.dayOffset();
            return "restored " + saved.pools().size() + " price pools";
        } catch (IOException | RuntimeException e) {
            RealisticMarkets.LOGGER.error("Could not read {}; starting with fresh prices", stateFile, e);
            return "saved dealer state unreadable, starting fresh";
        }
    }

    /** Writes state atomically (temp file, then move) so a crash mid-write can't corrupt it. */
    public void save() {
        ticksSinceSave = 0;
        if (stateFile == null) return;
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                DealerStateIO.write(new DealerStateIO.Saved(dealer.snapshot(), dayOffset), w);
            }
            try {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save dealer state to {}", stateFile, e);
        }
    }

    /** Isolated instance with default catalog and no fair-value drift, for GameTests. */
    public static DealerService forTest(long seed) {
        return new DealerService(new Dealer(DealerCatalog.loadDefault(), DealerParams.noDrift(), seed), null, null);
    }

    public static DealerService get() {
        if (instance == null) throw new IllegalStateException("Dealer not started (no server running?)");
        return instance;
    }

    public Dealer dealer() {
        return dealer;
    }

    /** Fractional in-game days since world creation, plus any dev time-shift. */
    public double day(long gameTime) {
        return gameTime / (double) TICKS_PER_DAY + dayOffset;
    }

    public double day(MinecraftServer server) {
        return day(server.overworld().getGameTime());
    }

    public void shiftDays(double days) {
        dayOffset += days;
    }

    /** (Re)loads catalog and params from the config folder, writing defaults if missing. */
    public String reload() {
        if (configDir == null) return "test instance: defaults only";
        try {
            Files.createDirectories(configDir);
            Path catalogFile = ensureDefault("dealer_catalog.csv");
            Path paramsFile = ensureDefault("dealer_params.properties");
            DealerCatalog catalog;
            try (Reader r = Files.newBufferedReader(catalogFile, StandardCharsets.UTF_8)) {
                catalog = DealerCatalog.parseCsv(r);
            }
            Properties props = new Properties();
            try (Reader r = Files.newBufferedReader(paramsFile, StandardCharsets.UTF_8)) {
                props.load(r);
            }
            DealerParams params = DealerParams.fromProperties(props);
            dealer.reload(catalog, params);
            return String.format(Locale.ROOT, "%d markets (%d pools), spread %.0f%%, recovery %.1f days",
                    catalog.all().size(), catalog.basePools().size(), params.spread() * 100, params.recoveryDays());
        } catch (IOException | RuntimeException e) {
            RealisticMarkets.LOGGER.error("Dealer config reload failed; keeping previous values", e);
            return "reload FAILED, kept previous values: " + e.getMessage();
        }
    }

    private Path ensureDefault(String name) throws IOException {
        Path target = configDir.resolve(name);
        if (Files.notExists(target)) {
            try (InputStream in = Dealer.class.getResourceAsStream("/realisticmarkets/" + name)) {
                if (in == null) throw new IOException("missing default resource " + name);
                Files.copy(in, target);
            }
        }
        return target;
    }

    // ------------------------------------------------------------------ selling

    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * Sells the whole stack to the Dealer, emptying it, and returns the payout in cents.
     * The caller hands the cash to the player (see {@link Wallet#give}).
     */
    public long sellStack(ItemStack stack, double day, boolean licensed) {
        Dealer.Quote q = dealer.sell(itemId(stack), stack.getCount(), day, licensed);
        stack.setCount(0);
        return q.cents();
    }
}
