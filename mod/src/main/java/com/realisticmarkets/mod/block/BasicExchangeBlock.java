package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.dealer.DealerService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Tier 0 trading table. M1 interaction (the container screen arrives in M1b):
 * <ul>
 *   <li>Right-click holding a stack: shows the Dealer's quote for the whole stack.</li>
 *   <li>Sneak + right-click twice within 3 seconds: sells the stack and pays out bills.</li>
 *   <li>Right-click empty-handed: shows how to use it.</li>
 * </ul>
 * All logic lives in {@link DealerService}; the block only routes the interaction.
 */
public class BasicExchangeBlock extends Block {

    public BasicExchangeBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty()) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) {
            DealerService.get().onUseWithItem(sp, stack, pos, level.getGameTime());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            sp.sendOverlayMessage(Component.literal(
                    "Hold items and right-click for a quote. Sneak + right-click twice to sell."));
        }
        return InteractionResult.SUCCESS;
    }
}
