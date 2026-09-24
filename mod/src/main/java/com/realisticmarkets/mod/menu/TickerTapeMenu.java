package com.realisticmarkets.mod.menu;

import com.realisticmarkets.agents.FloorCatalog;
import com.realisticmarkets.exchange.PriceChart;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.floor.FloorService;
import com.realisticmarkets.mod.item.PriceChartItem;
import com.realisticmarkets.mod.progression.Materials;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.progression.ProgressionEvent;
import java.util.List;
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
import net.minecraft.world.item.ItemStack;

/** Pick a Floor book and print its Price Chart for 1 Ledger Paper + 1 Ink Bottle from the inventory. */
public class TickerTapeMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176, HEIGHT = 178, INVENTORY_Y = 96, OUTPUT_X = 152, OUTPUT_Y = 28;
    public static final int COLS = 7, GRID_X = 8, GRID_Y = 18;
    public static final int BUTTON_PRINT = 0, BUTTON_BOOK_BASE = 100;
    public static final String PAPER = "realisticmarkets:ledger_paper", INK = "realisticmarkets:ink_bottle";
    public static final List<FloorCatalog.Book> BOOKS = TradingFloorMenu.BOOKS;

    private static final int D_BOOK = 0, D_AFFORD = 1;
    private final Container output = new SimpleContainer(1);
    private final ContainerData data = new SimpleContainerData(2);
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
        addSlot(new Slot(output, 0, OUTPUT_X, OUTPUT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });
        addStandardInventorySlots(inv, 8, INVENTORY_Y);
        addDataSlots(data);
        if (floor != null) refresh();
    }

    public int book() { return data.get(D_BOOK); }
    public boolean canAfford() { return data.get(D_AFFORD) != 0; }

    public Container output() { return output; }

    private void refresh() {
        Inventory inv = player.getInventory();
        data.set(D_AFFORD, Materials.count(inv, PAPER) >= 1 && Materials.count(inv, INK) >= 1 ? 1 : 0);
    }

    @Override
    public void broadcastChanges() {
        if (floor != null && ++ticks % 10 == 0) refresh();
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (floor == null) return false;
        boolean handled = false;
        if (id >= BUTTON_BOOK_BASE && id < BUTTON_BOOK_BASE + BOOKS.size()) {
            data.set(D_BOOK, id - BUTTON_BOOK_BASE);
            handled = true;
        } else if (id == BUTTON_PRINT) {
            handled = print(p);
        }
        refresh();
        return handled;
    }

    private boolean print(Player p) {
        Inventory inv = p.getInventory();
        if (Materials.count(inv, PAPER) < 1 || Materials.count(inv, INK) < 1) return false;
        String item = BOOKS.get(book()).item();
        double day = dealer.day(p.level().getGameTime());
        long today = (long) Math.floor(day);
        PriceChart chart = PriceChart.of(floor.floor().history(), item, today);
        String name = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(item))).getHoverName().getString();
        Materials.remove(inv, PAPER, 1);
        Materials.remove(inv, INK, 1);
        ItemStack printed = PriceChartItem.create(chart, name);
        if (output.getItem(0).isEmpty()) output.setItem(0, printed);
        else inv.placeItemBackInInventory(printed);
        output.setChanged();
        if (progression != null) progression.emit(p, new ProgressionEvent.ChartPrinted(item, today));
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        if (index != 0) return ItemStack.EMPTY;
        Slot slot = slots.get(0);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (!moveItemStackTo(stack, 1, 37, true)) return ItemStack.EMPTY;
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
        return stillValid(access, p, ModBlocks.TICKER_TAPE);
    }
}
