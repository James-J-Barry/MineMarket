package com.realisticmarkets.mod.menu;

import com.realisticmarkets.bonds.Bond;
import com.realisticmarkets.equities.Company;
import com.realisticmarkets.mod.bonds.BondService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * The Bond Desk. Buy tab: pick an issuer (the Treasury or a company) and a maturity, and buy new bonds at par; the
 * yield curve shows today's yield for every issuer and maturity. Holdings tab: the bonds you carry at the desk's price,
 * sell a series, and collect coupons, maturities and recoveries.
 */
public class BondDeskMenu extends AbstractContainerMenu {
    public static final int WIDTH = 236, HEIGHT = 254, INVENTORY_X = 38, INVENTORY_Y = 172, MAX_HOLDINGS = 5;
    public static final List<String> ISSUERS = issuers();
    public static final int TAB_BUY = 0, TAB_HOLDINGS = 1;
    public static final int BUTTON_TAB_BUY = 0, BUTTON_TAB_HOLDINGS = 1, BUTTON_COUNT_MINUS_10 = 2, BUTTON_COUNT_MINUS_1 = 3;
    public static final int BUTTON_COUNT_PLUS_1 = 4, BUTTON_COUNT_PLUS_10 = 5, BUTTON_BUY = 6, BUTTON_COLLECT = 7;
    public static final int BUTTON_ISSUER_BASE = 10, BUTTON_MATURITY_BASE = 20, BUTTON_SELL_BASE = 30;

    private static final int D_TAB = 0, D_ISSUER = 1, D_MAT = 2, D_COUNT = 3, D_COUPON = 5, D_YIELDS = 6;
    private static final int D_WAIT = D_YIELDS + 7 * 3, D_HOLD_COUNT = D_WAIT + 2, D_HOLD = D_HOLD_COUNT + 1, HOLD_STRIDE = 8;
    // per holding: issuer index, maturity day pair, count pair, bid pair, defaulted
    private static final int D_SIZE = D_HOLD + MAX_HOLDINGS * HOLD_STRIDE;

    private final ContainerData data = new SimpleContainerData(D_SIZE);
    private final ContainerLevelAccess access;
    private final Player player;
    private final BondService bonds; // null on the client
    private final ProgressionService progression;
    private final DealerService dealer;
    private List<BondService.Holding> shown = List.of();
    private int ticks;

    private static List<String> issuers() {
        List<String> out = new ArrayList<>();
        out.add(Bond.TREASURY);
        for (Company c : ShareCertificates.COMPANIES.all()) out.add(c.ticker());
        return List.copyOf(out);
    }

    public BondDeskMenu(int containerId, Inventory inv) {
        this(containerId, inv, ContainerLevelAccess.NULL, null, null, null);
    }

    public BondDeskMenu(int containerId, Inventory inv, ContainerLevelAccess access, BondService bonds, ProgressionService progression,
                        DealerService dealer) {
        super(ModMenus.BOND_DESK, containerId);
        this.access = access;
        this.player = inv.player;
        this.bonds = bonds;
        this.progression = progression;
        this.dealer = dealer;
        addStandardInventorySlots(inv, INVENTORY_X, INVENTORY_Y);
        addDataSlots(data);
        if (bonds != null) {
            data.set(D_COUNT, 10);
            data.set(D_MAT, 1);
            refresh();
        }
    }

    public int tab() { return data.get(D_TAB); }
    public int issuer() { return data.get(D_ISSUER); }
    public int maturity() { return Bond.MATURITY_QUARTERS[data.get(D_MAT)]; }
    public int maturityIndex() { return data.get(D_MAT); }
    public int count() { return data.get(D_COUNT) | (data.get(D_COUNT + 1) << 15); }
    /** Coupon a quarter (cents) of a bond bought now. */
    public int newCoupon() { return data.get(D_COUPON); }
    /** Today's yield a day, in thousandths of a percent, for issuer {@code i} and maturity index {@code m}. */
    public int yieldMilliPct(int i, int m) { return data.get(D_YIELDS + i * 3 + m); }
    public long waiting() { return pair(D_WAIT); }
    public int holdingCount() { return data.get(D_HOLD_COUNT); }
    public int holdingIssuer(int i) { return data.get(D_HOLD + i * HOLD_STRIDE); }
    public long holdingMaturityDay(int i) { return pair(D_HOLD + i * HOLD_STRIDE + 1); }
    public long holdingBonds(int i) { return pair(D_HOLD + i * HOLD_STRIDE + 3); }
    public long holdingBid(int i) { return pair(D_HOLD + i * HOLD_STRIDE + 5); }
    public boolean holdingDefaulted(int i) { return data.get(D_HOLD + i * HOLD_STRIDE + 7) == 1; }
    /** Past its maturity day: Collect pays the face (shown in {@link #holdingBid}) and hands the papers in. */
    public boolean holdingMatured(int i) { return data.get(D_HOLD + i * HOLD_STRIDE + 7) == 2; }

    private long pair(int i) {
        return (long) data.get(i) | ((long) data.get(i + 1) << 15);
    }

    private void setPair(int i, long value) {
        long v = Math.min(Math.max(value, 0), (1L << 30) - 1);
        data.set(i, (int) (v & 0x7FFF));
        data.set(i + 1, (int) ((v >> 15) & 0x7FFF));
    }

    private double day() {
        return dealer.day(player.level().getGameTime());
    }

    private void refresh() {
        double now = day();
        long today = (long) Math.floor(now);
        for (int i = 0; i < ISSUERS.size(); i++) {
            for (int m = 0; m < 3; m++) {
                double y = bonds.desk().yield(ISSUERS.get(i), Bond.MATURITY_QUARTERS[m], now);
                data.set(D_YIELDS + i * 3 + m, (int) Math.min(Short.MAX_VALUE, Math.round(y * 100_000)));
            }
        }
        data.set(D_COUPON, (int) Math.min(Short.MAX_VALUE, bonds.desk().issue(ISSUERS.get(issuer()), maturity(), today).couponCents()));
        setPair(D_WAIT, bonds.waiting(player, now));
        shown = bonds.holdings(player, now);
        data.set(D_HOLD_COUNT, Math.min(MAX_HOLDINGS, shown.size()));
        for (int i = 0; i < Math.min(MAX_HOLDINGS, shown.size()); i++) {
            BondService.Holding h = shown.get(i);
            int base = D_HOLD + i * HOLD_STRIDE;
            data.set(base, ISSUERS.indexOf(h.bond().issuer()));
            setPair(base + 1, h.bond().maturityDay());
            setPair(base + 3, h.count());
            boolean matured = !h.defaulted() && h.bond().matured(now);
            setPair(base + 5, matured ? bonds.desk().redemption(h.bond(), now) : h.bidCents());
            data.set(base + 7, h.defaulted() ? 1 : matured ? 2 : 0);
        }
    }

    @Override
    public void broadcastChanges() {
        if (bonds != null && ++ticks % 20 == 0) refresh();
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (bonds == null) return false;
        long today = (long) Math.floor(day());
        Optional<String> why = Optional.empty();
        boolean handled = true;
        if (id >= BUTTON_ISSUER_BASE && id < BUTTON_ISSUER_BASE + ISSUERS.size()) {
            data.set(D_ISSUER, id - BUTTON_ISSUER_BASE);
        } else if (id >= BUTTON_MATURITY_BASE && id < BUTTON_MATURITY_BASE + 3) {
            data.set(D_MAT, id - BUTTON_MATURITY_BASE);
        } else if (id >= BUTTON_SELL_BASE && id < BUTTON_SELL_BASE + MAX_HOLDINGS) {
            int i = id - BUTTON_SELL_BASE;
            why = i < shown.size() ? bonds.sell(p, shown.get(i).bond().series(), progression, today) : Optional.of("No such bond");
        } else {
            switch (id) {
                case BUTTON_TAB_BUY -> data.set(D_TAB, TAB_BUY);
                case BUTTON_TAB_HOLDINGS -> data.set(D_TAB, TAB_HOLDINGS);
                case BUTTON_COUNT_MINUS_10 -> setCount(count() - 10);
                case BUTTON_COUNT_MINUS_1 -> setCount(count() - 1);
                case BUTTON_COUNT_PLUS_1 -> setCount(count() + 1);
                case BUTTON_COUNT_PLUS_10 -> setCount(count() + 10);
                case BUTTON_BUY -> why = bonds.buy(p, ISSUERS.get(issuer()), maturity(), count(), today);
                case BUTTON_COLLECT -> {
                    if (bonds.present(p, progression, today) == 0) why = Optional.of("Nothing due on the bonds you carry");
                }
                default -> handled = false;
            }
        }
        if (why.isPresent()) {
            handled = false;
            if (p instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal(why.get()));
        }
        refresh();
        return handled;
    }

    private void setCount(int n) {
        int c = Math.max(1, Math.min(640, n));
        data.set(D_COUNT, c & 0x7FFF);
        data.set(D_COUNT + 1, c >> 15);
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.BOND_DESK);
    }
}
