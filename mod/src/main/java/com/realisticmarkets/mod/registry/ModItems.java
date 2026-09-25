package com.realisticmarkets.mod.registry;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.bank.PassbookItem;
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

    /** Tier 2 components. */
    public static Item LOCK_MECHANISM, SECURITY_PAPER;

    /** Tier 3: the Clockwork Gear component, Order Slips (spent per Floor order) and Trade Receipts. */
    public static Item CLOCKWORK_GEAR, ORDER_SLIP, TRADE_RECEIPT;

    /** Tier 4: the Engraved Plate component and Share Certificates (bearer papers, 1/10/100 shares of one company). */
    public static Item ENGRAVED_PLATE, SHARE_CERTIFICATE, PORTFOLIO_BINDER;

    /** Tier 5: bond papers (Treasury and company bonds, one $100 bond a paper). */
    public static Item BOND;

    /** Tier 5 components and the Record Link (links storage blocks to a Records Terminal). */
    public static Item DISPLAY_SCREEN, CIRCUIT_BOARD, RECORD_LINK;

    /** Tier 6: the Forward Contract statement (the contract itself is on the account). */
    public static Item FORWARD_CONTRACT, MARGIN_CALL_NOTICE;

    /** Tier 7: Option Contract papers (bearer, one contract a paper, stackable series). */
    public static Item OPTION_CONTRACT;

    /** Bank papers: the Passbook (balance and history) and Certificates of Deposit (bearer papers). */
    public static Item PASSBOOK, CERTIFICATE_OF_DEPOSIT, LOAN_NOTE;

    /** Tier 1 item made only at the Drafting Table (the Price Board and Trade Route Crate are blocks). */
    public static Item BILL_CLIP;

    public static void init() {
        for (Denomination d : Denomination.values()) {
            CURRENCY.put(d, register(d.itemName(), Item::new, new Item.Properties().stacksTo(64)));
        }
        LEDGER_PAPER = register("ledger_paper", Item::new, new Item.Properties());
        INK_BOTTLE = register("ink_bottle", Item::new, new Item.Properties());
        BRASS_FITTINGS = register("brass_fittings", Item::new, new Item.Properties());
        LOCK_MECHANISM = register("lock_mechanism", Item::new, new Item.Properties());
        SECURITY_PAPER = register("security_paper", Item::new, new Item.Properties());
        CLOCKWORK_GEAR = register("clockwork_gear", Item::new, new Item.Properties());
        ORDER_SLIP = register("order_slip", Item::new, new Item.Properties());
        TRADE_RECEIPT = register("trade_receipt", Item::new, new Item.Properties().stacksTo(1));
        ENGRAVED_PLATE = register("engraved_plate", Item::new, new Item.Properties());
        SHARE_CERTIFICATE = register("share_certificate", Item::new, new Item.Properties());
        BOND = register("bond", Item::new, new Item.Properties());
        DISPLAY_SCREEN = register("display_screen", Item::new, new Item.Properties());
        CIRCUIT_BOARD = register("circuit_board", Item::new, new Item.Properties());
        FORWARD_CONTRACT = register("forward_contract", Item::new, new Item.Properties().stacksTo(1));
        OPTION_CONTRACT = register("option_contract", Item::new, new Item.Properties());
        MARGIN_CALL_NOTICE = register("margin_call_notice", Item::new, new Item.Properties().stacksTo(1));
        RECORD_LINK = register("record_link", com.realisticmarkets.mod.records.RecordLinkItem::new, new Item.Properties().stacksTo(1));
        PORTFOLIO_BINDER = register("portfolio_binder", com.realisticmarkets.mod.item.PortfolioBinderItem::new,
                new Item.Properties().stacksTo(1).component(DataComponents.CONTAINER, ItemContainerContents.EMPTY));
        PASSBOOK = register("passbook", PassbookItem::new, new Item.Properties().stacksTo(1));
        CERTIFICATE_OF_DEPOSIT = register("certificate_of_deposit", Item::new, new Item.Properties().stacksTo(1));
        LOAN_NOTE = register("loan_note", Item::new, new Item.Properties().stacksTo(1));
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
