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
    public static final int WIDTH = 256, HEIGHT = 238, STRIKES = OptionDesk.MONEYNESS.length, MAX_HOLDINGS = 4, MAX_WRITTEN = 3;
    public static final int COLLATERAL_SLOTS = 9, COLLATERAL_X = 8, COLLATERAL_Y = 84, INVENTORY_X = 48, INVENTORY_Y = 156;
    public static final double[] PAYOFF_LEVELS = {0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3};
    public static final int TAB_BUY = 0, TAB_HOLDINGS = 1, TAB_WRITE = 2;
    public static final int BUTTON_WRITE = 55, BUTTON_COLLECT_RETURNS = 67, BUTTON_TOPUP_BASE = 70;
    public static final int BUTTON_UNDERLYING_BASE = 10, BUTTON_CALL = 30, BUTTON_PUT = 31, BUTTON_EXPIRY_BASE = 32;
    public static final int BUTTON_STRIKE_BASE = 40, BUTTON_QTY_BASE = 50, BUTTON_BUY = 54, BUTTON_CLOSE_BASE = 60, BUTTON_COLLECT = 66;
    public static final int[] QTY_STEPS = {-10, -1, 1, 10};

    private static final int D_OWNER = 0, D_DAY = 1, D_TAB = 3, D_UNDER = 4, D_CALL = 5, D_EXP = 6, D_STRIKE = 7, D_QTY = 8;
    private static final int D_SPOT = 9, D_FWD = 11, D_STRIKES = 13, D_COST = D_STRIKES + STRIKES * 2, D_BID = D_COST + 2;
    private static final int D_FAIR = D_BID + 2, D_DELTA = D_FAIR + 2, D_THETA = D_DELTA + 1, D_VEGA = D_THETA + 2, D_VOL = D_VEGA + 2;
    private static final int D_PAYOFF = D_VOL + 1, D_HCOUNT = D_PAYOFF + PAYOFF_LEVELS.length * 2, D_HOLD = D_HCOUNT + 1, H_STRIDE = 4;
    private static final int D_WCOUNT = D_HOLD + MAX_HOLDINGS * H_STRIDE, D_WRIT = D_WCOUNT + 1, W_STRIDE = 4;
    // per written: contracts, premium pair, under call
    private static final int D_PREMIUM = D_WRIT + MAX_WRITTEN * W_STRIDE, D_QUALITY = D_PREMIUM + 2, D_COVERED = D_QUALITY + 1;
    private static final int D_COLLATERAL = D_COVERED + 1, D_REQUIRED = D_COLLATERAL + 2, D_ENOUGH = D_REQUIRED + 2;
    private static final int D_RETURN_CASH = D_ENOUGH + 1, D_RETURN_ITEMS = D_RETURN_CASH + 2;
    private static final int D_SIZE = D_RETURN_ITEMS + 1;

    private final ContainerData data = new SimpleContainerData(D_SIZE);
    private final SimpleContainer icons = new SimpleContainer(MAX_HOLDINGS + MAX_WRITTEN);
    private final SimpleContainer collateral = new SimpleContainer(COLLATERAL_SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            collateralChanged = true;
        }
    };
    private boolean collateralChanged;
    private List<com.realisticmarkets.options.WrittenBook.Written> writtenShown = List.of();
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
        for (int i = 0; i < MAX_HOLDINGS + MAX_WRITTEN; i++) {
            addSlot(new Slot(icons, i, -10_000, -10_000) {
                @Override
                public boolean isActive() { return false; }
                @Override
                public boolean mayPickup(Player p) { return false; }
                @Override
                public boolean mayPlace(ItemStack s) { return false; }
            });
        }
        for (int i = 0; i < COLLATERAL_SLOTS; i++) {
            addSlot(new Slot(collateral, i, COLLATERAL_X + i * 18, COLLATERAL_Y) {
                @Override
                public boolean isActive() { return tab() == TAB_WRITE; }
            });
        }
        firstInventorySlot = slots.size();
        addStandardInventorySlots(inv, INVENTORY_X, INVENTORY_Y);
        for (int i = firstInventorySlot; i < slots.size(); i++) {
            Slot base = slots.get(i);
            Slot hidden = new Slot(base.container, base.getContainerSlot(), base.x, base.y) {
                @Override
                public boolean isActive() { return tab() == TAB_WRITE; }
            };
            hidden.index = i;
            slots.set(i, hidden);
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
    public int writtenCount() { return data.get(D_WCOUNT); }
    public int writtenContracts(int i) { return data.get(D_WRIT + i * W_STRIDE); }
    public long writtenPremium(int i) { return pair(D_WRIT + i * W_STRIDE + 1); }
    public boolean writtenUnderCall(int i) { return data.get(D_WRIT + i * W_STRIDE + 3) != 0; }
    public ItemStack writtenIcon(int i) { return icons.getItem(MAX_HOLDINGS + i); }
    /** Write tab: premium for the collateral in the slots, its quality (x1000), covered share (x1000), value, need. */
    public long writePremium() { return pair(D_PREMIUM); }
    public double writeQuality() { return data.get(D_QUALITY) / 1000.0; }
    public double writeCovered() { return data.get(D_COVERED) / 1000.0; }
    public long writeCollateral() { return pair(D_COLLATERAL); }
    public long writeRequired() { return pair(D_REQUIRED); }
    public boolean writeEnough() { return data.get(D_ENOUGH) != 0; }
    public long returnCash() { return pair(D_RETURN_CASH); }
    public int returnItems() { return data.get(D_RETURN_ITEMS); }

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
        writtenShown = options.written().open(a);
        int w = Math.min(MAX_WRITTEN, writtenShown.size());
        data.set(D_WCOUNT, w);
        for (int i = 0; i < MAX_WRITTEN; i++) {
            if (i >= w) {
                icons.setItem(MAX_HOLDINGS + i, ItemStack.EMPTY);
                continue;
            }
            var wr = writtenShown.get(i);
            int base = D_WRIT + i * W_STRIDE;
            data.set(base, wr.contracts());
            setPair(base + 1, wr.premiumCents());
            data.set(base + 3, wr.underCall() ? 1 : 0);
            icons.setItem(MAX_HOLDINGS + i, OptionPapers.create(wr.series(), 1));
        }
        var waiting = options.written().waiting(a);
        setPair(D_RETURN_CASH, waiting.cashCents());
        data.set(D_RETURN_ITEMS, waiting.items().values().stream().mapToInt(Integer::intValue).sum());
        refreshWrite(day);
    }

    private void refreshWrite(double day) {
        long premium = 0, value = 0, required = 0;
        double q = 0, covered = 0;
        boolean enough = false;
        if (OptionDesk.isGood(underlyingCode()) && !collateral.isEmpty()) {
            var c = options.collateral(collateral);
            var t = options.writeTerms(selected(), quantity(), c, day);
            premium = t.premiumCents();
            q = t.quality();
            covered = t.coveredShare();
            value = t.collateralCents();
            required = t.requiredCents();
            enough = t.enough() && c.refused().isEmpty();
        }
        setPair(D_PREMIUM, premium);
        data.set(D_QUALITY, (int) Math.round(q * 1000));
        data.set(D_COVERED, (int) Math.round(covered * 1000));
        setPair(D_COLLATERAL, value);
        setPair(D_REQUIRED, required);
        data.set(D_ENOUGH, enough ? 1 : 0);
    }

    @Override
    public void broadcastChanges() {
        if (options != null && (++ticks % 20 == 0 || collateralChanged)) {
            collateralChanged = false;
            refresh();
        }
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (options == null || !owner()) return false;
        double day = now();
        Optional<String> why = Optional.empty();
        boolean handled = true;
        if (id == TAB_BUY || id == TAB_HOLDINGS || id == TAB_WRITE) {
            data.set(D_TAB, id);
        } else if (id == BUTTON_WRITE) {
            why = options.write(p, selected(), quantity(), collateral, day, progression);
        } else if (id == BUTTON_COLLECT_RETURNS) {
            if (!options.collectReturns(p)) why = Optional.of("Nothing to collect");
        } else if (id >= BUTTON_TOPUP_BASE && id < BUTTON_TOPUP_BASE + MAX_WRITTEN) {
            int i = id - BUTTON_TOPUP_BASE;
            why = i < writtenShown.size() ? options.topUp(p, writtenShown.get(i).id(), day) : Optional.of("No such option");
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

    private int firstInventorySlot;

    /** Shift-click moves goods between the inventory and the collateral slots (Write tab only). */
    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        if (tab() != TAB_WRITE) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int first = MAX_HOLDINGS + MAX_WRITTEN;
        if (index >= first && index < first + COLLATERAL_SLOTS) {
            if (!moveItemStackTo(stack, firstInventorySlot, firstInventorySlot + 36, true)) return ItemStack.EMPTY;
        } else if (index >= firstInventorySlot) {
            if (!moveItemStackTo(stack, first, first + COLLATERAL_SLOTS, false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    /** Test hook: the collateral slots. */
    public SimpleContainer collateralSlots() {
        return collateral;
    }

    @Override
    public void removed(Player p) {
        super.removed(p);
        if (!p.level().isClientSide()) clearContainer(p, collateral);
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.OPTIONS_DESK);
    }
}
