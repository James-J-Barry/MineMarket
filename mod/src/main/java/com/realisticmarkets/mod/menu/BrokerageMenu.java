package com.realisticmarkets.mod.menu;

import com.realisticmarkets.mod.fx.Feedback;

import com.realisticmarkets.custody.BookEntries;
import com.realisticmarkets.mod.brokerage.BrokerageService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Brokerage Terminal. Holdings: book entries at today's value; Deposit every paper carried; Withdraw the selected
 * entry as papers. Cash: the account that income is credited to, paid out in bills. Markets: every exchange's screen
 * from here.
 */
public class BrokerageMenu extends AbstractContainerMenu {
    public static final int WIDTH = 256, HEIGHT = 206, ROWS = 8;
    public static final int TAB_HOLDINGS = 0, TAB_CASH = 1, TAB_MARKETS = 2;
    public static final int BUTTON_ROW_BASE = 10, BUTTON_WITHDRAW = 20, BUTTON_DEPOSIT = 21, BUTTON_CASH_OUT = 22;
    public static final int BUTTON_MARKET_BASE = 30;
    public static final String[] MARKETS = {"Trading Floor", "Stock Exchange", "Bond Desk", "Options Desk", "Clearing House"};

    private static final int L = 4;
    private static final int D_OWNER = 0, D_TAB = 1, D_SELECTED = 2, D_COUNT = 3, D_CASH = 4, D_TOTAL = D_CASH + L;
    private static final int D_ROW = D_TOTAL + L, ROW_STRIDE = 1 + L; // quantity (as long)... value per unit
    private static final int D_SIZE = D_ROW + ROWS * 2 * L;

    private final ContainerData data = new SimpleContainerData(D_SIZE);
    private final SimpleContainer icons = new SimpleContainer(ROWS);
    private final ContainerLevelAccess access;
    private final Player player;
    private final BrokerageService brokerage; // null on the client
    private final ProgressionService progression;
    private final DealerService dealer;
    private List<BookEntries.Entry> shown = List.of();
    private int ticks;

    public BrokerageMenu(int containerId, Inventory inv) {
        this(containerId, inv, ContainerLevelAccess.NULL, null, null, null);
    }

    public BrokerageMenu(int containerId, Inventory inv, ContainerLevelAccess access, BrokerageService brokerage,
                         ProgressionService progression, DealerService dealer) {
        super(ModMenus.BROKERAGE, containerId);
        this.access = access;
        this.player = inv.player;
        this.brokerage = brokerage;
        this.progression = progression;
        this.dealer = dealer;
        for (int i = 0; i < ROWS; i++) {
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
        if (brokerage != null) {
            data.set(D_SELECTED, -1 + 1000);
            refresh();
        }
    }

    public boolean owner() { return data.get(D_OWNER) != 0; }
    public int tab() { return data.get(D_TAB); }
    public int selected() { return data.get(D_SELECTED) - 1000; }
    public int rowCount() { return data.get(D_COUNT); }
    public long cash() { return getLong(D_CASH); }
    public long total() { return getLong(D_TOTAL); }
    public long rowQuantity(int i) { return getLong(D_ROW + i * 2 * L); }
    public long rowValue(int i) { return getLong(D_ROW + i * 2 * L + L); }
    public ItemStack rowIcon(int i) { return icons.getItem(i); }

    private static final long OFFSET = 1L << 59;

    private void setLong(int i, long value) {
        long v = Math.max(-OFFSET + 1, Math.min(OFFSET - 1, value)) + OFFSET;
        for (int k = 0; k < L; k++) data.set(i + k, (int) ((v >> (15 * k)) & 0x7FFF));
    }

    private long getLong(int i) {
        long v = 0;
        for (int k = 0; k < L; k++) v |= (long) (data.get(i + k) & 0x7FFF) << (15 * k);
        return v - OFFSET;
    }

    private double now() {
        return dealer.day(player.level().getGameTime());
    }

    private void refresh() {
        double day = now();
        data.set(D_OWNER, progression == null || progression.progress(player).hasNode(BrokerageService.NODE) ? 1 : 0);
        String a = BrokerageService.account(player);
        shown = brokerage.entries(player);
        int n = Math.min(ROWS, shown.size());
        data.set(D_COUNT, n);
        long total = brokerage.books().cash(a);
        for (BookEntries.Entry e : shown) total += e.quantity() * brokerage.unitValue(a, e, day);
        setLong(D_CASH, brokerage.books().cash(a));
        setLong(D_TOTAL, total);
        if (selected() >= n) data.set(D_SELECTED, -1 + 1000);
        for (int i = 0; i < ROWS; i++) {
            if (i >= n) {
                icons.setItem(i, ItemStack.EMPTY);
                continue;
            }
            BookEntries.Entry e = shown.get(i);
            setLong(D_ROW + i * 2 * L, e.quantity());
            setLong(D_ROW + i * 2 * L + L, e.quantity() * brokerage.unitValue(a, e, day));
            icons.setItem(i, BrokerageService.icon(e));
        }
    }

    @Override
    public void broadcastChanges() {
        if (brokerage != null && ++ticks % 20 == 0) refresh();
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (brokerage == null || !owner()) return false;
        Optional<String> why = Optional.empty();
        boolean handled = true;
        if (id == TAB_HOLDINGS || id == TAB_CASH || id == TAB_MARKETS) {
            data.set(D_TAB, id);
        } else if (id >= BUTTON_ROW_BASE && id < BUTTON_ROW_BASE + ROWS) {
            data.set(D_SELECTED, id - BUTTON_ROW_BASE + 1000);
        } else if (id == BUTTON_WITHDRAW) {
            int i = selected();
            if (i < 0 || i >= shown.size()) why = Optional.of("Pick a holding first");
            else why = brokerage.withdraw(p, shown.get(i).kind(), shown.get(i).key(), shown.get(i).quantity());
        } else if (id == BUTTON_DEPOSIT) {
            if (brokerage.depositAll(p, now(), progression) == 0) why = Optional.of("You carry no securities");
        } else if (id == BUTTON_CASH_OUT) {
            if (brokerage.withdrawCash(p) == 0) why = Optional.of("No cash in the account");
        } else if (id >= BUTTON_MARKET_BASE && id < BUTTON_MARKET_BASE + MARKETS.length) {
            why = openMarket(p, id - BUTTON_MARKET_BASE);
        } else {
            handled = false;
        }
        if (why.isPresent()) {
            handled = false;
            if (p instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal(why.get()));
        }
        if (brokerage != null) refresh();
        if (handled) Feedback.play(p, access, cueFor(id));
        return handled;
    }

    /** Opens an exchange's own screen from here (no need to stand at it). */
    private Optional<String> openMarket(Player p, int i) {
        ContainerLevelAccess anywhere = ContainerLevelAccess.NULL;
        var prog = progression;
        var d = dealer;
        try {
            switch (i) {
                case 0 -> p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new TradingFloorMenu(id, inv, anywhere,
                        com.realisticmarkets.mod.floor.FloorService.get(), prog, d), Component.translatable("container.realisticmarkets.trading_floor")));
                case 1 -> p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new StockExchangeMenu(id, inv, anywhere,
                        com.realisticmarkets.mod.stocks.StockService.get(), prog, d), Component.translatable("container.realisticmarkets.stock_exchange")));
                case 2 -> p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new BondDeskMenu(id, inv, anywhere,
                        com.realisticmarkets.mod.bonds.BondService.get(), prog, d), Component.translatable("container.realisticmarkets.bond_desk")));
                case 3 -> p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new OptionsDeskMenu(id, inv, anywhere,
                        com.realisticmarkets.mod.options.OptionsService.get(), prog, d), Component.translatable("container.realisticmarkets.options_desk")));
                default -> p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new ClearingHouseMenu(id, inv, anywhere,
                        com.realisticmarkets.mod.futures.FuturesService.get(), prog, d), Component.translatable("container.realisticmarkets.clearing_house")));
            }
        } catch (IllegalStateException notRunning) {
            return Optional.of("That market isn't open");
        }
        return Optional.empty();
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.BROKERAGE_TERMINAL);
    }

    /** The sound and particles for a successful button press, or null for none. */
    private static Feedback.Cue cueFor(int id) {
        return switch (id) {
            case BUTTON_DEPOSIT -> Feedback.Cue.SIGNED;
            case BUTTON_WITHDRAW -> Feedback.Cue.PURCHASE;
            case BUTTON_CASH_OUT -> Feedback.Cue.SALE;
            default -> null;
        };
    }
}
