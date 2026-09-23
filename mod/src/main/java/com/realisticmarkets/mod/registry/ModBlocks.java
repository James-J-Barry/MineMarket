package com.realisticmarkets.mod.registry;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.block.BasicExchangeBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class ModBlocks {
    private ModBlocks() {}

    public static Block BASIC_EXCHANGE;

    public static void init() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, RealisticMarkets.id("basic_exchange"));
        BASIC_EXCHANGE = Registry.register(BuiltInRegistries.BLOCK, key,
                new BasicExchangeBlock(BlockBehaviour.Properties.of()
                        .setId(key)
                        .mapColor(MapColor.WOOD)
                        .strength(2.5f)
                        .sound(SoundType.WOOD)));
        ModItems.register("basic_exchange", props -> new BlockItem(BASIC_EXCHANGE, props),
                new Item.Properties().useBlockDescriptionPrefix());
    }
}
