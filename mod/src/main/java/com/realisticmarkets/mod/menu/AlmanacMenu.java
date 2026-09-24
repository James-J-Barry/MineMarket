package com.realisticmarkets.mod.menu;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.dealer.Wallet;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModMenus;
import com.realisticmarkets.progression.PlayerProgress;
import com.realisticmarkets.progression.Quest;
import com.realisticmarkets.progression.Quests;
import com.realisticmarkets.progression.UnlockNode;
import com.realisticmarkets.progression.UnlockTree;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * The Almanac: Upgrades (buy nodes with bills), Guides, Quests. No item slots. Deliberately no net-worth
 * view; that's a later purchase. Opening it reports net worth to the quests (Bookkeeper).
 */
public class AlmanacMenu extends AbstractContainerMenu {
    public static final int WIDTH = 220;
    public static final int HEIGHT = 170;

    public static final int TAB_UPGRADES = 0, TAB_GUIDES = 1, TAB_QUESTS = 2;
    public static final int BUTTON_TAB_BASE = 0; // 0..2
    public static final int BUTTON_BUY = 3;
    public static final int BUTTON_SELECT_BASE = 100;

    public static final int OWNED = 0, AVAILABLE = 1, NO_CASH = 2, NEEDS_QUEST = 3, LOCKED = 4;

    /** Same resources on client and server, so indices line up. */
    public static final List<UnlockNode> NODES = List.copyOf(UnlockTree.loadDefault().all());
    public static final List<Quest> QUESTS = List.copyOf(Quests.loadDefault().all());
    public static final List<String> GUIDES = guideIds();

    private static final int D_TAB = 0;
    private static final int D_CASH = 1;       // 2 slots
    private static final int D_SELECTED = 3;   // node index + 1
    private static final int D_NODES = 4;
    private static final int D_QUESTS = D_NODES + NODES.size();
    private static final int D_GUIDES = D_QUESTS + QUESTS.size();
    private static final int DATA_SIZE = D_GUIDES + GUIDES.size();
    private static final int REFRESH_TICKS = 10;

    private final ContainerData data = new SimpleContainerData(DATA_SIZE);
    private final ContainerLevelAccess access;
    private final Player player;
    private final ProgressionService progression; // null on the client
    private int ticks;

    public AlmanacMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL, null, null);
    }

    public AlmanacMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access,
                       ProgressionService progression, DealerService dealer) {
        super(ModMenus.ALMANAC, containerId);
        this.access = access;
        this.player = playerInventory.player;
        this.progression = progression;
        addDataSlots(data);
        if (progression != null) {
            if (dealer != null) {
                progression.emitNetWorth(player, dealer, dealer.day(player.level().getGameTime()), 0);
            }
            refresh();
        }
    }

    private static List<String> guideIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Quest q : Quests.loadDefault().all()) collectGuides(q.grants(), ids);
        for (UnlockNode n : UnlockTree.loadDefault().all()) collectGuides(n.grants(), ids);
        return List.copyOf(new ArrayList<>(ids));
    }

    private static void collectGuides(List<String> grants, Set<String> into) {
        for (String g : grants) if (g.startsWith("guide:")) into.add(g.substring("guide:".length()));
    }

    // ---- reads (client and server)

    public int tab() { return data.get(D_TAB); }
    public long cashCents() { return (long) data.get(D_CASH) | ((long) data.get(D_CASH + 1) << 15); }
    public int selected() { return data.get(D_SELECTED) - 1; }
    public int nodeState(int i) { return data.get(D_NODES + i); }
    public boolean questDone(int i) { return data.get(D_QUESTS + i) != 0; }
    public boolean guideUnlocked(int i) { return data.get(D_GUIDES + i) != 0; }

    // ---- server

    @Override
    public void broadcastChanges() {
        if (progression != null && ++ticks % REFRESH_TICKS == 0) refresh();
        super.broadcastChanges();
    }

    private void refresh() {
        PlayerProgress p = progression.progress(player);
        UnlockTree tree = progression.tree();
        long cash = Wallet.count(player.getInventory());
        long synced = Math.min(cash, (1L << 30) - 1);
        data.set(D_CASH, (int) (synced & 0x7FFF));
        data.set(D_CASH + 1, (int) ((synced >> 15) & 0x7FFF));
        for (int i = 0; i < NODES.size(); i++) {
            UnlockNode n = tree.node(NODES.get(i).id());
            int state;
            if (p.hasNode(n.id())) state = OWNED;
            else if (p.canBuy(n, tree, cash)) state = AVAILABLE;
            else if (n.requiredQuest() != null && !p.hasCompleted(n.requiredQuest())) state = NEEDS_QUEST;
            else if (p.canBuy(n, tree, Long.MAX_VALUE)) state = NO_CASH;
            else state = LOCKED;
            data.set(D_NODES + i, state);
        }
        for (int i = 0; i < QUESTS.size(); i++) data.set(D_QUESTS + i, p.hasCompleted(QUESTS.get(i).id()) ? 1 : 0);
        for (int i = 0; i < GUIDES.size(); i++) data.set(D_GUIDES + i, p.hasGuide(GUIDES.get(i)) ? 1 : 0);
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (progression == null) return false;
        boolean handled;
        if (id >= BUTTON_TAB_BASE && id <= BUTTON_TAB_BASE + TAB_QUESTS) {
            data.set(D_TAB, id - BUTTON_TAB_BASE);
            handled = true;
        } else if (id == BUTTON_BUY) {
            int sel = selected();
            handled = tab() == TAB_UPGRADES && sel >= 0 && sel < NODES.size()
                    && progression.buyNode(p, NODES.get(sel).id()).isEmpty();
        } else if (id >= BUTTON_SELECT_BASE && id < BUTTON_SELECT_BASE + NODES.size()) {
            data.set(D_SELECTED, id - BUTTON_SELECT_BASE + 1);
            handled = true;
        } else {
            handled = false;
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
        return stillValid(access, p, ModBlocks.ALMANAC_LECTERN);
    }
}
