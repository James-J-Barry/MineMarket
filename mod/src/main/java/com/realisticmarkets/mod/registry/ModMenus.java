package com.realisticmarkets.mod.registry;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.menu.AlmanacMenu;
import com.realisticmarkets.mod.menu.BankVaultMenu;
import com.realisticmarkets.mod.menu.BasicExchangeMenu;
import com.realisticmarkets.mod.menu.BillClipMenu;
import com.realisticmarkets.mod.menu.DraftingTableMenu;
import com.realisticmarkets.mod.menu.TradeRouteCrateMenu;
import com.realisticmarkets.mod.menu.NewsstandMenu;
import com.realisticmarkets.mod.menu.StockExchangeMenu;
import com.realisticmarkets.mod.menu.TickerTapeMenu;
import com.realisticmarkets.mod.menu.TradingFloorMenu;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

public final class ModMenus {
    private ModMenus() {}

    public static MenuType<BasicExchangeMenu> BASIC_EXCHANGE;
    public static MenuType<AlmanacMenu> ALMANAC;
    public static MenuType<DraftingTableMenu> DRAFTING_TABLE;
    public static MenuType<BillClipMenu> BILL_CLIP;
    public static MenuType<TradeRouteCrateMenu> TRADE_ROUTE_CRATE;
    public static MenuType<BankVaultMenu> BANK_VAULT;
    public static MenuType<TradingFloorMenu> TRADING_FLOOR;
    public static MenuType<NewsstandMenu> NEWSSTAND;
    public static MenuType<TickerTapeMenu> TICKER_TAPE;
    public static MenuType<StockExchangeMenu> STOCK_EXCHANGE;

    public static void init() {
        ResourceKey<MenuType<?>> key = ResourceKey.create(Registries.MENU, RealisticMarkets.id("basic_exchange"));
        BASIC_EXCHANGE = Registry.register(BuiltInRegistries.MENU, key,
                new MenuType<>(BasicExchangeMenu::new, FeatureFlags.VANILLA_SET));
        ALMANAC = Registry.register(BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, RealisticMarkets.id("almanac")),
                new MenuType<>(AlmanacMenu::new, FeatureFlags.VANILLA_SET));
        DRAFTING_TABLE = Registry.register(BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, RealisticMarkets.id("drafting_table")),
                new MenuType<>(DraftingTableMenu::new, FeatureFlags.VANILLA_SET));
        BILL_CLIP = Registry.register(BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, RealisticMarkets.id("bill_clip")),
                new MenuType<>(BillClipMenu::new, FeatureFlags.VANILLA_SET));
        TRADE_ROUTE_CRATE = Registry.register(BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, RealisticMarkets.id("trade_route_crate")),
                new MenuType<>(TradeRouteCrateMenu::new, FeatureFlags.VANILLA_SET));
        BANK_VAULT = Registry.register(BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, RealisticMarkets.id("bank_vault")),
                new MenuType<>(BankVaultMenu::new, FeatureFlags.VANILLA_SET));
        TRADING_FLOOR = Registry.register(BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, RealisticMarkets.id("trading_floor")),
                new MenuType<>(TradingFloorMenu::new, FeatureFlags.VANILLA_SET));
        NEWSSTAND = Registry.register(BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, RealisticMarkets.id("newsstand")),
                new MenuType<>(NewsstandMenu::new, FeatureFlags.VANILLA_SET));
        TICKER_TAPE = Registry.register(BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, RealisticMarkets.id("ticker_tape")),
                new MenuType<>(TickerTapeMenu::new, FeatureFlags.VANILLA_SET));
        STOCK_EXCHANGE = Registry.register(BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, RealisticMarkets.id("stock_exchange")),
                new MenuType<>(StockExchangeMenu::new, FeatureFlags.VANILLA_SET));
    }
}
