package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.BLUE;
import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;

import com.realisticmarkets.mod.menu.TradingFloorMenu;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class TradingFloorScreen extends AbstractContainerScreen<TradingFloorMenu> {
    private static final int BOOK_X = 8, BOOK_Y = 18, COLS = 7, INFO_X = 140;
    private Button side, market;
    private final List<Button> priceButtons = new ArrayList<>();
    private final Button[] cancel = new Button[TradingFloorMenu.MAX_SHOWN_ORDERS];
    private final Button[] reprice = new Button[TradingFloorMenu.MAX_SHOWN_ORDERS];

    public TradingFloorScreen(TradingFloorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TradingFloorMenu.WIDTH, TradingFloorMenu.HEIGHT);
        inventoryLabelY = TradingFloorMenu.INVENTORY_Y - 11;
        inventoryLabelX = TradingFloorMenu.INVENTORY_X;
    }

    private Button button(String label, int id, int x, int y, int w) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> click(id))
                .bounds(leftPos + x, topPos + y, w, 14).build());
    }

    @Override
    protected void init() {
        super.init();
        priceButtons.clear();
        side = button("Buy", TradingFloorMenu.BUTTON_SIDE, 8, 56, 36);
        market = button("Limit", TradingFloorMenu.BUTTON_MARKET, 46, 56, 44);
        button("-16", TradingFloorMenu.BUTTON_QTY_MINUS_16, 8, 72, 22);
        button("-1", TradingFloorMenu.BUTTON_QTY_MINUS_1, 32, 72, 18);
        button("+1", TradingFloorMenu.BUTTON_QTY_PLUS_1, 52, 72, 18);
        button("+16", TradingFloorMenu.BUTTON_QTY_PLUS_16, 72, 72, 22);
        priceButtons.add(button("-10%", TradingFloorMenu.BUTTON_PRICE_MINUS_10PCT, 8, 88, 26));
        priceButtons.add(button("-1c", TradingFloorMenu.BUTTON_PRICE_MINUS_1, 36, 88, 20));
        priceButtons.add(button("+1c", TradingFloorMenu.BUTTON_PRICE_PLUS_1, 58, 88, 20));
        priceButtons.add(button("+10%", TradingFloorMenu.BUTTON_PRICE_PLUS_10PCT, 80, 88, 26));
        button("Place order", TradingFloorMenu.BUTTON_PLACE, 8, 104, 70);
        for (int i = 0; i < cancel.length; i++) {
            reprice[i] = button("=", TradingFloorMenu.BUTTON_REPRICE_BASE + i, 205, 104 + i * 11, 12);
            reprice[i].setTooltip(Tooltip.create(Component.literal("Move this order to the price you've set (no new slip)")));
            cancel[i] = button("x", TradingFloorMenu.BUTTON_CANCEL_BASE + i, 218, 104 + i * 11, 12);
            cancel[i].setTooltip(Tooltip.create(Component.literal("Cancel: goods or cash come back")));
        }
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
        TradingFloorMenu m = getMenu();
        side.setMessage(Component.literal(m.selling() ? "Sell" : "Buy"));
        market.setMessage(Component.literal(m.market() ? "Market" : "Limit"));
        for (Button b : priceButtons) b.visible = !m.market();
        for (int i = 0; i < cancel.length; i++) {
            cancel[i].visible = i < m.orderCount();
            reprice[i].visible = i < m.orderCount() && !m.market();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x() - leftPos - BOOK_X, y = event.y() - topPos - BOOK_Y;
        if (x >= 0 && y >= 0 && x < COLS * 18 && y < 2 * 18) {
            int i = (int) (y / 18) * COLS + (int) (x / 18);
            if (i < TradingFloorMenu.BOOKS.size()) {
                click(TradingFloorMenu.BUTTON_BOOK_BASE + i);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private static ItemStack icon(int book) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(TradingFloorMenu.BOOKS.get(book).item())));
    }

    static String cents(long c) {
        return BasicExchangeScreen.formatMills(c * 10);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int x = leftPos, y = topPos;
        Panels.panel(g, x, y, imageWidth, imageHeight);
        Panels.well(g, x + BOOK_X, y + BOOK_Y, COLS * 18, 2 * 18);
        int sel = getMenu().book();
        g.fill(x + BOOK_X + (sel % COLS) * 18, y + BOOK_Y + (sel / COLS) * 18, x + BOOK_X + (sel % COLS) * 18 + 18,
                y + BOOK_Y + (sel / COLS) * 18 + 18, 0xFFC6D7F0);
        for (int i = 0; i < TradingFloorMenu.OUTPUT_SLOTS; i++) {
            Panels.slot(g, x + TradingFloorMenu.OUTPUT_X + i * 18, y + TradingFloorMenu.OUTPUT_Y);
        }
        Panels.inventory(g, x, y, TradingFloorMenu.INVENTORY_X, TradingFloorMenu.INVENTORY_Y);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        TradingFloorMenu m = getMenu();
        for (int i = 0; i < TradingFloorMenu.BOOKS.size(); i++) g.item(icon(i), BOOK_X + (i % COLS) * 18 + 1, BOOK_Y + (i / COLS) * 18 + 1);
        String name = icon(m.book()).getHoverName().getString();
        g.text(font, Panels.trim(font, name, imageWidth - INFO_X - 6), INFO_X, 18, BLUE, false);
        g.text(font, "Bid " + cents(m.bidCents()), INFO_X, 27, GREY, false);
        g.text(font, "Ask " + cents(m.askCents()), INFO_X, 36, GREY, false);
        int infoW = imageWidth - INFO_X - 6;
        g.text(font, Panels.trim(font, m.lastCents() > 0 ? "Last trade " + cents(m.lastCents()) : "No trades yet", infoW),
                INFO_X, 47, LIGHT_GREY, false);
        if (m.todayAvg() > 0) {
            g.text(font, Panels.trim(font, "Today avg " + cents(m.todayAvg()), infoW), INFO_X, 58, GREY, false);
            g.text(font, Panels.trim(font, "(" + cents(m.todayLow()) + "-" + cents(m.todayHigh()) + ")", infoW), INFO_X, 67,
                    LIGHT_GREY, false);
        } else {
            g.text(font, Panels.trim(font, "Nothing traded today", infoW), INFO_X, 58, LIGHT_GREY, false);
        }
        g.text(font, "Qty " + m.qty(), 98, 75, GREY, false);
        g.text(font, m.market() ? "At market" : cents(m.priceCents()), 110, 91, m.market() ? LIGHT_GREY : GREY, false);
        String auction = "Auction in " + m.secondsToAuction() + "s";
        g.text(font, auction, imageWidth - 8 - font.width(auction), 6, LIGHT_GREY, false);
        if (m.orderCount() == 0) {
            g.text(font, "No open orders", 84, 107, LIGHT_GREY, false);
        }
        for (int i = 0; i < m.orderCount(); i++) {
            String line = (m.orderSelling(i) ? "S " : "B ") + m.orderFilled(i) + "/" + m.orderQty(i) + " @" + cents(m.orderPrice(i));
            g.item(icon(m.orderBook(i)), 80, 102 + i * 11);
            g.text(font, Panels.trim(font, line, 106), 96, 107 + i * 11, GREEN, false);
        }
    }
}
