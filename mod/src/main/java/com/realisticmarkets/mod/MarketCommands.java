package com.realisticmarkets.mod;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.realisticmarkets.exchange.AuctionResult;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.Order;
import com.realisticmarkets.exchange.OrderRequest;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.exchange.Side;
import com.realisticmarkets.exchange.Snapshots;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * /mkt commands. Everything a GUI will eventually do is first reachable here, so it can be driven
 * from chat, RCON scripts, and GameTests before any screen exists.
 *
 * <pre>
 * /mkt buy  &lt;TICKER&gt; &lt;qty&gt; &lt;price&gt;
 * /mkt sell &lt;TICKER&gt; &lt;qty&gt; &lt;price&gt;
 * /mkt cancel &lt;orderId&gt;
 * /mkt book &lt;TICKER&gt;          -> JSON
 * /mkt account                 -> JSON
 * /mkt dev fund &lt;cash&gt;         (dev environment only)
 * /mkt dev give &lt;TICKER&gt; &lt;qty&gt; (dev environment only)
 * /mkt dev auction             (dev environment only; run auctions now)
 * </pre>
 */
public final class MarketCommands {
    private MarketCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        var root = literal("mkt")
                .then(literal("buy").then(orderArgs(Side.BUY)))
                .then(literal("sell").then(orderArgs(Side.SELL)))
                .then(literal("cancel").then(argument("orderId", LongArgumentType.longArg(1))
                        .executes(ctx -> run(ctx, () -> {
                            ex().cancel(LongArgumentType.getLong(ctx, "orderId"));
                            return "Cancelled order " + LongArgumentType.getLong(ctx, "orderId");
                        }))))
                .then(literal("book").then(argument("ticker", StringArgumentType.word())
                        .executes(ctx -> run(ctx, () -> Snapshots.bookJson(ex(), ticker(ctx), 10)))))
                .then(literal("account").executes(ctx -> run(ctx,
                        () -> Snapshots.accountJson(ex(), account(ctx)))));

        // Dev-only helpers: never available on a real server.
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            root.then(literal("dev")
                    .then(literal("fund").then(argument("cash", LongArgumentType.longArg(1))
                            .executes(ctx -> run(ctx, () -> {
                                long cash = LongArgumentType.getLong(ctx, "cash");
                                ex().deposit(account(ctx), cash);
                                return "Deposited " + cash;
                            }))))
                    .then(literal("give").then(argument("ticker", StringArgumentType.word())
                            .then(argument("qty", LongArgumentType.longArg(1))
                                    .executes(ctx -> run(ctx, () -> {
                                        long qty = LongArgumentType.getLong(ctx, "qty");
                                        ex().depositPosition(account(ctx), ticker(ctx), qty);
                                        return "Credited " + qty + " " + ticker(ctx);
                                    })))))
                    .then(literal("auction").executes(ctx -> run(ctx, () -> {
                        List<AuctionResult> rs = RealisticMarkets.service().runAuctions(ctx.getSource().getServer());
                        StringBuilder sb = new StringBuilder();
                        for (AuctionResult r : rs) {
                            sb.append(r.instrument()).append(": ")
                                    .append(r.traded() ? r.volume() + " @ " + r.clearingPrice().getAsLong() : "no cross")
                                    .append('\n');
                        }
                        return sb.toString().trim();
                    }))));
        }

        d.register(root);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> orderArgs(Side side) {
        return argument("ticker", StringArgumentType.word())
                .then(argument("qty", LongArgumentType.longArg(1))
                        .then(argument("price", LongArgumentType.longArg(1))
                                .executes(ctx -> run(ctx, () -> {
                                    Order o = ex().submit(OrderRequest.limit(
                                            account(ctx), ticker(ctx), side,
                                            LongArgumentType.getLong(ctx, "qty"),
                                            LongArgumentType.getLong(ctx, "price")));
                                    return "Order #" + o.id() + " accepted: " + side + " " + o.originalQuantity()
                                            + " " + o.instrument() + " @ " + o.limitPrice()
                                            + " (clears at next auction)";
                                }))));
    }

    // ------------------------------------------------------------------ helpers

    private interface Action {
        String run() throws CommandSyntaxException;
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Action action) {
        try {
            String msg = action.run();
            ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
            return 1;
        } catch (RejectedException | IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Rejected: " + e.getMessage()));
            return 0;
        } catch (CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static Exchange ex() {
        return RealisticMarkets.service().exchange();
    }

    private static String ticker(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "ticker").toUpperCase(Locale.ROOT);
    }

    private static String account(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return MarketService.accountId(ctx.getSource().getPlayerOrException());
    }
}
