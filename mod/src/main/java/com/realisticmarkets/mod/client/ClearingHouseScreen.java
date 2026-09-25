package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.BLUE;
import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;
import static com.realisticmarkets.mod.client.Panels.RED;

import com.realisticmarkets.futures.ClearingHouse;
import com.realisticmarkets.mod.menu.ClearingHouseMenu;
import com.realisticmarkets.money.Money;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class ClearingHouseScreen extends AbstractContainerScreen<ClearingHouseMenu> {
    private final Button[] products = new Button[ClearingHouseMenu.N];
    private final Button[] expiries = new Button[ClearingHouseMenu.E];
    private final Button[] lotButtons = new Button[ClearingHouseMenu.LOT_STEPS.length];
    private Button buy, sell, deposit, withdraw;

    public ClearingHouseScreen(ClearingHouseMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, ClearingHouseMenu.WIDTH, ClearingHouseMenu.HEIGHT);
        inventoryLabelY = -10_000;
    }

    private Button button(String label, int id, int x, int y, int w) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> click(id)).bounds(leftPos + x, topPos + y, w, 13).build());
    }

    @Override
    protected void init() {
        super.init();
        for (int p = 0; p < products.length; p++) {
            ClearingHouse.Product pr = ClearingHouse.PRODUCTS.get(p);
            products[p] = button(pr.name(), ClearingHouseMenu.BUTTON_PRODUCT_BASE + p, 8, 34 + p * 14, 66);
            products[p].setTooltip(Tooltip.create(Component.literal(pr.lot() + " " + pr.name() + " a lot, settled in cash")));
        }
        for (int e = 0; e < expiries.length; e++) expiries[e] = button("", ClearingHouseMenu.BUTTON_EXPIRY_BASE + e, 96 + e * 76, 19, 60);
        for (int i = 0; i < lotButtons.length; i++) {
            int step = ClearingHouseMenu.LOT_STEPS[i];
            lotButtons[i] = button((step > 0 ? "+" : "") + step, ClearingHouseMenu.BUTTON_LOTS_BASE + i, 8 + i * 24 + (i >= 2 ? 44 : 0), 134, 22);
        }
        buy = button("Buy", ClearingHouseMenu.BUTTON_BUY, 164, 134, 40);
        sell = button("Sell", ClearingHouseMenu.BUTTON_SELL, 208, 134, 40);
        buy.setTooltip(Tooltip.create(Component.literal("Buy (go long): you gain if the price rises")));
        sell.setTooltip(Tooltip.create(Component.literal("Sell (go short): you gain if the price falls. Hedgers sell what they grow")));
        deposit = button("Deposit all cash", ClearingHouseMenu.BUTTON_DEPOSIT, 8, 193, 118);
        withdraw = button("Withdraw free", ClearingHouseMenu.BUTTON_WITHDRAW, 130, 193, 118);
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
        ClearingHouseMenu m = getMenu();
        boolean on = m.owner();
        for (int p = 0; p < products.length; p++) {
            products[p].visible = on;
            products[p].active = p != m.product();
        }
        for (int e = 0; e < expiries.length; e++) {
            expiries[e].visible = on;
            expiries[e].active = e != m.expiryIndex();
            expiries[e].setMessage(Component.literal("day " + m.expiry(e)));
        }
        for (Button b : lotButtons) b.visible = on;
        buy.visible = sell.visible = deposit.visible = withdraw.visible = on;
        deposit.active = m.cashOnHand() > 0;
        withdraw.active = m.free() >= 10;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
    }

    private void right(GuiGraphicsExtractor g, String s, int rightX, int y, int color) {
        g.text(font, s, rightX - font.width(s), y, color, false);
    }

    private static String signed(long cents) {
        return (cents < 0 ? "-" : "") + Money.format(Math.abs(cents));
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        ClearingHouseMenu m = getMenu();
        String day = "Day " + m.day();
        g.text(font, day, imageWidth - 8 - font.width(day), 6, LIGHT_GREY, false);
        if (!m.owner()) {
            g.text(font, "Unlock the Clearing House at the Almanac", 8, 30, GREY, false);
            g.text(font, "to trade futures here.", 8, 40, GREY, false);
            return;
        }
        g.text(font, "Price a lot", 8, 22, LIGHT_GREY, false);
        for (int p = 0; p < ClearingHouseMenu.N; p++) {
            int y = 37 + p * 14;
            for (int e = 0; e < ClearingHouseMenu.E; e++) {
                boolean sel = p == m.product() && e == m.expiryIndex();
                right(g, Money.format(m.price(p, e)), 150 + e * 76, y, sel ? BLUE : GREY);
                int pos = m.position(p, e);
                if (pos != 0) g.text(font, (pos > 0 ? "+" : "") + pos, 154 + e * 76, y, pos > 0 ? GREEN : RED, false);
            }
        }
        ClearingHouse.Product pr = ClearingHouse.PRODUCTS.get(m.product());
        g.text(font, pr.name() + " x" + pr.lot() + ", day " + m.expiry(m.expiryIndex()) + ": buy " + Money.format(m.buyPrice())
                + ", sell " + Money.format(m.sellPrice()) + " a lot", 8, 122, GREY, false);
        String lots = m.lots() + (m.lots() == 1 ? " lot" : " lots");
        g.text(font, lots, 58 + (44 - font.width(lots)) / 2, 137, GREY, false);

        // The margin account.
        g.fill(8, 152, 248, 153, 0xFF8B8B8B);
        g.text(font, "Futures account", 8, 156, LIGHT_GREY, false);
        right(g, "Cash " + signed(m.cash()), 140, 156, m.cash() < 0 ? RED : GREY);
        right(g, "Equity " + signed(m.equity()), 248, 156, m.equity() < m.maintenance() ? RED : BLUE);
        g.text(font, "Margin " + Money.format(m.initial()) + " (min " + Money.format(m.maintenance()) + ")", 8, 167, GREY, false);
        right(g, "Free " + Money.format(Math.max(0, m.free())), 248, 167, GREY);
        if (m.underCall()) {
            g.text(font, "MARGIN CALL: add " + Money.format(Math.max(0, m.initial() - m.equity())) + " by dawn", 8, 179, RED, false);
        } else if (m.cash() < 0) {
            g.text(font, "You owe the Clearing House " + Money.format(-m.cash()), 8, 179, RED, false);
        } else {
            g.text(font, "Marked to market every dawn", 8, 179, LIGHT_GREY, false);
        }
    }
}
