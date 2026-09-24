package com.realisticmarkets.mod.client;

import com.realisticmarkets.mod.registry.ModBlockEntities;
import com.realisticmarkets.mod.registry.ModMenus;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public final class RealisticMarketsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(ModMenus.BASIC_EXCHANGE, BasicExchangeScreen::new);
        MenuScreens.register(ModMenus.ALMANAC, AlmanacScreen::new);
        MenuScreens.register(ModMenus.DRAFTING_TABLE, DraftingTableScreen::new);
        MenuScreens.register(ModMenus.BILL_CLIP, BillClipScreen::new);
        BlockEntityRenderers.register(ModBlockEntities.PRICE_BOARD, PriceBoardRenderer::new);
    }
}
