package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.menu.BankVaultMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
 * The owner's one Bank Vault. Only the owner can open it, and only the owner can break it, and only when their
 * account is completely empty: moving it means carrying the cash yourself (remote access is the ATM's job).
 * Blast-proof.
 */
public class BankVaultBlock extends Block implements EntityBlock {
    private static final Component TITLE = Component.translatable("container.realisticmarkets.bank_vault");

    public BankVaultBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel server) || !(placer instanceof Player player)) return;
        if (!(level.getBlockEntity(pos) instanceof BankVaultBlockEntity vault)) return;
        vault.setOwner(player);
        BankService bank = BankService.get();
        long day = BankService.day(server.getServer());
        boolean ok = bank.claimVault(player.getUUID(), vault.location(), day,
                old -> Locations.mayStillStand(server.getServer(), old, BankVaultBlockEntity.class));
        if (!ok) {
            String where = bank.account(player.getUUID(), day).vaultLocation().replace('|', ' ');
            level.removeBlock(pos, false);
            if (!player.isCreative()) player.getInventory().placeItemBackInInventory(stack.copyWithCount(1));
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("You already have a Bank Vault (" + where
                        + "). Empty it and break it first."));
            }
            return;
        }
        vault.setLocked(!bank.account(player.getUUID(), day).isEmpty());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof BankVaultBlockEntity vault)) return InteractionResult.PASS;
        if (!vault.isOwner(player)) {
            if (player instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal("This vault belongs to " + vault.ownerName()));
            return InteractionResult.SUCCESS;
        }
        var server = ((ServerLevel) level).getServer();
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new BankVaultMenu(id, inv, vault,
                ContainerLevelAccess.create(level, pos), BankService.get(), ProgressionService.get(),
                () -> BankService.day(server)), TITLE));
        return InteractionResult.SUCCESS;
    }

    /** Can't be mined at all unless you own it and your account is empty. */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof BankVaultBlockEntity vault)) return super.getDestroyProgress(state, player, level, pos);
        boolean allowed;
        if (player instanceof ServerPlayer sp) {
            allowed = mayBreak(BankService.get(), vault, player, BankService.day(((ServerLevel) sp.level()).getServer()));
        } else {
            allowed = !vault.locked() && (vault.owner() == null || vault.isOwner(player));
        }
        return allowed ? super.getDestroyProgress(state, player, level, pos) : 0f;
    }

    public static boolean mayBreak(BankService bank, BankVaultBlockEntity vault, Player player, long day) {
        return bank.mayBreakVault(vault.owner(), player, day);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BankVaultBlockEntity(pos, state);
    }
}
