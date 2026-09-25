package com.realisticmarkets.mod.block;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.exchange.Exchange;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.floor.FloorService;
import com.realisticmarkets.mod.registry.ModBlockEntities;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import com.realisticmarkets.mod.stocks.StockService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Up to three items on a Price Board, with live quotes from its market, refreshed every {@link #REFRESH_TICKS} ticks
 * and synced to clients through the block entity update packet.
 * <ul>
 *   <li>Dealer board: the Basic Exchange's public (unlicensed) bid, ask, mid and Normal.</li>
 *   <li>Floor board: the Trading Floor book's best bid and ask, the last trade, and the price at today's first look.</li>
 *   <li>Stock board: the same for a company's shares (added with any of its certificates, which isn't used up).</li>
 * </ul>
 * Prices are kept in mills (thousandths of a dollar).
 */
public class PriceBoardBlockEntity extends BlockEntity {
    public static final int SLOTS = 3;
    public static final int REFRESH_TICKS = 100;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private final long[] bidMills = new long[SLOTS];
    private final long[] askMills = new long[SLOTS];
    private final long[] midMills = new long[SLOTS];  // Dealer: mid; Floor/Stock: last trade
    private final long[] fairMills = new long[SLOTS]; // Dealer: Normal; Floor/Stock: today's open
    private long openDay = Long.MIN_VALUE;
    private ItemStack overflow = ItemStack.EMPTY;     // a fourth item from a board saved before boards held three

    public PriceBoardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PRICE_BOARD, pos, state);
    }

    public PriceBoardBlock.Kind kind() {
        return getBlockState().getBlock() instanceof PriceBoardBlock b ? b.kind() : PriceBoardBlock.Kind.DEALER;
    }

    public int count() {
        int n = 0;
        while (n < SLOTS && !items.get(n).isEmpty()) n++;
        return n;
    }

    public ItemStack item(int i) { return items.get(i); }
    public long bidMills(int i) { return bidMills[i]; }
    public long askMills(int i) { return askMills[i]; }
    public long midMills(int i) { return midMills[i]; }
    public long fairMills(int i) { return fairMills[i]; }

    /** The market key an item stands for on this board (an item id, or a ticker), or null if it can't go on it. */
    private String key(ItemStack stack) {
        if (stack.isEmpty() || ModItems.denominationOf(stack) != null) return null;
        return switch (kind()) {
            case STOCK -> ShareCertificates.read(stack).map(ShareCertificates.Paper::ticker).orElse(null);
            default -> DealerService.itemId(stack);
        };
    }

    /** Adds one item if its market quotes it and there's room. Returns why not, or null on success. */
    public String tryAdd(ItemStack stack) {
        String k = key(stack);
        switch (kind()) {
            case STOCK -> {
                if (k == null) return "Use a Share Certificate of the company to show";
            }
            case FLOOR -> {
                FloorService floor = get(FloorService::get);
                if (k == null || floor == null || floor.floor().catalog().all().stream().noneMatch(b -> b.item().equals(k))) {
                    return "The Trading Floor has no book for that";
                }
            }
            default -> {
                if (k == null) return "Money has no price";
                DealerService d = get(DealerService::get);
                if (d == null || !d.dealer().catalog().trades(k)) return "The Dealer doesn't trade that";
            }
        }
        int n = count();
        if (n == SLOTS) return "The board is full (three items)";
        for (int i = 0; i < n; i++) if (k.equals(key(items.get(i)))) return "Already on the board";
        items.set(n, kind() == PriceBoardBlock.Kind.STOCK ? ShareCertificates.create(k, 1, -1, 1) : stack.copyWithCount(1));
        changed();
        return null;
    }

    /** Takes back the last item added, or EMPTY. */
    public ItemStack removeLast() {
        int n = count();
        if (n == 0) return ItemStack.EMPTY;
        ItemStack out = items.get(n - 1);
        items.set(n - 1, ItemStack.EMPTY);
        bidMills[n - 1] = askMills[n - 1] = midMills[n - 1] = fairMills[n - 1] = 0;
        changed();
        return out;
    }

    public void serverTick() {
        if (level == null) return;
        if (!overflow.isEmpty()) {
            Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, overflow);
            overflow = ItemStack.EMPTY;
            setChanged();
        }
        if (Math.floorMod(level.getGameTime() + worldPosition.hashCode(), REFRESH_TICKS) != 0) return;
        refreshNow();
    }

    /** Recomputes the quotes from the running services (nothing if they aren't running). */
    public void refreshNow() {
        DealerService svc = get(DealerService::get);
        if (svc == null || level == null) return;
        double day = svc.day(level.getGameTime());
        switch (kind()) {
            case FLOOR -> {
                FloorService floor = get(FloorService::get);
                if (floor != null) refreshBook(floor.floor().exchange(), day, key -> floor.fairCents(key, day));
            }
            case STOCK -> {
                StockService stocks = get(StockService::get);
                if (stocks != null) refreshBook(stocks.market().exchange(), day, stocks::fairCents);
            }
            default -> refresh(svc.dealer(), day);
        }
    }

    /** Dealer board: the public quotes. Sends an update to clients if anything moved. */
    public void refresh(Dealer dealer, double day) {
        long[][] v = new long[4][SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            String id = key(items.get(i));
            if (id == null || !dealer.catalog().trades(id)) continue;
            try {
                v[0][i] = mills(dealer.bid(id, day, false));
                v[1][i] = mills(dealer.ask(id, day, false));
                v[2][i] = mills(dealer.mid(id, day));
                v[3][i] = mills(dealer.normalValue(id, day));
            } catch (RejectedException e) {
                // leave zeros
            }
        }
        set(v);
    }

    /** Floor or Stock board: best bid and ask in the book, the last trade (fair value before any), today's open. */
    public void refreshBook(Exchange ex, double day, java.util.function.ToLongFunction<String> fairCents) {
        long today = (long) Math.floor(day);
        boolean newDay = today != openDay;
        openDay = today;
        long[][] v = new long[4][SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            String k = key(items.get(i));
            if (k == null || !ex.instruments().contains(k)) continue;
            var book = ex.book(k);
            long last = ex.lastPrice(k).orElse(fairCents.applyAsLong(k));
            v[0][i] = book.bestBid() * 10;
            v[1][i] = book.bestAsk() * 10;
            v[2][i] = last * 10;
            v[3][i] = newDay || fairMills[i] == 0 ? last * 10 : fairMills[i]; // the open stays for the day
        }
        set(v);
    }

    private void set(long[][] v) {
        boolean moved = false;
        for (int i = 0; i < SLOTS; i++) {
            moved |= v[0][i] != bidMills[i] || v[1][i] != askMills[i] || v[2][i] != midMills[i] || v[3][i] != fairMills[i];
            bidMills[i] = v[0][i];
            askMills[i] = v[1][i];
            midMills[i] = v[2][i];
            fairMills[i] = v[3][i];
        }
        if (moved) changed();
    }

    private static long mills(double dollars) {
        return Math.round(dollars * 1000.0);
    }

    private static <T> T get(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (IllegalStateException notRunning) {
            return null;
        }
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ------------------------------------------------------------------ persistence and sync

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        for (int i = 0; i < SLOTS; i++) {
            if (items.get(i).isEmpty()) continue;
            String k = key(items.get(i));
            out.putString("Item" + i, kind() == PriceBoardBlock.Kind.STOCK ? "ticker:" + k : DealerService.itemId(items.get(i)));
            out.putLong("Bid" + i, bidMills[i]);
            out.putLong("Ask" + i, askMills[i]);
            out.putLong("Mid" + i, midMills[i]);
            out.putLong("Fair" + i, fairMills[i]);
        }
        out.putLong("OpenDay", openDay);
        if (!overflow.isEmpty()) out.putString("Overflow", DealerService.itemId(overflow));
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        for (int i = 0; i < SLOTS; i++) {
            items.set(i, in.getString("Item" + i).map(PriceBoardBlockEntity::stackFor).orElse(ItemStack.EMPTY));
            bidMills[i] = in.getLongOr("Bid" + i, 0);
            askMills[i] = in.getLongOr("Ask" + i, 0);
            midMills[i] = in.getLongOr("Mid" + i, 0);
            fairMills[i] = in.getLongOr("Fair" + i, 0);
        }
        openDay = in.getLongOr("OpenDay", Long.MIN_VALUE);
        // Boards used to hold four: a fourth item drops out at the board on the next tick, rather than being lost.
        overflow = in.getString("Item3").or(() -> in.getString("Overflow")).map(PriceBoardBlockEntity::stackFor).orElse(ItemStack.EMPTY);
    }

    private static ItemStack stackFor(String id) {
        if (id.startsWith("ticker:")) return ShareCertificates.create(id.substring(7), 1, -1, 1);
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null && kind() == PriceBoardBlock.Kind.DEALER) Containers.dropContents(level, pos, items);
        if (level != null && !overflow.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), overflow);
    }
}
