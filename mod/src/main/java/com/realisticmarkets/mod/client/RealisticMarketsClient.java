package com.realisticmarkets.mod.client;

import com.realisticmarkets.mod.bank.PassbookItem;
import com.realisticmarkets.mod.registry.ModBlockEntities;
import com.realisticmarkets.mod.registry.ModMenus;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public final class RealisticMarketsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(ModMenus.BASIC_EXCHANGE, BasicExchangeScreen::new);
        MenuScreens.register(ModMenus.ALMANAC, AlmanacScreen::new);
        MenuScreens.register(ModMenus.DRAFTING_TABLE, DraftingTableScreen::new);
        MenuScreens.register(ModMenus.BILL_CLIP, BillClipScreen::new);
        MenuScreens.register(ModMenus.TRADE_ROUTE_CRATE, TradeRouteCrateScreen::new);
        MenuScreens.register(ModMenus.BANK_VAULT, BankVaultScreen::new);
        MenuScreens.register(ModMenus.TRADING_FLOOR, TradingFloorScreen::new);
        MenuScreens.register(ModMenus.NEWSSTAND, NewsstandScreen::new);
        MenuScreens.register(ModMenus.TICKER_TAPE, TickerTapeScreen::new);
        com.realisticmarkets.mod.item.PriceChartItem.openChart = stack -> Minecraft.getInstance().setScreen(new PriceChartScreen(stack));
        PassbookItem.openBook = stack -> Minecraft.getInstance().setScreen(
                new BookViewScreen(BookViewScreen.BookAccess.fromItem(stack)));
        BlockEntityRenderers.register(ModBlockEntities.PRICE_BOARD, PriceBoardRenderer::new);
    }
}
