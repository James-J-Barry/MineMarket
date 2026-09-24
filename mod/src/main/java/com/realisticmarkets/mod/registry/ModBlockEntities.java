package com.realisticmarkets.mod.registry;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.block.PriceBoardBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
    private ModBlockEntities() {}

    public static BlockEntityType<PriceBoardBlockEntity> PRICE_BOARD;

    public static void init() {
        PRICE_BOARD = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, RealisticMarkets.id("price_board"),
                FabricBlockEntityTypeBuilder.create(PriceBoardBlockEntity::new, ModBlocks.PRICE_BOARD).build());
    }
}
