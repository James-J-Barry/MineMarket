package com.realisticmarkets.mod.menu;

import com.realisticmarkets.mod.progression.Materials;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.progression.Blueprint;
import com.realisticmarkets.progression.Blueprints;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Stonecutter-style list of every blueprint. Unlocked ones can be crafted from inventory materials;
 * the server re-checks the unlock and materials on every click.
 */
public class DraftingTableMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 186;
    public static final int OUTPUT_X = 152, OUTPUT_Y = 74;
    public static final int INVENTORY_Y = 104;

    public static final int OUTPUT = 0;
    public static final int INV_START = 1;
    public static final int INV_END = INV_START + 36;

    public static final int BUTTON_CRAFT_ONE = 0;
    public static final int BUTTON_CRAFT_MAX = 1;
    public static final int BUTTON_SELECT_BASE = 100;

    /** Same resource on client and server, so indices line up. */
    public static final List<Blueprint> BLUEPRINTS = List.copyOf(Blueprints.loadDefault().all());

    private static final int D_SELECTED = 0; // index + 1, 0 = none
    private static final int D_UNLOCKED = 1; // one flag per blueprint

    private final Container output = new SimpleContainer(1);
    private final ContainerData data = new SimpleContainerData(D_UNLOCKED + BLUEPRINTS.size());
    private final ContainerLevelAccess access;
    private final Player player;
    private final ProgressionService progression; // null on the client

    public DraftingTableMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL, null);
    }

    public DraftingTableMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access, ProgressionService progression) {
        super(ModMenus.DRAFTING_TABLE, containerId);
        this.access = access;
        this.player = playerInventory.player;
        this.progression = progression;
        addSlot(new Slot(output, 0, OUTPUT_X, OUTPUT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });
        addStandardInventorySlots(playerInventory, 8, INVENTORY_Y);
        addDataSlots(data);
        if (progression != null) refresh();
    }

    public int selected() { return data.get(D_SELECTED) - 1; }
    public boolean unlocked(int i) { return data.get(D_UNLOCKED + i) != 0; }

    public static Item resultItem(Blueprint bp) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(bp.result()));
    }

    private void refresh() {
        for (int i = 0; i < BLUEPRINTS.size(); i++) {
            data.set(D_UNLOCKED + i, progression.progress(player).hasBlueprint(BLUEPRINTS.get(i).result()) ? 1 : 0);
        }
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (progression == null) return false;
        boolean handled;
        if (id >= BUTTON_SELECT_BASE && id < BUTTON_SELECT_BASE + BLUEPRINTS.size()) {
            data.set(D_SELECTED, id - BUTTON_SELECT_BASE + 1);
            handled = true;
        } else if (id == BUTTON_CRAFT_ONE || id == BUTTON_CRAFT_MAX) {
            handled = craft(p, id == BUTTON_CRAFT_MAX ? Integer.MAX_VALUE : 1);
        } else {
            handled = false;
        }
        refresh();
        return handled;
    }

    private boolean craft(Player p, int requested) {
        int sel = selected();
        if (sel < 0 || sel >= BLUEPRINTS.size()) return false;
        Blueprint bp = BLUEPRINTS.get(sel);
        if (!progression.progress(p).hasBlueprint(bp.result())) return false;
        Item result = resultItem(bp);
        Inventory inv = p.getInventory();
        Blueprint.CraftResult r = bp.craft(Materials.counts(inv, bp), requested);
        if (r.times() == 0) return false;
        r.consumed().forEach((id, n) -> Materials.remove(inv, id, n));

        int left = r.times();
        ItemStack out = output.getItem(0);
        if (out.isEmpty()) {
            int n = Math.min(left, result.getDefaultMaxStackSize());
            output.setItem(0, new ItemStack(result, n));
            left -= n;
        } else if (out.is(result)) {
            int n = Math.min(left, out.getMaxStackSize() - out.getCount());
            out.grow(n);
            left -= n;
        }
        while (left > 0) {
            int n = Math.min(left, result.getDefaultMaxStackSize());
            inv.placeItemBackInInventory(new ItemStack(result, n));
            left -= n;
        }
        output.setChanged();
        progression.emit(p, new ProgressionEvent.Craft(bp.result(), r.times(), p.level().getGameTime() / 24_000L));
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        if (index != OUTPUT) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (!moveItemStackTo(stack, INV_START, INV_END, true)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(p, stack);
        return original;
    }

    @Override
    public void removed(Player p) {
        super.removed(p);
        if (!p.level().isClientSide()) clearContainer(p, output);
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.DRAFTING_TABLE);
    }
}
