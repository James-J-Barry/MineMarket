package com.realisticmarkets.mod.menu;

import com.realisticmarkets.mod.dealer.BillClip;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.registry.ModMenus;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The Bill Clip's 9 slots (currency only) above the player's inventory. Edits write straight back to the item. */
public class BillClipMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176, HEIGHT = 133;
    public static final int CLIP_Y = 20, INVENTORY_Y = 51;
    public static final int CLIP_END = BillClip.SLOTS;             // slots 0..8 are the clip
    public static final int INV_END = CLIP_END + 36;

    private final SimpleContainer clip;
    private final ContainerData data = new SimpleContainerData(1); // inventory slot holding the open clip, +1
    private final ItemStack clipStack; // server only

    /** Client-side constructor. */
    public BillClipMenu(int containerId, Inventory inv) {
        this(containerId, inv, null, ItemStack.EMPTY);
    }

    /** Server-side constructor: {@code hand} holds {@code clipStack}. */
    public BillClipMenu(int containerId, Inventory inv, InteractionHand hand, ItemStack clipStack) {
        super(ModMenus.BILL_CLIP, containerId);
        this.clipStack = clipStack;
        this.clip = new SimpleContainer(BillClip.SLOTS) {
            @Override
            public void setChanged() {
                super.setChanged();
                if (!BillClipMenu.this.clipStack.isEmpty()) {
                    BillClip.setContents(BillClipMenu.this.clipStack, getItems());
                }
            }
        };
        if (!clipStack.isEmpty()) {
            var items = BillClip.contents(clipStack);
            for (int i = 0; i < BillClip.SLOTS; i++) clip.setItem(i, items.get(i));
            data.set(0, hand == InteractionHand.MAIN_HAND ? inv.getSelectedSlot() + 1 : 0);
        }

        for (int i = 0; i < BillClip.SLOTS; i++) {
            addSlot(new Slot(clip, i, 8 + i * 18, CLIP_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return ModItems.denominationOf(stack) != null;
                }
            });
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) addInventorySlot(inv, 9 + r * 9 + c, 8 + c * 18, INVENTORY_Y + r * 18);
        }
        for (int c = 0; c < 9; c++) addInventorySlot(inv, c, 8 + c * 18, INVENTORY_Y + 58);
        addDataSlots(data);
    }

    private void addInventorySlot(Inventory inv, int index, int x, int y) {
        addSlot(new Slot(inv, index, x, y) {
            @Override
            public boolean mayPickup(Player p) {
                return !locked();
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return !locked();
            }

            private boolean locked() {
                return data.get(0) - 1 == getContainerSlot();
            }
        });
    }

    public long cents() {
        long cents = 0;
        for (int i = 0; i < BillClip.SLOTS; i++) {
            var d = ModItems.denominationOf(clip.getItem(i));
            if (d != null) cents += d.cents() * clip.getItem(i).getCount();
        }
        return cents;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(p)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < CLIP_END) {
            if (!moveItemStackTo(stack, CLIP_END, INV_END, true)) return ItemStack.EMPTY;
        } else if (ModItems.denominationOf(stack) == null || !moveItemStackTo(stack, 0, CLIP_END, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player p) {
        if (clipStack.isEmpty()) return true; // client
        return p.getInventory().contains(s -> s == clipStack) && BillClip.isClip(clipStack);
    }
}
