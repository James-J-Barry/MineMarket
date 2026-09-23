package com.realisticmarkets.mod;

import com.realisticmarkets.exchange.AuctionResult;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.Fill;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The bridge between Minecraft and exchange-core. Owns the Exchange and runs a batch auction for
 * every instrument once per {@link #AUCTION_INTERVAL_TICKS} server ticks.
 *
 * <p>v0 limitations (deliberate, see ROADMAP in README):
 * <ul>
 *   <li>Positions are virtual. Real item custody (deposit/withdraw at a terminal block) comes next.</li>
 *   <li>State is in memory only; it resets on server restart. Persistence via SavedData comes next.</li>
 * </ul>
 */
public final class MarketService {
    public static final int AUCTION_INTERVAL_TICKS = 20; // one auction per second at 20 TPS
    public static final List<String> DEFAULT_TICKERS = List.of("DIAMOND", "IRON", "WHEAT");

    private final Exchange exchange = new Exchange();
    private long ticks;

    public MarketService() {
        DEFAULT_TICKERS.forEach(exchange::listInstrument);
    }

    public Exchange exchange() {
        return exchange;
    }

    public static String accountId(ServerPlayer player) {
        return player.getUUID().toString();
    }

    public void onServerTick(MinecraftServer server) {
        if (++ticks % AUCTION_INTERVAL_TICKS != 0) return;
        runAuctions(server);
    }

    public List<AuctionResult> runAuctions(MinecraftServer server) {
        List<AuctionResult> results = exchange.runAllAuctions();
        for (AuctionResult r : results) {
            if (!r.traded()) continue;
            RealisticMarkets.LOGGER.info("[auction {}] {} cleared {} @ {}",
                    r.seq(), r.instrument(), r.volume(), r.clearingPrice().getAsLong());
            if (server != null) notifyParticipants(server, r);
        }
        return results;
    }

    private static void notifyParticipants(MinecraftServer server, AuctionResult r) {
        for (Fill f : r.fills()) {
            tell(server, f.buyer(), "Bought " + f.quantity() + " " + f.instrument() + " @ " + f.price());
            tell(server, f.seller(), "Sold " + f.quantity() + " " + f.instrument() + " @ " + f.price());
        }
    }

    private static void tell(MinecraftServer server, String accountId, String msg) {
        try {
            ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(accountId));
            if (p != null) p.sendSystemMessage(Component.literal("[Market] " + msg));
        } catch (IllegalArgumentException notAPlayerUuid) {
            // bots and test accounts use non-UUID ids
        }
    }
}
