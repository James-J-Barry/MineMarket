package com.realisticmarkets.mod.bank;

import com.realisticmarkets.collateral.Loan;
import com.realisticmarkets.collateral.MarginCheck;
import com.realisticmarkets.contracts.BankAccount;
import com.realisticmarkets.contracts.BankParams;
import com.realisticmarkets.contracts.Cd;
import com.realisticmarkets.rates.CentralBank;
import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Denomination;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
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
    public static final String LOAN_PERK = "loans";
    private static final int CHECK_TICKS = 200;

    private static BankService instance;

    private final BankParams params = BankParams.loadDefault();
    private final Path dir; // <world>/realisticmarkets, or a temp dir / null in tests
    private final Map<UUID, BankAccount> accounts = new HashMap<>();
    private final Map<UUID, Loan> loans = new HashMap<>(); // every open loan, loaded at start so dawn checks reach offline players
    private long lastDawn = Long.MIN_VALUE;
    private SecurityRegistry registry = new SecurityRegistry();
    private int ticks;

    private final CentralBank central;

    private BankService(Path dir, CentralBank central) {
        this.dir = dir;
        this.central = central;
        if (dir != null) {
            loadRegistry();
            loadLoans();
        }
    }

    public static void start(MinecraftServer server) {
        instance = new BankService(server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID),
                new CentralBank(server.overworld().getSeed() ^ 0x52617465L)); // "Rate"
        ProgressionService.get().useBank(instance.funds(() -> day(server)));
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
        return new BankService(dir, CentralBank.constant(CentralBank.START));
    }

    /** Test instance with a central bank of the test's choosing. */
    public static BankService forTest(Path dir, CentralBank central) {
        return new BankService(dir, central);
    }

    public CentralBank centralBank() { return central; }

    /** What the vault pays a day on {@code day}: the central bank's rate. */
    public double vaultRate(long day) {
        return central.rate(day);
    }

    /** How far bank rates (new CDs, loans) have moved from the configured ones, on {@code day}. */
    public double rateShift(long day) {
        return params.shift(central.rate(day));
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
        if (loans.containsKey(owner)) return false;
        return !hasAccount(owner) || account(owner, day).isEmpty();
    }

    // ------------------------------------------------------------------ money in and out

    /** Credits any interest due, telling the quests. Returns cents credited. */
    public long accrue(Player player, long day, ProgressionService prog) {
        BankAccount a = account(player.getUUID(), day);
        long credited = a.accrueTo(day, d -> central.rate(d));
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

    /** The Almanac's view of this bank: balances it may draw on for big unlocks. */
    public ProgressionService.BankFunds funds(java.util.function.LongSupplier day) {
        return new ProgressionService.BankFunds() {
            @Override
            public long balance(Player player) {
                return hasAccount(player.getUUID()) ? account(player.getUUID(), day.getAsLong()).balanceCents() : 0;
            }

            @Override
            public void take(Player player, long cents) {
                long d = day.getAsLong();
                account(player.getUUID(), d).withdraw(cents, d, BankAccount.Kind.WITHDRAW);
                save(player.getUUID());
            }
        };
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
            cd = Cd.issue(principalCents, params.term(termDays, central.rate(day)), day, params);
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

    /** The terms of a genuine, outstanding CD. */
    public Optional<Cd> cd(ItemStack stack) {
        return CdItem.serial(stack).flatMap(registry::lookup)
                .filter(s -> s.status() == SecurityRegistry.Status.ISSUED && "CD".equals(s.type()))
                .map(s -> Cd.fromTerms(s.terms()));
    }

    /** Current redemption value of a genuine, outstanding CD, or -1. */
    public long cdValue(ItemStack stack, long day) {
        return CdItem.serial(stack).flatMap(registry::lookup)
                .filter(s -> s.status() == SecurityRegistry.Status.ISSUED && "CD".equals(s.type()))
                .map(s -> Cd.fromTerms(s.terms()).redemptionValueCents(day))
                .orElse(-1L);
    }

    // ------------------------------------------------------------------ loans

    public Optional<Loan> loan(UUID owner) {
        return Optional.ofNullable(loans.get(owner));
    }

    /**
     * Opens a loan against the items in {@code collateralSlots} (bills there count as cash collateral), paying the
     * principal into the account. Costs 1 Security Paper; issues a Loan Note statement.
     */
    public Optional<String> openLoan(Player player, Container collateralSlots, long principalCents, long day,
                                     Dealer dealer, ProgressionService prog) {
        if (!prog.progress(player).hasPerk(LOAN_PERK)) return Optional.of("Unlock the Loan Note at the Almanac");
        if (loans.containsKey(player.getUUID())) return Optional.of("You already have a loan: repay it first");
        Inventory inv = player.getInventory();
        if (inv.countItem(ModItems.SECURITY_PAPER) < 1) return Optional.of("A Loan Note needs 1 Security Paper");
        Map<String, Integer> items = new LinkedHashMap<>();
        long cash = 0;
        for (int i = 0; i < collateralSlots.getContainerSize(); i++) {
            ItemStack s = collateralSlots.getItem(i);
            if (s.isEmpty()) continue;
            Denomination d = ModItems.denominationOf(s);
            if (d != null) cash += d.cents() * s.getCount();
            else items.merge(DealerService.itemId(s), s.getCount(), Integer::sum);
        }
        if (items.isEmpty() && cash == 0) return Optional.of("Put collateral in the slots");
        Loan loan;
        try {
            loan = Loan.open(items, cash, principalCents, dealer, day, rateShift(day));
        } catch (RejectedException | IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
        collateralSlots.clearContent();
        removeOne(inv, ModItems.SECURITY_PAPER);
        accrue(player, day, prog);
        account(player.getUUID(), day).deposit(principalCents, day, BankAccount.Kind.LOAN);
        loans.put(player.getUUID(), loan);
        ItemStack note = new ItemStack(ModItems.LOAN_NOTE);
        LoanNoteItem.write(note, loan, loan.value(dealer, day), day);
        inv.placeItemBackInInventory(note);
        save(player.getUUID());
        saveLoan(player.getUUID());
        return Optional.empty();
    }

    /** Moves the slots' items into escrow for the open loan. */
    public Optional<String> addCollateral(Player player, Container collateralSlots, Dealer dealer) {
        Loan loan = loans.get(player.getUUID());
        if (loan == null || loan.repaid()) return Optional.of("No open loan");
        Map<String, Integer> items = new LinkedHashMap<>();
        long cash = 0;
        for (int i = 0; i < collateralSlots.getContainerSize(); i++) {
            ItemStack s = collateralSlots.getItem(i);
            if (s.isEmpty()) continue;
            Denomination d = ModItems.denominationOf(s);
            if (d != null) cash += d.cents() * s.getCount();
            else items.merge(DealerService.itemId(s), s.getCount(), Integer::sum);
        }
        if (items.isEmpty() && cash == 0) return Optional.of("Put collateral in the slots");
        var refused = com.realisticmarkets.collateral.CollateralValuer.value(items, 0, dealer, 0).refused();
        if (!refused.isEmpty()) return Optional.of("The bank won't take " + String.join(", ", refused));
        loan.addCollateral(items, cash);
        collateralSlots.clearContent();
        saveLoan(player.getUUID());
        return Optional.empty();
    }

    /** Repays from the account balance ({@code cents < 0}: as much as owed). Paying off returns the collateral. */
    public Optional<String> repayLoan(Player player, long cents, long day, ProgressionService prog) {
        Loan loan = loans.get(player.getUUID());
        if (loan == null) return Optional.of("No open loan");
        loan.accrueTo(day);
        accrue(player, day, prog);
        BankAccount a = account(player.getUUID(), day);
        long want = cents < 0 ? loan.owedCents() : Math.min(cents, loan.owedCents());
        long pay = Math.min(want, a.balanceCents());
        if (pay <= 0 && !loan.repaid()) return Optional.of("Deposit cash first: repayments come from your balance");
        if (pay > 0) {
            a.withdraw(pay, day, BankAccount.Kind.LOAN_REPAY);
            loan.repay(pay);
        }
        boolean paidOff = loan.repaid();
        if (paidOff) {
            closeLoan(player, day);
            if (prog != null) prog.emit(player, new ProgressionEvent.LoanRepaid(loan.principalCents(), loan.interestCents(), day));
        }
        save(player.getUUID());
        saveLoan(player.getUUID());
        return Optional.empty();
    }

    /** A paid-off loan (by the borrower or a forced sale): hand back what's left in escrow and forget it. */
    private void closeLoan(Player player, long day) {
        Loan loan = loans.remove(player.getUUID());
        for (Map.Entry<String, Integer> e : loan.releaseCollateral().entrySet()) {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                    net.minecraft.resources.Identifier.parse(e.getKey()));
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(left, item.getDefaultMaxStackSize());
                player.getInventory().placeItemBackInInventory(new ItemStack(item, n));
                left -= n;
            }
        }
        long cash = loan.releaseCash();
        if (cash > 0) account(player.getUUID(), day).deposit(cash, day, BankAccount.Kind.DEPOSIT);
    }

    /** Collects a loan the bank closed by forced sale while the borrower was away. */
    public void collectClosedLoan(Player player, long day) {
        Loan loan = loans.get(player.getUUID());
        if (loan != null && loan.repaid()) {
            closeLoan(player, day);
            save(player.getUUID());
            saveLoan(player.getUUID());
        }
    }

    /**
     * The dawn check for every open loan: interest, re-valuation, margin calls and forced sales. Surplus from a
     * forced sale goes to the borrower's balance. {@code alarm} lights or clears the owner's vault.
     */
    public void dawn(long day, Dealer dealer, Function<UUID, Player> online, ProgressionService prog,
                     BiConsumer<UUID, Boolean> alarm) {
        lastDawn = day;
        for (Map.Entry<UUID, Loan> e : new ArrayList<>(loans.entrySet())) {
            UUID owner = e.getKey();
            Loan loan = e.getValue();
            if (loan.repaid()) continue;
            MarginCheck.Result r = MarginCheck.atDawn(loan, dealer, day, rateShift(day));
            Player p = online.apply(owner);
            switch (r.status()) {
                case CALL_ISSUED -> {
                    alarm.accept(owner, true);
                    tell(p, "MARGIN CALL: your collateral covers only " + pct(r.coverage()) + " of your loan (110% needed). "
                            + "Add collateral or repay by dawn tomorrow, or the bank will sell it.");
                }
                case CALL_CLEARED -> {
                    alarm.accept(owner, false);
                    tell(p, "Margin call cleared: coverage is back to " + pct(r.coverage()) + ".");
                }
                case LIQUIDATED -> {
                    if (r.surplusCents() > 0) account(owner, day).deposit(r.surplusCents(), day, BankAccount.Kind.LIQUIDATION);
                    alarm.accept(owner, loan.underMarginCall());
                    tell(p, "The bank sold your collateral: " + r.sales().size() + " sale(s)"
                            + (loan.repaid() ? ", the loan is paid off" : ", " + com.realisticmarkets.money.Money.format(loan.owedCents()) + " still owed")
                            + (r.surplusCents() > 0 ? ", " + com.realisticmarkets.money.Money.format(r.surplusCents()) + " returned to your balance" : "")
                            + ".");
                    if (loan.repaid() && p != null) closeLoan(p, day);
                    save(owner);
                }
                default -> { }
            }
            saveLoan(owner);
        }
    }

    private static void tell(Player p, String text) {
        if (p instanceof ServerPlayer sp) sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
    }

    private static String pct(double coverage) {
        return Double.isInfinite(coverage) ? "all" : Math.round(coverage * 100) + "%";
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
        PassbookItem.write(book, player.getName().getString(), a, day, vaultRate(day));
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
        if (day > instance.lastDawn && !instance.loans.isEmpty()) {
            instance.dawn(day, DealerService.get().dealer(), id -> server.getPlayerList().getPlayer(id), prog,
                    (owner, on) -> instance.setAlarm(server, owner, on, day));
        } else {
            instance.lastDawn = Math.max(instance.lastDawn, day);
        }
    }

    private void setAlarm(MinecraftServer server, UUID owner, boolean on, long day) {
        if (!hasAccount(owner)) return;
        var vault = com.realisticmarkets.mod.block.Locations.find(server, account(owner, day).vaultLocation(),
                com.realisticmarkets.mod.block.BankVaultBlockEntity.class);
        if (vault != null) vault.setAlarm(on);
    }

    // ------------------------------------------------------------------ persistence

    private Path loanFile(UUID id) {
        return dir.resolve("players").resolve(id + ".loan.txt");
    }

    private void loadLoans() {
        Path players = dir.resolve("players");
        if (Files.notExists(players)) return;
        try (var files = Files.list(players)) {
            for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".loan.txt")).toList()) {
                UUID id = UUID.fromString(f.getFileName().toString().replace(".loan.txt", ""));
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    Loan loan = Loan.read(r);
                    if (loan != null) loans.put(id, loan);
                } catch (IOException | RuntimeException e) {
                    RealisticMarkets.LOGGER.error("Could not read {}; moving it aside", f, e);
                    moveAside(f);
                }
            }
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not list {}", players, e);
        }
    }

    private void saveLoan(UUID id) {
        if (dir == null) return;
        Loan loan = loans.get(id);
        try {
            if (loan == null) {
                Files.deleteIfExists(loanFile(id));
                return;
            }
            StringWriter w = new StringWriter();
            loan.write(w);
            writeAtomic(loanFile(id), w.toString());
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save loan {}", id, e);
        }
    }

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
        loans.keySet().forEach(this::saveLoan);
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
