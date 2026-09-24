package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A Bank Vault: the owner's one access point to their account. Holds no money itself. {@code locked} mirrors
 * "the account isn't empty" so the client doesn't predict breaking a vault the server will refuse.
 */
public class BankVaultBlockEntity extends OwnedBlockEntity {
    private boolean locked;

    public BankVaultBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BANK_VAULT, pos, state);
    }

    public boolean locked() {
        return locked;
    }

    public void setLocked(boolean value) {
        if (locked == value) return;
        locked = value;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public void setAlarm(boolean on) {
        if (level == null || level.isClientSide()) return;
        BlockState state = getBlockState();
        if (state.getValue(BankVaultBlock.ALARM) != on) level.setBlock(worldPosition, state.setValue(BankVaultBlock.ALARM, on), Block.UPDATE_ALL);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putBoolean("Locked", locked);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        locked = in.getBooleanOr("Locked", false);
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
        if (level instanceof ServerLevel server && owner() != null) {
            try {
                BankService.get().releaseVault(owner(), location(), BankService.day(server.getServer()));
            } catch (IllegalStateException notRunning) {
                // test world or shutdown: nothing to release
            }
        }
    }
}
