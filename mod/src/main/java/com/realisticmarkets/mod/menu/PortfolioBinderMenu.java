package com.realisticmarkets.mod.menu;

import com.realisticmarkets.equities.Company;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.item.PortfolioBinderItem;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.mod.stocks.Securities;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import com.realisticmarkets.mod.stocks.StockService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Portfolio Binder: 27 slots for security papers, and what they're worth together at live prices (the purchase:
 * papers on their own show no value). Edits write straight back to the binder item.
 */
public class PortfolioBinderMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176, HEIGHT = 232, BINDER_Y = 18, INVENTORY_Y = 150;
    public static final int BINDER_END = PortfolioBinderItem.SLOTS, INV_END = BINDER_END + 36;
    /** Kinds of holding, in display order: each company, then CDs, then Loan Notes. */
    public static final List<String> KINDS = kinds();

    private static final int D_LOCKED = 0, D_TOTAL = 1, D_KINDS = 4, KIND_STRIDE = 5; // qty pair, value triple
    private final SimpleContainer binder;
    private final ContainerData data = new SimpleContainerData(D_KINDS + KINDS.size() * KIND_STRIDE);
    private final ItemStack binderStack; // server only
    private final StockService stocks;
    private final BankService bank;
    private final long day;
    private int ticks;

    private static List<String> kinds() {
        List<String> k = new ArrayList<>();
        for (Company c : ShareCertificates.COMPANIES.all()) k.add(c.ticker());
        k.add(Securities.BONDS);
        k.add(Securities.CDS);
        k.add(Securities.LOAN_NOTES);
        return List.copyOf(k);
    }

    public PortfolioBinderMenu(int containerId, Inventory inv) {
        this(containerId, inv, null, ItemStack.EMPTY, null, null, 0);
    }

    public PortfolioBinderMenu(int containerId, Inventory inv, InteractionHand hand, ItemStack binderStack, StockService stocks,
                               BankService bank, long day) {
        super(ModMenus.PORTFOLIO_BINDER, containerId);
        this.binderStack = binderStack;
        this.stocks = stocks;
        this.bank = bank;
        this.day = day;
        this.binder = new SimpleContainer(PortfolioBinderItem.SLOTS) {
            @Override
            public void setChanged() {
                super.setChanged();
                if (!PortfolioBinderMenu.this.binderStack.isEmpty()) {
                    PortfolioBinderItem.setContents(PortfolioBinderMenu.this.binderStack, getItems());
                    refresh();
                }
            }
        };
        if (!binderStack.isEmpty()) {
            var items = PortfolioBinderItem.contents(binderStack);
            for (int i = 0; i < PortfolioBinderItem.SLOTS; i++) binder.setItem(i, items.get(i));
            data.set(D_LOCKED, hand == InteractionHand.MAIN_HAND ? inv.getSelectedSlot() + 1 : 0);
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(binder, r * 9 + c, 8 + c * 18, BINDER_Y + r * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return Securities.isSecurity(stack);
                    }
                });
            }
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) addInventorySlot(inv, 9 + r * 9 + c, 8 + c * 18, INVENTORY_Y + r * 18);
        }
        for (int c = 0; c < 9; c++) addInventorySlot(inv, c, 8 + c * 18, INVENTORY_Y + 58);
        addDataSlots(data);
        if (!binderStack.isEmpty()) refresh();
    }

    private void addInventorySlot(Inventory inv, int index, int x, int y) {
        addSlot(new Slot(inv, index, x, y) {
            @Override
            public boolean mayPickup(Player p) {
                return data.get(D_LOCKED) - 1 != getContainerSlot();
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return data.get(D_LOCKED) - 1 != getContainerSlot();
            }
        });
    }

    // ---- reads

    public long totalCents() { return triple(D_TOTAL); }
    public long quantity(int kind) { return (long) data.get(D_KINDS + kind * KIND_STRIDE) | ((long) data.get(D_KINDS + kind * KIND_STRIDE + 1) << 15); }
    public long valueCents(int kind) { return triple(D_KINDS + kind * KIND_STRIDE + 2); }

    private long triple(int i) {
        return (long) data.get(i) | ((long) data.get(i + 1) << 15) | ((long) data.get(i + 2) << 30);
    }

    private void setTriple(int i, long v) {
        v = Math.max(0, Math.min(v, (1L << 45) - 1));
        data.set(i, (int) (v & 0x7FFF));
        data.set(i + 1, (int) ((v >> 15) & 0x7FFF));
        data.set(i + 2, (int) ((v >> 30) & 0x7FFF));
    }

    /** Server: what's in the binder, valued now. */
    public Securities.Valuation valuation() {
        return Securities.value(binder.getItems(), stocks, bank, day);
    }

    private void refresh() {
        Securities.Valuation v = valuation();
        setTriple(D_TOTAL, v.totalCents());
        for (int k = 0; k < KINDS.size(); k++) {
            long qty = 0, cents = 0;
            for (Securities.Line l : v.lines()) {
                if (l.kind().equals(KINDS.get(k))) {
                    qty = l.quantity();
                    cents = l.cents();
                }
            }
            int base = D_KINDS + k * KIND_STRIDE;
            long q = Math.min(qty, (1L << 30) - 1);
            data.set(base, (int) (q & 0x7FFF));
            data.set(base + 1, (int) ((q >> 15) & 0x7FFF));
            setTriple(base + 2, cents);
        }
    }

    @Override
    public void broadcastChanges() {
        if (!binderStack.isEmpty() && ++ticks % 20 == 0) refresh(); // live prices
        super.broadcastChanges();
    }

    /** Server, for tests: the binder's slots. */
    public SimpleContainer binder() {
        return binder;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(p)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < BINDER_END) {
            if (!moveItemStackTo(stack, BINDER_END, INV_END, true)) return ItemStack.EMPTY;
        } else if (!Securities.isSecurity(stack) || !moveItemStackTo(stack, 0, BINDER_END, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player p) {
        if (binderStack.isEmpty()) return true; // client
        return p.getInventory().contains(s -> s == binderStack) && binderStack.is(ModItems.PORTFOLIO_BINDER);
    }
}
