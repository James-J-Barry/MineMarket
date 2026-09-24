package com.realisticmarkets.mod.registry;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.block.BankVaultBlock;
import com.realisticmarkets.mod.block.BasicExchangeBlock;
import com.realisticmarkets.mod.block.MenuBlock;
import com.realisticmarkets.mod.block.PriceBoardBlock;
import com.realisticmarkets.mod.block.TradeRouteCrateBlock;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.menu.AlmanacMenu;
import com.realisticmarkets.mod.menu.DraftingTableMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class ModBlocks {
    private ModBlocks() {}

    public static Block BASIC_EXCHANGE;
    public static Block ALMANAC_LECTERN;
    public static Block DRAFTING_TABLE;
    public static Block PRICE_BOARD;
    public static Block TRADE_ROUTE_CRATE;
    public static Block BANK_VAULT;

    public static void init() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, RealisticMarkets.id("basic_exchange"));
        BASIC_EXCHANGE = Registry.register(BuiltInRegistries.BLOCK, key,
                new BasicExchangeBlock(BlockBehaviour.Properties.of()
                        .setId(key)
                        .mapColor(MapColor.WOOD)
                        .strength(2.5f)
                        .sound(SoundType.WOOD)));
        ModItems.register("basic_exchange", props -> new BlockItem(BASIC_EXCHANGE, props),
                new Item.Properties().useBlockDescriptionPrefix());

        ALMANAC_LECTERN = registerMenuBlock("almanac_lectern", (id, inv, access) ->
                new AlmanacMenu(id, inv, access, ProgressionService.get(), DealerService.get()));
        DRAFTING_TABLE = registerMenuBlock("drafting_table", (id, inv, access) ->
                new DraftingTableMenu(id, inv, access, ProgressionService.get()));

        ResourceKey<Block> boardKey = ResourceKey.create(Registries.BLOCK, RealisticMarkets.id("price_board"));
        PRICE_BOARD = Registry.register(BuiltInRegistries.BLOCK, boardKey,
                new PriceBoardBlock(BlockBehaviour.Properties.of()
                        .setId(boardKey)
                        .mapColor(MapColor.WOOD)
                        .strength(1.0f)
                        .sound(SoundType.WOOD)
                        .noOcclusion()));
        ModItems.register("price_board", props -> new BlockItem(PRICE_BOARD, props),
                new Item.Properties().useBlockDescriptionPrefix().stacksTo(16));

        ResourceKey<Block> crateKey = ResourceKey.create(Registries.BLOCK, RealisticMarkets.id("trade_route_crate"));
        TRADE_ROUTE_CRATE = Registry.register(BuiltInRegistries.BLOCK, crateKey,
                new TradeRouteCrateBlock(BlockBehaviour.Properties.of()
                        .setId(crateKey)
                        .mapColor(MapColor.WOOD)
                        .strength(2.5f)
                        .sound(SoundType.WOOD)));
        ModItems.register("trade_route_crate", props -> new BlockItem(TRADE_ROUTE_CRATE, props),
                new Item.Properties().useBlockDescriptionPrefix());

        ResourceKey<Block> vaultKey = ResourceKey.create(Registries.BLOCK, RealisticMarkets.id("bank_vault"));
        BANK_VAULT = Registry.register(BuiltInRegistries.BLOCK, vaultKey,
                new BankVaultBlock(BlockBehaviour.Properties.of()
                        .setId(vaultKey)
                        .mapColor(MapColor.METAL)
                        .strength(5.0f, 3_600_000.0f)
                        .requiresCorrectToolForDrops()
                        .sound(SoundType.METAL)));
        ModItems.register("bank_vault", props -> new BlockItem(BANK_VAULT, props),
                new Item.Properties().useBlockDescriptionPrefix());
    }

    private static Block registerMenuBlock(String name, MenuBlock.Factory factory) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, RealisticMarkets.id(name));
        Block block = Registry.register(BuiltInRegistries.BLOCK, key,
                new MenuBlock(BlockBehaviour.Properties.of()
                        .setId(key)
                        .mapColor(MapColor.WOOD)
                        .strength(2.5f)
                        .sound(SoundType.WOOD),
                        Component.translatable("container.realisticmarkets." + name), factory));
        ModItems.register(name, props -> new BlockItem(block, props), new Item.Properties().useBlockDescriptionPrefix());
        return block;
    }
}
