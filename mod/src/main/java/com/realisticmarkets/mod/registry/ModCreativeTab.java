package com.realisticmarkets.mod.registry;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.money.Denomination;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * The mod's creative tab. Per the design doc, currency is not craftable or obtainable in survival
 * except through exchanges; creative mode is the only other source (for building and testing).
 * Fabric's creative tab API pages modded tabs automatically, so the row/column here is a placeholder.
 */
public final class ModCreativeTab {
    private ModCreativeTab() {}

    public static CreativeModeTab MAIN;

    public static void init() {
        ResourceKey<CreativeModeTab> key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, RealisticMarkets.id("main"));
        MAIN = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, key,
                CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                        .title(Component.translatable("itemGroup.realisticmarkets"))
                        .icon(() -> new ItemStack(ModItems.CURRENCY.get(Denomination.HUNDRED)))
                        .displayItems((params, output) -> {
                            output.accept(ModBlocks.BASIC_EXCHANGE);
                            output.accept(ModBlocks.ALMANAC_LECTERN);
                            output.accept(ModBlocks.DRAFTING_TABLE);
                            output.accept(ModItems.CURRENCY.get(Denomination.DIME));
                            output.accept(ModItems.CURRENCY.get(Denomination.ONE));
                            output.accept(ModItems.CURRENCY.get(Denomination.TEN));
                            output.accept(ModItems.CURRENCY.get(Denomination.HUNDRED));
                            output.accept(ModItems.LEDGER_PAPER);
                            output.accept(ModItems.INK_BOTTLE);
                            output.accept(ModItems.BRASS_FITTINGS);
                            output.accept(ModItems.BILL_CLIP);
                            output.accept(ModBlocks.PRICE_BOARD);
                            output.accept(ModBlocks.TRADE_ROUTE_CRATE);
                            output.accept(ModBlocks.BANK_VAULT);
                            output.accept(ModItems.LOCK_MECHANISM);
                            output.accept(ModItems.SECURITY_PAPER);
                            output.accept(ModItems.PASSBOOK);
                            output.accept(ModBlocks.TRADING_FLOOR);
                            output.accept(ModItems.CLOCKWORK_GEAR);
                            output.accept(ModItems.ORDER_SLIP);
                            output.accept(ModBlocks.NEWSSTAND);
                            output.accept(ModBlocks.TICKER_TAPE);
                            output.accept(ModBlocks.STOCK_EXCHANGE);
                            output.accept(ModBlocks.NEWSFEED);
                            output.accept(ModItems.PORTFOLIO_BINDER);
                            output.accept(ModBlocks.SAFE_DEPOSIT_BOX);
                            output.accept(ModBlocks.BOND_DESK);
                            output.accept(ModItems.ENGRAVED_PLATE);
                            output.accept(ModBlocks.RECORDS_TERMINAL);
                            output.accept(ModItems.RECORD_LINK);
                            output.accept(ModItems.DISPLAY_SCREEN);
                            output.accept(ModItems.CIRCUIT_BOARD);
                            output.accept(ModItems.FORWARD_CONTRACT);
                            output.accept(ModBlocks.CLEARING_HOUSE);
                            output.accept(ModItems.MARGIN_CALL_NOTICE);
                            output.accept(ModBlocks.OPTIONS_DESK);
                            output.accept(ModItems.OPTION_CONTRACT);
                            output.accept(ModBlocks.VOLATILITY_BOARD);
                            output.accept(ModItems.RISK_REPORT_MODULE);
                            output.accept(ModBlocks.ATM);
                            output.accept(ModItems.BANK_CARD);
                            output.accept(ModItems.COMPUTER_CHIP);
                            output.accept(ModBlocks.BROKERAGE_TERMINAL);
                            output.accept(ModBlocks.MARKET_BOARD);
                            output.accept(ModBlocks.LEDGER_DISPLAY);
                        })
                        .build());
    }
}
