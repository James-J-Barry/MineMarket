package com.realisticmarkets.mod.menu;

import com.realisticmarkets.futures.ClearingHouse;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.futures.FuturesService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
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
 * The Clearing House screen: every listed future (six goods x two expiries) with its price per lot and the player's
 * position; buy or sell lots of the selected one; the margin account (cash, equity, initial and maintenance margin,
 * what's free) with Deposit and Withdraw; and the margin call, if there is one.
 */
public class ClearingHouseMenu extends AbstractContainerMenu {
    public static final String NODE = "clearing_house";
    public static final int WIDTH = 256, HEIGHT = 214;
    public static final int N = ClearingHouse.PRODUCTS.size(), E = ClearingHouse.LISTED;
    public static final int BUTTON_PRODUCT_BASE = 0, BUTTON_EXPIRY_BASE = 10, BUTTON_LOTS_BASE = 20; // 20..23 -> LOT_STEPS
    public static final int BUTTON_BUY = 24, BUTTON_SELL = 25, BUTTON_DEPOSIT = 26, BUTTON_WITHDRAW = 27;
    public static final int[] LOT_STEPS = {-5, -1, 1, 5};

    private static final int L = 4;
    private static final int D_OWNER = 0, D_DAY = 1, D_PRODUCT = 3, D_EXPIRY = 4, D_LOTS = 5, D_CALL = 6, D_CASH_ON_HAND = 7;
    private static final int D_PRICES = 9;                       // N x E pairs
    private static final int D_POS = D_PRICES + N * E * 2;       // N x E: lots + 100
    private static final int D_BUY = D_POS + N * E, D_SELL = D_BUY + 2;
    private static final int D_CASH = D_SELL + 2, D_EQUITY = D_CASH + L, D_INITIAL = D_EQUITY + L, D_MAINT = D_INITIAL + L, D_FREE = D_MAINT + L;
    private static final int D_SIZE = D_FREE + L;

    private final ContainerData data = new SimpleContainerData(D_SIZE);
    private final ContainerLevelAccess access;
    private final Player player;
    private final FuturesService futures; // null on the client
    private final ProgressionService progression;
    private final DealerService dealer;
    private int ticks;

    public ClearingHouseMenu(int containerId, Inventory inv) {
        this(containerId, inv, ContainerLevelAccess.NULL, null, null, null);
    }

    public ClearingHouseMenu(int containerId, Inventory inv, ContainerLevelAccess access, FuturesService futures,
                             ProgressionService progression, DealerService dealer) {
        super(ModMenus.CLEARING_HOUSE, containerId);
        this.access = access;
        this.player = inv.player;
        this.futures = futures;
        this.progression = progression;
        this.dealer = dealer;
        addDataSlots(data);
        if (futures != null) {
            data.set(D_LOTS, 1);
            refresh();
        }
    }

    // ------------------------------------------------------------------ client reads

    public boolean owner() { return data.get(D_OWNER) != 0; }
    public long day() { return pair(D_DAY); }
    public int product() { return data.get(D_PRODUCT); }
    public int expiryIndex() { return data.get(D_EXPIRY); }
    public long expiry(int i) { return ClearingHouse.expiries(day())[i]; }
    public int lots() { return data.get(D_LOTS); }
    public boolean underCall() { return data.get(D_CALL) != 0; }
    public long cashOnHand() { return pair(D_CASH_ON_HAND); }
    /** Price per lot in cents of product {@code p} for listed expiry {@code e}. */
    public long price(int p, int e) { return pair(D_PRICES + (p * E + e) * 2); }
    /** The player's position in lots (negative = short). */
    public int position(int p, int e) { return data.get(D_POS + p * E + e) - 100; }
    public long buyPrice() { return pair(D_BUY); }
    public long sellPrice() { return pair(D_SELL); }
    public long cash() { return getLong(D_CASH); }
    public long equity() { return getLong(D_EQUITY); }
    public long initial() { return getLong(D_INITIAL); }
    public long maintenance() { return getLong(D_MAINT); }
    public long free() { return getLong(D_FREE); }

    private long pair(int i) {
        return (long) data.get(i) | ((long) data.get(i + 1) << 15);
    }

    private void setPair(int i, long value) {
        long v = Math.min(Math.max(value, 0), (1L << 30) - 1);
        data.set(i, (int) (v & 0x7FFF));
        data.set(i + 1, (int) ((v >> 15) & 0x7FFF));
    }

    private static final long OFFSET = 1L << 59;

    private void setLong(int i, long value) {
        long v = Math.max(-OFFSET + 1, Math.min(OFFSET - 1, value)) + OFFSET;
        for (int k = 0; k < L; k++) data.set(i + k, (int) ((v >> (15 * k)) & 0x7FFF));
    }

    private long getLong(int i) {
        long v = 0;
        for (int k = 0; k < L; k++) v |= (long) (data.get(i + k) & 0x7FFF) << (15 * k);
        return v - OFFSET;
    }

    // ------------------------------------------------------------------ server

    private double now() {
        return dealer.day(player.level().getGameTime());
    }

    private void refresh() {
        double day = now();
        long today = (long) Math.floor(day);
        setPair(D_DAY, today);
        data.set(D_OWNER, progression == null || progression.progress(player).hasNode(NODE) ? 1 : 0);
        ClearingHouse h = futures.house();
        String a = FuturesService.account(player);
        long[] expiries = ClearingHouse.expiries(today);
        var positions = h.existing(a).map(ClearingHouse.Account::positions).orElse(java.util.List.of());
        for (int p = 0; p < N; p++) {
            String code = ClearingHouse.PRODUCTS.get(p).code();
            for (int e = 0; e < E; e++) {
                setPair(D_PRICES + (p * E + e) * 2, Math.round(h.price(code, expiries[e], day)));
                long lots = 0;
                for (var pos : positions) if (pos.code().equals(code) && pos.expiry() == expiries[e]) lots = pos.lots();
                data.set(D_POS + p * E + e, (int) lots + 100);
            }
        }
        String code = ClearingHouse.PRODUCTS.get(product()).code();
        setPair(D_BUY, Math.round(h.quote(a, code, expiries[expiryIndex()], lots(), day)));
        setPair(D_SELL, Math.round(h.quote(a, code, expiries[expiryIndex()], -lots(), day)));
        boolean has = h.existing(a).isPresent();
        setLong(D_CASH, has ? h.account(a).cashCents() : 0);
        setLong(D_EQUITY, has ? h.equity(a, day) : 0);
        setLong(D_INITIAL, has ? h.required(a, day, true) : 0);
        setLong(D_MAINT, has ? h.required(a, day, false) : 0);
        setLong(D_FREE, has ? h.free(a, day) : 0);
        data.set(D_CALL, has && h.account(a).underCall() ? 1 : 0);
        setPair(D_CASH_ON_HAND, Wallet.count(player.getInventory()));
    }

    @Override
    public void broadcastChanges() {
        if (futures != null && ++ticks % 20 == 0) refresh();
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (futures == null || !owner()) return false;
        double day = now();
        Optional<String> why = Optional.empty();
        boolean handled = true;
        long expiry = ClearingHouse.expiries((long) Math.floor(day))[expiryIndex()];
        String code = ClearingHouse.PRODUCTS.get(product()).code();
        if (id >= BUTTON_PRODUCT_BASE && id < BUTTON_PRODUCT_BASE + N) {
            data.set(D_PRODUCT, id - BUTTON_PRODUCT_BASE);
        } else if (id >= BUTTON_EXPIRY_BASE && id < BUTTON_EXPIRY_BASE + E) {
            data.set(D_EXPIRY, id - BUTTON_EXPIRY_BASE);
        } else if (id >= BUTTON_LOTS_BASE && id < BUTTON_LOTS_BASE + LOT_STEPS.length) {
            data.set(D_LOTS, Math.max(1, Math.min(ClearingHouse.POSITION_LIMIT, lots() + LOT_STEPS[id - BUTTON_LOTS_BASE])));
        } else {
            switch (id) {
                case BUTTON_BUY -> why = futures.trade(p, code, expiry, lots(), day, progression);
                case BUTTON_SELL -> why = futures.trade(p, code, expiry, -lots(), day, progression);
                case BUTTON_DEPOSIT -> {
                    if (futures.depositAll(p, day, progression) == 0) why = Optional.of("You carry no cash to deposit");
                }
                case BUTTON_WITHDRAW -> {
                    if (futures.withdrawFree(p, day) == 0) why = Optional.of("Nothing free to withdraw: it's all margin");
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

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.CLEARING_HOUSE);
    }
}
