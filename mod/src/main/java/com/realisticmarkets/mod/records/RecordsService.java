package com.realisticmarkets.mod.records;

import com.realisticmarkets.bonds.Bond;
import com.realisticmarkets.contracts.Cd;
import com.realisticmarkets.equities.Company;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.block.BankVaultBlockEntity;
import com.realisticmarkets.mod.block.OwnedBlockEntity;
import com.realisticmarkets.mod.block.RecordsTerminalBlockEntity;
import com.realisticmarkets.mod.block.SafeDepositBoxBlockEntity;
import com.realisticmarkets.mod.block.TradeRouteCrateBlockEntity;
import com.realisticmarkets.mod.bonds.BondPapers;
import com.realisticmarkets.mod.bonds.BondService;
import com.realisticmarkets.mod.dealer.BillClip;
import com.realisticmarkets.mod.dealer.CapitalService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.item.PortfolioBinderItem;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import com.realisticmarkets.mod.stocks.StockService;
import com.realisticmarkets.money.Denomination;
import com.realisticmarkets.progression.ProgressionEvent;
import com.realisticmarkets.records.Calendar;
import com.realisticmarkets.records.Ledger;
import com.realisticmarkets.records.NetWorth;
import com.realisticmarkets.records.NetWorth.Kind;
import com.realisticmarkets.records.NetWorth.Line;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Digital Record Keeping. Keeps each account's income ledger (fed by progression events, from the day the node is
 * bought) and values what a Records Terminal can see: the linked Bank Vault's balance, loan and collateral, and the
 * bills, papers and goods in linked Safe Deposit Boxes and Trade Route Crates, each at today's mark. Papers anywhere
 * else are invisible to it.
 */
public final class RecordsService {
    public static final String NODE = "digital_record_keeping";

    private static RecordsService instance;

    /** The services marks come from; any but {@code dealer} and {@code prog} may be null. */
    public record Sources(DealerService dealer, ProgressionService prog, BankService bank, StockService stocks, BondService bonds,
                          CapitalService capital) {}

    private final Ledger ledger;
    private final Path file; // null in tests
    private final Sources sources;
    private boolean dirty;
    private int ticks;

    private RecordsService(Ledger ledger, Path file, Sources sources) {
        this.ledger = ledger;
        this.file = file;
        this.sources = sources;
        sources.prog().listen(this::onEvent);
    }

    public static void start(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(RealisticMarkets.MOD_ID).resolve("income_ledger.txt");
        Ledger ledger = new Ledger();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                ledger = Ledger.read(r);
            } catch (IOException | RuntimeException e) {
                RealisticMarkets.LOGGER.error("Could not read the income ledger {}", file, e);
            }
        }
        instance = new RecordsService(ledger, file, new Sources(DealerService.get(), ProgressionService.get(), BankService.get(),
                StockService.get(), BondService.get(), CapitalService.get()));
    }

    public static void stop() {
        if (instance != null) instance.save();
        instance = null;
    }

    public static RecordsService get() {
        if (instance == null) throw new IllegalStateException("Records not started (no server running?)");
        return instance;
    }

    public static RecordsService forTest(Sources sources) {
        return new RecordsService(new Ledger(), null, sources);
    }

    public static void tick(MinecraftServer server) {
        if (instance != null && ++instance.ticks % DealerService.SAVE_INTERVAL_TICKS == 0 && instance.dirty) instance.save();
    }

    public Ledger ledger() { return ledger; }
    public Sources sources() { return sources; }

    public static String account(Player p) {
        return p.getUUID().toString();
    }

    private void onEvent(Player player, ProgressionEvent event) {
        if (event instanceof ProgressionEvent.RecordsViewed) return;
        if (!sources.prog().progress(player).hasNode(NODE)) return;
        String a = account(player);
        ledger.open(a, event.day());
        ledger.record(a, event);
        dirty = true;
    }

    /** Starts the ledger for a player who owns the node (buying it, or opening a terminal). */
    public void open(Player player, long day) {
        if (!sources.prog().progress(player).hasNode(NODE) || ledger.isOpen(account(player))) return;
        ledger.open(account(player), day);
        dirty = true;
    }

    public void noteNetWorth(Player player, double day, long cents) {
        ledger.noteNetWorth(account(player), day, cents);
        dirty = true;
    }

    // ------------------------------------------------------------------ valuation

    /** One holding as the terminal shows it: the balance-sheet line and an item to draw next to it. */
    public record Row(Line line, ItemStack icon) {}

    /** Everything a terminal shows: the balance sheet, its rows, the calendar, and how many links still stand. */
    public record View(NetWorth netWorth, List<Row> rows, Calendar calendar, Map<String, ItemStack> icons, int links) {}

    /** Values what {@code terminal} can see for its owner {@code owner} on {@code day}. */
    public View view(RecordsTerminalBlockEntity terminal, UUID owner, double day) {
        long today = (long) Math.floor(day);
        Valuer v = new Valuer(owner == null ? "" : owner.toString(), day);
        List<OwnedBlockEntity> blocks = terminal.linkedBlocks();
        int boxes = 0, crates = 0;
        boolean vault = false;
        for (OwnedBlockEntity be : blocks) {
            if (be instanceof BankVaultBlockEntity && !vault && owner != null) {
                vault = true;
                v.vault(owner);
            } else if (be instanceof SafeDepositBoxBlockEntity box) {
                v.container(box.contents(), "Box " + ++boxes);
            } else if (be instanceof TradeRouteCrateBlockEntity crate) {
                String where = "Crate " + ++crates;
                v.container(crate.cargo(), where);
                v.container(crate.drawer(), where);
                v.shipment(crate, where);
            }
        }
        v.calendar.add(Calendar.nextEvery(today, com.realisticmarkets.rates.CentralBank.REVIEW_DAYS), Calendar.Kind.RATE_DECISION, "", 0);
        for (String ticker : v.tickers) {
            v.calendar.add(Calendar.nextEvery(today, Company.QUARTER_DAYS), Calendar.Kind.EARNINGS, ticker, 0);
            v.icons.putIfAbsent("E|" + ticker, ShareCertificates.create(ticker, 1, 0, 1));
        }
        NetWorth nw = new NetWorth();
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : v.lines.entrySet()) {
            String[] k = e.getKey().split("\\|", 4); // kind|label|location|mark
            long[] q = e.getValue(); // quantity, cost (-1 unknown)
            Line line = new Line(Kind.valueOf(k[0]), k[1], k[2], q[0], Long.parseLong(k[3]), q[1]);
            int before = nw.lines().size();
            nw.add(line);
            if (nw.lines().size() > before) rows.add(new Row(line, v.icons.getOrDefault(e.getKey(), ItemStack.EMPTY)));
        }
        rows.sort((a, b) -> Long.compare(b.line().value(), a.line().value()));
        return new View(nw, rows, v.calendar, v.icons, blocks.size());
    }

    /** The icon for a calendar entry: the paper that falls due, or a company's certificate. */
    public static String iconKey(Calendar.Entry e) {
        return e.kind() == Calendar.Kind.EARNINGS ? "E|" + e.what() : "C|" + e.what();
    }

    /** Walks the linked storage, adding up lines by kind, label, location and mark. */
    private final class Valuer {
        final String account;
        final double day;
        final long today;
        final Map<String, long[]> lines = new LinkedHashMap<>(); // key -> {quantity, cost or -1}
        final Map<String, ItemStack> icons = new LinkedHashMap<>();
        final Set<String> tickers = new LinkedHashSet<>();
        final Calendar calendar = new Calendar();

        Valuer(String account, double day) {
            this.account = account;
            this.day = day;
            this.today = (long) Math.floor(day);
        }

        void add(Kind kind, String label, String where, long qty, long mark, long cost, ItemStack icon) {
            String key = kind + "|" + label.replace("|", "/") + "|" + where + "|" + mark;
            long[] q = lines.computeIfAbsent(key, k -> new long[] {0, 0});
            q[0] += qty;
            q[1] = q[1] < 0 || cost < 0 ? -1 : q[1] + cost;
            if (!icon.isEmpty()) icons.putIfAbsent(key, icon.copyWithCount(1));
        }

        void vault(UUID owner) {
            BankService bank = sources.bank();
            if (bank == null) return;
            if (bank.hasAccount(owner)) {
                long bal = bank.account(owner, today).balanceCents();
                add(Kind.VAULT, "Vault balance", "Vault", 1, bal, bal, new ItemStack(ModItems.PASSBOOK));
            }
            bank.loan(owner).filter(l -> !l.repaid()).ifPresent(loan -> {
                add(Kind.DEBTS, "Loan", "Vault", 1, loan.owedCents(), -1, new ItemStack(ModItems.LOAN_NOTE));
                long collateral = loan.value(sources.dealer().dealer(), day).marketCents();
                add(Kind.GOODS, "Loan collateral", "Vault", 1, collateral, -1, ItemStack.EMPTY);
                calendar.add(today + 1, Calendar.Kind.MARGIN_CHECK, "", 0);
            });
        }

        void container(Container c, String where) {
            List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < c.getContainerSize(); i++) stacks.add(c.getItem(i));
            stacks(stacks, where);
        }

        void stacks(List<ItemStack> stacks, String where) {
            long cash = 0;
            for (ItemStack s : stacks) {
                if (s.isEmpty()) continue;
                Denomination d = ModItems.denominationOf(s);
                if (d != null) {
                    cash += d.cents() * s.getCount();
                } else if (BillClip.isClip(s)) {
                    cash += BillClip.cents(s);
                } else if (s.is(ModItems.PORTFOLIO_BINDER)) {
                    stacks(PortfolioBinderItem.contents(s), where);
                } else if (s.is(ModItems.SHARE_CERTIFICATE)) {
                    share(s, where);
                } else if (s.is(ModItems.BOND)) {
                    bond(s, where);
                } else if (s.is(ModItems.CERTIFICATE_OF_DEPOSIT)) {
                    cd(s, where);
                } else if (!s.is(ModItems.LOAN_NOTE)) {
                    goods(s, where);
                }
            }
            if (cash > 0) add(Kind.CASH, "Cash", where, 1, cash, cash, new ItemStack(ModItems.CURRENCY.get(Denomination.HUNDRED)));
        }

        void share(ItemStack s, String where) {
            Optional<ShareCertificates.Paper> p = ShareCertificates.read(s);
            StockService stocks = sources.stocks();
            if (p.isEmpty() || stocks == null) return;
            String t = p.get().ticker();
            long shares = (long) p.get().denomination() * s.getCount();
            long price = stocks.market().exchange().lastPrice(t).orElse(stocks.fairCents(t));
            double avg = stocks.costBasis().averageCents(account, t);
            add(Kind.SHARES, t, where, shares, price, avg < 0 ? -1 : Math.round(avg * shares), ShareCertificates.create(t, 1, 0, 1));
            tickers.add(t);
        }

        void bond(ItemStack s, String where) {
            Optional<BondPapers.Paper> p = BondPapers.read(s);
            BondService bonds = sources.bonds();
            if (p.isEmpty() || bonds == null) return;
            Bond b = p.get().bond();
            int n = s.getCount();
            long redemption = bonds.desk().redemption(b, day); // matured or defaulted: what the desk pays back
            long mark = (redemption > 0 ? redemption : bonds.desk().bid(b, day)) + bonds.desk().couponsOwed(b, p.get().paidThrough(), day);
            double avg = bonds.averageCost(account, b.series());
            String label = BondPapers.issuerName(b.issuer()) + " bond, day " + b.maturityDay();
            ItemStack icon = BondPapers.create(b, 0, 1);
            add(Kind.BONDS, label, where, n, mark, avg < 0 ? -1 : Math.round(avg * n), icon);
            if (bonds.desk().defaulted(b, day)) return;
            icons.putIfAbsent("C|" + label, icon);
            int next = b.couponsDueBy(day) + 1;
            if (next <= b.coupons()) calendar.add(b.couponDay(next), Calendar.Kind.COUPON, label, b.couponCents() * n);
            calendar.add(b.maturityDay(), Calendar.Kind.BOND_MATURITY, label, (long) Bond.FACE_CENTS * n);
        }

        void cd(ItemStack s, String where) {
            BankService bank = sources.bank();
            if (bank == null) return;
            Optional<Cd> cd = bank.cd(s);
            if (cd.isEmpty()) return;
            long value = Math.max(0, bank.cdValue(s, today));
            String label = "CD, day " + cd.get().maturityDay();
            add(Kind.CDS, label, where, s.getCount(), value, cd.get().principalCents() * s.getCount(), s);
            icons.putIfAbsent("C|" + label, s.copyWithCount(1));
            calendar.add(cd.get().maturityDay(), Calendar.Kind.CD_MATURITY, label, cd.get().valueAtMaturityCents() * s.getCount());
        }

        void goods(ItemStack s, String where) {
            String id = DealerService.itemId(s);
            var d = sources.dealer().dealer();
            if (!d.catalog().trades(id)) return;
            long cents;
            try {
                cents = d.quoteSell(id, s.getCount(), day, false).cents();
            } catch (RejectedException e) {
                return;
            }
            add(Kind.GOODS, s.getHoverName().getString(), where, s.getCount(), cents / s.getCount(), -1, s);
        }

        void shipment(TradeRouteCrateBlockEntity crate, String where) {
            CapitalService capital = sources.capital();
            if (capital == null) return;
            capital.inTransitAt(crate.location()).ifPresent(sh -> {
                long est = capital.estimate(sh.items(), day);
                add(Kind.GOODS, "Shipment to the Capital", where, 1, est, sh.localQuoteCents(),
                        new ItemStack(com.realisticmarkets.mod.registry.ModBlocks.TRADE_ROUTE_CRATE));
            });
        }
    }

    // ------------------------------------------------------------------ persistence

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                ledger.write(w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            RealisticMarkets.LOGGER.error("Could not save the income ledger {}", file, e);
        }
    }
}
