package com.realisticmarkets.mod.menu;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.money.Denomination;
import com.realisticmarkets.money.Money;
import java.util.Map;
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

/**
 * Furnace-style Basic Exchange screen: one input slot, a Sell button, and a 2x2 grid of payout
 * slots (dime, $1, $10, $100). The table stores nothing: on close, anything left in it goes back
 * to the player, like a crafting table.
 *
 * <p>The server recomputes the quote every tick (prices recover over time) and syncs it to the
 * client through {@link ContainerData}. Data slots travel as shorts, so cents are split into two
 * 15-bit halves (max about $10.7M per quote).
 */
public class BasicExchangeMenu extends AbstractContainerMenu {
    public static final int INPUT = 0;
    public static final int OUTPUT_START = 1;
    public static final int OUTPUT_COUNT = 4;
    public static final int INV_START = OUTPUT_START + OUTPUT_COUNT; // 5
    public static final int INV_END = INV_START + 36;                 // 41 (exclusive)

    public static final int BUTTON_SELL = 0;

    public static final int STATUS_EMPTY = 0;
    public static final int STATUS_OK = 1;
    public static final int STATUS_NOT_TRADED = 2;
    public static final int STATUS_COLLAPSED = 3;
    public static final int STATUS_MONEY = 4;

    /** Output slot order, left-to-right then top-to-bottom. */
    public static final Denomination[] OUTPUT_ORDER = {
            Denomination.DIME, Denomination.ONE, Denomination.TEN, Denomination.HUNDRED};

    private static final long MAX_SYNCED_CENTS = (1L << 30) - 1;

    private final Container input = new SimpleContainer(1);
    private final Container output = new SimpleContainer(OUTPUT_COUNT);
    private final ContainerData data = new SimpleContainerData(3); // status, cents low 15 bits, cents high 15 bits
    private final ContainerLevelAccess access;
    private final Player player;
    private final DealerService dealer; // null on the client

    /** Client-side constructor, called when the server opens the screen. */
    public BasicExchangeMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL, null);
    }

    /** Server-side constructor. */
    public BasicExchangeMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access, DealerService dealer) {
        super(ModMenus.BASIC_EXCHANGE, containerId);
        this.access = access;
        this.player = playerInventory.player;
        this.dealer = dealer;

        addSlot(new Slot(input, 0, 26, 38));
        for (int i = 0; i < OUTPUT_COUNT; i++) {
            addSlot(new Slot(output, i, 116 + (i % 2) * 18, 29 + (i / 2) * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }
        addStandardInventorySlots(playerInventory, 8, 84);
        addDataSlots(data);
    }

    // ------------------------------------------------------------------ synced state (client reads these)

    public int status() {
        return data.get(0);
    }

    public long quoteCents() {
        return (long) data.get(1) | ((long) data.get(2) << 15);
    }

    public ItemStack inputStack() {
        return input.getItem(0);
    }

    // ------------------------------------------------------------------ server logic

    @Override
    public void broadcastChanges() {
        if (dealer != null) updateQuote();
        super.broadcastChanges();
    }

    private double day() {
        return dealer.day(player.level().getGameTime());
    }

    private void updateQuote() {
        ItemStack stack = input.getItem(0);
        if (stack.isEmpty()) {
            setQuote(STATUS_EMPTY, 0);
        } else if (ModItems.denominationOf(stack) != null) {
            setQuote(STATUS_MONEY, 0);
        } else if (!dealer.dealer().catalog().trades(DealerService.itemId(stack))) {
            setQuote(STATUS_NOT_TRADED, 0);
        } else {
            try {
                Dealer.Quote q = dealer.dealer().quoteSell(DealerService.itemId(stack), stack.getCount(), day(), false);
                setQuote(STATUS_OK, q.cents());
            } catch (RejectedException e) {
                setQuote(STATUS_COLLAPSED, 0);
            }
        }
    }

    private void setQuote(int status, long cents) {
        long c = Math.min(Math.max(cents, 0), MAX_SYNCED_CENTS);
        data.set(0, status);
        data.set(1, (int) (c & 0x7FFF));
        data.set(2, (int) ((c >> 15) & 0x7FFF));
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (id != BUTTON_SELL || dealer == null) return false;
        ItemStack stack = input.getItem(0);
        if (stack.isEmpty() || ModItems.denominationOf(stack) != null) return false;
        try {
            long cents = dealer.sellStack(stack, day(), false);
            input.setItem(0, ItemStack.EMPTY);
            pay(p, cents);
            updateQuote();
            return true;
        } catch (RejectedException e) {
            return false;
        }
    }

    /** Fills the denomination slots; whatever doesn't fit goes to the player's inventory. */
    private void pay(Player p, long cents) {
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

    // ------------------------------------------------------------------ container plumbing

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < INV_START) {
            if (!moveItemStackTo(stack, INV_START, INV_END, true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, INPUT, INPUT + 1, false)) {
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
