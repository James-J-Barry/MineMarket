package com.realisticmarkets.mod.block;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** A block entity that belongs to the player who placed it (Trade Route Crate, Bank Vault). */
public abstract class OwnedBlockEntity extends BlockEntity {
    private UUID owner;
    private String ownerName = "";

    protected OwnedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

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

    /** "dimension|x|y|z": how saved records find this block again. */
    public String location() {
        return Locations.of(level, worldPosition);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (owner != null) {
            out.putString("Owner", owner.toString());
            out.putString("OwnerName", ownerName);
        }
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        owner = in.getString("Owner").map(UUID::fromString).orElse(null);
        ownerName = in.getStringOr("OwnerName", "");
    }
}
