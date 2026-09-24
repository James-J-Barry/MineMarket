package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.menu.SafeDepositBoxMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Safekeeping for security papers and currency: 54 slots, blast-proof, owner-only, and (like the Bank Vault)
 * breakable only by its owner once it's empty. It shows no totals of its own.
 */
public class SafeDepositBoxBlock extends Block implements EntityBlock {
    private static final Component TITLE = Component.translatable("container.realisticmarkets.safe_deposit_box");

    public SafeDepositBoxBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof Player p && level.getBlockEntity(pos) instanceof SafeDepositBoxBlockEntity box) box.setOwner(p);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof SafeDepositBoxBlockEntity box)) return InteractionResult.PASS;
        if (box.owner() == null) box.setOwner(player); // placed by a command: the first user claims it
        if (!box.isOwner(player)) {
            if (player instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal("This box belongs to " + box.ownerName()));
            return InteractionResult.SUCCESS;
        }
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new SafeDepositBoxMenu(id, inv, box.contents(),
                ContainerLevelAccess.create(level, pos)), TITLE));
        return InteractionResult.SUCCESS;
    }

    /** Only its owner can break it, and only once it's empty. */
    public static boolean mayBreak(SafeDepositBoxBlockEntity box, Player player) {
        return box.isEmpty() && (box.owner() == null || box.isOwner(player));
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof SafeDepositBoxBlockEntity box && !mayBreak(box, player)) return 0f;
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SafeDepositBoxBlockEntity(pos, state);
    }
}
