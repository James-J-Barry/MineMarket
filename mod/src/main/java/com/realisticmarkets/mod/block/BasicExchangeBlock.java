package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.menu.BasicExchangeMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Tier 0 trading table. Right-click (with or without an item in hand) opens the furnace-style
 * {@link BasicExchangeMenu}. All pricing lives in {@link DealerService}.
 */
public class BasicExchangeBlock extends Block {
    private static final Component TITLE = Component.translatable("container.realisticmarkets.basic_exchange");

    public BasicExchangeBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) {
            player.openMenu(state.getMenuProvider(level, pos));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider(
                (containerId, inventory, player) -> new BasicExchangeMenu(
                        containerId, inventory, ContainerLevelAccess.create(level, pos), DealerService.get()),
                TITLE);
    }
}
