package com.realisticmarkets.mod.menu;

import com.realisticmarkets.dealer.WorldEvents;
import com.realisticmarkets.rates.CentralBank;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * The Newsstand's board: the market stories of the last few days, newest first, for players who own the Newsstand
 * upgrade. Only which story and how many days ago travel to the client; the client reads headlines and the goods
 * each story moves from the same {@code events.csv}. When the rest of the market hears is not shown.
 */
public class NewsstandMenu extends AbstractContainerMenu {
    public static final String NODE = "newsstand";
    public static final int WIDTH = 236, HEIGHT = 176, MAX_STORIES = 6, DAYS_SHOWN = 3;

    private static final int D_OWNER = 0, D_DAY = 1, D_COUNT = 3, D_STORIES = 4; // per story: type index, age
    private static final int D_RATE_MOVE = D_STORIES + MAX_STORIES * 2, D_RATE = D_RATE_MOVE + 1, D_RATE_AGE = D_RATE + 1;
    private final ContainerData data = new SimpleContainerData(D_RATE_AGE + 1);
    private final ContainerLevelAccess access;
    private final Player player;
    private final ProgressionService progression; // null on the client
    private final DealerService dealer;
    private final CentralBank central; // null: no rate news (tests, or no bank)
    private int ticks;

    public NewsstandMenu(int containerId, Inventory inv) {
        this(containerId, inv, ContainerLevelAccess.NULL, null, null);
    }

    public NewsstandMenu(int containerId, Inventory inv, ContainerLevelAccess access, ProgressionService progression,
                         DealerService dealer) {
        this(containerId, inv, access, progression, dealer, null);
    }

    public NewsstandMenu(int containerId, Inventory inv, ContainerLevelAccess access, ProgressionService progression,
                         DealerService dealer, CentralBank central) {
        super(ModMenus.NEWSSTAND, containerId);
        this.central = central;
        this.access = access;
        this.player = inv.player;
        this.progression = progression;
        this.dealer = dealer;
        addDataSlots(data);
        if (progression != null) refresh();
    }

    public boolean owner() { return data.get(D_OWNER) != 0; }
    public long day() { return (long) data.get(D_DAY) | ((long) data.get(D_DAY + 1) << 15); }
    public int storyCount() { return data.get(D_COUNT); }
    /** Index into {@code WorldEvents.loadTypes()}. */
    public int storyType(int i) { return data.get(D_STORIES + i * 2); }
    /** 0 = today, 1 = yesterday, ... */
    public int storyAge(int i) { return data.get(D_STORIES + i * 2 + 1); }
    /** The latest central-bank raise or cut in the last few days: RAISE, CUT, or null. */
    public CentralBank.Move rateMove() {
        int m = data.get(D_RATE_MOVE);
        return m == 1 ? CentralBank.Move.RAISE : m == 2 ? CentralBank.Move.CUT : null;
    }
    /** Its new rate a day, in thousandths of a percent (350 = 0.35%). */
    public int rateMilliPct() { return data.get(D_RATE); }
    public int rateAge() { return data.get(D_RATE_AGE); }

    private void refresh() {
        boolean owner = progression.progress(player).hasNode(NODE);
        data.set(D_OWNER, owner ? 1 : 0);
        long today = (long) Math.floor(dealer.day(player.level().getGameTime()));
        data.set(D_DAY, (int) (today & 0x7FFF));
        data.set(D_DAY + 1, (int) ((today >> 15) & 0x7FFF));
        WorldEvents ev = dealer.events();
        List<WorldEvents.Event> stories = !owner || ev == null ? List.of() : ev.recent(today + 0.5, DAYS_SHOWN);
        int n = Math.min(stories.size(), MAX_STORIES);
        data.set(D_COUNT, n);
        for (int i = 0; i < n; i++) {
            data.set(D_STORIES + i * 2, stories.get(i).type().index());
            data.set(D_STORIES + i * 2 + 1, (int) (today - stories.get(i).day()));
        }
        data.set(D_RATE_MOVE, 0);
        if (owner && central != null) {
            for (long d = today; d > today - DAYS_SHOWN; d--) {
                var dec = central.decisionOn(d);
                if (dec.isEmpty() || dec.get().move() == CentralBank.Move.HOLD) continue;
                data.set(D_RATE_MOVE, dec.get().move() == CentralBank.Move.RAISE ? 1 : 2);
                data.set(D_RATE, (int) Math.round(dec.get().rate() * 100_000));
                data.set(D_RATE_AGE, (int) (today - d));
                break;
            }
        }
    }

    @Override
    public void broadcastChanges() {
        if (progression != null && ++ticks % 20 == 0) refresh(); // a new day's news while the board is open
        super.broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.NEWSSTAND);
    }
}
