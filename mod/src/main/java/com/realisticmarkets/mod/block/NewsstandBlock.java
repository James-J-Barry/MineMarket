package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.news.NewsService;
import com.realisticmarkets.mod.progression.ProgressionService;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Right-click for the morning paper (once a day, for owners of the Newsstand upgrade). */
public class NewsstandBlock extends Block {
    public NewsstandBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            DealerService dealer = DealerService.get();
            Optional<String> why = NewsService.get().collect(sp, ProgressionService.get(), dealer, dealer.day(level.getGameTime()));
            sp.sendOverlayMessage(Component.literal(why.orElse("Today's paper: hover over it to read")));
        }
        return InteractionResult.SUCCESS;
    }
}
