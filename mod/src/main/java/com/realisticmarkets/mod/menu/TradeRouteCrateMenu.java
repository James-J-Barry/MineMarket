package com.realisticmarkets.mod.menu;

import com.realisticmarkets.dealer.ShipmentBook;
import com.realisticmarkets.mod.block.TradeRouteCrateBlockEntity;
import com.realisticmarkets.mod.dealer.CapitalService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.registry.ModMenus;
import java.util.Map;
import java.util.Optional;
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
 * Trade Route Crate: 3x3 cargo, a Ship button, the 2x2 payout drawer, and a status line comparing the Capital's
 * estimate (after freight) with what the local Dealer would pay.
 */
public class TradeRouteCrateMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176, HEIGHT = 186;
    public static final int CARGO_X = 8, CARGO_Y = 18, DRAWER_X = 134, DRAWER_Y = 18, INVENTORY_Y = 104;
    public static final int CARGO_END = TradeRouteCrateBlockEntity.CARGO_SLOTS;              // 0..8
    public static final int DRAWER_END = CARGO_END + TradeRouteCrateBlockEntity.DRAWER_SLOTS;  // 9..12
    public static final int INV_END = DRAWER_END + 36;

    public static final int BUTTON_SHIP = 0;
    public static final int STATUS_EMPTY = 0, STATUS_READY = 1, STATUS_IN_TRANSIT = 2, STATUS_NOT_BOUGHT = 3;

    private static final int D_STATUS = 0, D_ESTIMATE = 1, D_LOCAL = 3, D_MINUTES = 5, D_SIZE = 6;
    private static final long MAX_SYNCED = (1L << 30) - 1;
    private static final int REFRESH_TICKS = 10;

    private final ContainerData data = new SimpleContainerData(D_SIZE);
    private final ContainerLevelAccess access;
    private final Player player;
    private final TradeRouteCrateBlockEntity crate; // server only
    private final DealerService dealer;
    private final CapitalService capital;
    private final ProgressionService progression;
    private int ticks;

    public TradeRouteCrateMenu(int containerId, Inventory inv) {
        this(containerId, inv, null, new SimpleContainer(CARGO_END), new SimpleContainer(DRAWER_END - CARGO_END),
                ContainerLevelAccess.NULL, null, null, null);
    }

    public TradeRouteCrateMenu(int containerId, Inventory inv, TradeRouteCrateBlockEntity crate, ContainerLevelAccess access,
                               DealerService dealer, CapitalService capital, ProgressionService progression) {
        this(containerId, inv, crate, crate.cargo(), crate.drawer(), access, dealer, capital, progression);
    }

    private TradeRouteCrateMenu(int containerId, Inventory inv, TradeRouteCrateBlockEntity crate, Container cargo,
                                Container drawer, ContainerLevelAccess access, DealerService dealer, CapitalService capital,
                                ProgressionService progression) {
        super(ModMenus.TRADE_ROUTE_CRATE, containerId);
        this.player = inv.player;
        this.crate = crate;
        this.access = access;
        this.dealer = dealer;
        this.capital = capital;
        this.progression = progression;
        for (int i = 0; i < CARGO_END; i++) {
            addSlot(new Slot(cargo, i, CARGO_X + (i % 3) * 18, CARGO_Y + (i / 3) * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return !inTransit() && ModItems.denominationOf(stack) == null;
                }
            });
        }
        for (int i = 0; i < DRAWER_END - CARGO_END; i++) {
            addSlot(new Slot(drawer, i, DRAWER_X + (i % 2) * 18, DRAWER_Y + (i / 2) * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }
        addStandardInventorySlots(inv, 8, INVENTORY_Y);
        addDataSlots(data);
        if (crate != null) refresh();
    }

    public int status() { return data.get(D_STATUS); }
    public long estimateCents() { return pair(D_ESTIMATE); }
    public long localCents() { return pair(D_LOCAL); }
    public int minutesLeft() { return data.get(D_MINUTES); }
    private boolean inTransit() { return status() == STATUS_IN_TRANSIT; }

    private long pair(int i) {
        return (long) data.get(i) | ((long) data.get(i + 1) << 15);
    }

    private void setPair(int i, long value) {
        long v = Math.min(Math.max(value, 0), MAX_SYNCED);
        data.set(i, (int) (v & 0x7FFF));
        data.set(i + 1, (int) ((v >> 15) & 0x7FFF));
    }

    private double day() {
        return dealer.day(player.level().getGameTime());
    }

    @Override
    public void broadcastChanges() {
        if (crate != null && ++ticks % REFRESH_TICKS == 0) refresh();
        super.broadcastChanges();
    }

    private void refresh() {
        double day = day();
        Optional<ShipmentBook.Shipment> onRoad = capital.inTransitAt(crate.location());
        long estimate = 0, local = 0;
        int minutes = 0;
        int status;
        if (onRoad.isPresent()) {
            status = STATUS_IN_TRANSIT;
            local = onRoad.get().localQuoteCents();
            estimate = capital.estimate(onRoad.get().items(), day);
            minutes = (int) Math.ceil(Math.max(0, onRoad.get().arrivesDay() - day) * 20.0);
        } else {
            Map<String, Integer> items = CapitalService.manifest(crate);
            if (items.isEmpty()) {
                status = STATUS_EMPTY;
            } else if (items.keySet().stream().anyMatch(id -> !capital.capital().catalog().trades(id))) {
                status = STATUS_NOT_BOUGHT;
            } else {
                status = STATUS_READY;
                estimate = capital.estimate(items, day);
                local = CapitalService.localQuote(dealer, items, day, progression.licensed(player));
            }
        }
        data.set(D_STATUS, status);
        setPair(D_ESTIMATE, estimate);
        setPair(D_LOCAL, local);
        data.set(D_MINUTES, minutes);
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (crate == null || id != BUTTON_SHIP) return false;
        boolean shipped = capital.ship(p, crate, dealer, progression.licensed(p), day()).isEmpty();
        refresh();
        return shipped;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < DRAWER_END) {
            if (!moveItemStackTo(stack, DRAWER_END, INV_END, true)) return ItemStack.EMPTY;
        } else if (inTransit() || ModItems.denominationOf(stack) != null || !moveItemStackTo(stack, 0, CARGO_END, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.TRADE_ROUTE_CRATE) && (crate == null || crate.isOwner(p));
    }
}
