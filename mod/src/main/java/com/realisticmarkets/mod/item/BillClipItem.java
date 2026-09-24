package com.realisticmarkets.mod.item;

import com.realisticmarkets.mod.menu.BillClipMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Right-click to open the clip. Exchanges and the Almanac pay from it and into it (see Wallet). */
public class BillClipItem extends Item {
    private static final Component TITLE = Component.translatable("container.realisticmarkets.bill_clip");

    public BillClipItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) {
            ItemStack clip = player.getItemInHand(hand);
            player.openMenu(new SimpleMenuProvider((id, inv, p) -> new BillClipMenu(id, inv, hand, clip), TITLE));
        }
        return InteractionResult.SUCCESS;
    }
}
