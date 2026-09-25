package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.fx.Feedback;
import com.realisticmarkets.mod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** A Market Board on a wall: right-click to switch between Trading Floor prices, shares and the news. */
public class MarketBoardBlock extends WallDisplayBlock {
    public MarketBoardBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected BlockEntityType<? extends DisplayBlockEntity> type() {
        return ModBlockEntities.MARKET_BOARD;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof MarketBoardBlockEntity board) {
            board.nextPage();
            Feedback.at(level, pos, Feedback.Cue.CLICK);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MarketBoardBlockEntity(pos, state);
    }
}
