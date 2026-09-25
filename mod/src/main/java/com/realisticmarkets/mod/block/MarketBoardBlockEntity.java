package com.realisticmarkets.mod.block;

import com.realisticmarkets.dealer.WorldEvents;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.floor.FloorService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlockEntities;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import com.realisticmarkets.mod.stocks.StockService;
import com.realisticmarkets.money.Money;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A Market Board: live prices on a wall. Three pages, switched by right-clicking it: the Trading Floor's books, the
 * Stock Exchange's shares (each with its change since this morning), and the news (its owner's Newsstand stories and
 * the central bank's rate).
 */
public class MarketBoardBlockEntity extends DisplayBlockEntity {
    public static final int GOODS = 0, SHARES = 1, NEWS = 2, PAGES = 3;

    private int page;
    private long openDay = Long.MIN_VALUE;
    private final Map<String, Long> open = new HashMap<>(); // price at this morning's first look

    public MarketBoardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MARKET_BOARD, pos, state);
    }

    public int page() { return page; }

    public void nextPage() {
        page = (page + 1) % PAGES;
        setChanged();
        if (level != null && level.getServer() != null) refresh(level.getServer());
    }

    @Override
    protected void refresh(MinecraftServer server) {
        DealerService dealer = get(DealerService::get);
        if (dealer == null) return;
        double now = dealer.day(server);
        long today = (long) Math.floor(now);
        if (today != openDay) {
            openDay = today;
            open.clear();
        }
        Lines l = new Lines();
        switch (page) {
            case SHARES -> {
                StockService stocks = get(StockService::get);
                if (stocks == null) {
                    show("Shares", l.add("The Stock Exchange isn't open", "", DIM));
                    return;
                }
                for (var c : ShareCertificates.COMPANIES.all()) {
                    long price = stocks.market().exchange().lastPrice(c.ticker()).orElse(stocks.fairCents(c.ticker()));
                    l.add(c.ticker() + " " + c.name(), Money.format(price) + change(c.ticker(), price), color(c.ticker(), price));
                }
                show("Shares, day " + today, l);
            }
            case NEWS -> {
                ProgressionService prog = get(ProgressionService::get);
                var player = owner() == null ? null : server.getPlayerList().getPlayer(owner());
                boolean reader = prog != null && player != null && prog.progress(player).hasNode("newsstand");
                BankService bank = get(BankService::get);
                if (bank != null) {
                    l.add("Central bank rate", String.format(Locale.ROOT, "%.2f%% a day", bank.centralBank().rate(now) * 100), GOLD);
                }
                WorldEvents ev = dealer.events();
                if (!reader) {
                    l.add("Unlock the Newsstand to read", "", DIM).add("the market news here", "", DIM);
                } else if (ev != null) {
                    var stories = ev.recent(now + 0.5, 2);
                    if (stories.isEmpty()) l.add("A quiet day: no market news", "", DIM);
                    for (var e : stories) l.add((e.day() == today ? "" : "(yesterday) ") + e.type().headline(), "", e.type().shock() > 0 ? UP : DOWN);
                }
                show("News, day " + today, l);
            }
            default -> {
                FloorService floor = get(FloorService::get);
                if (floor == null) {
                    show("Trading Floor", l.add("The Trading Floor isn't open", "", DIM));
                    return;
                }
                for (var b : floor.floor().catalog().all()) {
                    long price = floor.floor().exchange().lastPrice(b.item()).orElse(floor.fairCents(b.item(), now));
                    l.add(name(b.item()), Money.format(price) + change(b.item(), price), color(b.item(), price));
                }
                show("Trading Floor, day " + today, l);
            }
        }
    }

    private String change(String key, long price) {
        long o = open.computeIfAbsent(key, k -> price);
        if (o <= 0) return "";
        double pct = (price - o) * 100.0 / o;
        if (Math.abs(pct) < 0.05) return "  ~";
        return String.format(Locale.ROOT, "  %s%.1f%%", pct > 0 ? "▲" : "▼", Math.abs(pct));
    }

    private int color(String key, long price) {
        long o = open.getOrDefault(key, price);
        return price > o ? UP : price < o ? DOWN : WHITE;
    }

    static String name(String itemId) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId))).getHoverName().getString();
    }

    static <T> T get(Supplier<T> s) {
        try {
            return s.get();
        } catch (IllegalStateException notRunning) {
            return null;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("Page", page);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        page = in.getIntOr("Page", 0);
    }
}
