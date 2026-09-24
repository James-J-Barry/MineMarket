package com.realisticmarkets.mod.menu;

import com.realisticmarkets.agents.FloorCatalog;
import com.realisticmarkets.exchange.PriceChart;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.floor.FloorService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.progression.ProgressionEvent;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * The Ticker Tape: pick a Floor book and read its chart, the last {@link PriceChart#DAYS} days in quarter-day points
 * with volume, kept up to date while the screen is open. Nothing to carry: the block is the purchase.
 */
public class TickerTapeMenu extends AbstractContainerMenu {
    public static final int WIDTH = 280, HEIGHT = 212;
    public static final int BUTTON_BOOK_BASE = 100;
    public static final List<FloorCatalog.Book> BOOKS = TradingFloorMenu.BOOKS;

    private static final int D_BOOK = 0, D_DAY = 1, D_POINTS = 3, POINT_STRIDE = 8; // per point: high, low, close, volume
    private static final long MAX_SYNCED = (1L << 30) - 1;
    private final ContainerData data = new SimpleContainerData(D_POINTS + PriceChart.POINTS * POINT_STRIDE);
    private final ContainerLevelAccess access;
    private final Player player;
    private final FloorService floor; // null on the client
    private final ProgressionService progression;
    private final DealerService dealer;
    private int ticks;

    public TickerTapeMenu(int containerId, Inventory inv) {
        this(containerId, inv, ContainerLevelAccess.NULL, null, null, null);
    }

    public TickerTapeMenu(int containerId, Inventory inv, ContainerLevelAccess access, FloorService floor,
                          ProgressionService progression, DealerService dealer) {
        super(ModMenus.TICKER_TAPE, containerId);
        this.access = access;
        this.player = inv.player;
        this.floor = floor;
        this.progression = progression;
        this.dealer = dealer;
        addDataSlots(data);
        if (floor != null) {
            refresh();
            read();
        }
    }

    public int book() { return data.get(D_BOOK); }

    public String item() { return BOOKS.get(book()).item(); }

    private long pair(int i) {
        return (long) data.get(i) | ((long) data.get(i + 1) << 15);
    }

    private void setPair(int i, long value) {
        long v = Math.min(Math.max(value, 0), MAX_SYNCED);
        data.set(i, (int) (v & 0x7FFF));
        data.set(i + 1, (int) ((v >> 15) & 0x7FFF));
    }

    /** The chart as synced (client) or computed (server). */
    public PriceChart chart() {
        int n = PriceChart.POINTS;
        long[] hi = new long[n], lo = new long[n], cl = new long[n], vol = new long[n];
        for (int i = 0; i < n; i++) {
            int base = D_POINTS + i * POINT_STRIDE;
            cl[i] = pair(base + 4) == 0 ? PriceChart.NONE : pair(base + 4); // prices are at least a cent, so 0 = none
            hi[i] = cl[i] == PriceChart.NONE ? PriceChart.NONE : pair(base);
            lo[i] = cl[i] == PriceChart.NONE ? PriceChart.NONE : pair(base + 2);
            vol[i] = pair(base + 6);
        }
        return new PriceChart(item(), pair(D_DAY), hi, lo, cl, vol);
    }

    private void refresh() {
        long today = (long) Math.floor(dealer.day(player.level().getGameTime()));
        PriceChart c = PriceChart.of(floor.floor().history(), item(), today);
        setPair(D_DAY, today);
        for (int i = 0; i < PriceChart.POINTS; i++) {
            int base = D_POINTS + i * POINT_STRIDE;
            boolean none = c.close()[i] == PriceChart.NONE;
            setPair(base, none ? 0 : c.high()[i]);
            setPair(base + 2, none ? 0 : c.low()[i]);
            setPair(base + 4, none ? 0 : c.close()[i]);
            setPair(base + 6, c.volume()[i]);
        }
    }

    private void read() {
        if (progression != null) progression.emit(player, new ProgressionEvent.ChartRead(item(), pair(D_DAY)));
    }

    @Override
    public void broadcastChanges() {
        if (floor != null && ++ticks % 20 == 0) refresh(); // live: new auctions show up while you watch
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (floor == null || id < BUTTON_BOOK_BASE || id >= BUTTON_BOOK_BASE + BOOKS.size()) return false;
        data.set(D_BOOK, id - BUTTON_BOOK_BASE);
        refresh();
        read();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.TICKER_TAPE);
    }
}
