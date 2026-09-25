package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.registry.ModBlockEntities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A Records Terminal: its owner and the blocks linked to it with a Record Link (positions in the terminal's own
 * dimension). A link counts only while a linkable block of the same owner still stands there, so moving either block
 * breaks it: a moved block is somewhere else, and a moved terminal is a new block with no links.
 */
public class RecordsTerminalBlockEntity extends OwnedBlockEntity {
    public static final int MAX_LINKS = 16, RANGE = 64;

    private final List<BlockPos> links = new ArrayList<>();

    public RecordsTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RECORDS_TERMINAL, pos, state);
    }

    public List<BlockPos> links() {
        return Collections.unmodifiableList(links);
    }

    /** A block the terminal can read: a Bank Vault, Safe Deposit Box, Trade Route Crate or Clearing House. */
    public static boolean linkable(BlockEntity be) {
        return be instanceof BankVaultBlockEntity || be instanceof SafeDepositBoxBlockEntity || be instanceof TradeRouteCrateBlockEntity
                || be instanceof ClearingHouseBlockEntity;
    }

    public boolean inRange(BlockPos pos) {
        return pos.distSqr(worldPosition) <= (double) RANGE * RANGE;
    }

    /** Links {@code pos}, or unlinks it if it was linked. Returns why not, or null (then {@link #isLinked} says which). */
    public String toggle(BlockPos pos) {
        if (links.remove(pos)) {
            setChanged();
            return null;
        }
        if (!inRange(pos)) return "Too far from the terminal (" + RANGE + " blocks at most)";
        if (links.size() >= MAX_LINKS) return "This terminal already has " + MAX_LINKS + " links";
        links.add(pos.immutable());
        setChanged();
        return null;
    }

    public boolean isLinked(BlockPos pos) {
        return links.contains(pos);
    }

    /**
     * The linked blocks still standing (same owner, a linkable block in place). Links whose block is gone or changed
     * are dropped; links in unloaded chunks are kept but not returned.
     */
    public List<OwnedBlockEntity> linkedBlocks() {
        List<OwnedBlockEntity> out = new ArrayList<>();
        if (level == null) return out;
        boolean dropped = links.removeIf(pos -> {
            if (!level.isLoaded(pos)) return false;
            BlockEntity be = level.getBlockEntity(pos);
            return !(linkable(be) && be instanceof OwnedBlockEntity o && owner() != null && owner().equals(o.owner()));
        });
        if (dropped) setChanged();
        for (BlockPos pos : links) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof OwnedBlockEntity o) out.add(o);
        }
        return out;
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : links) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
        }
        out.putString("Links", sb.toString());
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        links.clear();
        String s = in.getStringOr("Links", "");
        if (s.isEmpty()) return;
        for (String part : s.split(";")) {
            String[] c = part.split(",");
            if (c.length == 3) links.add(new BlockPos(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])));
        }
    }
}
