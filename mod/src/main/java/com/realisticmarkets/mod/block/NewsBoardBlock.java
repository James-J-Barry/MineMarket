package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** A News Board on a wall: the day's market news and the central bank's rate. */
public class NewsBoardBlock extends WallDisplayBlock {
    public NewsBoardBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected BlockEntityType<? extends DisplayBlockEntity> type() {
        return ModBlockEntities.NEWS_BOARD;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NewsBoardBlockEntity(pos, state);
    }
}
