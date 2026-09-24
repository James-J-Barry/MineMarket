package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.dealer.CapitalService;
import com.realisticmarkets.mod.menu.BasicExchangeMenu;
import com.realisticmarkets.mod.registry.ModBlockEntities;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Denomination;
import com.realisticmarkets.money.Money;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A Trade Route Crate: its owner, 9 cargo slots, and a 2x2 drawer (dime, $1, $10, $100) where Capital payouts
 * land. Shipments in transit live in CapitalService, keyed by {@link #location()}.
 */
public class TradeRouteCrateBlockEntity extends BlockEntity {
    public static final int CARGO_SLOTS = 9;
    public static final int DRAWER_SLOTS = 4;

    private final SimpleContainer cargo = container(CARGO_SLOTS);
    private final SimpleContainer drawer = container(DRAWER_SLOTS);
    private UUID owner;
    private String ownerName = "";

    public TradeRouteCrateBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TRADE_ROUTE_CRATE, pos, state);
    }

    private SimpleContainer container(int size) {
        return new SimpleContainer(size) {
            @Override
            public void setChanged() {
                super.setChanged();
                TradeRouteCrateBlockEntity.this.setChanged();
            }
        };
    }

    public SimpleContainer cargo() { return cargo; }
    public SimpleContainer drawer() { return drawer; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }

    public void setOwner(Player player) {
        owner = player.getUUID();
        ownerName = player.getName().getString();
        setChanged();
    }

    public boolean isOwner(Player player) {
        return owner != null && owner.equals(player.getUUID());
    }

    /** "dimension|x|y|z": how shipments find their crate again. */
    public String location() {
        return CapitalService.location(level, worldPosition);
    }

    /** Pays bills into the drawer (one denomination per slot, max 64 each). Returns cents that didn't fit. */
    public long depositCash(long cents) {
        long left = 0;
        for (Map.Entry<Denomination, Long> e : Money.makeChange(cents).entrySet()) {
            int slot = drawerSlot(e.getKey());
            Item item = ModItems.CURRENCY.get(e.getKey());
            ItemStack current = drawer.getItem(slot);
            long want = e.getValue();
            int room = current.isEmpty() ? 64 : current.is(item) ? 64 - current.getCount() : 0;
            int put = (int) Math.min(room, want);
            if (put > 0) {
                if (current.isEmpty()) drawer.setItem(slot, new ItemStack(item, put));
                else current.grow(put);
            }
            left += (want - put) * e.getKey().cents();
        }
        drawer.setChanged();
        return left;
    }

    public long drawerCents() {
        long cents = 0;
        for (int i = 0; i < DRAWER_SLOTS; i++) {
            ItemStack s = drawer.getItem(i);
            Denomination d = ModItems.denominationOf(s);
            if (d != null) cents += d.cents() * s.getCount();
        }
        return cents;
    }

    private static int drawerSlot(Denomination d) {
        for (int i = 0; i < BasicExchangeMenu.OUTPUT_ORDER.length; i++) if (BasicExchangeMenu.OUTPUT_ORDER[i] == d) return i;
        throw new IllegalArgumentException(String.valueOf(d));
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (owner != null) {
            out.putString("Owner", owner.toString());
            out.putString("OwnerName", ownerName);
        }
        ContainerHelper.saveAllItems(out.child("Cargo"), cargo.getItems());
        ContainerHelper.saveAllItems(out.child("Drawer"), drawer.getItems());
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        owner = in.getString("Owner").map(UUID::fromString).orElse(null);
        ownerName = in.getStringOr("OwnerName", "");
        ContainerHelper.loadAllItems(in.childOrEmpty("Cargo"), cargo.getItems());
        ContainerHelper.loadAllItems(in.childOrEmpty("Drawer"), drawer.getItems());
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null) {
            Containers.dropContents(level, pos, cargo);
            Containers.dropContents(level, pos, drawer);
        }
    }
}
