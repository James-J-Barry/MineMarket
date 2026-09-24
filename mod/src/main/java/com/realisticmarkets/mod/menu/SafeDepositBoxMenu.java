package com.realisticmarkets.mod.menu;

import com.realisticmarkets.mod.block.SafeDepositBoxBlockEntity;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.mod.stocks.Securities;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Six rows of papers and currency above the player's inventory, like a large chest that takes nothing else. */
public class SafeDepositBoxMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176, HEIGHT = 222, BOX_Y = 18, INVENTORY_Y = 140;
    public static final int BOX_END = SafeDepositBoxBlockEntity.SLOTS, INV_END = BOX_END + 36;

    private final Container box;
    private final ContainerLevelAccess access;

    public SafeDepositBoxMenu(int containerId, Inventory inv) {
        this(containerId, inv, new SimpleContainer(SafeDepositBoxBlockEntity.SLOTS), ContainerLevelAccess.NULL);
    }

    public SafeDepositBoxMenu(int containerId, Inventory inv, Container box, ContainerLevelAccess access) {
        super(ModMenus.SAFE_DEPOSIT_BOX, containerId);
        this.box = box;
        this.access = access;
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(box, r * 9 + c, 8 + c * 18, BOX_Y + r * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return Securities.isPaperOrCash(stack);
                    }
                });
            }
        }
        addStandardInventorySlots(inv, 8, INVENTORY_Y);
    }

    public Container box() {
        return box;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < BOX_END) {
            if (!moveItemStackTo(stack, BOX_END, INV_END, true)) return ItemStack.EMPTY;
        } else if (!Securities.isPaperOrCash(stack) || !moveItemStackTo(stack, 0, BOX_END, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.SAFE_DEPOSIT_BOX);
    }
}
