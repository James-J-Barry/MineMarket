package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.BLUE;
import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;
import static com.realisticmarkets.mod.client.Panels.RED;

import com.realisticmarkets.equities.Company;
import com.realisticmarkets.equities.CompanyCatalog;
import com.realisticmarkets.mod.menu.StockExchangeMenu;
import com.realisticmarkets.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class StockExchangeScreen extends AbstractContainerScreen<StockExchangeMenu> {
    private static final CompanyCatalog COMPANIES = CompanyCatalog.loadDefault();
    private static final int INFO_X = 140;
    private final List<Button> tradeButtons = new ArrayList<>(), priceButtons = new ArrayList<>(), companyButtons = new ArrayList<>();
    private final List<Button> books = new ArrayList<>();
    private final Button[] cancel = new Button[StockExchangeMenu.MAX_SHOWN_ORDERS];
    private final Button[] reprice = new Button[StockExchangeMenu.MAX_SHOWN_ORDERS];
    private Button side, market, tradeTab, companyTab;

    public StockExchangeScreen(StockExchangeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, StockExchangeMenu.WIDTH, StockExchangeMenu.HEIGHT);
        inventoryLabelY = StockExchangeMenu.INVENTORY_Y - 11;
        inventoryLabelX = StockExchangeMenu.INVENTORY_X;
    }

    private Button button(String label, int id, int x, int y, int w) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> click(id)).bounds(leftPos + x, topPos + y, w, 14).build());
    }

    @Override
    protected void init() {
        super.init();
        tradeButtons.clear();
        priceButtons.clear();
        companyButtons.clear();
        books.clear();
        tradeTab = button("Trade", StockExchangeMenu.BUTTON_TAB_TRADE, 120, 3, 50);
        companyTab = button("Company", StockExchangeMenu.BUTTON_TAB_COMPANY, 172, 3, 56);
        for (int i = 0; i < StockExchangeMenu.BOOKS.size(); i++) {
            books.add(button(StockExchangeMenu.BOOKS.get(i).item(), StockExchangeMenu.BUTTON_BOOK_BASE + i, 8 + i * 37, 18, 35));
        }
        side = button("Buy", StockExchangeMenu.BUTTON_SIDE, 8, 36, 36);
        market = button("Limit", StockExchangeMenu.BUTTON_MARKET, 46, 36, 44);
        tradeButtons.add(side);
        tradeButtons.add(market);
        tradeButtons.add(button("-10", StockExchangeMenu.BUTTON_QTY_MINUS_10, 8, 52, 22));
        tradeButtons.add(button("-1", StockExchangeMenu.BUTTON_QTY_MINUS_1, 32, 52, 18));
        tradeButtons.add(button("+1", StockExchangeMenu.BUTTON_QTY_PLUS_1, 52, 52, 18));
        tradeButtons.add(button("+10", StockExchangeMenu.BUTTON_QTY_PLUS_10, 72, 52, 22));
        priceButtons.add(button("-10%", StockExchangeMenu.BUTTON_PRICE_MINUS_10PCT, 8, 68, 26));
        priceButtons.add(button("-1c", StockExchangeMenu.BUTTON_PRICE_MINUS_1, 36, 68, 20));
        priceButtons.add(button("+1c", StockExchangeMenu.BUTTON_PRICE_PLUS_1, 58, 68, 20));
        priceButtons.add(button("+10%", StockExchangeMenu.BUTTON_PRICE_PLUS_10PCT, 80, 68, 26));
        tradeButtons.add(button("Place order", StockExchangeMenu.BUTTON_PLACE, 8, 84, 70));
        for (int i = 0; i < cancel.length; i++) {
            reprice[i] = button("=", StockExchangeMenu.BUTTON_REPRICE_BASE + i, 205, 84 + i * 11, 12);
            reprice[i].setTooltip(Tooltip.create(Component.literal("Move this order to the price you've set (no new slip)")));
            cancel[i] = button("x", StockExchangeMenu.BUTTON_CANCEL_BASE + i, 218, 84 + i * 11, 12);
            cancel[i].setTooltip(Tooltip.create(Component.literal("Cancel: certificates or cash come back")));
        }
        Button collect = button("Collect dividends", StockExchangeMenu.BUTTON_COLLECT, 8, 121, 96);
        collect.setTooltip(Tooltip.create(Component.literal("Present every certificate you carry and get the dividends owed on them")));
        Button merge = button("Merge", StockExchangeMenu.BUTTON_MERGE, 106, 121, 44);
        merge.setTooltip(Tooltip.create(Component.literal("Swap your certificates for the fewest: 100s, 10s and 1s")));
        Button split = button("Split", StockExchangeMenu.BUTTON_SPLIT, 152, 121, 40);
        split.setTooltip(Tooltip.create(Component.literal("Break one of this company's largest certificates into ten smaller ones")));
        companyButtons.add(collect);
        companyButtons.add(merge);
        companyButtons.add(split);
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
        if (side == null) return;
        StockExchangeMenu m = getMenu();
        boolean trade = m.tab() == StockExchangeMenu.TAB_TRADE;
        tradeTab.active = !trade;
        companyTab.active = trade;
        for (int i = 0; i < books.size(); i++) books.get(i).active = i != m.book();
        side.setMessage(Component.literal(m.selling() ? "Sell" : "Buy"));
        market.setMessage(Component.literal(m.market() ? "Market" : "Limit"));
        for (Button b : tradeButtons) b.visible = trade;
        for (Button b : priceButtons) b.visible = trade && !m.market();
        for (Button b : companyButtons) b.visible = !trade;
        for (int i = 0; i < cancel.length; i++) {
            cancel[i].visible = trade && i < m.orderCount();
            reprice[i].visible = trade && i < m.orderCount() && !m.market();
        }
    }

    static String cents(long c) {
        return BasicExchangeScreen.formatMills(c * 10);
    }

    /** Whole dollars, shortened: $260k, $1.2m. */
    static String big(long dollars) {
        long a = Math.abs(dollars);
        String s = a >= 1_000_000 ? String.format(Locale.ROOT, "$%.1fm", a / 1e6)
                : a >= 10_000 ? String.format(Locale.ROOT, "$%dk", Math.round(a / 1000.0)) : Money.format(a * 100);
        return dollars < 0 ? "-" + s : s;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
        if (getMenu().tab() == StockExchangeMenu.TAB_TRADE) {
            for (int i = 0; i < StockExchangeMenu.OUTPUT_SLOTS; i++) {
                Panels.slot(g, leftPos + StockExchangeMenu.OUTPUT_X + i * 18, topPos + StockExchangeMenu.OUTPUT_Y);
            }
        }
        Panels.inventory(g, leftPos, topPos, StockExchangeMenu.INVENTORY_X, StockExchangeMenu.INVENTORY_Y);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        StockExchangeMenu m = getMenu();
        if (m.tab() == StockExchangeMenu.TAB_TRADE) trade(g, m);
        else company(g, m);
    }

    private void trade(GuiGraphicsExtractor g, StockExchangeMenu m) {
        int infoW = imageWidth - INFO_X - 6;
        g.text(font, Panels.trim(font, COMPANIES.company(m.ticker()).name(), infoW), INFO_X, 36, BLUE, false);
        g.text(font, "Bid " + cents(m.bidCents()) + "  Ask " + cents(m.askCents()), INFO_X, 46, GREY, false);
        g.text(font, Panels.trim(font, m.lastCents() > 0 ? "Last trade " + cents(m.lastCents()) : "No trades yet", infoW), INFO_X, 56,
                LIGHT_GREY, false);
        if (m.todayAvg() > 0) {
            g.text(font, Panels.trim(font, "Today avg " + cents(m.todayAvg()), infoW), INFO_X, 66, GREY, false);
        }
        g.text(font, "Shares " + m.qty(), 98, 55, GREY, false);
        g.text(font, m.market() ? "At market" : cents(m.priceCents()), 110, 71, m.market() ? LIGHT_GREY : GREY, false);
        String auction = "Auction in " + m.secondsToAuction() + "s";
        g.text(font, auction, INFO_X, 75, LIGHT_GREY, false);
        if (m.orderCount() == 0) g.text(font, "No open orders", 96, 87, LIGHT_GREY, false);
        for (int i = 0; i < m.orderCount(); i++) {
            String t = StockExchangeMenu.BOOKS.get(m.orderBook(i)).item();
            String line = (m.orderSelling(i) ? "S " : "B ") + t + " " + m.orderFilled(i) + "/" + m.orderQty(i) + " @" + cents(m.orderPrice(i));
            g.text(font, Panels.trim(font, line, 106), 96, 87 + i * 11, GREEN, false);
        }
        g.text(font, "You hold " + m.held() + " " + m.ticker(), 8, 104, LIGHT_GREY, false);
    }

    private void company(GuiGraphicsExtractor g, StockExchangeMenu m) {
        Company c = COMPANIES.company(m.ticker());
        g.text(font, c.name(), 8, 36, BLUE, false);
        long price = m.lastCents() > 0 ? m.lastCents() : m.bidCents();
        long eps4 = 0, div4 = 0;
        for (int i = 0; i < m.reportCount(); i++) {
            eps4 += m.reportEarningsDollars(i) * 100 / c.shares();
            div4 += m.reportDividendCents(i);
        }
        String pe = m.reportCount() < StockExchangeMenu.REPORT_QUARTERS || eps4 <= 0 ? "P/E n/a"
                : String.format(Locale.ROOT, "P/E %.1f", price / (double) eps4);
        String yield = m.reportCount() == 0 || price == 0 ? "" : String.format(Locale.ROOT, "  Yield %.1f%%", div4 * 100.0 / price);
        g.text(font, "Price " + cents(price) + "  " + pe + yield, 8, 46, GREY, false);
        if (m.reportCount() == 0) {
            g.text(font, "No quarterly report yet: the first comes", 8, 62, LIGHT_GREY, false);
            g.text(font, "at the end of this quarter (7 days).", 8, 72, LIGHT_GREY, false);
        } else {
            int[] col = {8, 44, 94, 144, 194};
            String[] head = {"Qtr", "Revenue", "Costs", "Earnings", "Div"};
            for (int k = 0; k < head.length; k++) g.text(font, head[k], col[k], 56, LIGHT_GREY, false);
            for (int i = 0; i < m.reportCount(); i++) {
                int y = 65 + i * 9;
                long e = m.reportEarningsDollars(i);
                g.text(font, String.valueOf(m.reportQuarter(i)), col[0], y, GREY, false);
                g.text(font, big(m.reportRevenueDollars(i)), col[1], y, GREY, false);
                g.text(font, big(m.reportCostsDollars(i)), col[2], y, GREY, false);
                g.text(font, big(e), col[3], y, e < 0 ? RED : GREEN, false);
                g.text(font, cents(m.reportDividendCents(i)), col[4], y, GREY, false);
            }
        }
        g.text(font, "Cash a share " + (m.cashPerShareCents() < 0 ? "-" : "") + cents(Math.abs(m.cashPerShareCents())), 8, 101,
                LIGHT_GREY, false);
        String held = "You hold " + m.held() + ", dividends waiting " + cents(m.dividendsWaiting());
        g.text(font, Panels.trim(font, held, imageWidth - 16), 8, 110, GREY, false);
    }
}
