package com.realisticmarkets.mod.registry;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.block.BankVaultBlock;
import com.realisticmarkets.mod.block.BasicExchangeBlock;
import com.realisticmarkets.mod.block.MenuBlock;
import com.realisticmarkets.mod.block.PriceBoardBlock;
import com.realisticmarkets.mod.block.TradeRouteCrateBlock;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.floor.FloorService;
import com.realisticmarkets.mod.menu.AlmanacMenu;
import com.realisticmarkets.mod.menu.DraftingTableMenu;
import com.realisticmarkets.mod.menu.NewsstandMenu;
import com.realisticmarkets.mod.menu.StockExchangeMenu;
import com.realisticmarkets.mod.menu.TickerTapeMenu;
import com.realisticmarkets.mod.menu.TradingFloorMenu;
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
    public static Block TRADING_FLOOR;
    public static Block NEWSSTAND;
    public static Block TICKER_TAPE;
    public static Block STOCK_EXCHANGE;
    public static Block NEWSFEED;
    public static Block SAFE_DEPOSIT_BOX;
    public static Block BOND_DESK;
    public static Block RECORDS_TERMINAL;
    public static Block CLEARING_HOUSE;

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

        TRADING_FLOOR = registerMenuBlock("trading_floor", (id, inv, access) ->
                new TradingFloorMenu(id, inv, access, FloorService.get(), ProgressionService.get(), DealerService.get()));

        NEWSSTAND = registerMenuBlock("newsstand", (id, inv, access) ->
                new NewsstandMenu(id, inv, access, ProgressionService.get(), DealerService.get(),
                        com.realisticmarkets.mod.bank.BankService.get().centralBank()));
        TICKER_TAPE = registerMenuBlock("ticker_tape", (id, inv, access) ->
                new TickerTapeMenu(id, inv, access, FloorService.get(), ProgressionService.get(), DealerService.get()));
        STOCK_EXCHANGE = registerMenuBlock("stock_exchange", (id, inv, access) -> new StockExchangeMenu(id, inv, access,
                com.realisticmarkets.mod.stocks.StockService.get(), ProgressionService.get(), DealerService.get()));
        ResourceKey<Block> boxKey = ResourceKey.create(Registries.BLOCK, RealisticMarkets.id("safe_deposit_box"));
        SAFE_DEPOSIT_BOX = Registry.register(BuiltInRegistries.BLOCK, boxKey,
                new com.realisticmarkets.mod.block.SafeDepositBoxBlock(BlockBehaviour.Properties.of()
                        .setId(boxKey)
                        .mapColor(MapColor.METAL)
                        .strength(5.0f, 3_600_000.0f)
                        .requiresCorrectToolForDrops()
                        .sound(SoundType.METAL)));
        ModItems.register("safe_deposit_box", props -> new BlockItem(SAFE_DEPOSIT_BOX, props), new Item.Properties().useBlockDescriptionPrefix());
        BOND_DESK = registerMenuBlock("bond_desk", (id, inv, access) -> new com.realisticmarkets.mod.menu.BondDeskMenu(id, inv, access,
                com.realisticmarkets.mod.bonds.BondService.get(), ProgressionService.get(), DealerService.get()));
        ResourceKey<Block> terminalKey = ResourceKey.create(Registries.BLOCK, RealisticMarkets.id("records_terminal"));
        RECORDS_TERMINAL = Registry.register(BuiltInRegistries.BLOCK, terminalKey,
                new com.realisticmarkets.mod.block.RecordsTerminalBlock(BlockBehaviour.Properties.of()
                        .setId(terminalKey)
                        .mapColor(MapColor.WOOD)
                        .strength(2.5f)
                        .sound(SoundType.WOOD)));
        ModItems.register("records_terminal", props -> new BlockItem(RECORDS_TERMINAL, props), new Item.Properties().useBlockDescriptionPrefix());
        ResourceKey<Block> houseKey = ResourceKey.create(Registries.BLOCK, RealisticMarkets.id("clearing_house"));
        CLEARING_HOUSE = Registry.register(BuiltInRegistries.BLOCK, houseKey,
                new com.realisticmarkets.mod.block.ClearingHouseBlock(BlockBehaviour.Properties.of()
                        .setId(houseKey)
                        .mapColor(MapColor.STONE)
                        .strength(3.0f)
                        .requiresCorrectToolForDrops()
                        .sound(SoundType.STONE)));
        ModItems.register("clearing_house", props -> new BlockItem(CLEARING_HOUSE, props), new Item.Properties().useBlockDescriptionPrefix());
        NEWSFEED = registerMenuBlock("electronic_newsfeed", (id, inv, access) -> new com.realisticmarkets.mod.menu.NewsfeedMenu(id, inv,
                access, com.realisticmarkets.mod.stocks.StockService.get(), ProgressionService.get(), DealerService.get()));
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
