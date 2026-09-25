package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** A Ledger Display on a wall: a Records Terminal's headline numbers. Link it with a Record Link. */
public class LedgerDisplayBlock extends WallDisplayBlock {
    public LedgerDisplayBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected BlockEntityType<? extends DisplayBlockEntity> type() {
        return ModBlockEntities.LEDGER_DISPLAY;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LedgerDisplayBlockEntity(pos, state);
    }
}
