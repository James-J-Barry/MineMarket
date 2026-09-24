package com.realisticmarkets.mod.block;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.registry.ModBlockEntities;
import com.realisticmarkets.mod.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Up to four items on a Price Board, with the Basic Exchange's public (unlicensed) quotes for each, refreshed
 * every {@link #REFRESH_TICKS} ticks and synced to clients through the block entity update packet.
 */
public class PriceBoardBlockEntity extends BlockEntity {
    public static final int SLOTS = 4;
    public static final int REFRESH_TICKS = 100;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private final long[] bidMills = new long[SLOTS];
    private final long[] askMills = new long[SLOTS];
    private final long[] midMills = new long[SLOTS];
    private final long[] fairMills = new long[SLOTS];

    public PriceBoardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PRICE_BOARD, pos, state);
    }

    public int count() {
        int n = 0;
        while (n < SLOTS && !items.get(n).isEmpty()) n++;
        return n;
    }

    public ItemStack item(int i) { return items.get(i); }
    public long bidMills(int i) { return bidMills[i]; }
    public long askMills(int i) { return askMills[i]; }
    public long midMills(int i) { return midMills[i]; }
    public long fairMills(int i) { return fairMills[i]; }

    /** Adds one of the item if the Dealer trades it and there's room. Returns why not, or null on success. */
    public String tryAdd(ItemStack stack, DealerCatalog catalog) {
        if (stack.isEmpty() || ModItems.denominationOf(stack) != null) return "Money has no price";
        if (!catalog.trades(DealerService.itemId(stack))) return "The Dealer doesn't trade that";
        int n = count();
        if (n == SLOTS) return "The board is full";
        items.set(n, stack.copyWithCount(1));
        changed();
        return null;
    }

    /** Takes back the last item added, or EMPTY. */
    public ItemStack removeLast() {
        int n = count();
        if (n == 0) return ItemStack.EMPTY;
        ItemStack out = items.get(n - 1);
        items.set(n - 1, ItemStack.EMPTY);
        bidMills[n - 1] = askMills[n - 1] = midMills[n - 1] = fairMills[n - 1] = 0;
        changed();
        return out;
    }

    public void serverTick() {
        if (level == null || Math.floorMod(level.getGameTime() + worldPosition.hashCode(), REFRESH_TICKS) != 0) return;
        DealerService svc;
        try {
            svc = DealerService.get();
        } catch (IllegalStateException notRunning) {
            return;
        }
        refresh(svc.dealer(), svc.day(level.getGameTime()));
    }

    /** Recomputes the quotes; sends an update to clients if anything moved. */
    public void refresh(Dealer dealer, double day) {
        boolean moved = false;
        for (int i = 0; i < SLOTS; i++) {
            long bid = 0, ask = 0, mid = 0, fair = 0;
            ItemStack s = items.get(i);
            String id = s.isEmpty() ? null : DealerService.itemId(s);
            if (id != null && dealer.catalog().trades(id)) {
                try {
                    bid = mills(dealer.bid(id, day, false));
                    ask = mills(dealer.ask(id, day, false));
                    mid = mills(dealer.mid(id, day));
                    fair = mills(dealer.normalValue(id, day));
                } catch (RejectedException e) {
                    // leave zeros
                }
            }
            moved |= bid != bidMills[i] || ask != askMills[i] || mid != midMills[i] || fair != fairMills[i];
            bidMills[i] = bid;
            askMills[i] = ask;
            midMills[i] = mid;
            fairMills[i] = fair;
        }
        if (moved) changed();
    }

    private static long mills(double dollars) {
        return Math.round(dollars * 1000.0);
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ------------------------------------------------------------------ persistence and sync

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        for (int i = 0; i < SLOTS; i++) {
            if (items.get(i).isEmpty()) continue;
            out.putString("Item" + i, DealerService.itemId(items.get(i)));
            out.putLong("Bid" + i, bidMills[i]);
            out.putLong("Ask" + i, askMills[i]);
            out.putLong("Mid" + i, midMills[i]);
            out.putLong("Fair" + i, fairMills[i]);
        }
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            items.set(i, in.getString("Item" + i)
                    .map(id -> BuiltInRegistries.ITEM.getValue(Identifier.parse(id)))
                    .filter(item -> item != Items.AIR)
                    .map(ItemStack::new)
                    .orElse(ItemStack.EMPTY));
            bidMills[slot] = in.getLongOr("Bid" + i, 0);
            askMills[slot] = in.getLongOr("Ask" + i, 0);
            midMills[slot] = in.getLongOr("Mid" + i, 0);
            fairMills[slot] = in.getLongOr("Fair" + i, 0);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null) Containers.dropContents(level, pos, items);
    }
}
