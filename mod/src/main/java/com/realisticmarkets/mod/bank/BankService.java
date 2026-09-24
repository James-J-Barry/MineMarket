package com.realisticmarkets.mod.bank;

import com.realisticmarkets.contracts.BankAccount;
import com.realisticmarkets.contracts.BankParams;
import com.realisticmarkets.contracts.Cd;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.progression.ProgressionEvent;
import com.realisticmarkets.registry.SecurityRegistry;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Bank accounts (one per player, reachable only at their Bank Vault) and the security registry. Accounts are
 * saved to {@code players/<uuid>.bank.txt}, the registry to {@code registry.txt}, after every change.
 */
public final class BankService {
    public static final String CD_PERK = "certificate_of_deposit";
    private static final int CHECK_TICKS = 200;

    private static BankService instance;

    private final BankParams params = BankParams.loadDefault();
    private final Path dir; // <world>/realisticmarkets, or a temp dir / null in tests
    private final Map<UUID, BankAccount> accounts = new HashMap<>();
    private SecurityRegistry registry = new SecurityRegistry();
    private int ticks;

    private BankService(Path dir) {
        this.dir = dir;
        if (dir != null) loadRegistry();
    }

    public static void start(MinecraftServer server) {
        instance = new BankService(server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID));
    }

    public static void stop() {
        if (instance != null) instance.saveAll();
        instance = null;
    }

    public static BankService get() {
        if (instance == null) throw new IllegalStateException("Bank not started (no server running?)");
        return instance;
    }

    /** Test instance saving under {@code dir} (null: memory only). */
    public static BankService forTest(Path dir) {
        return new BankService(dir);
    }

    public BankParams params() { return params; }
    public SecurityRegistry registry() { return registry; }

    public static long day(MinecraftServer server) {
        return (long) Math.floor(DealerService.get().day(server));
    }

    public boolean hasAccount(UUID id) {
        return accounts.containsKey(id) || (dir != null && Files.exists(accountFile(id)));
    }

    public BankAccount account(UUID id, long day) {
        return accounts.computeIfAbsent(id, u -> load(u, day));
    }

    // ------------------------------------------------------------------ vault placement

    /**
     * Records {@code location} as the player's vault unless another of theirs still stands. {@code stillStands}
     * answers for the previously recorded location.
     */
    public boolean claimVault(UUID owner, String location, long day, Predicate<String> stillStands) {
        BankAccount a = account(owner, day);
        String old = a.vaultLocation();
        if (old != null && !old.equals(location) && stillStands.test(old)) return false;
        a.setVaultLocation(location);
        save(owner);
        return true;
    }

    public void releaseVault(UUID owner, String location, long day) {
        if (owner == null || !hasAccount(owner)) return;
        BankAccount a = account(owner, day);
        if (location.equals(a.vaultLocation())) {
            a.setVaultLocation(null);
            save(owner);
        }
    }

    /** The vault may be broken only by its owner, and only when their account holds nothing. */
    public boolean mayBreakVault(UUID owner, Player breaker, long day) {
        if (owner == null) return true;
        if (!owner.equals(breaker.getUUID())) return false;
        return !hasAccount(owner) || account(owner, day).isEmpty();
    }

    // ------------------------------------------------------------------ money in and out

    /** Credits any interest due, telling the quests. Returns cents credited. */
    public long accrue(Player player, long day, ProgressionService prog) {
        BankAccount a = account(player.getUUID(), day);
        long credited = a.accrueTo(day, params.interestRate());
        if (credited > 0) {
            save(player.getUUID());
            if (prog != null) prog.emit(player, new ProgressionEvent.Interest(credited, a.interestTotalCents(), day));
        }
        return credited;
    }

    /** Deposits every bill and coin the player carries (Bill Clips included). Returns cents deposited. */
    public long depositAll(Player player, long day, ProgressionService prog) {
        accrue(player, day, prog);
        long cents = Wallet.takeAll(player);
        if (cents == 0) return 0;
        account(player.getUUID(), day).deposit(cents, day, BankAccount.Kind.DEPOSIT);
        save(player.getUUID());
        return cents;
    }

    /** Withdraws as bills (into Bill Clips first). {@code cents < 0} means everything. */
    public Optional<String> withdraw(Player player, long cents, long day, ProgressionService prog) {
        accrue(player, day, prog);
        BankAccount a = account(player.getUUID(), day);
        long amount = cents < 0 ? a.balanceCents() : cents;
        if (amount <= 0) return Optional.of("Nothing to withdraw");
        try {
            a.withdraw(amount, day, BankAccount.Kind.WITHDRAW);
        } catch (RejectedException e) {
            return Optional.of(e.getMessage());
        }
        Wallet.give(player, amount);
        save(player.getUUID());
        return Optional.empty();
    }

    // ------------------------------------------------------------------ certificates of deposit

    public Optional<String> issueCd(Player player, long principalCents, int termDays, long day, ProgressionService prog) {
        if (!prog.progress(player).hasPerk(CD_PERK)) return Optional.of("Unlock Certificates of Deposit at the Almanac");
        Inventory inv = player.getInventory();
        if (inv.countItem(ModItems.SECURITY_PAPER) < 1) return Optional.of("A CD needs 1 Security Paper");
        accrue(player, day, prog);
        BankAccount a = account(player.getUUID(), day);
        Cd cd;
        try {
            cd = Cd.issue(principalCents, params.term(termDays), day, params);
            a.withdraw(principalCents, day, BankAccount.Kind.CD_ISSUE);
        } catch (RejectedException | IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
        removeOne(inv, ModItems.SECURITY_PAPER);
        SecurityRegistry.Security sec = registry.issue("CD", day, cd.terms());
        inv.placeItemBackInInventory(CdItem.create(sec.serial(), cd));
        save(player.getUUID());
        saveRegistry();
        return Optional.empty();
    }

    /**
     * Redeems the CD in {@code stack} into the player's account (principal only if early). A paper whose serial is
     * unknown or already settled is marked VOID and pays nothing.
     */
    public Optional<String> redeemCd(Player player, ItemStack stack, long day, ProgressionService prog) {
        Optional<UUID> serial = CdItem.serial(stack);
        Optional<SecurityRegistry.Security> sec = serial.flatMap(registry::lookup);
        if (sec.isEmpty() || !"CD".equals(sec.get().type())
                || registry.settle(sec.get().serial()) != SecurityRegistry.SettleResult.OK) {
            CdItem.markVoid(stack);
            saveRegistry();
            return Optional.of("VOID: this certificate was already redeemed or isn't genuine");
        }
        Cd cd = Cd.fromTerms(sec.get().terms());
        long value = cd.redemptionValueCents(day);
        accrue(player, day, prog);
        account(player.getUUID(), day).deposit(value, day, BankAccount.Kind.CD_REDEEM);
        stack.shrink(1);
        save(player.getUUID());
        saveRegistry();
        if (prog != null) prog.emit(player, new ProgressionEvent.CdRedeemed(cd.principalCents(), value, cd.maturedBy(day), day));
        return Optional.empty();
    }

    /** Current redemption value of a genuine, outstanding CD, or -1. */
    public long cdValue(ItemStack stack, long day) {
        return CdItem.serial(stack).flatMap(registry::lookup)
                .filter(s -> s.status() == SecurityRegistry.Status.ISSUED && "CD".equals(s.type()))
                .map(s -> Cd.fromTerms(s.terms()).redemptionValueCents(day))
                .orElse(-1L);
    }

    // ------------------------------------------------------------------ passbooks

    /** First Passbook free; later ones cost 1 Ledger Paper. */
    public Optional<String> printPassbook(Player player, long day) {
        BankAccount a = account(player.getUUID(), day);
        Inventory inv = player.getInventory();
        if (a.passbooksIssued() > 0) {
            if (inv.countItem(ModItems.LEDGER_PAPER) < 1) return Optional.of("A replacement Passbook needs 1 Ledger Paper");
            removeOne(inv, ModItems.LEDGER_PAPER);
        }
        a.passbookIssued();
        ItemStack book = new ItemStack(ModItems.PASSBOOK);
        PassbookItem.write(book, player.getName().getString(), a, day, params);
        inv.placeItemBackInInventory(book);
        save(player.getUUID());
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

    // ------------------------------------------------------------------ ticking

    /** Credits daily interest to online account holders, so Nest Egg completes without a vault visit. */
    public static void tick(MinecraftServer server) {
        if (instance == null || ++instance.ticks % CHECK_TICKS != 0) return;
        long day;
        ProgressionService prog;
        try {
            day = day(server);
            prog = ProgressionService.get();
        } catch (IllegalStateException notRunning) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (instance.hasAccount(p.getUUID())) instance.accrue(p, day, prog);
        }
    }

    // ------------------------------------------------------------------ persistence

    private Path accountFile(UUID id) {
        return dir.resolve("players").resolve(id + ".bank.txt");
    }

    private BankAccount load(UUID id, long day) {
        if (dir == null || Files.notExists(accountFile(id))) return new BankAccount(day);
        try (Reader r = Files.newBufferedReader(accountFile(id), StandardCharsets.UTF_8)) {
            return BankAccount.read(r);
        } catch (IOException | RuntimeException e) {
            // Keep the unreadable file (the player's savings) aside for recovery rather than overwrite it.
            RealisticMarkets.LOGGER.error("Could not read {}; moving it aside and opening an empty account", accountFile(id), e);
            moveAside(accountFile(id));
            return new BankAccount(day);
        }
    }

    private void loadRegistry() {
        Path f = dir.resolve("registry.txt");
        if (Files.notExists(f)) return;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            registry = SecurityRegistry.read(r);
        } catch (IOException | RuntimeException e) {
            RealisticMarkets.LOGGER.error("Could not read {}; moving it aside (outstanding CDs will read as VOID)", f, e);
            moveAside(f);
        }
    }

    private static void moveAside(Path f) {
        try {
            Files.move(f, f.resolveSibling(f.getFileName() + ".broken-" + System.currentTimeMillis()));
        } catch (IOException ignored) {
            // best effort
        }
    }

    private void save(UUID id) {
        BankAccount a = accounts.get(id);
        if (dir == null || a == null) return;
        try {
            StringWriter w = new StringWriter();
            a.write(w);
            writeAtomic(accountFile(id), w.toString());
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save bank account {}", id, e);
        }
    }

    private void saveRegistry() {
        if (dir == null) return;
        try {
            StringWriter w = new StringWriter();
            registry.write(w);
            writeAtomic(dir.resolve("registry.txt"), w.toString());
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save the security registry", e);
        }
    }

    private void saveAll() {
        accounts.keySet().forEach(this::save);
        saveRegistry();
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
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
