package com.realisticmarkets.mod.registry;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.block.BankVaultBlockEntity;
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
    public static BlockEntityType<BankVaultBlockEntity> BANK_VAULT;
    public static BlockEntityType<com.realisticmarkets.mod.block.SafeDepositBoxBlockEntity> SAFE_DEPOSIT_BOX;
    public static BlockEntityType<com.realisticmarkets.mod.block.RecordsTerminalBlockEntity> RECORDS_TERMINAL;
    public static BlockEntityType<com.realisticmarkets.mod.block.ClearingHouseBlockEntity> CLEARING_HOUSE;
    public static BlockEntityType<com.realisticmarkets.mod.block.BrokerageTerminalBlockEntity> BROKERAGE_TERMINAL;

    public static void init() {
        PRICE_BOARD = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, RealisticMarkets.id("price_board"),
                FabricBlockEntityTypeBuilder.create(PriceBoardBlockEntity::new, ModBlocks.PRICE_BOARD).build());
        TRADE_ROUTE_CRATE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, RealisticMarkets.id("trade_route_crate"),
                FabricBlockEntityTypeBuilder.create(TradeRouteCrateBlockEntity::new, ModBlocks.TRADE_ROUTE_CRATE).build());
        BANK_VAULT = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, RealisticMarkets.id("bank_vault"),
                FabricBlockEntityTypeBuilder.create(BankVaultBlockEntity::new, ModBlocks.BANK_VAULT).build());
        SAFE_DEPOSIT_BOX = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, RealisticMarkets.id("safe_deposit_box"),
                FabricBlockEntityTypeBuilder.create(com.realisticmarkets.mod.block.SafeDepositBoxBlockEntity::new,
                        ModBlocks.SAFE_DEPOSIT_BOX).build());
        RECORDS_TERMINAL = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, RealisticMarkets.id("records_terminal"),
                FabricBlockEntityTypeBuilder.create(com.realisticmarkets.mod.block.RecordsTerminalBlockEntity::new,
                        ModBlocks.RECORDS_TERMINAL).build());
        CLEARING_HOUSE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, RealisticMarkets.id("clearing_house"),
                FabricBlockEntityTypeBuilder.create(com.realisticmarkets.mod.block.ClearingHouseBlockEntity::new,
                        ModBlocks.CLEARING_HOUSE).build());
        BROKERAGE_TERMINAL = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, RealisticMarkets.id("brokerage_terminal"),
                FabricBlockEntityTypeBuilder.create(com.realisticmarkets.mod.block.BrokerageTerminalBlockEntity::new,
                        ModBlocks.BROKERAGE_TERMINAL).build());
    }
}
