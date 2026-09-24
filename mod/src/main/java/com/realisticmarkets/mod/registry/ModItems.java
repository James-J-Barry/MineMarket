package com.realisticmarkets.mod.registry;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.item.BillClipItem;
import com.realisticmarkets.money.Denomination;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

public final class ModItems {
    private ModItems() {}

    /** Dime, $1, $10, $100. Not craftable and not in any loot table: exchanges are the only source. */
    public static final Map<Denomination, Item> CURRENCY = new EnumMap<>(Denomination.class);

    /** Tier 1 components: buy-only from the Dealer. No recipes, loot or villager trades. */
    public static Item LEDGER_PAPER, INK_BOTTLE, BRASS_FITTINGS;

    /** Tier 1 item made only at the Drafting Table (the Price Board and Trade Route Crate are blocks). */
    public static Item BILL_CLIP;

    public static void init() {
        for (Denomination d : Denomination.values()) {
            CURRENCY.put(d, register(d.itemName(), Item::new, new Item.Properties().stacksTo(64)));
        }
        LEDGER_PAPER = register("ledger_paper", Item::new, new Item.Properties());
        INK_BOTTLE = register("ink_bottle", Item::new, new Item.Properties());
        BRASS_FITTINGS = register("brass_fittings", Item::new, new Item.Properties());
        BILL_CLIP = register("bill_clip", BillClipItem::new, new Item.Properties().stacksTo(1)
                .component(DataComponents.CONTAINER, ItemContainerContents.EMPTY));
    }

    public static <T extends Item> T register(String name, Function<Item.Properties, T> factory, Item.Properties props) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, RealisticMarkets.id(name));
        T item = factory.apply(props.setId(key));
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    /** The denomination of a currency stack, or null if it isn't money. */
    public static Denomination denominationOf(ItemStack stack) {
        if (stack.isEmpty()) return null;
        for (Map.Entry<Denomination, Item> e : CURRENCY.entrySet()) {
            if (stack.is(e.getValue())) return e.getKey();
        }
        return null;
    }
}
