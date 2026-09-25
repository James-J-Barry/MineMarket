package com.realisticmarkets.mod.menu;

import com.realisticmarkets.mod.block.RecordsTerminalBlockEntity;
import com.realisticmarkets.mod.records.RecordsService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.progression.ProgressionEvent;
import com.realisticmarkets.records.Calendar;
import com.realisticmarkets.records.Ledger;
import com.realisticmarkets.records.NetWorth;
import java.util.List;
import java.util.Map;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Records Terminal's screen data. Overview: net worth, assets and debts by kind, and a 30-day line. Holdings:
 * each line in linked storage with its mark, location and profit where the cost is known. Income: by source over 7
 * and 30 days. Calendar: what falls due next. Numbers travel as ContainerData; the items drawn next to holdings and
 * calendar entries travel in hidden display slots (so their names come along).
 */
public class RecordsTerminalMenu extends AbstractContainerMenu {
    public static final int WIDTH = 256, HEIGHT = 206, ROWS_PER_PAGE = 9, CAL_MAX = 9, LINE_DAYS = 30;
    public static final int TAB_OVERVIEW = 0, TAB_HOLDINGS = 1, TAB_INCOME = 2, TAB_CALENDAR = 3;
    public static final int BUTTON_PREV = 4, BUTTON_NEXT = 5;
    public static final int LOC_VAULT = 1, LOC_BOX = 2, LOC_CRATE = 3, LOC_DEALER = 4, LOC_CLEARING = 5;
    public static final long NO_DATA = Long.MIN_VALUE;

    private static final int L = 4; // shorts per long
    private static final int D_TAB = 0, D_PAGE = 1, D_PAGES = 2, D_LINKS = 3, D_OWNER = 4, D_DAY = 5;
    private static final int D_TOTAL = 7, D_ASSETS = D_TOTAL + L, D_DEBTS = D_ASSETS + L, D_KINDS = D_DEBTS + L;
    private static final int D_LINE = D_KINDS + NetWorth.Kind.values().length * L;
    private static final int D_INCOME = D_LINE + LINE_DAYS * L;
    private static final int D_LEDGER = D_INCOME + Ledger.Source.values().length * 2 * L;
    private static final int D_ROWS = D_LEDGER + 1, D_ROW = D_ROWS + 1, ROW_STRIDE = 2 + 3 * L + 1;
    private static final int D_CAL_COUNT = D_ROW + ROWS_PER_PAGE * ROW_STRIDE, D_CAL = D_CAL_COUNT + 1, CAL_STRIDE = 3 + L;
    private static final int D_SIZE = D_CAL + CAL_MAX * CAL_STRIDE;

    private final ContainerData data = new SimpleContainerData(D_SIZE);
    private final SimpleContainer icons = new SimpleContainer(ROWS_PER_PAGE + CAL_MAX);
    private final ContainerLevelAccess access;
    private final Player player;
    private final RecordsTerminalBlockEntity terminal; // null on the client
    private final RecordsService records;
    private int ticks;

    public RecordsTerminalMenu(int containerId, Inventory inv) {
        this(containerId, inv, ContainerLevelAccess.NULL, null, null);
    }

    public RecordsTerminalMenu(int containerId, Inventory inv, ContainerLevelAccess access, RecordsTerminalBlockEntity terminal,
                               RecordsService records) {
        super(ModMenus.RECORDS_TERMINAL, containerId);
        this.access = access;
        this.player = inv.player;
        this.terminal = terminal;
        this.records = records;
        for (int i = 0; i < icons.getContainerSize(); i++) {
            addSlot(new Slot(icons, i, -10_000, -10_000) {
                @Override
                public boolean isActive() { return false; }
                @Override
                public boolean mayPickup(Player p) { return false; }
                @Override
                public boolean mayPlace(ItemStack s) { return false; }
            });
        }
        addDataSlots(data);
        if (records != null) {
            records.open(player, (long) Math.floor(records.sources().dealer().day(player.level().getGameTime())));
            refresh(true);
        }
    }

    // ------------------------------------------------------------------ client reads

    public int tab() { return data.get(D_TAB); }
    public int page() { return data.get(D_PAGE); }
    public int pages() { return Math.max(1, data.get(D_PAGES)); }
    public int links() { return data.get(D_LINKS); }
    public boolean owner() { return data.get(D_OWNER) != 0; }
    public long day() { return pair(D_DAY); }
    public long total() { return getLong(D_TOTAL); }
    public long assets() { return getLong(D_ASSETS); }
    public long debts() { return getLong(D_DEBTS); }
    public long byKind(NetWorth.Kind k) { return getLong(D_KINDS + k.ordinal() * L); }
    /** Net worth on each of the last 30 days, oldest first; {@link #NO_DATA} before records began. */
    public long lineDay(int i) { return getLong(D_LINE + i * L); }
    public long income(Ledger.Source s, boolean month) { return getLong(D_INCOME + (s.ordinal() * 2 + (month ? 1 : 0)) * L); }
    public boolean ledgerOpen() { return data.get(D_LEDGER) != 0; }
    public int rowCount() { return data.get(D_ROWS); }
    public NetWorth.Kind rowKind(int i) { return NetWorth.Kind.values()[data.get(D_ROW + i * ROW_STRIDE)]; }
    /** "Vault", "Box 2", "Crate 1". */
    public String rowLocation(int i) { return location(data.get(D_ROW + i * ROW_STRIDE + 1)); }
    public long rowQuantity(int i) { return getLong(D_ROW + i * ROW_STRIDE + 2); }
    public long rowValue(int i) { return getLong(D_ROW + i * ROW_STRIDE + 2 + L); }
    /** Value minus cost, or {@link #NO_DATA} when the cost isn't known. */
    public long rowProfit(int i) {
        return data.get(D_ROW + i * ROW_STRIDE + 2 + 3 * L) == 0 ? NO_DATA : getLong(D_ROW + i * ROW_STRIDE + 2 + 2 * L);
    }
    public ItemStack rowIcon(int i) { return icons.getItem(i); }
    public int calendarCount() { return data.get(D_CAL_COUNT); }
    public long calendarDay(int i) { return pair(D_CAL + i * CAL_STRIDE); }
    public Calendar.Kind calendarKind(int i) { return Calendar.Kind.values()[data.get(D_CAL + i * CAL_STRIDE + 2)]; }
    public long calendarCents(int i) { return getLong(D_CAL + i * CAL_STRIDE + 3); }
    public ItemStack calendarIcon(int i) { return icons.getItem(ROWS_PER_PAGE + i); }

    private static String location(int code) {
        int type = code / 100, n = code % 100;
        return switch (type) {
            case LOC_VAULT -> "Vault";
            case LOC_BOX -> "Box " + n;
            case LOC_CRATE -> "Crate " + n;
            case LOC_DEALER -> "Dealer";
            case LOC_CLEARING -> "Clearing";
            default -> "?";
        };
    }

    private static int locationCode(String where) {
        if (where.equals("Vault")) return LOC_VAULT * 100;
        if (where.equals("Dealer")) return LOC_DEALER * 100;
        if (where.equals("Clearing")) return LOC_CLEARING * 100;
        String[] p = where.split(" ");
        int n = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        return (where.startsWith("Box") ? LOC_BOX : where.startsWith("Crate") ? LOC_CRATE : 0) * 100 + Math.min(n, 99);
    }

    // ------------------------------------------------------------------ sync helpers

    private long pair(int i) {
        return (long) data.get(i) | ((long) data.get(i + 1) << 15);
    }

    private void setPair(int i, long value) {
        long v = Math.min(Math.max(value, 0), (1L << 30) - 1);
        data.set(i, (int) (v & 0x7FFF));
        data.set(i + 1, (int) ((v >> 15) & 0x7FFF));
    }

    private static final long OFFSET = 1L << 59;

    /** Signed longs in four 15-bit shorts (about ±5.7e17 cents). NO_DATA travels as the lowest value. */
    private void setLong(int i, long value) {
        long v = value == NO_DATA ? 0 : Math.max(-OFFSET + 1, Math.min(OFFSET - 1, value)) + OFFSET;
        for (int k = 0; k < L; k++) data.set(i + k, (int) ((v >> (15 * k)) & 0x7FFF));
    }

    private long getLong(int i) {
        long v = 0;
        for (int k = 0; k < L; k++) v |= (long) (data.get(i + k) & 0x7FFF) << (15 * k);
        return v == 0 ? NO_DATA : v - OFFSET;
    }

    // ------------------------------------------------------------------ server

    private RecordsService.View lastView;

    /** The last valuation (server side; tests read it). */
    public RecordsService.View view() { return lastView; }

    private void refresh(boolean note) {
        long today = (long) Math.floor(records.sources().dealer().day(player.level().getGameTime()));
        setPair(D_DAY, today);
        boolean owns = records.sources().prog().progress(player).hasNode(RecordsService.NODE);
        data.set(D_OWNER, owns ? 1 : 0);
        if (!owns) return;
        RecordsService.View v = records.view(terminal, terminal.owner(), records.sources().dealer().day(player.level().getGameTime()));
        lastView = v;
        NetWorth nw = v.netWorth();
        data.set(D_LINKS, v.links());
        setLong(D_TOTAL, nw.total());
        setLong(D_ASSETS, nw.assets());
        setLong(D_DEBTS, nw.debts());
        for (Map.Entry<NetWorth.Kind, Long> e : nw.byKind().entrySet()) setLong(D_KINDS + e.getKey().ordinal() * L, e.getValue());
        if (note) {
            records.noteNetWorth(player, today, nw.total());
            records.sources().prog().emit(player, new ProgressionEvent.RecordsViewed(nw.total(), today));
        }
        String account = RecordsService.account(player);
        long[] line = records.ledger().netWorthLine(account, today, LINE_DAYS);
        for (int i = 0; i < LINE_DAYS; i++) setLong(D_LINE + i * L, line[i] == Ledger.NO_DATA ? NO_DATA : line[i]);
        data.set(D_LEDGER, records.ledger().isOpen(account) ? 1 : 0);
        Map<Ledger.Source, Long> week = records.ledger().income(account, today, 7), month = records.ledger().income(account, today, 30);
        for (Ledger.Source s : Ledger.Source.values()) {
            setLong(D_INCOME + (s.ordinal() * 2) * L, week.get(s));
            setLong(D_INCOME + (s.ordinal() * 2 + 1) * L, month.get(s));
        }
        List<RecordsService.Row> rows = v.rows();
        int pages = Math.max(1, (rows.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        data.set(D_PAGES, pages);
        if (page() >= pages) data.set(D_PAGE, pages - 1);
        int from = page() * ROWS_PER_PAGE, n = Math.max(0, Math.min(ROWS_PER_PAGE, rows.size() - from));
        data.set(D_ROWS, n);
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            if (i >= n) {
                icons.setItem(i, ItemStack.EMPTY);
                continue;
            }
            RecordsService.Row r = rows.get(from + i);
            int base = D_ROW + i * ROW_STRIDE;
            data.set(base, r.line().kind().ordinal());
            data.set(base + 1, locationCode(r.line().location()));
            setLong(base + 2, r.line().quantity());
            setLong(base + 2 + L, r.line().value());
            var profit = r.line().profit();
            setLong(base + 2 + 2 * L, profit.orElse(0));
            data.set(base + 2 + 3 * L, profit.isPresent() ? 1 : 0);
            icons.setItem(i, r.icon());
        }
        List<Calendar.Entry> due = v.calendar().upcoming(today, CAL_MAX);
        data.set(D_CAL_COUNT, due.size());
        for (int i = 0; i < CAL_MAX; i++) {
            if (i >= due.size()) {
                icons.setItem(ROWS_PER_PAGE + i, ItemStack.EMPTY);
                continue;
            }
            Calendar.Entry e = due.get(i);
            int base = D_CAL + i * CAL_STRIDE;
            setPair(base, e.day());
            data.set(base + 2, e.kind().ordinal());
            setLong(base + 3, e.cents());
            icons.setItem(ROWS_PER_PAGE + i, v.icons().getOrDefault(RecordsService.iconKey(e), ItemStack.EMPTY));
        }
    }

    @Override
    public void broadcastChanges() {
        if (records != null && ++ticks % 20 == 0) refresh(ticks % 200 == 0); // net worth noted every 10 s
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (records == null) return false;
        switch (id) {
            case TAB_OVERVIEW, TAB_HOLDINGS, TAB_INCOME, TAB_CALENDAR -> data.set(D_TAB, id);
            case BUTTON_PREV -> data.set(D_PAGE, Math.max(0, page() - 1));
            case BUTTON_NEXT -> data.set(D_PAGE, Math.min(pages() - 1, page() + 1));
            default -> {
                return false;
            }
        }
        refresh(false);
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.RECORDS_TERMINAL);
    }
}
