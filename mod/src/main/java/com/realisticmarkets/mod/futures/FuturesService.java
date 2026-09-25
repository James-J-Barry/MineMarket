package com.realisticmarkets.mod.futures;

import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.futures.ClearingHouse;
import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Money;
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
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The Clearing House in the world: margin accounts in cash, futures trades, and the dawn mark with its margin calls
 * and close-outs ({@link ClearingHouse}), saved to {@code futures.txt}. A margin call arrives as a chat message and a
 * Margin Call Notice paper.
 */
public final class FuturesService {
    private static FuturesService instance;

    private final ClearingHouse house;
    private final DealerService dealer;
    private final Path file; // null in tests
    private long lastDawn = Long.MIN_VALUE;
    private int ticks;

    private FuturesService(ClearingHouse house, DealerService dealer, Path file) {
        this.house = house;
        this.dealer = dealer;
        this.file = file;
    }

    public static void start(MinecraftServer server) {
        DealerService dealer = DealerService.get();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID).resolve("futures.txt");
        ClearingHouse house = new ClearingHouse(dealer.dealer());
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                house = ClearingHouse.read(r, dealer.dealer());
            } catch (IOException | RuntimeException e) {
                RealisticMarkets.LOGGER.error("Could not read the Clearing House {}", file, e);
            }
        }
        instance = new FuturesService(house, dealer, file);
    }

    public static void stop() {
        if (instance != null) instance.save();
        instance = null;
    }

    public static FuturesService get() {
        if (instance == null) throw new IllegalStateException("Clearing House not started (no server running?)");
        return instance;
    }

    public static FuturesService getOrNull() {
        return instance;
    }

    public static FuturesService forTest(DealerService dealer) {
        return new FuturesService(new ClearingHouse(dealer.dealer()), dealer, null);
    }

    public ClearingHouse house() { return house; }

    public static String account(Player p) {
        return p.getUUID().toString();
    }

    // ------------------------------------------------------------------ cash

    /** Moves every bill the player carries into their margin account. Returns cents deposited. */
    public long depositAll(Player player, double day, ProgressionService prog) {
        long cents = Wallet.takeAll(player);
        if (cents <= 0) return 0;
        house.deposit(account(player), cents);
        afterChange(player, day, prog);
        save();
        return cents;
    }

    /** Pays out what isn't needed as margin, in bills (down to the dime). Returns cents withdrawn. */
    public long withdrawFree(Player player, double day) {
        long free = Money.roundDownToDime(house.free(account(player), day));
        if (free <= 0) return 0;
        house.withdraw(account(player), free, day);
        Wallet.give(player, free);
        save();
        return free;
    }

    // ------------------------------------------------------------------ trading

    /** Buys ({@code lots} > 0) or sells futures. Returns why not, or empty. */
    public Optional<String> trade(Player player, String code, long expiry, long lots, double day, ProgressionService prog) {
        ClearingHouse.Trade t;
        try {
            t = house.trade(account(player), code, expiry, lots, day);
        } catch (RejectedException e) {
            return Optional.of(e.getMessage());
        }
        if (prog != null && t.realizedCents() != 0) {
            prog.emit(player, new ProgressionEvent.FuturesClosed(code, lots, t.realizedCents(), (long) Math.floor(day)));
        }
        afterChange(player, day, prog);
        save();
        return Optional.empty();
    }

    private void afterChange(Player player, double day, ProgressionService prog) {
        if (house.callMet(account(player), day)) {
            if (player instanceof ServerPlayer sp) sp.sendSystemMessage(Component.literal("Margin call met.").withStyle(ChatFormatting.GREEN));
            if (prog != null) prog.emit(player, new ProgressionEvent.MarginCallMet((long) Math.floor(day)));
        }
    }

    // ------------------------------------------------------------------ dawn

    /** The dawn mark: variation margin, expiries, margin calls and close-outs; tells online players. */
    public List<ClearingHouse.Dawn> dawn(long day, Function<UUID, Player> online, ProgressionService prog) {
        List<ClearingHouse.Dawn> out = house.dawn(day);
        for (ClearingHouse.Dawn d : out) {
            Player p = online.apply(UUID.fromString(d.account()));
            if (p == null) continue;
            long moved = d.variationCents() + d.expiredCents();
            if (prog != null && moved != 0) prog.emit(p, new ProgressionEvent.FuturesMarked(moved, day));
            if (prog != null && d.callMet()) prog.emit(p, new ProgressionEvent.MarginCallMet(day));
            if (!(p instanceof ServerPlayer sp)) continue;
            com.realisticmarkets.mod.fx.Feedback.at(p, d.called() ? com.realisticmarkets.mod.fx.Feedback.Cue.ALARM
                    : d.closedOut() || moved < 0 ? com.realisticmarkets.mod.fx.Feedback.Cue.LOSS
                    : moved > 0 ? com.realisticmarkets.mod.fx.Feedback.Cue.GAIN : null);
            if (moved != 0) {
                sp.sendSystemMessage(Component.literal("Clearing House mark: " + (moved > 0 ? "+" : "-") + Money.format(Math.abs(moved))
                        + " to your futures account").withStyle(moved > 0 ? ChatFormatting.GREEN : ChatFormatting.GOLD));
            }
            if (d.called()) {
                long need = Math.max(0, d.requiredCents() - d.equityCents());
                sp.sendSystemMessage(Component.literal("MARGIN CALL: add " + Money.format(need) + " to your futures account by dawn of day "
                        + (day + 1) + ", or every position will be closed.").withStyle(ChatFormatting.RED));
                p.getInventory().placeItemBackInInventory(notice(day, need));
            }
            if (d.closedOut()) {
                sp.sendSystemMessage(Component.literal("Margin call not met: the Clearing House closed your futures at this morning's price.")
                        .withStyle(ChatFormatting.RED));
            }
        }
        if (!out.isEmpty()) save();
        return out;
    }

    public static void tick(MinecraftServer server) {
        if (instance == null || ++instance.ticks % 100 != 0) return;
        long day = (long) Math.floor(instance.dealer.day(server));
        if (instance.lastDawn == Long.MIN_VALUE) instance.lastDawn = day;
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
        if (instance.ticks % DealerService.SAVE_INTERVAL_TICKS == 0) instance.save();
    }

    /** The Margin Call Notice: the call's amount and deadline, on paper. */
    public static ItemStack notice(long day, long needCents) {
        ItemStack s = new ItemStack(ModItems.MARGIN_CALL_NOTICE);
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Margin Call, day " + day).withStyle(st -> st.withItalic(false)
                .withColor(ChatFormatting.RED)));
        s.set(DataComponents.LORE, new ItemLore(List.of(
                line("Add " + Money.format(needCents) + " to your futures account", ChatFormatting.GRAY),
                line("at a Clearing House by dawn of day " + (day + 1) + ",", ChatFormatting.GRAY),
                line("or every position will be closed.", ChatFormatting.GRAY))));
        return s;
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(st -> st.withColor(color).withItalic(false));
    }

    // ------------------------------------------------------------------ persistence

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                house.write(w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save the Clearing House {}", file, e);
        }
    }
}
