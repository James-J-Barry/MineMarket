package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.dealer.CapitalService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.menu.TradeRouteCrateMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
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

/** Belongs to whoever placed it; only they can open it. See {@link TradeRouteCrateMenu}. */
public class TradeRouteCrateBlock extends Block implements EntityBlock {
    private static final Component TITLE = Component.translatable("container.realisticmarkets.trade_route_crate");

    public TradeRouteCrateBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof Player p && level.getBlockEntity(pos) instanceof TradeRouteCrateBlockEntity crate) {
            crate.setOwner(p);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof TradeRouteCrateBlockEntity crate)) return InteractionResult.PASS;
        if (crate.owner() == null) crate.setOwner(player); // placed by a command or structure: first user claims it
        if (!crate.isOwner(player)) {
            if (player instanceof ServerPlayer sp) {
                sp.sendOverlayMessage(Component.literal("This crate belongs to " + crate.ownerName()));
            }
            return InteractionResult.SUCCESS;
        }
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new TradeRouteCrateMenu(id, inv, crate,
                ContainerLevelAccess.create(level, pos), DealerService.get(), CapitalService.get(), ProgressionService.get()),
                TITLE));
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TradeRouteCrateBlockEntity(pos, state);
    }
}
