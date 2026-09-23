package com.realisticmarkets.mod.registry;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.menu.BasicExchangeMenu;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

public final class ModMenus {
    private ModMenus() {}

    public static MenuType<BasicExchangeMenu> BASIC_EXCHANGE;

    public static void init() {
        ResourceKey<MenuType<?>> key = ResourceKey.create(Registries.MENU, RealisticMarkets.id("basic_exchange"));
        BASIC_EXCHANGE = Registry.register(BuiltInRegistries.MENU, key,
                new MenuType<>(BasicExchangeMenu::new, FeatureFlags.VANILLA_SET));
    }
}
