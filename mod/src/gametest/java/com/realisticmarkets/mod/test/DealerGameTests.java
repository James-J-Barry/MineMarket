package com.realisticmarkets.mod.test;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.money.Denomination;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * M1 acceptance tests that need real Minecraft items and registries.
 * Pure pricing math is covered by exchange-core's DealerTest; these check the wiring.
 */
public class DealerGameTests {

    @GameTest
    public void sixtyFourWheatPaysTwentyFiveFortyInBills(GameTestHelper helper) {
        DealerService svc = DealerService.forTest(1234L);
        ItemStack wheat = new ItemStack(Items.WHEAT, 64);

        long cents = svc.sellStack(wheat, 0.0, false);

        check(cents == 2540, "expected $25.40, got " + cents + " cents");
        check(wheat.isEmpty(), "the sold stack should be consumed");

        List<ItemStack> bills = Wallet.toStacks(cents);
        check(countOf(bills, Denomination.TEN) == 2, "expected two $10 bills");
        check(countOf(bills, Denomination.ONE) == 5, "expected five $1 bills");
        check(countOf(bills, Denomination.DIME) == 4, "expected four dimes");
        check(countOf(bills, Denomination.HUNDRED) == 0, "expected no $100 bills");
        helper.succeed();
    }

    @GameTest
    public void compressedBlocksShareThePool(GameTestHelper helper) {
        DealerService svc = DealerService.forTest(1234L);
        svc.sellStack(new ItemStack(Items.IRON_BLOCK, 10), 0.0, false);
        double inventory = svc.dealer().inventory("minecraft:iron_ingot", 0.0);
        check(Math.abs(inventory - 90.0) < 1e-9, "10 iron blocks should add 90 ingots to the pool, got " + inventory);
        helper.succeed();
    }

    @GameTest
    public void currencyIsRegistered(GameTestHelper helper) {
        for (Denomination d : Denomination.values()) {
            check(ModItems.CURRENCY.get(d) != null, d + " not registered");
            check(ModItems.denominationOf(new ItemStack(ModItems.CURRENCY.get(d))) == d, d + " not recognized as money");
        }
        check(ModItems.denominationOf(new ItemStack(Items.EMERALD)) == null, "emeralds are not money");
        helper.succeed();
    }

    private static long countOf(List<ItemStack> stacks, Denomination d) {
        return stacks.stream().filter(s -> ModItems.denominationOf(s) == d).mapToLong(ItemStack::getCount).sum();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
