package com.realisticmarkets.mod.dealer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.money.Money;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Dealer commands. The read-only ones work from the server console / RCON (no player needed),
 * so scripts and AI agents can inspect prices as JSON.
 *
 * <pre>
 * /mkt dealer quote &lt;item&gt; &lt;qty&gt;     JSON: sell and buy totals, bid, ask, fair value, inventory
 * /mkt dealer state                  JSON: every pool
 * /mkt dealer reload                 re-read config/realisticmarkets/*.csv|properties
 * dev only:
 * /mkt dealer sell &lt;item&gt; &lt;qty&gt;      move the Dealer as if a player sold (no items involved)
 * /mkt dealer buy &lt;item&gt; &lt;qty&gt;       buy from the Dealer, paying with bills (until the M1b screen)
 * /mkt dealer timeshift &lt;days&gt;       jump the Dealer's clock forward to test price recovery
 * /mkt dealer cash &lt;dollars&gt;         give yourself bills
 * </pre>
 * Items may be written as {@code wheat} or {@code "minecraft:wheat"}.
 */
public final class DealerCommands {
    private DealerCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> dealer = literal("dealer")
                .then(literal("quote").then(argument("item", StringArgumentType.string())
                        .then(argument("qty", LongArgumentType.longArg(1)).executes(ctx -> run(ctx, () -> quoteJson(ctx))))))
                .then(literal("state").executes(ctx -> run(ctx,
                        () -> svc().dealer().stateJson(svc().day(ctx.getSource().getServer()), false))))
                .then(literal("reload").executes(ctx -> run(ctx, () -> "Dealer reloaded: " + svc().reload())));

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            dealer.then(literal("sell").then(argument("item", StringArgumentType.string())
                            .then(argument("qty", LongArgumentType.longArg(1)).executes(ctx -> run(ctx, () -> {
                                Dealer.Quote q = svc().dealer().sell(item(ctx), qty(ctx), day(ctx), false);
                                return "Dealer bought " + q.items() + " " + q.itemId() + " for " + Money.format(q.cents())
                                        + String.format(Locale.ROOT, "; bid now %.3f", q.unitPriceAfter());
                            })))))
                    .then(literal("buy").then(argument("item", StringArgumentType.string())
                            .then(argument("qty", IntegerArgumentType.integer(1, 64 * 36)).executes(ctx -> run(ctx, () -> buy(ctx))))))
                    .then(literal("timeshift").then(argument("days", DoubleArgumentType.doubleArg(0, 10_000))
                            .executes(ctx -> run(ctx, () -> {
                                double days = DoubleArgumentType.getDouble(ctx, "days");
                                svc().shiftDays(days);
                                return String.format(Locale.ROOT, "Dealer clock moved forward %.2f days (now day %.2f)",
                                        days, day(ctx));
                            }))))
                    .then(literal("cash").then(argument("dollars", LongArgumentType.longArg(1, 1_000_000))
                            .executes(ctx -> run(ctx, () -> {
                                long cents = LongArgumentType.getLong(ctx, "dollars") * 100;
                                Wallet.give(ctx.getSource().getPlayerOrException(), cents);
                                return "Gave " + Money.format(cents);
                            }))));
        }

        d.register(literal("mkt").then(dealer));
    }

    // ------------------------------------------------------------------ actions

    private static String quoteJson(CommandContext<CommandSourceStack> ctx) {
        Dealer dl = svc().dealer();
        String item = item(ctx);
        long qty = qty(ctx);
        double day = day(ctx);
        String sell;
        try {
            sell = Long.toString(dl.quoteSell(item, qty, day, false).cents());
        } catch (RejectedException e) {
            sell = "null";
        }
        String buy;
        try {
            buy = Long.toString(dl.quoteBuy(item, qty, day, false).cents());
        } catch (RejectedException e) {
            buy = "null";
        }
        return String.format(Locale.ROOT,
                "{\"item\":\"%s\",\"qty\":%d,\"sellCents\":%s,\"buyCents\":%s,\"bid\":%.4f,\"ask\":%.4f,\"fairValue\":%.4f,\"inventory\":%.2f,\"day\":%.3f}",
                DealerCatalog.normalize(item), qty, sell, buy, dl.bid(item, day, false), dl.ask(item, day, false),
                dl.fairValue(item, day), dl.inventory(item, day), day);
    }

    private static String buy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = DealerCatalog.normalize(item(ctx));
        int qty = IntegerArgumentType.getInteger(ctx, "qty");
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        if (item == Items.AIR) throw new RejectedException("unknown item " + id);

        double day = day(ctx);
        long cost = svc().dealer().quoteBuy(id, qty, day, false).cents();
        if (!Wallet.pay(player, cost)) {
            throw new RejectedException("You need " + Money.format(cost) + " in cash; you have "
                    + Money.format(Wallet.count(player.getInventory())));
        }
        svc().dealer().buy(id, qty, day, false);
        int left = qty;
        while (left > 0) {
            int n = Math.min(left, item.getDefaultMaxStackSize());
            player.getInventory().placeItemBackInInventory(new ItemStack(item, n));
            left -= n;
        }
        return "Bought " + qty + " " + id + " for " + Money.format(cost);
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
        } catch (RejectedException | IllegalArgumentException | IllegalStateException e) {
            ctx.getSource().sendFailure(Component.literal("Rejected: " + e.getMessage()));
            return 0;
        } catch (CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static DealerService svc() {
        return DealerService.get();
    }

    private static String item(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "item");
    }

    private static long qty(CommandContext<CommandSourceStack> ctx) {
        return LongArgumentType.getLong(ctx, "qty");
    }

    private static double day(CommandContext<CommandSourceStack> ctx) {
        return svc().day(ctx.getSource().getServer());
    }
}
