package com.realisticmarkets.mod.bank;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.menu.BankVaultMenu;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.progression.ProgressionEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;

/** Opening a player's account away from their vault: at an ATM (with a Bank Card) or anywhere with the Pocket ATM. */
public final class Atm {
    public static final String POCKET_PERK = "pocket_atm";
    private static final Component TITLE = Component.translatable("container.realisticmarkets.atm");

    private Atm() {}

    /** Opens the account's Account tab; {@code access} is the ATM's, or NULL for a Pocket ATM. Returns why not, or null. */
    public static String open(Player player, ContainerLevelAccess access, MinecraftServer server) {
        return open(player, access, BankService.get(), ProgressionService.get(), DealerService.get(), () -> BankService.day(server));
    }

    public static String open(Player player, ContainerLevelAccess access, BankService bank, ProgressionService prog,
                              DealerService dealer, java.util.function.LongSupplier day) {
        if (!bank.hasAccount(player.getUUID())) return "No account yet: build a Bank Vault first";
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> BankVaultMenu.remote(id, inv, access, bank, prog, dealer, day), TITLE));
        prog.emit(player, new ProgressionEvent.AtmUsed(day.getAsLong()));
        return null;
    }

    static void tell(Player p, String msg) {
        if (p instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal(msg));
    }
}
