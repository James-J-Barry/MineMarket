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
import com.realisticmarkets.progression.Guides;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

/** Book-style Almanac: Upgrades, Guides and Quests tabs. */
public class AlmanacScreen extends AbstractContainerScreen<AlmanacMenu> {
    private static final int LIST_X = 8, LIST_Y = 24, ROW = 13, LIST_W = 112;
    private static final int DETAIL_X = 126, DETAIL_RIGHT = 212;
    private static final int PAGE_TOP = 24, PAGE_BOTTOM_PAD = 26;

    private final Button[] tabs = new Button[3];
    private Button buy, back, tearOut;
    private int reading = -1; // guide index being read (client-side view state)
    private int readScroll;
    private int lastTab = -1;

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
        back = addRenderableWidget(Button.builder(Component.literal("Back"), b -> reading = -1)
                .bounds(leftPos + 8, topPos + imageHeight - 24, 50, 16).build());
        tearOut = addRenderableWidget(Button.builder(Component.literal("Tear out"),
                        b -> click(AlmanacMenu.BUTTON_TEAR_OUT_BASE + reading))
                .bounds(leftPos + imageWidth - 68, topPos + imageHeight - 24, 60, 16).build());
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
        if (m.tab() != lastTab) {
            lastTab = m.tab();
            reading = -1;
        }
        for (int t = 0; t < 3; t++) tabs[t].active = m.tab() != t;
        boolean isReading = m.tab() == AlmanacMenu.TAB_GUIDES && reading >= 0;
        back.visible = tearOut.visible = isReading;
        int sel = m.selected();
        buy.visible = m.tab() == AlmanacMenu.TAB_UPGRADES && sel >= 0 && sel < AlmanacMenu.NODES.size()
                && m.nodeState(sel) != AlmanacMenu.OWNED;
        buy.active = buy.visible && m.nodeState(sel) == AlmanacMenu.AVAILABLE;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (getMenu().tab() == AlmanacMenu.TAB_GUIDES && reading < 0) {
            int row = rowAt(event.x(), event.y(), AlmanacMenu.GUIDES.size());
            if (row >= 0 && getMenu().guideUnlocked(row)) {
                reading = row;
                readScroll = 0;
                updateWidgets();
                return true;
            }
        }
        if (getMenu().tab() == AlmanacMenu.TAB_UPGRADES) {
            int row = rowAt(event.x(), event.y(), AlmanacMenu.NODES.size());
            if (row >= 0) {
                click(AlmanacMenu.BUTTON_SELECT_BASE + row);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (getMenu().tab() == AlmanacMenu.TAB_GUIDES && reading >= 0) {
            int max = Math.max(0, guideLines(AlmanacMenu.GUIDES.get(reading)).size() - visibleLines());
            readScroll = Math.max(0, Math.min(max, readScroll - 3 * (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int visibleLines() {
        return (imageHeight - PAGE_TOP - 14 - PAGE_BOTTOM_PAD) / 10;
    }

    /** Wrapped lines of a guide; an empty sequence marks a paragraph gap. */
    private List<FormattedCharSequence> guideLines(Guides.Guide guide) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (int p = 0; p < guide.paragraphs().size(); p++) {
            if (p > 0) out.add(FormattedCharSequence.EMPTY);
            for (String line : guide.paragraphs().get(p).split("\n")) {
                out.addAll(font.split(Component.literal(line), imageWidth - 20));
            }
        }
        return out;
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
            case AlmanacMenu.TAB_GUIDES -> drawGuides(g, m, mouseX, mouseY);
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
            String cost = owned ? "Owned" : Money.format(m.costCents(i));
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
        String price = Money.format(m.costCents(sel)) + (m.costCents(sel) < n.costCents() ? " (was " + Money.format(n.costCents()) + ")" : "");
        ly = wrap(g, "Tier " + n.tier() + "  " + price, DETAIL_X, ly, GREY) + 4;
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

    private void drawGuides(GuiGraphicsExtractor g, AlmanacMenu m, int mouseX, int mouseY) {
        if (reading >= 0) {
            drawGuideText(g, AlmanacMenu.GUIDES.get(reading));
            return;
        }
        int hover = rowAt(mouseX, mouseY, AlmanacMenu.GUIDES.size());
        for (int i = 0; i < AlmanacMenu.GUIDES.size(); i++) {
            boolean open = m.guideUnlocked(i);
            String name = open ? AlmanacMenu.GUIDES.get(i).title() : "??? (finish a quest)";
            g.text(font, name, LIST_X, LIST_Y + i * ROW, !open ? LIGHT_GREY : i == hover ? BLUE : GREY, false);
        }
        wrap(g, "Click a guide to read it. You can tear out a copy to keep or share.", LIST_X, imageHeight - 28, LIGHT_GREY);
    }

    private void drawGuideText(GuiGraphicsExtractor g, Guides.Guide guide) {
        g.text(font, guide.title(), LIST_X, PAGE_TOP, BLUE, false);
        List<FormattedCharSequence> lines = guideLines(guide);
        int y = PAGE_TOP + 14;
        int shown = visibleLines();
        for (int i = readScroll; i < lines.size() && i < readScroll + shown; i++, y += 10) {
            g.text(font, lines.get(i), LIST_X, y, GREY, false);
        }
        if (readScroll + shown < lines.size()) {
            String more = "scroll \u2193";
            g.text(font, more, (imageWidth - font.width(more)) / 2, imageHeight - 20, LIGHT_GREY, false);
        }
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
        if (grant.startsWith("guide:")) {
            for (Guides.Guide gd : AlmanacMenu.GUIDES) if (gd.id().equals(id)) return "Guide: " + gd.title();
        }
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
            case QuestGoal.InterestEarned(long cents) -> "Earn " + Money.format(cents) + " of interest at your Bank Vault.";
            case QuestGoal.CdMatured() -> "Hold a Certificate of Deposit until it matures, then redeem it.";
            case QuestGoal.LoanRepaid() -> "Borrow against your goods at the Bank Vault, then pay the loan off in full.";
            case QuestGoal.LimitFilled() -> "Get a limit order filled on the Trading Floor: name your price and wait.";
            case QuestGoal.BeatDealer() -> "Sell something on the Trading Floor for more than the Dealer would pay.";
            case QuestGoal.TwoBooks g -> "In one day on the Floor, buy iron blocks and sell iron ingots (or the reverse) at a profit.";
            case QuestGoal.ShipBeatsLocal() ->
                    "Ship goods with a Trade Route Crate and get more, after freight, than the local Dealer would pay.";
            case QuestGoal.ChartRead() -> "Read a book's chart at the Ticker Tape.";
            case QuestGoal.DividendCollected() -> "Collect a dividend: present Share Certificates at the Stock Exchange.";
            case QuestGoal.SharesHeld g -> "Hold " + g.shares() + " shares of one company at once.";
            case QuestGoal.BeatMarket() -> "Sell shares at the Stock Exchange for more than you paid for them there.";
            case QuestGoal.CompaniesHeld g -> "Hold shares of " + g.companies() + " different companies at once.";
            case QuestGoal.CouponCollected() -> "Collect a bond coupon at the Bond Desk.";
            case QuestGoal.HeldToMaturity() -> "Hold a bond until it matures and redeem it at the Bond Desk.";
            case QuestGoal.RateWatcher() -> "Sell a bond for more than you paid after the central bank cuts its rate.";
        };
    }
}
