package com.realisticmarkets.mod.client;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.menu.BasicExchangeMenu;
import com.realisticmarkets.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Basic Exchange screen with Sell and Buy tabs.
 *
 * <p>Minecraft 26.1 renamed GUI drawing: GuiGraphics -> GuiGraphicsExtractor, renderBg ->
 * extractBackground, renderLabels -> extractLabels, drawString -> text. Text colors need an
 * explicit opaque alpha. Label coordinates are relative to the panel's top-left corner.
 */
public class BasicExchangeScreen extends AbstractContainerScreen<BasicExchangeMenu> {
    private static final Identifier TEXTURE = RealisticMarkets.id("textures/gui/container/basic_exchange.png");

    private static final int GREY = 0xFF404040;
    private static final int LIGHT_GREY = 0xFF707070;
    private static final int GREEN = 0xFF1E6B2E;
    private static final int RED = 0xFFA01010;
    private static final int BLUE = 0xFF1F3F8F;

    // Buy grid geometry (relative to panel): 5 columns of 18px cells, a scrollbar, group headers
    private static final int LIST_X = 4, LIST_Y = 18, CELL = 18, COLS = 5, HEADER_H = 10;
    private static final int GRID_W = COLS * CELL;           // 90
    private static final int LIST_W = GRID_W + 6;            // grid + gap + scrollbar
    private static final int LIST_H = 108;
    private static final int DETAIL_X = 102, DETAIL_RIGHT = 171;
    private static final String[] GROUP_LABELS = {"Farm", "Mining", "Mobs", "Wood & Stone", "Components", "Other"};

    /** One visual line of the buy grid: a group header (items == null) or up to COLS item indices. */
    private record Line(int group, int[] items) {
        boolean header() { return items == null; }
        int height() { return header() ? HEADER_H : CELL; }
    }

    private Button sellTab, buyTab, forwardTab, sellButton, signButton;
    private final Button[] qtyButtons = new Button[BasicExchangeMenu.FWD_QTY_STEPS.length];
    private final Button[] termButtons = new Button[com.realisticmarkets.contracts.ForwardBook.TERMS.length];
    private final Button[] deliverButtons = new Button[BasicExchangeMenu.MAX_FORWARDS_SHOWN];
    private final Button[] buyButtons = new Button[BasicExchangeMenu.BUY_QUANTITIES.length];
    private int scroll;

    public BasicExchangeScreen(BasicExchangeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BasicExchangeMenu.WIDTH, BasicExchangeMenu.HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        sellTab = addRenderableWidget(Button.builder(Component.translatable("gui.realisticmarkets.tab_sell"),
                b -> click(BasicExchangeMenu.BUTTON_TAB_SELL)).bounds(leftPos + 88, topPos + 3, 28, 13).build());
        buyTab = addRenderableWidget(Button.builder(Component.translatable("gui.realisticmarkets.tab_buy"),
                b -> click(BasicExchangeMenu.BUTTON_TAB_BUY)).bounds(leftPos + 116, topPos + 3, 28, 13).build());
        forwardTab = addRenderableWidget(Button.builder(Component.literal("Fwd"),
                b -> click(BasicExchangeMenu.BUTTON_TAB_FORWARD)).bounds(leftPos + 144, topPos + 3, 28, 13).build());
        forwardTab.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                "Forward Contracts: agree today on a price for goods you deliver later")));
        for (int i = 0; i < qtyButtons.length; i++) {
            int id = BasicExchangeMenu.BUTTON_FWD_QTY_BASE + i, step = BasicExchangeMenu.FWD_QTY_STEPS[i];
            qtyButtons[i] = addRenderableWidget(Button.builder(Component.literal((step > 0 ? "+" : "") + step), b -> click(id))
                    .bounds(leftPos + 48 + i * 31, topPos + 32, 29, 13).build());
        }
        for (int i = 0; i < termButtons.length; i++) {
            int id = BasicExchangeMenu.BUTTON_FWD_TERM_BASE + i;
            termButtons[i] = addRenderableWidget(Button.builder(Component.literal(com.realisticmarkets.contracts.ForwardBook.TERMS[i] + " days"),
                    b -> click(id)).bounds(leftPos + 48 + i * 42, topPos + 47, 40, 13).build());
        }
        signButton = addRenderableWidget(Button.builder(Component.literal("Sign"), b -> click(BasicExchangeMenu.BUTTON_FWD_SIGN))
                .bounds(leftPos + 8, topPos + 52, 34, 14).build());
        for (int i = 0; i < deliverButtons.length; i++) {
            int id = BasicExchangeMenu.BUTTON_FWD_DELIVER_BASE + i;
            deliverButtons[i] = addRenderableWidget(Button.builder(Component.literal("Deliver"), b -> click(id))
                    .bounds(leftPos + 128, topPos + 88 + i * 13, 44, 12).build());
        }
        sellButton = addRenderableWidget(Button.builder(Component.translatable("gui.realisticmarkets.sell"),
                b -> click(BasicExchangeMenu.BUTTON_SELL)).bounds(leftPos + 50, topPos + 28, 50, 20).build());
        for (int q = 0; q < buyButtons.length; q++) {
            int id = BasicExchangeMenu.BUTTON_BUY_FIRST + q;
            buyButtons[q] = addRenderableWidget(Button.builder(
                            Component.literal("x" + BasicExchangeMenu.BUY_QUANTITIES[q]), b -> click(id))
                    .bounds(leftPos + DETAIL_X + q * 24, topPos + 78, 22, 13).build());
        }
        updateWidgets();
    }

    private void click(int buttonId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(getMenu().containerId, buttonId);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateWidgets();
    }

    private void updateWidgets() {
        BasicExchangeMenu m = getMenu();
        boolean selling = m.tab() == BasicExchangeMenu.TAB_SELL;
        boolean forward = m.tab() == BasicExchangeMenu.TAB_FORWARD;
        if (sellTab == null) return;
        sellTab.active = !selling;
        buyTab.active = m.tab() != BasicExchangeMenu.TAB_BUY;
        forwardTab.visible = m.forwardsUnlocked();
        forwardTab.active = !forward;
        for (int i = 0; i < qtyButtons.length; i++) qtyButtons[i].visible = forward;
        for (int i = 0; i < termButtons.length; i++) {
            termButtons[i].visible = forward;
            termButtons[i].active = m.forwardTermIndex() != i;
        }
        signButton.visible = forward;
        signButton.active = m.forwardStatus() == BasicExchangeMenu.FWD_OK && m.forwardDepositCents() <= m.cashCents();
        for (int i = 0; i < deliverButtons.length; i++) {
            long due = i < m.openForwards() ? m.openForwardDay(i) : Long.MAX_VALUE;
            deliverButtons[i].visible = forward && m.today() >= due && m.today() <= due + 1;
        }
        sellButton.visible = selling;
        sellButton.active = m.status() == BasicExchangeMenu.STATUS_OK;
        boolean hasSelection = m.selected() >= 0 && m.selected() < m.itemCount();
        for (int q = 0; q < buyButtons.length; q++) {
            long total = m.buyTotalCents(q);
            buyButtons[q].visible = m.tab() == BasicExchangeMenu.TAB_BUY && hasSelection;
            buyButtons[q].active = total > 0 && total <= m.cashCents();
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    /** Group headers and item rows, in catalog order. Recomputed each use; it's tiny. */
    private List<Line> layout() {
        BasicExchangeMenu m = getMenu();
        List<Line> lines = new ArrayList<>();
        List<Integer> row = new ArrayList<>();
        int group = -1;
        for (int i = 0; i < m.itemCount(); i++) {
            int g = m.buyGroup(i);
            if (g != group || row.size() == COLS) {
                if (!row.isEmpty()) lines.add(new Line(group, row.stream().mapToInt(Integer::intValue).toArray()));
                row.clear();
            }
            if (g != group) {
                lines.add(new Line(g, null));
                group = g;
            }
            row.add(i);
        }
        if (!row.isEmpty()) lines.add(new Line(group, row.stream().mapToInt(Integer::intValue).toArray()));
        return lines;
    }

    /** Largest first-line index that still fills the list window to the bottom. */
    private int maxScroll() {
        List<Line> lines = layout();
        int h = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            h += lines.get(i).height();
            if (h > LIST_H - 2) return i + 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (getMenu().tab() == BasicExchangeMenu.TAB_BUY) {
            int item = itemAt(event.x(), event.y());
            if (item >= 0) {
                click(BasicExchangeMenu.BUTTON_SELECT_BASE + item);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (getMenu().tab() == BasicExchangeMenu.TAB_BUY && inList(mouseX, mouseY)) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean inList(double mouseX, double mouseY) {
        double x = mouseX - leftPos, y = mouseY - topPos;
        return x >= LIST_X && x < LIST_X + LIST_W && y >= LIST_Y && y < LIST_Y + LIST_H;
    }

    /** Buy-list item index under the mouse, or -1. Mouse coordinates are screen-absolute. */
    private int itemAt(double mouseX, double mouseY) {
        if (!inList(mouseX, mouseY)) return -1;
        double x = mouseX - leftPos - LIST_X - 1, y = mouseY - topPos;
        int ly = LIST_Y + 1;
        List<Line> lines = layout();
        for (int i = scroll; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (ly + line.height() > LIST_Y + LIST_H) break;
            if (!line.header() && y >= ly && y < ly + CELL && x >= 0) {
                int col = (int) (x / CELL);
                if (col < line.items().length) return line.items()[col];
            }
            ly += line.height();
        }
        return -1;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256);
        int x = leftPos, y = topPos;
        if (getMenu().tab() == BasicExchangeMenu.TAB_FORWARD) {
            slotFrame(g, x + BasicExchangeMenu.INPUT_X, y + BasicExchangeMenu.INPUT_Y);
        } else if (getMenu().tab() == BasicExchangeMenu.TAB_SELL) {
            slotFrame(g, x + BasicExchangeMenu.INPUT_X, y + BasicExchangeMenu.INPUT_Y);
            for (int i = 0; i < BasicExchangeMenu.OUTPUT_COUNT; i++) {
                slotFrame(g, x + BasicExchangeMenu.OUTPUT_X + (i % 2) * 18, y + BasicExchangeMenu.OUTPUT_Y + (i / 2) * 18);
            }
        } else {
            // list well
            g.fill(x + LIST_X - 1, y + LIST_Y - 1, x + LIST_X + LIST_W + 1, y + LIST_Y + LIST_H + 1, 0xFF373737);
            g.fill(x + LIST_X, y + LIST_Y, x + LIST_X + LIST_W, y + LIST_Y + LIST_H, 0xFF8B8B8B);
            int sel = getMenu().selected();
            int hover = itemAt(mouseX, mouseY);
            List<Line> lines = layout();
            int ly = y + LIST_Y + 1;
            for (int i = scroll; i < lines.size(); i++) {
                Line line = lines.get(i);
                if (ly + line.height() > y + LIST_Y + LIST_H) break;
                if (!line.header()) {
                    for (int c = 0; c < line.items().length; c++) {
                        int idx = line.items()[c];
                        int cx = x + LIST_X + 1 + c * CELL;
                        int color = idx == sel ? 0xFFC6D7F0 : idx == hover ? 0xFFB8B8B8 : 0xFF9E9E9E;
                        g.fill(cx + 1, ly + 1, cx + CELL - 1, ly + CELL - 1, color);
                    }
                }
                ly += line.height();
            }
            // scrollbar
            int max = maxScroll();
            int track = LIST_H - 2;
            int thumbH = max == 0 ? track : Math.max(10, track * track / Math.max(track, totalHeight(lines)));
            int thumbY = max == 0 ? 0 : (track - thumbH) * scroll / max;
            int bx = x + LIST_X + LIST_W - 4;
            g.fill(bx, y + LIST_Y + 1 + thumbY, bx + 3, y + LIST_Y + 1 + thumbY + thumbH, 0xFF555555);
        }
    }

    private static int totalHeight(List<Line> lines) {
        int h = 0;
        for (Line l : lines) h += l.height();
        return h;
    }

    private static void slotFrame(GuiGraphicsExtractor g, int itemX, int itemY) {
        int x = itemX - 1, y = itemY - 1;
        g.fill(x, y, x + 18, y + 18, 0xFFFFFFFF);
        g.fill(x, y, x + 17, y + 17, 0xFF373737);
        g.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        BasicExchangeMenu m = getMenu();
        String cash = "Cash " + Money.format(m.cashCents());
        g.text(font, cash, imageWidth - 8 - font.width(cash), inventoryLabelY, GREY, false);
        if (m.tab() == BasicExchangeMenu.TAB_SELL) drawSellTab(g, m);
        else if (m.tab() == BasicExchangeMenu.TAB_FORWARD) drawForwardTab(g, m);
        else drawBuyTab(g, m);
    }

    private void drawSellTab(GuiGraphicsExtractor g, BasicExchangeMenu m) {
        String status;
        int color;
        switch (m.status()) {
            case BasicExchangeMenu.STATUS_OK -> {
                status = "Dealer pays " + Money.format(m.quoteCents());
                color = GREEN;
            }
            case BasicExchangeMenu.STATUS_NOT_TRADED -> {
                status = "Dealer doesn't buy this";
                color = RED;
            }
            case BasicExchangeMenu.STATUS_COLLAPSED -> {
                status = "Price collapsed; wait";
                color = RED;
            }
            case BasicExchangeMenu.STATUS_MONEY -> {
                status = "That's already money";
                color = RED;
            }
            default -> {
                status = "Place items to sell";
                color = GREY;
            }
        }
        g.text(font, status, 8, 60, color, false);
        if (m.status() == BasicExchangeMenu.STATUS_OK || m.status() == BasicExchangeMenu.STATUS_COLLAPSED) {
            g.text(font, "Per item", 8, 74, LIGHT_GREY, false);
            priceRow(g, "Normal", m.sellNormalMills(), 86, 8, 108, GREY);
            priceRow(g, "Market", m.sellMarketMills(), 98, 8, 108, GREY);
            priceRow(g, "Pays", m.sellPaysMills(), 110, 8, 108, GREEN);
        }
    }

    private void drawForwardTab(GuiGraphicsExtractor g, BasicExchangeMenu m) {
        g.text(font, "Deliver " + m.forwardQuantity() + " in", 48, 21, GREY, false);
        String price;
        int color;
        switch (m.forwardStatus()) {
            case BasicExchangeMenu.FWD_OK -> {
                price = "Pays " + Money.format(m.forwardPriceCents()) + " on day " + (m.today() + m.forwardTerm());
                color = GREEN;
            }
            case BasicExchangeMenu.FWD_NOT_TRADED -> {
                price = "No forwards on this";
                color = RED;
            }
            case BasicExchangeMenu.FWD_REFUSED -> {
                price = "The Dealer won't agree a price";
                color = RED;
            }
            default -> {
                price = "Place goods to pick what you'll deliver";
                color = LIGHT_GREY;
            }
        }
        g.text(font, trim(price, 164), 8, 64, color, false);
        if (m.forwardStatus() == BasicExchangeMenu.FWD_OK) {
            g.text(font, "Deposit " + Money.format(m.forwardDepositCents()) + " + 1 Security Paper", 8, 74, LIGHT_GREY, false);
        }
        if (m.openForwards() == 0) {
            g.text(font, "No open forwards", 8, 90, LIGHT_GREY, false);
            return;
        }
        for (int i = 0; i < m.openForwards(); i++) {
            int y = 90 + i * 13;
            long due = m.openForwardDay(i), in = due - m.today();
            ItemStack stack = new ItemStack(m.openForwardItem(i));
            String line = m.openForwardQuantity(i) + " " + stack.getHoverName().getString() + " " + Money.format(m.openForwardPrice(i));
            g.text(font, trim(line, 118), 8, y, GREY, false);
            if (in > 0) {
                String when = "in " + in + "d";
                g.text(font, when, 172 - font.width(when), y, LIGHT_GREY, false);
            } else if (in < -1) {
                g.text(font, "late", 150, y, RED, false);
            }
        }
    }

    private void drawBuyTab(GuiGraphicsExtractor g, BasicExchangeMenu m) {
        int count = m.itemCount();
        List<Line> lines = layout();
        int ly = LIST_Y + 1;
        for (int i = scroll; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (ly + line.height() > LIST_Y + LIST_H) break;
            if (line.header()) {
                String label = GROUP_LABELS[Math.min(line.group(), GROUP_LABELS.length - 1)];
                g.text(font, label, LIST_X + 3, ly + 1, 0xFF2A2A2A, false);
            } else {
                for (int c = 0; c < line.items().length; c++) {
                    Item item = m.buyItem(line.items()[c]);
                    if (item != Items.AIR) g.item(new ItemStack(item), LIST_X + 2 + c * CELL, ly + 1);
                }
            }
            ly += line.height();
        }
        if (count == 0) g.text(font, "Nothing for sale", LIST_X + 4, LIST_Y + 4, GREY, false);

        int sel = m.selected();
        if (sel < 0 || sel >= count) {
            g.text(font, "Pick an item", DETAIL_X, 22, LIGHT_GREY, false);
            return;
        }
        ItemStack stack = new ItemStack(m.buyItem(sel));
        g.item(stack, DETAIL_X, 18);
        g.text(font, trim(stack.getHoverName().getString(), DETAIL_RIGHT - DETAIL_X - 20), DETAIL_X + 19, 22, GREY, false);
        priceRow(g, "Normal", m.buyNormalMills(sel), 38, DETAIL_X, DETAIL_RIGHT, GREY);
        priceRow(g, "Market", m.buyMarketMills(sel), 50, DETAIL_X, DETAIL_RIGHT, GREY);
        priceRow(g, "Sells", m.buySellsMills(sel), 62, DETAIL_X, DETAIL_RIGHT, BLUE);
        for (int q = 0; q < BasicExchangeMenu.BUY_QUANTITIES.length; q++) {
            long total = m.buyTotalCents(q);
            String label = BasicExchangeMenu.BUY_QUANTITIES[q] + ":";
            String value = total > 0 ? Money.format(total) : "n/a";
            int y = 96 + q * 10;
            g.text(font, label, DETAIL_X, y, LIGHT_GREY, false);
            g.text(font, value, DETAIL_RIGHT - font.width(value), y,
                    total > 0 && total <= m.cashCents() ? GREY : RED, false);
        }
    }

    private void priceRow(GuiGraphicsExtractor g, String label, long mills, int y, int left, int right, int valueColor) {
        String value = formatMills(mills);
        g.text(font, label, left, y, LIGHT_GREY, false);
        g.text(font, value, right - font.width(value), y, valueColor, false);
    }

    private String trim(String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        while (!s.isEmpty() && font.width(s + "..") > maxWidth) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    /** Mills (thousandths of a dollar): whole cents print as money, sub-cent prices get a third decimal. */
    static String formatMills(long mills) {
        if (mills % 10 == 0) return Money.format(mills / 10);
        return String.format(Locale.ROOT, "$%.3f", mills / 1000.0);
    }
}
