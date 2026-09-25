package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.bank.Atm;
import com.realisticmarkets.mod.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** An ATM: right-click it holding your Bank Card to reach your account (deposit, withdraw, Passbook) from anywhere. */
public class AtmBlock extends Block {
    public AtmBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(ModItems.BANK_CARD)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        String why = Atm.open(player, ContainerLevelAccess.create(level, pos), ((ServerLevel) level).getServer());
        if (why != null && player instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal(why));
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal("Insert your Bank Card"));
        return InteractionResult.SUCCESS;
    }
}
