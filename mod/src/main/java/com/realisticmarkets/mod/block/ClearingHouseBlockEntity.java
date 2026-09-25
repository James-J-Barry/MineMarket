package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** A Clearing House: holds nothing (futures accounts are keyed by account); its owner can link it to a Records Terminal. */
public class ClearingHouseBlockEntity extends OwnedBlockEntity {
    public ClearingHouseBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CLEARING_HOUSE, pos, state);
    }
}
