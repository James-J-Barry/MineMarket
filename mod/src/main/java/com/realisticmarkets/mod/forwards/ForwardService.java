package com.realisticmarkets.mod.forwards;

import com.realisticmarkets.contracts.ForwardBook;
import com.realisticmarkets.contracts.ForwardBook.Forward;
import com.realisticmarkets.exchange.RejectedException;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Forward Contracts with the Dealer, signed and delivered at the Basic Exchange. The contracts live on the account
 * ({@link ForwardBook}, saved to {@code forwards.txt}); the Forward Contract paper is the printed statement. At each
 * dawn, forwards past their delivery window default and their deposits are forfeit.
 */
public final class ForwardService {
    public static final String PERK = "forwards";

    private static ForwardService instance;

    private final ForwardBook book;
    private final DealerService dealer;
    private final Path file; // null in tests
    private long lastDawn = Long.MIN_VALUE;
    private int ticks;

    private ForwardService(ForwardBook book, DealerService dealer, Path file) {
        this.book = book;
        this.dealer = dealer;
        this.file = file;
    }

    public static void start(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID).resolve("forwards.txt");
        ForwardBook book = new ForwardBook();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                book = ForwardBook.read(r);
            } catch (IOException | RuntimeException e) {
                RealisticMarkets.LOGGER.error("Could not read the forwards {}", file, e);
            }
        }
        instance = new ForwardService(book, DealerService.get(), file);
    }

    public static void stop() {
        if (instance != null) instance.save();
        instance = null;
    }

    public static ForwardService get() {
        if (instance == null) throw new IllegalStateException("Forwards not started (no server running?)");
        return instance;
    }

    public static ForwardService getOrNull() {
        return instance;
    }

    public static ForwardService forTest(DealerService dealer) {
        return new ForwardService(new ForwardBook(), dealer, null);
    }

    public ForwardBook book() { return book; }

    public static String account(Player p) {
        return p.getUUID().toString();
    }

    public List<Forward> open(Player p) {
        return book.open(account(p));
    }

    // ------------------------------------------------------------------ signing and delivering

    /** Signs a forward: takes the deposit in bills and a Security Paper, prints the paper. Returns why not, or empty. */
    public Optional<String> sign(Player player, String item, long quantity, int termDays, double day, boolean licensed) {
        long today = (long) Math.floor(day);
        Optional<String> why = ForwardBook.check(dealer.dealer(), item, quantity, termDays);
        if (why.isPresent()) return why;
        long price;
        try {
            price = book.quote(dealer.dealer(), item, quantity, termDays, today, licensed).cents();
        } catch (RejectedException e) {
            return Optional.of(e.getMessage());
        }
        long deposit = ForwardBook.deposit(price);
        Inventory inv = player.getInventory();
        if (inv.countItem(ModItems.SECURITY_PAPER) < 1) return Optional.of("A forward needs 1 Security Paper");
        if (Wallet.count(inv) < deposit) return Optional.of("The deposit is " + Money.format(deposit));
        Forward f = book.sign(account(player), dealer.dealer(), item, quantity, termDays, today, licensed);
        Wallet.pay(player, f.depositCents());
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(ModItems.SECURITY_PAPER)) {
                inv.getItem(i).shrink(1);
                break;
            }
        }
        inv.placeItemBackInInventory(paper(f));
        save();
        return Optional.empty();
    }

    /**
     * Delivers forward {@code id} from the goods the player carries: pays the agreed price and the deposit back.
     * Returns why not, or empty.
     */
    public Optional<String> deliver(Player player, long id, double day, boolean licensed, ProgressionService prog) {
        Optional<Forward> of = book.get(id).filter(f -> f.account().equals(account(player)));
        if (of.isEmpty()) return Optional.of("No such forward");
        Forward f = of.get();
        long today = (long) Math.floor(day);
        if (!f.dueOn(today)) return Optional.of("Not due until day " + f.deliveryDay());
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(f.item()));
        Inventory inv = player.getInventory();
        int have = inv.countItem(item);
        if (have < f.quantity()) return Optional.of("Bring " + f.quantity() + " (you carry " + have + ")");
        long spot;
        try {
            spot = dealer.dealer().quoteSell(f.item(), f.quantity(), day, licensed).cents();
        } catch (RejectedException collapsed) {
            spot = 0;
        }
        long left = f.quantity();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(item) || s.has(DataComponents.CUSTOM_DATA)) continue;
            int n = (int) Math.min(left, s.getCount());
            s.shrink(n);
            left -= n;
        }
        long pay = book.deliver(id, dealer.dealer(), day);
        Wallet.give(player, pay);
        voidPaper(inv, id);
        save();
        if (prog != null) prog.emit(player, new ProgressionEvent.ForwardDelivered(f.item(), f.quantity(), f.priceCents(), spot, today));
        return Optional.empty();
    }

    /** At dawn: forwards past their window default. Tells their owners (and their quests and ledgers) if online. */
    public List<Forward> dawn(long day, Function<UUID, Player> online, ProgressionService prog) {
        List<Forward> gone = book.dawn(day);
        for (Forward f : gone) {
            Player p = online.apply(UUID.fromString(f.account()));
            if (p == null) continue;
            if (p instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("Forward not delivered: " + f.quantity() + " " + name(f.item())
                        + " was due by day " + (f.deliveryDay() + 1) + ". Your " + Money.format(f.depositCents())
                        + " deposit is forfeit.").withStyle(ChatFormatting.RED));
            }
            com.realisticmarkets.mod.fx.Feedback.at(p, com.realisticmarkets.mod.fx.Feedback.Cue.LOSS);
            if (prog != null) prog.emit(p, new ProgressionEvent.ForwardDefaulted(f.item(), f.depositCents(), day));
            voidPaper(p.getInventory(), f.id());
        }
        if (!gone.isEmpty()) save();
        return gone;
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
    }

    // ------------------------------------------------------------------ the paper

    static String name(String itemId) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        return new ItemStack(item).getHoverName().getString();
    }

    public static ItemStack paper(Forward f) {
        ItemStack s = new ItemStack(ModItems.FORWARD_CONTRACT);
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Forward: " + f.quantity() + " " + name(f.item()) + ", day " + f.deliveryDay())
                .withStyle(st -> st.withItalic(false)));
        s.set(DataComponents.LORE, new ItemLore(List.of(
                line("Deliver " + f.quantity() + " " + name(f.item()) + " at a Basic Exchange", ChatFormatting.DARK_AQUA),
                line("on day " + f.deliveryDay() + " or " + (f.deliveryDay() + 1) + " for " + Money.format(f.priceCents()), ChatFormatting.GRAY),
                line("Deposit held: " + Money.format(f.depositCents()), ChatFormatting.GRAY),
                line("Statement: the contract is on your account", ChatFormatting.DARK_GRAY))));
        CompoundTag tag = new CompoundTag();
        tag.putLong("forward_id", f.id());
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    public static long paperId(ItemStack s) {
        if (!s.is(ModItems.FORWARD_CONTRACT)) return -1;
        return s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getLongOr("forward_id", -1);
    }

    /** Marks the statement for a closed forward as settled (so an old paper can't be mistaken for a live one). */
    private static void voidPaper(Inventory inv, long id) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (paperId(s) != id) continue;
            ItemLore lore = s.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
            List<Component> lines = new java.util.ArrayList<>(lore.lines());
            lines.add(0, line("SETTLED", ChatFormatting.DARK_RED));
            s.set(DataComponents.LORE, new ItemLore(lines));
            s.remove(DataComponents.CUSTOM_DATA);
        }
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
                book.write(w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save the forwards {}", file, e);
        }
    }
}
