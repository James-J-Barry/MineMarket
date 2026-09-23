package com.realisticmarkets.mod.test;

import com.realisticmarkets.exchange.AuctionResult;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.OrderRequest;
import com.realisticmarkets.exchange.Side;
import com.realisticmarkets.mod.MarketService;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Server GameTests: run inside a real (headless) Minecraft server via
 * {@code ./scripts/dev.sh gametest}. Use these for anything that depends on the game being loaded:
 * the mod wiring, ticking, commands, and (soon) real item custody.
 *
 * <p>Pure market logic belongs in exchange-core unit tests instead, where it runs in milliseconds.
 */
public class MarketGameTests {

    @GameTest
    public void auctionClearsInsideRunningServer(GameTestHelper helper) {
        // Fresh service so tests don't share state with each other or the live mod.
        MarketService service = new MarketService();
        Exchange ex = service.exchange();
        ex.deposit("gt-buyer", 10_000);
        ex.depositPosition("gt-seller", "DIAMOND", 10);

        ex.submit(OrderRequest.limit("gt-buyer", "DIAMOND", Side.BUY, 5, 120));
        ex.submit(OrderRequest.limit("gt-seller", "DIAMOND", Side.SELL, 5, 100));

        List<AuctionResult> results = service.runAuctions(helper.getLevel().getServer());
        AuctionResult diamond = results.stream()
                .filter(r -> r.instrument().equals("DIAMOND")).findFirst().orElseThrow();

        check(diamond.volume() == 5, "expected 5 diamonds to trade, got " + diamond.volume());
        check(ex.account("gt-buyer").position("DIAMOND") == 5, "buyer should hold 5 DIAMOND");
        helper.succeed();
    }

    /** Any exception thrown from a GameTest fails it; keeps us independent of assert-API churn. */
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
