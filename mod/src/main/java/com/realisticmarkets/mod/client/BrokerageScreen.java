package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.BLUE;
import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;

import com.realisticmarkets.mod.menu.BrokerageMenu;
import com.realisticmarkets.money.Money;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class BrokerageScreen extends AbstractContainerScreen<BrokerageMenu> {
    private final Button[] rows = new Button[BrokerageMenu.ROWS];
    private final Button[] markets = new Button[BrokerageMenu.MARKETS.length];
    private Button holdTab, cashTab, marketTab, deposit, withdraw, cashOut;

    public BrokerageScreen(BrokerageMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BrokerageMenu.WIDTH, BrokerageMenu.HEIGHT);
        inventoryLabelY = -10_000;
    }

    private Button button(String label, int id, int x, int y, int w, int h) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> click(id)).bounds(leftPos + x, topPos + y, w, h).build());
    }

    @Override
    protected void init() {
        super.init();
        holdTab = button("Holdings", BrokerageMenu.TAB_HOLDINGS, 118, 3, 50, 13);
        cashTab = button("Cash", BrokerageMenu.TAB_CASH, 170, 3, 34, 13);
        marketTab = button("Markets", BrokerageMenu.TAB_MARKETS, 206, 3, 44, 13);
        for (int i = 0; i < rows.length; i++) rows[i] = button("", BrokerageMenu.BUTTON_ROW_BASE + i, 8, 20 + i * 18, 240, 17);
        deposit = button("Deposit all papers", BrokerageMenu.BUTTON_DEPOSIT, 8, 186, 118, 14);
        deposit.setTooltip(Tooltip.create(Component.literal("Every share, bond and option paper you carry (binders too) into book entry")));
        withdraw = button("Withdraw as papers", BrokerageMenu.BUTTON_WITHDRAW, 130, 186, 118, 14);
        cashOut = button("Withdraw cash", BrokerageMenu.BUTTON_CASH_OUT, 8, 70, 118, 14);
        for (int i = 0; i < markets.length; i++) markets[i] = button(BrokerageMenu.MARKETS[i], BrokerageMenu.BUTTON_MARKET_BASE + i, 48, 30 + i * 22, 160, 18);
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
        if (deposit == null) return;
        BrokerageMenu m = getMenu();
        boolean on = m.owner(), holding = on && m.tab() == BrokerageMenu.TAB_HOLDINGS;
        holdTab.visible = cashTab.visible = marketTab.visible = on;
        holdTab.active = m.tab() != BrokerageMenu.TAB_HOLDINGS;
        cashTab.active = m.tab() != BrokerageMenu.TAB_CASH;
        marketTab.active = m.tab() != BrokerageMenu.TAB_MARKETS;
        for (int i = 0; i < rows.length; i++) {
            rows[i].visible = holding && i < m.rowCount();
            rows[i].active = i != m.selected();
            rows[i].setAlpha(0.25f);
        }
        deposit.visible = holding;
        withdraw.visible = holding;
        withdraw.active = m.selected() >= 0;
        cashOut.visible = on && m.tab() == BrokerageMenu.TAB_CASH;
        for (Button b : markets) b.visible = on && m.tab() == BrokerageMenu.TAB_MARKETS;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        BrokerageMenu m = getMenu();
        if (!m.owner()) {
            g.text(font, "Unlock the Brokerage Terminal at the Almanac.", 8, 30, GREY, false);
            return;
        }
        switch (m.tab()) {
            case BrokerageMenu.TAB_CASH -> {
                g.text(font, "Cash account " + Money.format(m.cash()), 8, 30, BLUE, false);
                g.text(font, "Dividends, coupons, maturities and option", 8, 44, LIGHT_GREY, false);
                g.text(font, "settlements on book entries land here at dawn.", 8, 54, LIGHT_GREY, false);
            }
            case BrokerageMenu.TAB_MARKETS -> g.text(font, "Trade anywhere from here. Papers you buy arrive as papers.", 8, 20, LIGHT_GREY, false);
            default -> holdings(g, m);
        }
    }

    private void holdings(GuiGraphicsExtractor g, BrokerageMenu m) {
        if (m.rowCount() == 0) {
            g.text(font, "No book entries yet. Deposit papers to hold them here.", 8, 26, LIGHT_GREY, false);
        }
        for (int i = 0; i < m.rowCount(); i++) {
            int y = 20 + i * 18;
            ItemStack icon = m.rowIcon(i);
            if (!icon.isEmpty()) g.item(icon, 10, y);
            String name = icon.isEmpty() ? "?" : icon.getHoverName().getString().replaceAll(", 1 share$", "");
            g.text(font, Panels.trim(font, m.rowQuantity(i) + " x " + name, 150), 30, y + 5, i == m.selected() ? BLUE : GREY, false);
            String v = Money.format(m.rowValue(i));
            g.text(font, v, 244 - font.width(v), y + 5, GREEN, false);
        }
        String t = "Total with cash " + Money.format(m.total());
        g.text(font, t, 248 - font.width(t), 175, BLUE, false);
    }
}
