package com.realisticmarkets.mod.menu;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.options.OptionPapers;
import com.realisticmarkets.mod.options.OptionsService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.options.OptionDesk;
import com.realisticmarkets.options.OptionMath;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Options Desk. Buy tab: pick an underlying (six goods, six companies), call or put, an expiry and one of five
 * strikes; see the price, what the desk would pay back, the Greeks in plain terms and a payoff table; buy contracts.
 * Holdings tab: the option papers you carry at today's value, sell back or collect after expiry.
 */
public class OptionsDeskMenu extends AbstractContainerMenu {
    public static final String NODE = "options_desk";
    public static final int WIDTH = 256, HEIGHT = 222, STRIKES = OptionDesk.MONEYNESS.length, MAX_HOLDINGS = 6;
    public static final double[] PAYOFF_LEVELS = {0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3};
    public static final int TAB_BUY = 0, TAB_HOLDINGS = 1;
    public static final int BUTTON_UNDERLYING_BASE = 10, BUTTON_CALL = 30, BUTTON_PUT = 31, BUTTON_EXPIRY_BASE = 32;
    public static final int BUTTON_STRIKE_BASE = 40, BUTTON_QTY_BASE = 50, BUTTON_BUY = 54, BUTTON_CLOSE_BASE = 60, BUTTON_COLLECT = 66;
    public static final int[] QTY_STEPS = {-10, -1, 1, 10};

    private static final int D_OWNER = 0, D_DAY = 1, D_TAB = 3, D_UNDER = 4, D_CALL = 5, D_EXP = 6, D_STRIKE = 7, D_QTY = 8;
    private static final int D_SPOT = 9, D_FWD = 11, D_STRIKES = 13, D_COST = D_STRIKES + STRIKES * 2, D_BID = D_COST + 2;
    private static final int D_FAIR = D_BID + 2, D_DELTA = D_FAIR + 2, D_THETA = D_DELTA + 1, D_VEGA = D_THETA + 2, D_VOL = D_VEGA + 2;
    private static final int D_PAYOFF = D_VOL + 1, D_HCOUNT = D_PAYOFF + PAYOFF_LEVELS.length * 2, D_HOLD = D_HCOUNT + 1, H_STRIDE = 4;
    private static final int D_SIZE = D_HOLD + MAX_HOLDINGS * H_STRIDE;

    private final ContainerData data = new SimpleContainerData(D_SIZE);
    private final SimpleContainer icons = new SimpleContainer(MAX_HOLDINGS);
    private final ContainerLevelAccess access;
    private final Player player;
    private final OptionsService options; // null on the client
    private final ProgressionService progression;
    private final DealerService dealer;
    private List<OptionsService.Holding> shown = List.of();
    private int ticks;

    public OptionsDeskMenu(int containerId, Inventory inv) {
        this(containerId, inv, ContainerLevelAccess.NULL, null, null, null);
    }

    public OptionsDeskMenu(int containerId, Inventory inv, ContainerLevelAccess access, OptionsService options,
                           ProgressionService progression, DealerService dealer) {
        super(ModMenus.OPTIONS_DESK, containerId);
        this.access = access;
        this.player = inv.player;
        this.options = options;
        this.progression = progression;
        this.dealer = dealer;
        for (int i = 0; i < MAX_HOLDINGS; i++) {
            addSlot(new Slot(icons, i, -10_000, -10_000) {
                @Override
                public boolean isActive() { return false; }
                @Override
                public boolean mayPickup(Player p) { return false; }
                @Override
                public boolean mayPlace(ItemStack s) { return false; }
            });
        }
        addDataSlots(data);
        if (options != null) {
            data.set(D_CALL, 1);
            data.set(D_STRIKE, STRIKES / 2);
            data.set(D_QTY, 1);
            refresh();
        }
    }

    // ------------------------------------------------------------------ client reads

    public boolean owner() { return data.get(D_OWNER) != 0; }
    public long day() { return pair(D_DAY); }
    public int tab() { return data.get(D_TAB); }
    public int underlying() { return data.get(D_UNDER); }
    public String underlyingCode() { return OptionPapers.UNDERLYINGS.get(underlying()); }
    public boolean call() { return data.get(D_CALL) != 0; }
    public int expiryIndex() { return data.get(D_EXP); }
    public long expiry() { return OptionDesk.expiries(day())[expiryIndex()]; }
    public int strikeIndex() { return data.get(D_STRIKE); }
    public long strike(int i) { return pair(D_STRIKES + i * 2); }
    public int quantity() { return data.get(D_QTY); }
    /** One contract's worth of the underlying now, and its forward to the expiry (cents). */
    public long spot() { return pair(D_SPOT); }
    public long forward() { return pair(D_FWD); }
    /** What {@link #quantity()} contracts cost now; what the desk pays back for one; fair value of one (cents). */
    public long cost() { return pair(D_COST); }
    public long bid() { return pair(D_BID); }
    public long fair() { return pair(D_FAIR); }
    /** Delta per contract (per unit of forward), -1..1. */
    public double delta() { return (data.get(D_DELTA) - 2000) / 1000.0; }
    public long thetaCents() { return pair(D_THETA); }
    public long vegaCents() { return pair(D_VEGA); }
    /** Daily volatility the desk prices at, in hundredths of a percent (300 = 3.00% a day). */
    public int volBp() { return data.get(D_VOL); }
    /** What one contract pays if the underlying ends at PAYOFF_LEVELS[i] of the forward. */
    public long payoff(int i) { return pair(D_PAYOFF + i * 2); }
    public int holdingCount() { return data.get(D_HCOUNT); }
    public int holdingContracts(int i) { return data.get(D_HOLD + i * H_STRIDE); }
    public long holdingValue(int i) { return pair(D_HOLD + i * H_STRIDE + 1); }
    /** 0 = open, 1 = expired and waiting for settlement, 2 = settled (collect). */
    public int holdingState(int i) { return data.get(D_HOLD + i * H_STRIDE + 3); }
    public ItemStack holdingIcon(int i) { return icons.getItem(i); }

    private long pair(int i) {
        return (long) data.get(i) | ((long) data.get(i + 1) << 15);
    }

    private void setPair(int i, long value) {
        long v = Math.min(Math.max(value, 0), (1L << 30) - 1);
        data.set(i, (int) (v & 0x7FFF));
        data.set(i + 1, (int) ((v >> 15) & 0x7FFF));
    }

    // ------------------------------------------------------------------ server

    private double now() {
        return dealer.day(player.level().getGameTime());
    }

    /** The series selected on the Buy tab (server side). */
    public OptionDesk.Series selected() {
        double day = now();
        List<Long> ks = options.desk().strikes(underlyingCode(), expiryAt(day), day);
        long k = ks.get(Math.min(strikeIndex(), ks.size() - 1));
        return new OptionDesk.Series(underlyingCode(), call(), k, expiryAt(day));
    }

    private long expiryAt(double day) {
        return OptionDesk.expiries((long) Math.floor(day))[expiryIndex()];
    }

    private void refresh() {
        double day = now();
        long today = (long) Math.floor(day);
        setPair(D_DAY, today);
        data.set(D_OWNER, progression == null || progression.progress(player).hasNode(NODE) ? 1 : 0);
        if (!options.underlyings().contains(underlyingCode())) data.set(D_UNDER, 0);
        OptionDesk desk = options.desk();
        String u = underlyingCode();
        long expiry = expiryAt(day);
        setPair(D_SPOT, Math.round(options.spotCents(u, day)));
        double f = desk.market().forwardCents(u, expiry, day);
        setPair(D_FWD, Math.round(f));
        List<Long> ks = desk.strikes(u, expiry, day);
        for (int i = 0; i < STRIKES; i++) setPair(D_STRIKES + i * 2, i < ks.size() ? ks.get(i) : 0);
        OptionDesk.Series s = selected();
        String a = OptionsService.account(player);
        setPair(D_COST, options.costToBuy(a, s, quantity(), day));
        setPair(D_BID, desk.bid(a, s, day));
        OptionMath.Greeks g = desk.greeks(s, day);
        setPair(D_FAIR, Math.round(g.price()));
        data.set(D_DELTA, (int) Math.round(g.delta() * 1000) + 2000);
        setPair(D_THETA, Math.round(g.theta()));
        setPair(D_VEGA, Math.round(g.vega()));
        data.set(D_VOL, (int) Math.min(Short.MAX_VALUE, Math.round(desk.impliedVol(u, s.strikeCents(), f) * 10_000)));
        for (int i = 0; i < PAYOFF_LEVELS.length; i++) setPair(D_PAYOFF + i * 2, s.intrinsic(Math.round(f * PAYOFF_LEVELS[i])));
        shown = options.holdings(player, day);
        int n = Math.min(MAX_HOLDINGS, shown.size());
        data.set(D_HCOUNT, n);
        for (int i = 0; i < MAX_HOLDINGS; i++) {
            if (i >= n) {
                icons.setItem(i, ItemStack.EMPTY);
                continue;
            }
            OptionsService.Holding h = shown.get(i);
            int base = D_HOLD + i * H_STRIDE;
            data.set(base, (int) Math.min(Short.MAX_VALUE, h.contracts()));
            setPair(base + 1, h.valueCents());
            data.set(base + 3, h.settled() ? 2 : h.expired() ? 1 : 0);
            icons.setItem(i, OptionPapers.create(h.series(), 1));
        }
    }

    @Override
    public void broadcastChanges() {
        if (options != null && ++ticks % 20 == 0) refresh();
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (options == null || !owner()) return false;
        double day = now();
        Optional<String> why = Optional.empty();
        boolean handled = true;
        if (id == TAB_BUY || id == TAB_HOLDINGS) {
            data.set(D_TAB, id);
        } else if (id >= BUTTON_UNDERLYING_BASE && id < BUTTON_UNDERLYING_BASE + OptionPapers.UNDERLYINGS.size()) {
            String u = OptionPapers.UNDERLYINGS.get(id - BUTTON_UNDERLYING_BASE);
            if (options.underlyings().contains(u)) data.set(D_UNDER, id - BUTTON_UNDERLYING_BASE);
            else why = Optional.of("No share options without a Stock Exchange");
        } else if (id == BUTTON_CALL || id == BUTTON_PUT) {
            data.set(D_CALL, id == BUTTON_CALL ? 1 : 0);
        } else if (id >= BUTTON_EXPIRY_BASE && id < BUTTON_EXPIRY_BASE + 2) {
            data.set(D_EXP, id - BUTTON_EXPIRY_BASE);
        } else if (id >= BUTTON_STRIKE_BASE && id < BUTTON_STRIKE_BASE + STRIKES) {
            data.set(D_STRIKE, id - BUTTON_STRIKE_BASE);
        } else if (id >= BUTTON_QTY_BASE && id < BUTTON_QTY_BASE + QTY_STEPS.length) {
            data.set(D_QTY, Math.max(1, Math.min(64, quantity() + QTY_STEPS[id - BUTTON_QTY_BASE])));
        } else if (id == BUTTON_BUY) {
            why = options.buy(p, selected(), quantity(), day);
        } else if (id >= BUTTON_CLOSE_BASE && id < BUTTON_CLOSE_BASE + MAX_HOLDINGS) {
            int i = id - BUTTON_CLOSE_BASE;
            why = i < shown.size() ? options.close(p, shown.get(i).series(), day, progression) : Optional.of("No such option");
        } else if (id == BUTTON_COLLECT) {
            if (options.collectExpired(p, day, progression) == 0) why = Optional.of("Nothing to collect");
        } else {
            handled = false;
        }
        if (why.isPresent()) {
            handled = false;
            if (p instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal(why.get()));
        }
        refresh();
        return handled;
    }

    /** Cash the player carries (for the Buy button's state). */
    public long cash() {
        return Wallet.count(player.getInventory());
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.OPTIONS_DESK);
    }
}
