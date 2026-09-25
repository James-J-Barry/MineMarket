package com.realisticmarkets.mod.bank;

import com.realisticmarkets.mod.progression.ProgressionService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * The Bank Card: the key to ATMs. With the Pocket ATM upgrade, right-click it anywhere to open your account. It's not
 * the account: lose it and you craft another; the money stays where it is.
 */
public class BankCardItem extends Item {
    public BankCardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!ProgressionService.get().progress(player).hasPerk(Atm.POCKET_PERK)) {
            Atm.tell(player, "Use it at an ATM. The Pocket ATM upgrade lets you bank from anywhere.");
            return InteractionResult.SUCCESS;
        }
        String why = Atm.open(player, ContainerLevelAccess.NULL, ((ServerLevel) level).getServer());
        if (why != null) Atm.tell(player, why);
        return InteractionResult.SUCCESS;
    }
}
