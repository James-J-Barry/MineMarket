package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** A Brokerage Terminal: the books are keyed by account; its owner can link it to a Records Terminal. */
public class BrokerageTerminalBlockEntity extends OwnedBlockEntity {
    public BrokerageTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BROKERAGE_TERMINAL, pos, state);
    }
}
