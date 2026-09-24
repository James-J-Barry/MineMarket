package com.realisticmarkets.mod.menu;

import com.realisticmarkets.agents.FloorCatalog;
import com.realisticmarkets.agents.TradingFloor;
import com.realisticmarkets.equities.Equities;
import com.realisticmarkets.exchange.Side;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import com.realisticmarkets.mod.stocks.StockService;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
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
 * The Stock Exchange. Trade tab: like the Trading Floor, one book per company, orders in shares; bought shares arrive
 * as Share Certificates in the output slots. Company tab: the chosen company's last four quarters, and your
 * certificates of it: collect dividends, merge into the fewest certificates, split one.
 */
public class StockExchangeMenu extends AbstractContainerMenu {
    public static final int WIDTH = 236, HEIGHT = 230, INVENTORY_X = 38, INVENTORY_Y = 148;
    public static final int OUTPUT_X = 8, OUTPUT_Y = 122, OUTPUT_SLOTS = 6, INV_START = OUTPUT_SLOTS, INV_END = INV_START + 36;
    public static final int MAX_SHOWN_ORDERS = 3, REPORT_QUARTERS = 4;
    public static final List<FloorCatalog.Book> BOOKS = FloorCatalog.loadStocks().all();
    public static final int TAB_TRADE = 0, TAB_COMPANY = 1;

    public static final int BUTTON_SIDE = 0, BUTTON_MARKET = 1, BUTTON_PLACE = 2;
    public static final int BUTTON_QTY_MINUS_10 = 3, BUTTON_QTY_MINUS_1 = 4, BUTTON_QTY_PLUS_1 = 5, BUTTON_QTY_PLUS_10 = 6;
    public static final int BUTTON_PRICE_MINUS_10PCT = 7, BUTTON_PRICE_MINUS_1 = 8, BUTTON_PRICE_PLUS_1 = 9, BUTTON_PRICE_PLUS_10PCT = 10;
    public static final int BUTTON_TAB_TRADE = 11, BUTTON_TAB_COMPANY = 12, BUTTON_COLLECT = 13, BUTTON_MERGE = 14, BUTTON_SPLIT = 15;
    public static final int BUTTON_CANCEL_BASE = 20, BUTTON_REPRICE_BASE = 30, BUTTON_BOOK_BASE = 100;

    private static final int D_TAB = 0, D_BOOK = 1, D_SELL = 2, D_MARKET = 3, D_QTY = 4, D_PRICE = 6, D_BID = 8, D_ASK = 10;
    private static final int D_LAST = 12, D_SECONDS = 14, D_ORDERS = 15, D_ORDER_BASE = 16, ORDER_STRIDE = 8;
    private static final int D_TODAY = D_ORDER_BASE + MAX_SHOWN_ORDERS * ORDER_STRIDE; // avg, low, high
    private static final int D_HELD = D_TODAY + 6, D_WAITING = D_HELD + 2;
    private static final int D_REPORT_COUNT = D_WAITING + 2, D_REPORTS = D_REPORT_COUNT + 1, REPORT_STRIDE = 10;
    // per report: quarter, revenue $, costs $, |earnings| $, earnings sign, dividend cents (pairs except the sign)
    private static final int D_CASH = D_REPORTS + REPORT_QUARTERS * REPORT_STRIDE; // |cash a share| cents, sign
    private static final int D_SIZE = D_CASH + 3;
    private static final long MAX_SYNCED = (1L << 30) - 1;

    private final Container output = new SimpleContainer(OUTPUT_SLOTS);
    private final ContainerData data = new SimpleContainerData(D_SIZE);
    private final ContainerLevelAccess access;
    private final Player player;
    private final StockService stocks; // null on the client
    private final ProgressionService progression;
    private final DealerService dealer;
    private List<TradingFloor.Ticket> shown = List.of();
    private int ticks;

    public StockExchangeMenu(int containerId, Inventory inv) {
        this(containerId, inv, ContainerLevelAccess.NULL, null, null, null);
    }

    public StockExchangeMenu(int containerId, Inventory inv, ContainerLevelAccess access, StockService stocks,
                             ProgressionService progression, DealerService dealer) {
        super(ModMenus.STOCK_EXCHANGE, containerId);
        this.player = inv.player;
        this.access = access;
        this.stocks = stocks;
        this.progression = progression;
        this.dealer = dealer;
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            addSlot(new Slot(output, i, OUTPUT_X + i * 18, OUTPUT_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean isActive() {
                    return tab() == TAB_TRADE;
                }
            });
        }
        addStandardInventorySlots(inv, INVENTORY_X, INVENTORY_Y);
        addDataSlots(data);
        if (stocks != null) {
            setPair(D_QTY, 10);
            selectBook(0);
            refresh();
        }
    }

    // ---- reads (client and server)

    public int tab() { return data.get(D_TAB); }
    public int book() { return data.get(D_BOOK); }
    public String ticker() { return BOOKS.get(book()).item(); }
    public boolean selling() { return data.get(D_SELL) != 0; }
    public boolean market() { return data.get(D_MARKET) != 0; }
    public long qty() { return pair(D_QTY); }
    public long priceCents() { return pair(D_PRICE); }
    public long bidCents() { return pair(D_BID); }
    public long askCents() { return pair(D_ASK); }
    public long lastCents() { return pair(D_LAST); }
    public int secondsToAuction() { return data.get(D_SECONDS); }
    public int orderCount() { return data.get(D_ORDERS); }
    public int orderBook(int i) { return data.get(D_ORDER_BASE + i * ORDER_STRIDE); }
    public boolean orderSelling(int i) { return data.get(D_ORDER_BASE + i * ORDER_STRIDE + 1) != 0; }
    public long orderQty(int i) { return pair(D_ORDER_BASE + i * ORDER_STRIDE + 2); }
    public long orderFilled(int i) { return pair(D_ORDER_BASE + i * ORDER_STRIDE + 4); }
    public long orderPrice(int i) { return pair(D_ORDER_BASE + i * ORDER_STRIDE + 6); }
    public long todayAvg() { return pair(D_TODAY); }
    public long todayLow() { return pair(D_TODAY + 2); }
    public long todayHigh() { return pair(D_TODAY + 4); }
    /** Shares of the chosen company in the player's inventory. */
    public long held() { return pair(D_HELD); }
    /** Dividends (cents) the certificates in the player's inventory would pay now. */
    public long dividendsWaiting() { return pair(D_WAITING); }
    public int reportCount() { return data.get(D_REPORT_COUNT); }
    public long reportQuarter(int i) { return pair(D_REPORTS + i * REPORT_STRIDE); }
    public long reportRevenueDollars(int i) { return pair(D_REPORTS + i * REPORT_STRIDE + 2); }
    public long reportCostsDollars(int i) { return pair(D_REPORTS + i * REPORT_STRIDE + 4); }
    public long reportEarningsDollars(int i) {
        long v = pair(D_REPORTS + i * REPORT_STRIDE + 6);
        return data.get(D_REPORTS + i * REPORT_STRIDE + 8) != 0 ? -v : v;
    }
    public long reportDividendCents(int i) { return data.get(D_REPORTS + i * REPORT_STRIDE + 9); }
    public long cashPerShareCents() { long v = pair(D_CASH); return data.get(D_CASH + 2) != 0 ? -v : v; }

    private long pair(int i) {
        return (long) data.get(i) | ((long) data.get(i + 1) << 15);
    }

    private void setPair(int i, long value) {
        long v = Math.min(Math.max(value, 0), MAX_SYNCED);
        data.set(i, (int) (v & 0x7FFF));
        data.set(i + 1, (int) ((v >> 15) & 0x7FFF));
    }

    // ---- server

    private double day() {
        return dealer.day(player.level().getGameTime());
    }

    public Container output() {
        return output;
    }

    private void selectBook(int i) {
        data.set(D_BOOK, i);
        String t = BOOKS.get(i).item();
        setPair(D_PRICE, stocks.market().reference(t, stocks.fairCents(t)));
    }

    @Override
    public void broadcastChanges() {
        if (stocks != null && ++ticks % 10 == 0) refresh();
        super.broadcastChanges();
    }

    private void refresh() {
        long today = (long) Math.floor(day());
        stocks.deliver(player, output, progression, today);
        String t = ticker();
        long fair = stocks.fairCents(t);
        long[] q = stocks.market().population(t).makerQuotes(fair);
        setPair(D_BID, q[0]);
        setPair(D_ASK, q[1]);
        setPair(D_LAST, stocks.market().exchange().lastPrice(t).orElse(0));
        data.set(D_SECONDS, (int) Math.ceil(stocks.ticksToAuction() / 20.0));
        var bar = stocks.market().history().day(t, today);
        setPair(D_TODAY, bar.map(b -> b.average()).orElse(0L));
        setPair(D_TODAY + 2, bar.map(b -> b.low()).orElse(0L));
        setPair(D_TODAY + 4, bar.map(b -> b.high()).orElse(0L));
        shown = stocks.market().openTickets(StockService.account(player));
        data.set(D_ORDERS, Math.min(shown.size(), MAX_SHOWN_ORDERS));
        for (int i = 0; i < Math.min(shown.size(), MAX_SHOWN_ORDERS); i++) {
            TradingFloor.Ticket tk = shown.get(i);
            int base = D_ORDER_BASE + i * ORDER_STRIDE, b = 0;
            while (b < BOOKS.size() && !BOOKS.get(b).item().equals(tk.item())) b++;
            data.set(base, b);
            data.set(base + 1, tk.side() == Side.SELL ? 1 : 0);
            setPair(base + 2, tk.qty());
            setPair(base + 4, tk.filledQty());
            setPair(base + 6, tk.limitCents());
        }
        setPair(D_HELD, ShareCertificates.loose(player.getInventory()).getOrDefault(t, 0L));
        setPair(D_WAITING, stocks.dividendsWaiting(player));
        List<Equities.Report> reports = stocks.equities().reports(t);
        int n = Math.min(REPORT_QUARTERS, reports.size());
        data.set(D_REPORT_COUNT, n);
        for (int i = 0; i < n; i++) {
            Equities.Report r = reports.get(reports.size() - 1 - i); // newest first
            int base = D_REPORTS + i * REPORT_STRIDE;
            setPair(base, r.quarter());
            setPair(base + 2, r.revenue() / 100);
            setPair(base + 4, r.costs() / 100);
            setPair(base + 6, Math.abs(r.earnings()) / 100);
            data.set(base + 8, r.earnings() < 0 ? 1 : 0);
            data.set(base + 9, (int) Math.min(Short.MAX_VALUE, r.dividend()));
        }
        long cash = stocks.equities().cashPerShareCents(t);
        setPair(D_CASH, Math.abs(cash));
        data.set(D_CASH + 2, cash < 0 ? 1 : 0);
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (stocks == null) return false;
        Optional<String> why = Optional.empty();
        boolean handled = true;
        long price = priceCents(), today = (long) Math.floor(day());
        if (id >= BUTTON_BOOK_BASE && id < BUTTON_BOOK_BASE + BOOKS.size()) {
            selectBook(id - BUTTON_BOOK_BASE);
        } else if (id >= BUTTON_CANCEL_BASE && id < BUTTON_CANCEL_BASE + MAX_SHOWN_ORDERS) {
            int i = id - BUTTON_CANCEL_BASE;
            why = i < shown.size() ? stocks.cancel(p, shown.get(i).orderId(), today) : Optional.of("No such order");
        } else if (id >= BUTTON_REPRICE_BASE && id < BUTTON_REPRICE_BASE + MAX_SHOWN_ORDERS) {
            int i = id - BUTTON_REPRICE_BASE;
            why = i < shown.size() ? stocks.reprice(p, shown.get(i).orderId(), price, today) : Optional.of("No such order");
        } else {
            switch (id) {
                case BUTTON_TAB_TRADE -> data.set(D_TAB, TAB_TRADE);
                case BUTTON_TAB_COMPANY -> data.set(D_TAB, TAB_COMPANY);
                case BUTTON_SIDE -> data.set(D_SELL, selling() ? 0 : 1);
                case BUTTON_MARKET -> data.set(D_MARKET, market() ? 0 : 1);
                case BUTTON_QTY_MINUS_10 -> setPair(D_QTY, Math.max(1, qty() - 10));
                case BUTTON_QTY_MINUS_1 -> setPair(D_QTY, Math.max(1, qty() - 1));
                case BUTTON_QTY_PLUS_1 -> setPair(D_QTY, qty() + 1);
                case BUTTON_QTY_PLUS_10 -> setPair(D_QTY, qty() + 10);
                case BUTTON_PRICE_MINUS_10PCT -> setPair(D_PRICE, Math.max(1, Math.round(price * 0.9)));
                case BUTTON_PRICE_MINUS_1 -> setPair(D_PRICE, Math.max(1, price - 1));
                case BUTTON_PRICE_PLUS_1 -> setPair(D_PRICE, price + 1);
                case BUTTON_PRICE_PLUS_10PCT -> setPair(D_PRICE, Math.max(price + 1, Math.round(price * 1.1)));
                case BUTTON_PLACE -> why = stocks.place(p, ticker(), selling() ? Side.SELL : Side.BUY, qty(), price, market(),
                        day(), progression);
                case BUTTON_COLLECT -> {
                    long paid = stocks.collectDividends(p, progression, today);
                    if (paid == 0) why = Optional.of("No dividends waiting on the certificates you carry");
                }
                case BUTTON_MERGE -> stocks.merge(p, progression, today);
                case BUTTON_SPLIT -> why = stocks.split(p, ticker());
                default -> handled = false;
            }
        }
        if (why.isPresent()) {
            handled = false;
            if (p instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal(why.get()));
        }
        refresh();
        return handled;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        if (index >= OUTPUT_SLOTS) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (!moveItemStackTo(stack, INV_START, INV_END, true)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public void removed(Player p) {
        super.removed(p);
        if (!p.level().isClientSide()) clearContainer(p, output);
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.STOCK_EXCHANGE);
    }
}
