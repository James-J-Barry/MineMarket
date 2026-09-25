package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.menu.RecordsTerminalMenu;
import com.realisticmarkets.mod.records.RecordsService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** A Records Terminal: net worth, holdings, income and a calendar across the blocks linked to it. Owner only. */
public class RecordsTerminalBlock extends Block implements EntityBlock {
    private static final Component TITLE = Component.translatable("container.realisticmarkets.records_terminal");

    public RecordsTerminalBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof Player p && level.getBlockEntity(pos) instanceof RecordsTerminalBlockEntity t) t.setOwner(p);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof RecordsTerminalBlockEntity terminal)) return InteractionResult.PASS;
        if (terminal.owner() == null) terminal.setOwner(player);
        if (!terminal.isOwner(player)) {
            if (player instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal("This terminal belongs to " + terminal.ownerName()));
            return InteractionResult.SUCCESS;
        }
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new RecordsTerminalMenu(id, inv, ContainerLevelAccess.create(level, pos),
                terminal, RecordsService.get()), TITLE));
        return InteractionResult.SUCCESS;
    }

    /** Right-clicking the terminal with a Risk Report Module fits it (once). */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(com.realisticmarkets.mod.registry.ModItems.RISK_REPORT_MODULE)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof RecordsTerminalBlockEntity terminal && terminal.isOwner(player)) {
            if (terminal.hasRiskModule()) {
                if (player instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal("This terminal already has a Risk Report Module"));
            } else {
                terminal.installRiskModule();
                stack.shrink(1);
                if (player instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal("Risk Report Module fitted: a Risk tab appears"));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RecordsTerminalBlockEntity(pos, state);
    }
}
