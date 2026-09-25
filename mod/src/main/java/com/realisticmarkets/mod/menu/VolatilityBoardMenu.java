package com.realisticmarkets.mod.menu;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.options.OptionPapers;
import com.realisticmarkets.mod.options.OptionsService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.options.OptionDesk;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * The Volatility Board: for one underlying, the Options Desk's implied volatility at strikes from 70% to 130% of the
 * forward (the smile), the realized volatility it rests on, and today's price of an at-the-money call for each expiry.
 */
public class VolatilityBoardMenu extends AbstractContainerMenu {
    public static final String NODE = "volatility_board";
    public static final int WIDTH = 256, HEIGHT = 196;
    public static final double[] LEVELS = {0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0, 1.05, 1.1, 1.15, 1.2, 1.25, 1.3};
    public static final int BUTTON_UNDERLYING_BASE = 0;

    private static final int D_OWNER = 0, D_UNDER = 1, D_REALIZED = 2, D_SMILE = 3, D_ATM = D_SMILE + LEVELS.length, D_DAY = D_ATM + 4;
    private static final int D_SIZE = D_DAY + 2;

    private final ContainerData data = new SimpleContainerData(D_SIZE);
    private final ContainerLevelAccess access;
    private final Player player;
    private final OptionsService options;
    private final ProgressionService progression;
    private final DealerService dealer;
    private int ticks;

    public VolatilityBoardMenu(int containerId, Inventory inv) {
        this(containerId, inv, ContainerLevelAccess.NULL, null, null, null);
    }

    public VolatilityBoardMenu(int containerId, Inventory inv, ContainerLevelAccess access, OptionsService options,
                               ProgressionService progression, DealerService dealer) {
        super(ModMenus.VOLATILITY_BOARD, containerId);
        this.access = access;
        this.player = inv.player;
        this.options = options;
        this.progression = progression;
        this.dealer = dealer;
        addDataSlots(data);
        if (options != null) refresh();
    }

    public boolean owner() { return data.get(D_OWNER) != 0; }
    public int underlying() { return data.get(D_UNDER); }
    /** Daily volatility in hundredths of a percent. */
    public int realizedBp() { return data.get(D_REALIZED); }
    public int smileBp(int i) { return data.get(D_SMILE + i); }
    /** Price (cents) of an at-the-money call for expiry {@code e}. */
    public long atm(int e) { return (long) data.get(D_ATM + e * 2) | ((long) data.get(D_ATM + e * 2 + 1) << 15); }
    public long day() { return (long) data.get(D_DAY) | ((long) data.get(D_DAY + 1) << 15); }

    private void setPair(int i, long v) {
        long x = Math.min(Math.max(v, 0), (1L << 30) - 1);
        data.set(i, (int) (x & 0x7FFF));
        data.set(i + 1, (int) ((x >> 15) & 0x7FFF));
    }

    private void refresh() {
        double day = dealer.day(player.level().getGameTime());
        setPair(D_DAY, (long) Math.floor(day));
        data.set(D_OWNER, progression == null || progression.progress(player).hasNode(NODE) ? 1 : 0);
        if (!options.underlyings().contains(OptionPapers.UNDERLYINGS.get(underlying()))) data.set(D_UNDER, 0);
        String u = OptionPapers.UNDERLYINGS.get(underlying());
        OptionDesk desk = options.desk();
        data.set(D_REALIZED, (int) Math.round(desk.realizedVol(u) * 10_000));
        long[] exp = OptionDesk.expiries((long) Math.floor(day));
        double f = desk.market().forwardCents(u, exp[0], day);
        for (int i = 0; i < LEVELS.length; i++) data.set(D_SMILE + i, (int) Math.min(Short.MAX_VALUE, Math.round(desk.impliedVol(u, f * LEVELS[i], f) * 10_000)));
        for (int e = 0; e < 2; e++) {
            double fe = desk.market().forwardCents(u, exp[e], day);
            setPair(D_ATM + e * 2, Math.round(desk.fair(new OptionDesk.Series(u, true, Math.round(fe), exp[e]), day)));
        }
    }

    @Override
    public void broadcastChanges() {
        if (options != null && ++ticks % 20 == 0) refresh();
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (options == null || id < BUTTON_UNDERLYING_BASE || id >= BUTTON_UNDERLYING_BASE + OptionPapers.UNDERLYINGS.size()) return false;
        if (!options.underlyings().contains(OptionPapers.UNDERLYINGS.get(id))) return false;
        data.set(D_UNDER, id - BUTTON_UNDERLYING_BASE);
        refresh();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.VOLATILITY_BOARD);
    }
}
