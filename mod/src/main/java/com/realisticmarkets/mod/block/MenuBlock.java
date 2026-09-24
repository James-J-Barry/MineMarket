package com.realisticmarkets.mod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** A block whose only job is opening a menu. Holds no state: progress lives with the player's account. */
public class MenuBlock extends Block {
    @FunctionalInterface
    public interface Factory {
        AbstractContainerMenu create(int containerId, Inventory inventory, ContainerLevelAccess access);
    }

    private final Component title;
    private final Factory factory;

    public MenuBlock(Properties properties, Component title, Factory factory) {
        super(properties);
        this.title = title;
        this.factory = factory;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) player.openMenu(state.getMenuProvider(level, pos));
        return InteractionResult.SUCCESS;
    }

    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return new SimpleMenuProvider(
                (containerId, inventory, player) -> factory.create(containerId, inventory, ContainerLevelAccess.create(level, pos)),
                title);
    }
}
