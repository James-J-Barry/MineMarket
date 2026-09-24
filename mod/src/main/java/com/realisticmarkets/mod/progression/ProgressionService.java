package com.realisticmarkets.mod.progression;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.MarketSpec;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.progression.Blueprints;
import com.realisticmarkets.progression.Guides;
import com.realisticmarkets.progression.PlayerProgress;
import com.realisticmarkets.progression.ProgressStateIO;
import com.realisticmarkets.progression.ProgressionEvent;
import com.realisticmarkets.progression.Quest;
import com.realisticmarkets.progression.Quests;
import com.realisticmarkets.progression.UnlockNode;
import com.realisticmarkets.progression.UnlockTree;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Per-account Almanac progress: node purchases, quest events and rewards. Each player's
 * {@link PlayerProgress} is saved to {@code <world>/realisticmarkets/players/<uuid>.txt} after every change.
 */
public final class ProgressionService {
    public static final String COMPONENTS_GROUP = "components";
    public static final String MERCHANT_LICENSE = "merchant_license";

    private static ProgressionService instance;

    private final UnlockTree tree = UnlockTree.loadDefault();
    private final Quests quests = Quests.loadDefault();
    private final Blueprints blueprints = Blueprints.loadDefault();
    private final Guides guides = Guides.loadDefault();
    private final Path dir; // null for test instances
    private final Map<UUID, PlayerProgress> players = new HashMap<>();

    private ProgressionService(Path dir) {
        this.dir = dir;
        blueprints.validateAgainst(tree);
    }

    public static void start(MinecraftServer server) {
        instance = new ProgressionService(server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID).resolve("players"));
    }

    public static void stop() {
        if (instance != null) instance.players.keySet().forEach(instance::save);
        instance = null;
    }

    public static ProgressionService get() {
        if (instance == null) throw new IllegalStateException("Progression not started (no server running?)");
        return instance;
    }

    /** In-memory instance for GameTests. */
    public static ProgressionService forTest() {
        return new ProgressionService(null);
    }

    /** Instance saving to {@code dir}, for persistence GameTests. */
    public static ProgressionService forTest(Path dir) {
        return new ProgressionService(dir);
    }

    public UnlockTree tree() { return tree; }
    public Quests quests() { return quests; }
    public Blueprints blueprints() { return blueprints; }
    public Guides guides() { return guides; }

    public PlayerProgress progress(Player player) {
        return players.computeIfAbsent(player.getUUID(), this::load);
    }

    // ------------------------------------------------------------------ actions

    /** Buys a node with the player's bills. Returns empty on success, else why not. */
    public Optional<String> buyNode(Player player, String nodeId) {
        UnlockNode node = tree.node(nodeId);
        PlayerProgress p = progress(player);
        long cash = Wallet.count(player.getInventory());
        Optional<String> why = p.whyCannotBuy(node, tree, cash);
        if (why.isPresent()) return why;
        if (!Wallet.pay(player, node.costCents())) return Optional.of("Not enough cash");
        p.buy(node, tree, cash);
        save(player.getUUID());
        message(player, "Unlocked " + node.title() + " (-" + Money.format(node.costCents()) + ")");
        return Optional.empty();
    }

    /** Feeds an event to the player's quests, paying cash rewards into their inventory. */
    public List<Quest> emit(Player player, ProgressionEvent event) {
        List<Quest> done = progress(player).apply(event, quests);
        for (Quest q : done) {
            if (q.rewardCents() > 0) Wallet.give(player, q.rewardCents());
            message(player, "Quest complete: " + q.title()
                    + (q.rewardCents() > 0 ? " (+" + Money.format(q.rewardCents()) + ")" : ""));
        }
        save(player.getUUID());
        return done;
    }

    /** Emits the player's cash and net worth (cash + goods at what the Dealer would pay now). */
    public void emitNetWorth(Player player, DealerService dealer, double day, long extraCashCents) {
        Inventory inv = player.getInventory();
        long cash = Wallet.count(inv) + extraCashCents;
        long goods = 0;
        Dealer d = dealer.dealer();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || ModItems.denominationOf(s) != null) continue;
            String id = DealerService.itemId(s);
            if (!d.catalog().trades(id)) continue;
            try {
                goods += d.quoteSell(id, s.getCount(), day, licensed(player)).cents();
            } catch (RejectedException e) {
                // collapsed price: worth nothing right now
            }
        }
        emit(player, new ProgressionEvent.NetWorth(cash, cash + goods, (long) Math.floor(day)));
    }

    /** Gives the player a written-book copy of an unlocked guide. Free: the guide stays in the Almanac. */
    public boolean tearOutGuide(Player player, String guideId) {
        if (!guides.has(guideId) || !progress(player).hasGuide(guideId)) return false;
        player.getInventory().placeItemBackInInventory(guideBook(guides.guide(guideId)));
        return true;
    }

    /** Vanilla pages hold about 14 short lines; 200 characters leaves room for the list line breaks. */
    public static final int BOOK_PAGE_CHARS = 200;

    public static ItemStack guideBook(Guides.Guide guide) {
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String page : guide.pages(BOOK_PAGE_CHARS)) pages.add(Filterable.passThrough(Component.literal(page)));
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough(guide.title()), "Market Almanac", 0, pages, true));
        return book;
    }

    public boolean licensed(Player player) {
        return progress(player).hasPerk(MERCHANT_LICENSE);
    }

    /** Component items this player may see in the Buy tab. */
    public Set<String> visibleComponents(Player player, DealerCatalog catalog) {
        Set<String> components = new LinkedHashSet<>();
        for (MarketSpec s : catalog.all().values()) if (COMPONENTS_GROUP.equals(s.group())) components.add(s.itemId());
        return blueprints.componentsVisibleTo(progress(player), components);
    }

    private static void message(Player player, String text) {
        if (player instanceof ServerPlayer sp) sp.sendSystemMessage(Component.literal(text));
    }

    // ------------------------------------------------------------------ persistence

    private Path file(UUID id) {
        return dir.resolve(id + ".txt");
    }

    private PlayerProgress load(UUID id) {
        if (dir == null || Files.notExists(file(id))) return new PlayerProgress();
        try (Reader r = Files.newBufferedReader(file(id), StandardCharsets.UTF_8)) {
            return ProgressStateIO.read(r);
        } catch (IOException | RuntimeException e) {
            // Keep the unreadable file aside rather than overwriting a player's unlocks with nothing.
            RealisticMarkets.LOGGER.error("Could not read {}; moving it aside", file(id), e);
            try {
                Files.move(file(id), file(id).resolveSibling(id + ".broken-" + System.currentTimeMillis()));
            } catch (IOException ignored) {
                // best effort
            }
            return new PlayerProgress();
        }
    }

    private void save(UUID id) {
        PlayerProgress p = players.get(id);
        if (dir == null || p == null) return;
        Path target = file(id);
        try {
            Files.createDirectories(dir);
            Path tmp = target.resolveSibling(id + ".txt.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                ProgressStateIO.write(p, w);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save progress to {}", target, e);
        }
    }
}
