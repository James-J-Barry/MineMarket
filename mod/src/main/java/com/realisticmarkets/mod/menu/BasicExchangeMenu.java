package com.realisticmarkets.mod.menu;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.money.Denomination;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.progression.ProgressionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Basic Exchange screen: a Sell tab (input slot, live quote, Sell button, 2x2 payout grid)
 * and a Buy tab (villager-style scrollable list of everything the Dealer sells).
 *
 * <p>Every price shown is per item:
 * <ul>
 *   <li><b>Normal</b>: the Dealer's fair value, what the price recovers to once it clears its stock.</li>
 *   <li><b>Market</b>: the Dealer's current mid price.</li>
 *   <li><b>Pays</b> (Sell tab) / <b>Sells</b> (Buy tab): what actually changes hands.</li>
 * </ul>
 *
 * <p>All state the client shows travels through {@link ContainerData}. Data slots are synced as
 * shorts, so every number is split into two 15-bit halves (max 2^30). Prices are in mills
 * (thousandths of a dollar) so cheap items like cobblestone still show useful precision.
 * The server refreshes prices every {@link #REFRESH_TICKS} ticks and right after any action.
 */
public class BasicExchangeMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 222;

    // ---- slots
    public static final int INPUT = 0;
    public static final int OUTPUT_START = 1;
    public static final int OUTPUT_COUNT = 4;
    public static final int INV_START = OUTPUT_START + OUTPUT_COUNT; // 5
    public static final int INV_END = INV_START + 36;                 // 41 (exclusive)
    public static final int INPUT_X = 26, INPUT_Y = 30;
    public static final int OUTPUT_X = 116, OUTPUT_Y = 22;
    public static final int INVENTORY_Y = 140;

    // ---- tabs and buttons
    public static final int TAB_SELL = 0;
    public static final int TAB_BUY = 1;
    public static final int BUTTON_SELL = 0;
    public static final int BUTTON_TAB_SELL = 1;
    public static final int BUTTON_TAB_BUY = 2;
    public static final int BUTTON_BUY_FIRST = 3; // 3, 4, 5 -> BUY_QUANTITIES
    public static final int BUTTON_SELECT_BASE = 100;
    public static final int[] BUY_QUANTITIES = {1, 16, 64};

    // ---- sell status
    public static final int STATUS_EMPTY = 0;
    public static final int STATUS_OK = 1;
    public static final int STATUS_NOT_TRADED = 2;
    public static final int STATUS_COLLAPSED = 3;
    public static final int STATUS_MONEY = 4;

    public static final Denomination[] OUTPUT_ORDER = {
            Denomination.DIME, Denomination.ONE, Denomination.TEN, Denomination.HUNDRED};

    // ---- data layout (indices into ContainerData)
    private static final int D_TAB = 0;
    private static final int D_STATUS = 1;
    private static final int D_SELL_TOTAL = 2;   // cents (2 slots)
    private static final int D_SELL_NORMAL = 4;  // mills per item (2)
    private static final int D_SELL_MARKET = 6;  // mills (2)
    private static final int D_SELL_PAYS = 8;    // mills (2)
    private static final int D_CASH = 10;        // cents (2)
    private static final int D_SELECTED = 12;    // selected buy index + 1 (0 = none)
    private static final int D_BUY_TOTALS = 13;  // cents for each BUY_QUANTITIES entry (3 x 2)
    private static final int D_COUNT = 19;       // number of buy-list items
    private static final int D_ITEMS = 20;       // per item: raw item id, normal, market, sells (4 x 2), group (1)
    private static final int ITEM_STRIDE = 9;
    public static final int MAX_ITEMS = 64;
    private static final int DATA_SIZE = D_ITEMS + MAX_ITEMS * ITEM_STRIDE;

    private static final long MAX_SYNCED = (1L << 30) - 1;
    private static final int REFRESH_TICKS = 10;

    private final Container input = new SimpleContainer(1);
    private final Container output = new SimpleContainer(OUTPUT_COUNT);
    private final ContainerData data = new SimpleContainerData(DATA_SIZE);
    private final ContainerLevelAccess access;
    private final Player player;
    private final DealerService dealer;          // null on the client
    private final ProgressionService progression; // null on the client and in M1 tests
    private final List<String> buyList = new ArrayList<>(); // server only
    private int ticks;
    private boolean inputChanged = true;

    /** Client-side constructor, called when the server opens the screen. */
    public BasicExchangeMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL, null);
    }

    /** Server-side constructor without progression: no license, no components, no quest events. */
    public BasicExchangeMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access, DealerService dealer) {
        this(containerId, playerInventory, access, dealer, null);
    }

    /** Server-side constructor. */
    public BasicExchangeMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access, DealerService dealer,
                             ProgressionService progression) {
        super(ModMenus.BASIC_EXCHANGE, containerId);
        this.access = access;
        this.player = playerInventory.player;
        this.dealer = dealer;
        this.progression = progression;

        addSlot(new Slot(input, 0, INPUT_X, INPUT_Y) {
            @Override
            public boolean isActive() {
                return tab() == TAB_SELL;
            }

            @Override
            public void setChanged() {
                super.setChanged();
                inputChanged = true; // quote the new stack immediately, not on the next refresh tick
            }
        });
        for (int i = 0; i < OUTPUT_COUNT; i++) {
            addSlot(new Slot(output, i, OUTPUT_X + (i % 2) * 18, OUTPUT_Y + (i / 2) * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean isActive() {
                    return tab() == TAB_SELL;
                }
            });
        }
        addStandardInventorySlots(playerInventory, 8, INVENTORY_Y);
        addDataSlots(data);

        if (dealer != null) refresh();
    }

    /** The catalog minus components this player hasn't unlocked a blueprint for. */
    private void rebuildBuyList() {
        Set<String> visible = progression == null ? Set.of() : progression.visibleComponents(player, dealer.dealer().catalog());
        buyList.clear();
        for (var e : dealer.dealer().catalog().all().entrySet()) {
            if (buyList.size() == MAX_ITEMS) break;
            String id = e.getKey();
            if (ProgressionService.COMPONENTS_GROUP.equals(e.getValue().group()) && !visible.contains(id)) continue;
            if (itemFor(id) != Items.AIR) buyList.add(id);
        }
    }

    private boolean licensed() {
        return progression != null && progression.licensed(player);
    }

    // ================================================================== reads (client and server)

    public int tab() { return data.get(D_TAB); }
    public int status() { return data.get(D_STATUS); }
    public long quoteCents() { return pair(D_SELL_TOTAL); }
    public long sellNormalMills() { return pair(D_SELL_NORMAL); }
    public long sellMarketMills() { return pair(D_SELL_MARKET); }
    public long sellPaysMills() { return pair(D_SELL_PAYS); }
    public long cashCents() { return pair(D_CASH); }
    public int selected() { return data.get(D_SELECTED) - 1; }
    public long buyTotalCents(int quantityIndex) { return pair(D_BUY_TOTALS + quantityIndex * 2); }
    public int itemCount() { return data.get(D_COUNT); }
    public ItemStack inputStack() { return input.getItem(0); }

    public Item buyItem(int i) {
        return BuiltInRegistries.ITEM.byId((int) pair(D_ITEMS + i * ITEM_STRIDE));
    }
    public long buyNormalMills(int i) { return pair(D_ITEMS + i * ITEM_STRIDE + 2); }
    public long buyMarketMills(int i) { return pair(D_ITEMS + i * ITEM_STRIDE + 4); }
    public long buySellsMills(int i) { return pair(D_ITEMS + i * ITEM_STRIDE + 6); }
    public int buyGroup(int i) { return data.get(D_ITEMS + i * ITEM_STRIDE + 8); }

    /** Catalog groups in display order; anything else is shown under "Other". */
    public static final String[] GROUPS = {"farm", "mining", "mobs", "wood_and_stone", ProgressionService.COMPONENTS_GROUP};
    public static final int GROUP_OTHER = GROUPS.length;

    private static int groupIndex(String group) {
        for (int g = 0; g < GROUPS.length; g++) if (GROUPS[g].equals(group)) return g;
        return GROUP_OTHER;
    }

    /** Server-side: position of an item id in the buy list, or -1. */
    public int indexOf(String itemId) {
        return buyList.indexOf(itemId);
    }

    private long pair(int i) {
        return (long) data.get(i) | ((long) data.get(i + 1) << 15);
    }

    private void setPair(int i, long value) {
        long v = Math.min(Math.max(value, 0), MAX_SYNCED);
        data.set(i, (int) (v & 0x7FFF));
        data.set(i + 1, (int) ((v >> 15) & 0x7FFF));
    }

    private static long mills(double dollars) {
        return Math.round(dollars * 1000.0);
    }

    // ================================================================== server: prices

    @Override
    public void broadcastChanges() {
        if (dealer != null && (++ticks % REFRESH_TICKS == 0 || inputChanged)) {
            inputChanged = false;
            refresh();
        }
        super.broadcastChanges();
    }

    private double day() {
        return dealer.day(player.level().getGameTime());
    }

    private static Item itemFor(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private void refresh() {
        double day = day();
        Dealer d = dealer.dealer();
        boolean licensed = licensed();
        rebuildBuyList();
        refreshSell(d, day, licensed);
        setPair(D_CASH, Wallet.count(player.getInventory()) + drawerCents());

        data.set(D_COUNT, buyList.size());
        for (int i = 0; i < buyList.size(); i++) {
            String id = buyList.get(i);
            int base = D_ITEMS + i * ITEM_STRIDE;
            setPair(base, BuiltInRegistries.ITEM.getId(itemFor(id)));
            setPair(base + 2, mills(d.fairValue(id, day)));
            setPair(base + 4, mills(d.mid(id, day)));
            setPair(base + 6, mills(d.ask(id, day, licensed)));
            data.set(base + 8, groupIndex(d.catalog().spec(id).group()));
        }
        int sel = selected();
        for (int q = 0; q < BUY_QUANTITIES.length; q++) {
            long total = 0;
            if (sel >= 0 && sel < buyList.size()) {
                try {
                    total = d.quoteBuy(buyList.get(sel), BUY_QUANTITIES[q], day, licensed).cents();
                } catch (RejectedException e) {
                    total = 0;
                }
            }
            setPair(D_BUY_TOTALS + q * 2, total);
        }
    }

    private void refreshSell(Dealer d, double day, boolean licensed) {
        ItemStack stack = input.getItem(0);
        int status;
        long total = 0, normal = 0, market = 0, pays = 0;
        if (stack.isEmpty()) {
            status = STATUS_EMPTY;
        } else if (ModItems.denominationOf(stack) != null) {
            status = STATUS_MONEY;
        } else if (!d.catalog().trades(DealerService.itemId(stack))) {
            status = STATUS_NOT_TRADED;
        } else {
            String id = DealerService.itemId(stack);
            normal = mills(d.fairValue(id, day));
            market = mills(d.mid(id, day));
            pays = mills(d.bid(id, day, licensed));
            try {
                total = d.quoteSell(id, stack.getCount(), day, licensed).cents();
                status = STATUS_OK;
            } catch (RejectedException e) {
                status = STATUS_COLLAPSED;
            }
        }
        data.set(D_STATUS, status);
        setPair(D_SELL_TOTAL, total);
        setPair(D_SELL_NORMAL, normal);
        setPair(D_SELL_MARKET, market);
        setPair(D_SELL_PAYS, pays);
    }

    // ================================================================== server: actions

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (dealer == null) return false;
        boolean handled;
        if (id == BUTTON_TAB_SELL || id == BUTTON_TAB_BUY) {
            data.set(D_TAB, id == BUTTON_TAB_SELL ? TAB_SELL : TAB_BUY);
            handled = true;
        } else if (id == BUTTON_SELL) {
            handled = tab() == TAB_SELL && sell(p);
        } else if (id >= BUTTON_BUY_FIRST && id < BUTTON_BUY_FIRST + BUY_QUANTITIES.length) {
            handled = tab() == TAB_BUY && buy(p, BUY_QUANTITIES[id - BUTTON_BUY_FIRST]);
        } else if (id >= BUTTON_SELECT_BASE && id < BUTTON_SELECT_BASE + buyList.size()) {
            data.set(D_SELECTED, id - BUTTON_SELECT_BASE + 1);
            handled = true;
        } else {
            handled = false;
        }
        if (handled) refresh();
        return handled;
    }

    private boolean sell(Player p) {
        ItemStack stack = input.getItem(0);
        if (stack.isEmpty() || ModItems.denominationOf(stack) != null) return false;
        String id = DealerService.itemId(stack);
        int qty = stack.getCount();
        double day = day();
        Dealer d = dealer.dealer();
        long cents;
        double before;
        try {
            before = d.mid(id, day) / d.fairValue(id, day);
            cents = dealer.sellStack(stack, day, licensed());
        } catch (RejectedException e) {
            return false;
        }
        input.setItem(0, ItemStack.EMPTY);
        payIntoDrawer(p, cents);
        if (progression != null) {
            double after = d.mid(id, day) / d.fairValue(id, day);
            String group = d.catalog().spec(id).group();
            progression.emit(p, new ProgressionEvent.Sale(id, group, qty, cents, before, after, (long) Math.floor(day)));
            progression.emitNetWorth(p, dealer, day, drawerCents());
        }
        return true;
    }

    /**
     * Buys from the Dealer, paying with every bill and coin the player carries (Bill Clips included) and the
     * payout drawer; change goes into Bill Clips first, goods into the inventory (dropped if full).
     */
    private boolean buy(Player p, int quantity) {
        int sel = selected();
        if (sel < 0 || sel >= buyList.size()) return false;
        String id = buyList.get(sel);
        Item item = itemFor(id);
        double day = day();
        long cost;
        try {
            cost = dealer.dealer().quoteBuy(id, quantity, day, licensed()).cents();
        } catch (RejectedException e) {
            return false;
        }
        long available = Wallet.count(p.getInventory()) + drawerCents();
        if (available < cost) return false;

        for (int i = 0; i < OUTPUT_COUNT; i++) output.setItem(i, ItemStack.EMPTY);
        Inventory inv = p.getInventory();
        Wallet.takeAll(p);
        dealer.dealer().buy(id, quantity, day, licensed());
        Wallet.give(p, available - cost);

        int left = quantity;
        while (left > 0) {
            int n = Math.min(left, item.getDefaultMaxStackSize());
            inv.placeItemBackInInventory(new ItemStack(item, n));
            left -= n;
        }
        if (progression != null) {
            String group = dealer.dealer().catalog().spec(id).group();
            progression.emit(p, new ProgressionEvent.Purchase(id, group, quantity, cost, (long) Math.floor(day)));
            progression.emitNetWorth(p, dealer, day, 0);
        }
        return true;
    }

    private long drawerCents() {
        long cents = 0;
        for (int i = 0; i < OUTPUT_COUNT; i++) {
            ItemStack s = output.getItem(i);
            Denomination d = ModItems.denominationOf(s);
            if (d != null) cents += d.cents() * s.getCount();
        }
        return cents;
    }

    /** Fills the denomination slots; whatever doesn't fit goes to the player's inventory. */
    private void payIntoDrawer(Player p, long cents) {
        for (Map.Entry<Denomination, Long> e : Money.makeChange(cents).entrySet()) {
            int slot = indexOf(e.getKey());
            Item item = ModItems.CURRENCY.get(e.getKey());
            long left = e.getValue();
            ItemStack current = output.getItem(slot);
            if (current.isEmpty()) {
                int n = (int) Math.min(left, 64);
                output.setItem(slot, new ItemStack(item, n));
                left -= n;
            } else if (current.is(item)) {
                int n = (int) Math.min(left, 64 - current.getCount());
                current.grow(n);
                left -= n;
            }
            while (left > 0) {
                int n = (int) Math.min(left, 64);
                p.getInventory().placeItemBackInInventory(new ItemStack(item, n));
                left -= n;
            }
        }
        output.setChanged();
    }

    private static int indexOf(Denomination d) {
        for (int i = 0; i < OUTPUT_ORDER.length; i++) if (OUTPUT_ORDER[i] == d) return i;
        throw new IllegalArgumentException(String.valueOf(d));
    }

    // ================================================================== container plumbing

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < INV_START) {
            if (!moveItemStackTo(stack, INV_START, INV_END, true)) return ItemStack.EMPTY;
        } else if (tab() != TAB_SELL || !moveItemStackTo(stack, INPUT, INPUT + 1, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(p, stack);
        return original;
    }

    @Override
    public void removed(Player p) {
        super.removed(p);
        if (!p.level().isClientSide()) {
            clearContainer(p, input);
            clearContainer(p, output);
        }
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.BASIC_EXCHANGE);
    }
}
