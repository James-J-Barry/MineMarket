package com.realisticmarkets.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RealisticMarkets implements ModInitializer {
    public static final String MOD_ID = "realisticmarkets";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final MarketService SERVICE = new MarketService();

    public static MarketService service() {
        return SERVICE;
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> MarketCommands.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(SERVICE::onServerTick);
        LOGGER.info("Realistic Markets loaded: {} instruments, auction every {} ticks",
                SERVICE.exchange().instruments().size(), MarketService.AUCTION_INTERVAL_TICKS);
    }
}
