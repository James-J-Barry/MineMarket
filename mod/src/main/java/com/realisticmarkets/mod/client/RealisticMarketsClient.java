package com.realisticmarkets.mod.client;

import com.realisticmarkets.mod.registry.ModMenus;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;

public final class RealisticMarketsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(ModMenus.BASIC_EXCHANGE, BasicExchangeScreen::new);
        MenuScreens.register(ModMenus.ALMANAC, AlmanacScreen::new);
        MenuScreens.register(ModMenus.DRAFTING_TABLE, DraftingTableScreen::new);
    }
}
