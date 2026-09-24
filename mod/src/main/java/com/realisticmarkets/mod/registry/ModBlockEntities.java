package com.realisticmarkets.mod.registry;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.block.PriceBoardBlockEntity;
import com.realisticmarkets.mod.block.TradeRouteCrateBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
    private ModBlockEntities() {}

    public static BlockEntityType<PriceBoardBlockEntity> PRICE_BOARD;
    public static BlockEntityType<TradeRouteCrateBlockEntity> TRADE_ROUTE_CRATE;

    public static void init() {
        PRICE_BOARD = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, RealisticMarkets.id("price_board"),
                FabricBlockEntityTypeBuilder.create(PriceBoardBlockEntity::new, ModBlocks.PRICE_BOARD).build());
        TRADE_ROUTE_CRATE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, RealisticMarkets.id("trade_route_crate"),
                FabricBlockEntityTypeBuilder.create(TradeRouteCrateBlockEntity::new, ModBlocks.TRADE_ROUTE_CRATE).build());
    }
}
