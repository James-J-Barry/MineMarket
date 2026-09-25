package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.futures.FuturesService;
import com.realisticmarkets.mod.menu.ClearingHouseMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

/** The Clearing House: futures, margin accounts and the dawn mark. Anyone can use one; each player has their own account. */
public class ClearingHouseBlock extends Block implements EntityBlock {
    private static final Component TITLE = Component.translatable("container.realisticmarkets.clearing_house");

    public ClearingHouseBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof Player p && level.getBlockEntity(pos) instanceof ClearingHouseBlockEntity be) be.setOwner(p);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof ClearingHouseBlockEntity be && be.owner() == null) be.setOwner(player);
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ClearingHouseMenu(id, inv, ContainerLevelAccess.create(level, pos),
                FuturesService.get(), ProgressionService.get(), DealerService.get()), TITLE));
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ClearingHouseBlockEntity(pos, state);
    }
}
