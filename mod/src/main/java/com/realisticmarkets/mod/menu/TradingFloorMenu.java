package com.realisticmarkets.mod.menu;

import com.realisticmarkets.agents.FloorCatalog;
import com.realisticmarkets.agents.TradingFloor;
import com.realisticmarkets.dealer.WorldEvents;
import com.realisticmarkets.exchange.Side;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.floor.FloorService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
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
 * The Trading Floor: pick a book, fill in an order (buy or sell, limit or market, quantity, price), place it with an
 * Order Slip, watch your open orders, and collect fills, refunds and receipts from the output slots.
 */
public class TradingFloorMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176, HEIGHT = 254, INVENTORY_Y = 172, NEWS_Y = 143, NEWS_LINES = 2;
    public static final int OUTPUT_X = 8, OUTPUT_Y = 122, OUTPUT_SLOTS = 6;
    public static final int INV_START = OUTPUT_SLOTS, INV_END = INV_START + 36;
    public static final int MAX_SHOWN_ORDERS = 3;
    public static final List<FloorCatalog.Book> BOOKS = FloorCatalog.loadDefault().all();

    public static final int BUTTON_SIDE = 0, BUTTON_MARKET = 1, BUTTON_PLACE = 2;
    public static final int BUTTON_QTY_MINUS_16 = 3, BUTTON_QTY_MINUS_1 = 4, BUTTON_QTY_PLUS_1 = 5, BUTTON_QTY_PLUS_16 = 6;
    public static final int BUTTON_PRICE_MINUS_10PCT = 7, BUTTON_PRICE_MINUS_1 = 8, BUTTON_PRICE_PLUS_1 = 9, BUTTON_PRICE_PLUS_10PCT = 10;
    public static final int BUTTON_CANCEL_BASE = 20; // + shown order index
    public static final int BUTTON_BOOK_BASE = 100;  // + book index

    private static final int D_BOOK = 0, D_SELL = 1, D_MARKET = 2, D_QTY = 3, D_PRICE = 5, D_BID = 7, D_ASK = 9;
    private static final int D_LAST = 11, D_SECONDS = 13, D_ORDERS = 14, D_ORDER_BASE = 15, ORDER_STRIDE = 8;
    private static final int D_NEWS = D_ORDER_BASE + MAX_SHOWN_ORDERS * ORDER_STRIDE; // per line: type index + 1, age in days
    private static final int D_SIZE = D_NEWS + NEWS_LINES * 2;
    private static final long MAX_SYNCED = (1L << 30) - 1;

    private final Container output = new SimpleContainer(OUTPUT_SLOTS);
    private final ContainerData data = new SimpleContainerData(D_SIZE);
    private final ContainerLevelAccess access;
    private final Player player;
    private final FloorService floor; // null on the client
    private final ProgressionService progression;
    private final DealerService dealer;
    private List<TradingFloor.Ticket> shown = List.of();
    private int ticks;

    public TradingFloorMenu(int containerId, Inventory inv) {
        this(containerId, inv, ContainerLevelAccess.NULL, null, null, null);
    }

    public TradingFloorMenu(int containerId, Inventory inv, ContainerLevelAccess access, FloorService floor,
                            ProgressionService progression, DealerService dealer) {
        super(ModMenus.TRADING_FLOOR, containerId);
        this.player = inv.player;
        this.access = access;
        this.floor = floor;
        this.progression = progression;
        this.dealer = dealer;
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            addSlot(new Slot(output, i, OUTPUT_X + i * 18, OUTPUT_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }
        addStandardInventorySlots(inv, 8, INVENTORY_Y);
        addDataSlots(data);
        if (floor != null) {
            setPair(D_QTY, 16);
            selectBook(0);
            refresh();
        }
    }

    // ---- reads (client and server)

    public int book() { return data.get(D_BOOK); }
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
    /** Index into {@code WorldEvents.loadTypes()} of news line {@code i}, or -1. */
    public int newsType(int i) { return data.get(D_NEWS + i * 2) - 1; }
    public int newsAge(int i) { return data.get(D_NEWS + i * 2 + 1); }

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

    private String item() {
        return BOOKS.get(book()).item();
    }

    private void selectBook(int i) {
        data.set(D_BOOK, i);
        long fair = floor.fairCents(BOOKS.get(i).item(), day());
        setPair(D_PRICE, floor.floor().reference(BOOKS.get(i).item(), fair));
    }

    @Override
    public void broadcastChanges() {
        if (floor != null && ++ticks % 10 == 0) refresh();
        super.broadcastChanges();
    }

    private void refresh() {
        floor.deliver(player, output, progression);
        String item = item();
        long fair = floor.fairCents(item, day());
        long[] q = floor.floor().population(item).makerQuotes(fair);
        setPair(D_BID, q[0]);
        setPair(D_ASK, q[1]);
        setPair(D_LAST, floor.floor().exchange().lastPrice(item).orElse(0));
        data.set(D_SECONDS, (int) Math.ceil(floor.ticksToAuction() / 20.0));
        shown = floor.floor().openTickets(FloorService.account(player));
        data.set(D_ORDERS, Math.min(shown.size(), MAX_SHOWN_ORDERS));
        for (int i = 0; i < Math.min(shown.size(), MAX_SHOWN_ORDERS); i++) {
            TradingFloor.Ticket t = shown.get(i);
            int base = D_ORDER_BASE + i * ORDER_STRIDE;
            int b = 0;
            while (b < BOOKS.size() && !BOOKS.get(b).item().equals(t.item())) b++;
            data.set(base, b);
            data.set(base + 1, t.side() == Side.SELL ? 1 : 0);
            setPair(base + 2, t.qty());
            setPair(base + 4, t.filledQty());
            setPair(base + 6, t.limitCents());
        }
        List<WorldEvents.Event> news = floor.news(day());
        long today = (long) Math.floor(day());
        for (int i = 0; i < NEWS_LINES; i++) {
            data.set(D_NEWS + i * 2, i < news.size() ? news.get(i).type().index() + 1 : 0);
            data.set(D_NEWS + i * 2 + 1, i < news.size() ? (int) (today - news.get(i).day()) : 0);
        }
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (floor == null) return false;
        Optional<String> why = Optional.empty();
        boolean handled = true;
        long price = priceCents();
        if (id >= BUTTON_BOOK_BASE && id < BUTTON_BOOK_BASE + BOOKS.size()) {
            selectBook(id - BUTTON_BOOK_BASE);
        } else if (id >= BUTTON_CANCEL_BASE && id < BUTTON_CANCEL_BASE + MAX_SHOWN_ORDERS) {
            int i = id - BUTTON_CANCEL_BASE;
            why = i < shown.size() ? floor.cancel(p, shown.get(i).orderId(), (long) Math.floor(day())) : Optional.of("No such order");
        } else {
            switch (id) {
                case BUTTON_SIDE -> data.set(D_SELL, selling() ? 0 : 1);
                case BUTTON_MARKET -> data.set(D_MARKET, market() ? 0 : 1);
                case BUTTON_QTY_MINUS_16 -> setPair(D_QTY, Math.max(1, qty() - 16));
                case BUTTON_QTY_MINUS_1 -> setPair(D_QTY, Math.max(1, qty() - 1));
                case BUTTON_QTY_PLUS_1 -> setPair(D_QTY, qty() + 1);
                case BUTTON_QTY_PLUS_16 -> setPair(D_QTY, qty() + 16);
                case BUTTON_PRICE_MINUS_10PCT -> setPair(D_PRICE, Math.max(1, Math.round(price * 0.9)));
                case BUTTON_PRICE_MINUS_1 -> setPair(D_PRICE, Math.max(1, price - 1));
                case BUTTON_PRICE_PLUS_1 -> setPair(D_PRICE, price + 1);
                case BUTTON_PRICE_PLUS_10PCT -> setPair(D_PRICE, Math.max(price + 1, Math.round(price * 1.1)));
                case BUTTON_PLACE -> why = floor.place(p, item(), selling() ? Side.SELL : Side.BUY, qty(), priceCents(),
                        market(), day(), progression.licensed(p));
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

    /** Server-side, for tests. */
    public Container output() {
        return output;
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
        return stillValid(access, p, ModBlocks.TRADING_FLOOR);
    }
}
