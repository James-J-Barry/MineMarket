package com.realisticmarkets.mod.menu;

import com.realisticmarkets.equities.CompanyNews;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.mod.stocks.StockService;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * The Electronic Newsfeed: company stories of the last few days, newest first, for players who own the upgrade.
 * Stories appear here at dawn, before the rest of the market has heard (never stated on screen).
 */
public class NewsfeedMenu extends AbstractContainerMenu {
    public static final String NODE = "electronic_newsfeed";
    public static final int WIDTH = 236, HEIGHT = 176, MAX_STORIES = 6, DAYS_SHOWN = 3;

    private static final int D_OWNER = 0, D_DAY = 1, D_COUNT = 3, D_STORIES = 4; // per story: type index, age
    private final ContainerData data = new SimpleContainerData(D_STORIES + MAX_STORIES * 2);
    private final ContainerLevelAccess access;
    private final Player player;
    private final StockService stocks; // null on the client
    private final ProgressionService progression;
    private final DealerService dealer;
    private int ticks;

    public NewsfeedMenu(int containerId, Inventory inv) {
        this(containerId, inv, ContainerLevelAccess.NULL, null, null, null);
    }

    public NewsfeedMenu(int containerId, Inventory inv, ContainerLevelAccess access, StockService stocks,
                        ProgressionService progression, DealerService dealer) {
        super(ModMenus.NEWSFEED, containerId);
        this.access = access;
        this.player = inv.player;
        this.stocks = stocks;
        this.progression = progression;
        this.dealer = dealer;
        addDataSlots(data);
        if (stocks != null) refresh();
    }

    public boolean owner() { return data.get(D_OWNER) != 0; }
    public long day() { return (long) data.get(D_DAY) | ((long) data.get(D_DAY + 1) << 15); }
    public int storyCount() { return data.get(D_COUNT); }
    /** Index into {@code CompanyNews.loadTypes()}. */
    public int storyType(int i) { return data.get(D_STORIES + i * 2); }
    public int storyAge(int i) { return data.get(D_STORIES + i * 2 + 1); }

    private void refresh() {
        boolean owner = progression.progress(player).hasNode(NODE);
        data.set(D_OWNER, owner ? 1 : 0);
        long today = (long) Math.floor(dealer.day(player.level().getGameTime()));
        data.set(D_DAY, (int) (today & 0x7FFF));
        data.set(D_DAY + 1, (int) ((today >> 15) & 0x7FFF));
        CompanyNews news = stocks.equities().news();
        List<CompanyNews.Story> stories = !owner || news == null ? List.of() : news.recent(today, DAYS_SHOWN);
        int n = Math.min(stories.size(), MAX_STORIES);
        data.set(D_COUNT, n);
        for (int i = 0; i < n; i++) {
            data.set(D_STORIES + i * 2, stories.get(i).type().index());
            data.set(D_STORIES + i * 2 + 1, (int) (today - stories.get(i).day()));
        }
    }

    @Override
    public void broadcastChanges() {
        if (stocks != null && ++ticks % 20 == 0) refresh();
        super.broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player p) {
        return stillValid(access, p, ModBlocks.NEWSFEED);
    }
}
