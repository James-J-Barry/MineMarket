package com.realisticmarkets.mod;

import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.dealer.CapitalService;
import com.realisticmarkets.mod.dealer.DealerCommands;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.floor.FloorService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlockEntities;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModCreativeTab;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.registry.ModMenus;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RealisticMarkets implements ModInitializer {
    public static final String MOD_ID = "realisticmarkets";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModItems.init();
        ModBlocks.init();
        ModBlockEntities.init();
        ModMenus.init();
        ModCreativeTab.init();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            DealerCommands.register(dispatcher);
        });
        ServerTickEvents.END_SERVER_TICK.register(DealerService::tick);
        ServerTickEvents.END_SERVER_TICK.register(CapitalService::tick);
        ServerTickEvents.END_SERVER_TICK.register(BankService::tick);
        ServerTickEvents.END_SERVER_TICK.register(FloorService::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(DealerService::start);
        ServerLifecycleEvents.SERVER_STARTED.register(ProgressionService::start);
        ServerLifecycleEvents.SERVER_STARTED.register(CapitalService::start);
        ServerLifecycleEvents.SERVER_STARTED.register(BankService::start);
        ServerLifecycleEvents.SERVER_STARTED.register(FloorService::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> DealerService.stop());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ProgressionService.stop());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> CapitalService.stop());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BankService.stop());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> FloorService.stop());

        LOGGER.info("Realistic Markets loaded");
    }
}
