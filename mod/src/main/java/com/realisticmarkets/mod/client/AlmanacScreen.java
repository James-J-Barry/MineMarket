package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.BLUE;
import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;
import static com.realisticmarkets.mod.client.Panels.RED;

import com.realisticmarkets.mod.menu.AlmanacMenu;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.progression.Quest;
import com.realisticmarkets.progression.QuestGoal;
import com.realisticmarkets.progression.UnlockNode;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Book-style Almanac: Upgrades, Guides and Quests tabs. */
public class AlmanacScreen extends AbstractContainerScreen<AlmanacMenu> {
    private static final int LIST_X = 8, LIST_Y = 24, ROW = 13, LIST_W = 112;
    private static final int DETAIL_X = 126, DETAIL_RIGHT = 212;
    private static final Map<String, String> GUIDE_TITLES = Map.of(
            "money_and_dealer", "Money & the Dealer",
            "spread", "The Spread",
            "price_impact", "Price Impact",
            "recovery", "Recovery & Patience",
            "diversification", "Diversification");

    private final Button[] tabs = new Button[3];
    private Button buy;

    public AlmanacScreen(AlmanacMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, AlmanacMenu.WIDTH, AlmanacMenu.HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        String[] names = {"Upgrades", "Guides", "Quests"};
        for (int t = 0; t < 3; t++) {
            int id = AlmanacMenu.BUTTON_TAB_BASE + t;
            tabs[t] = addRenderableWidget(Button.builder(Component.literal(names[t]), b -> click(id))
                    .bounds(leftPos + 70 + t * 48, topPos + 4, 46, 14).build());
        }
        buy = addRenderableWidget(Button.builder(Component.literal("Buy"), b -> click(AlmanacMenu.BUTTON_BUY))
                .bounds(leftPos + DETAIL_X, topPos + 132, DETAIL_RIGHT - DETAIL_X, 16).build());
        updateWidgets();
    }

    private void click(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(getMenu().containerId, id);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateWidgets();
    }

    private void updateWidgets() {
        if (buy == null) return;
        AlmanacMenu m = getMenu();
        for (int t = 0; t < 3; t++) tabs[t].active = m.tab() != t;
        int sel = m.selected();
        buy.visible = m.tab() == AlmanacMenu.TAB_UPGRADES && sel >= 0 && sel < AlmanacMenu.NODES.size()
                && m.nodeState(sel) != AlmanacMenu.OWNED;
        buy.active = buy.visible && m.nodeState(sel) == AlmanacMenu.AVAILABLE;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (getMenu().tab() == AlmanacMenu.TAB_UPGRADES) {
            int row = rowAt(event.x(), event.y(), AlmanacMenu.NODES.size());
            if (row >= 0) {
                click(AlmanacMenu.BUTTON_SELECT_BASE + row);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private int rowAt(double mx, double my, int count) {
        double x = mx - leftPos - LIST_X, y = my - topPos - LIST_Y;
        if (x < 0 || x >= LIST_W || y < 0) return -1;
        int i = (int) (y / ROW);
        return i < count ? i : -1;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int x = leftPos, y = topPos;
        Panels.panel(g, x, y, imageWidth, imageHeight);
        g.fill(x + 6, y + 21, x + imageWidth - 6, y + imageHeight - 6, 0xFFF3E9D2); // page
        if (getMenu().tab() == AlmanacMenu.TAB_UPGRADES) {
            g.fill(x + DETAIL_X - 4, y + 23, x + DETAIL_X - 3, y + imageHeight - 8, 0xFFB9A98A); // gutter
            int sel = getMenu().selected(), hover = rowAt(mouseX, mouseY, AlmanacMenu.NODES.size());
            for (int i = 0; i < AlmanacMenu.NODES.size(); i++) {
                if (i != sel && i != hover) continue;
                int ry = y + LIST_Y + i * ROW;
                g.fill(x + LIST_X - 1, ry - 2, x + LIST_X + LIST_W, ry + ROW - 2, i == sel ? 0xFFD9C9A3 : 0xFFE8DCC0);
            }
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, title, 8, 7, GREY, false);
        AlmanacMenu m = getMenu();
        switch (m.tab()) {
            case AlmanacMenu.TAB_GUIDES -> drawGuides(g, m);
            case AlmanacMenu.TAB_QUESTS -> drawQuests(g, m, mouseX, mouseY);
            default -> drawUpgrades(g, m);
        }
    }

    private void drawUpgrades(GuiGraphicsExtractor g, AlmanacMenu m) {
        for (int i = 0; i < AlmanacMenu.NODES.size(); i++) {
            UnlockNode n = AlmanacMenu.NODES.get(i);
            int ry = LIST_Y + i * ROW;
            int state = m.nodeState(i);
            boolean owned = state == AlmanacMenu.OWNED;
            String cost = owned ? "Owned" : Money.format(n.costCents());
            int color = owned ? GREEN : state == AlmanacMenu.AVAILABLE ? GREY : LIGHT_GREY;
            g.text(font, Panels.trim(font, n.title(), LIST_W - font.width(cost) - 4), LIST_X, ry, color, false);
            g.text(font, cost, LIST_X + LIST_W - 2 - font.width(cost), ry, owned ? GREEN : GREY, false);
        }
        String cash = "Cash " + Money.format(m.cashCents());
        g.text(font, cash, LIST_X, imageHeight - 18, GREY, false);

        int sel = m.selected();
        if (sel < 0 || sel >= AlmanacMenu.NODES.size()) {
            g.text(font, "Pick an upgrade", DETAIL_X, LIST_Y, LIGHT_GREY, false);
            return;
        }
        UnlockNode n = AlmanacMenu.NODES.get(sel);
        int ly = LIST_Y;
        ly = wrap(g, n.title(), DETAIL_X, ly, BLUE) + 4;
        g.text(font, "Tier " + n.tier() + "  " + Money.format(n.costCents()), DETAIL_X, ly, GREY, false);
        ly += 14;
        for (String grant : n.grants()) ly = wrap(g, describeGrant(grant), DETAIL_X, ly, GREY) + 2;
        ly += 4;
        String status = switch (m.nodeState(sel)) {
            case AlmanacMenu.OWNED -> "Unlocked for good";
            case AlmanacMenu.AVAILABLE -> "Available";
            case AlmanacMenu.NO_CASH -> "Not enough cash";
            case AlmanacMenu.NEEDS_QUEST -> "Needs quest: " + questTitle(n.requiredQuest());
            default -> "Locked";
        };
        wrap(g, status, DETAIL_X, ly, m.nodeState(sel) <= AlmanacMenu.AVAILABLE ? GREEN : RED);
    }

    private void drawGuides(GuiGraphicsExtractor g, AlmanacMenu m) {
        for (int i = 0; i < AlmanacMenu.GUIDES.size(); i++) {
            String id = AlmanacMenu.GUIDES.get(i);
            boolean open = m.guideUnlocked(i);
            String name = open ? GUIDE_TITLES.getOrDefault(id, id) : "??? (finish a quest)";
            g.text(font, name, LIST_X, LIST_Y + i * ROW, open ? GREY : LIGHT_GREY, false);
        }
        wrap(g, "Guide pages arrive in a later update.", LIST_X, imageHeight - 28, LIGHT_GREY);
    }

    private void drawQuests(GuiGraphicsExtractor g, AlmanacMenu m, int mouseX, int mouseY) {
        int hover = rowAt(mouseX, mouseY, AlmanacMenu.QUESTS.size());
        int w = imageWidth - 16;
        for (int i = 0; i < AlmanacMenu.QUESTS.size(); i++) {
            Quest q = AlmanacMenu.QUESTS.get(i);
            boolean done = m.questDone(i);
            int ry = LIST_Y + i * ROW;
            String reward = reward(q);
            g.text(font, (done ? "✔ " : "  ") + q.title(), LIST_X, ry, done ? GREEN : i == hover ? BLUE : GREY, false);
            g.text(font, reward, LIST_X + w - font.width(reward), ry, LIGHT_GREY, false);
        }
        String hint = hover >= 0 ? describeGoal(AlmanacMenu.QUESTS.get(hover).goal()) : "Hover a quest to see its goal.";
        wrap(g, hint, LIST_X, imageHeight - 30, hover >= 0 ? GREY : LIGHT_GREY);
    }

    /** Draws word-wrapped text within the detail/page width; returns the y after the last line. */
    private int wrap(GuiGraphicsExtractor g, String text, int x, int y, int color) {
        int right = x >= DETAIL_X ? DETAIL_RIGHT : imageWidth - 8;
        for (var line : font.split(Component.literal(text), right - x)) {
            g.text(font, line, x, y, color, false);
            y += 10;
        }
        return y;
    }

    private static String reward(Quest q) {
        if (q.rewardCents() > 0) return Money.format(q.rewardCents());
        for (String grant : q.grants()) if (grant.startsWith("perk:")) return "Perk";
        return "";
    }

    private static String questTitle(String id) {
        for (Quest q : AlmanacMenu.QUESTS) if (q.id().equals(id)) return q.title();
        return id;
    }

    private static String describeGrant(String grant) {
        String id = grant.substring(grant.indexOf(':') + 1);
        if (grant.startsWith("blueprint:")) {
            String name = id.substring(id.indexOf(':') + 1).replace('_', ' ');
            return "Blueprint: " + name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
        }
        if (grant.equals("perk:merchant_license")) return "Dealer spread 20% -> 12% on every trade";
        if (grant.startsWith("guide:")) return "Guide: " + GUIDE_TITLES.getOrDefault(id, id);
        return "Perk: " + id.replace('_', ' ');
    }

    static String describeGoal(QuestGoal goal) {
        return switch (goal) {
            case QuestGoal.AnySale() -> "Sell anything at a Basic Exchange.";
            case QuestGoal.BuyThenSell() -> "Buy an item from the Dealer, then sell the same item back.";
            case QuestGoal.SellQtyInDay(int qty) -> "Sell " + qty + " of one item within one day.";
            case QuestGoal.RecoverThenSell(double below, double back) -> String.format(Locale.ROOT,
                    "Push an item's market price below %.0f%% of normal, wait until it's back to %.0f%%, then sell it again.",
                    below * 100, back * 100);
            case QuestGoal.GroupsInDay(long cents, int groups) ->
                    "Earn " + Money.format(cents) + " from each of " + groups + " item groups in one day.";
            case QuestGoal.NetWorthAtLeast(long cents) ->
                    "Reach " + Money.format(cents) + " net worth: cash plus goods at what the Dealer pays.";
            case QuestGoal.HoldCashAtLeast(long cents) -> "Hold " + Money.format(cents) + " in cash at once.";
            case QuestGoal.Unreachable() -> "Needs the Trade Route Crate (coming soon).";
        };
    }
}
