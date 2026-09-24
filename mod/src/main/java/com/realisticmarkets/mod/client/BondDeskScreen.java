package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.BLUE;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;
import static com.realisticmarkets.mod.client.Panels.RED;

import com.realisticmarkets.bonds.Bond;
import com.realisticmarkets.mod.bonds.BondPapers;
import com.realisticmarkets.mod.menu.BondDeskMenu;
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

public class BondDeskScreen extends AbstractContainerScreen<BondDeskMenu> {
    private final List<Button> buyButtons = new ArrayList<>(), issuers = new ArrayList<>(), maturities = new ArrayList<>();
    private final Button[] sell = new Button[BondDeskMenu.MAX_HOLDINGS];
    private Button buyTab, holdingsTab, collect;

    public BondDeskScreen(BondDeskMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BondDeskMenu.WIDTH, BondDeskMenu.HEIGHT);
        inventoryLabelY = BondDeskMenu.INVENTORY_Y - 11;
        inventoryLabelX = BondDeskMenu.INVENTORY_X;
    }

    private Button button(String label, int id, int x, int y, int w) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> click(id)).bounds(leftPos + x, topPos + y, w, 14).build());
    }

    @Override
    protected void init() {
        super.init();
        buyButtons.clear();
        issuers.clear();
        maturities.clear();
        buyTab = button("Buy", BondDeskMenu.BUTTON_TAB_BUY, 128, 3, 40);
        holdingsTab = button("Holdings", BondDeskMenu.BUTTON_TAB_HOLDINGS, 170, 3, 58);
        for (int i = 0; i < BondDeskMenu.ISSUERS.size(); i++) {
            String label = Bond.TREASURY.equals(BondDeskMenu.ISSUERS.get(i)) ? "TREAS" : BondDeskMenu.ISSUERS.get(i);
            Button b = button(label, BondDeskMenu.BUTTON_ISSUER_BASE + i, 8 + i * 31, 18, 30);
            issuers.add(b);
            buyButtons.add(b);
        }
        for (int m = 0; m < 3; m++) {
            Button b = button(Bond.MATURITY_QUARTERS[m] + " quarters", BondDeskMenu.BUTTON_MATURITY_BASE + m, 8 + m * 58, 36, 56);
            maturities.add(b);
            buyButtons.add(b);
        }
        buyButtons.add(button("-10", BondDeskMenu.BUTTON_COUNT_MINUS_10, 8, 54, 22));
        buyButtons.add(button("-1", BondDeskMenu.BUTTON_COUNT_MINUS_1, 32, 54, 18));
        buyButtons.add(button("+1", BondDeskMenu.BUTTON_COUNT_PLUS_1, 52, 54, 18));
        buyButtons.add(button("+10", BondDeskMenu.BUTTON_COUNT_PLUS_10, 72, 54, 22));
        buyButtons.add(button("Buy", BondDeskMenu.BUTTON_BUY, 8, 72, 50));
        for (int i = 0; i < sell.length; i++) {
            sell[i] = button("Sell", BondDeskMenu.BUTTON_SELL_BASE + i, 192, 38 + i * 14, 36);
            sell[i].setTooltip(Tooltip.create(Component.literal("Sell every paper of this bond you carry, at the desk's price")));
        }
        collect = button("Collect coupons and maturities", BondDeskMenu.BUTTON_COLLECT, 8, 18, 180);
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
        if (buyTab == null) return;
        BondDeskMenu m = getMenu();
        boolean buy = m.tab() == BondDeskMenu.TAB_BUY;
        buyTab.active = !buy;
        holdingsTab.active = buy;
        for (Button b : buyButtons) b.visible = buy;
        for (int i = 0; i < issuers.size(); i++) issuers.get(i).active = i != m.issuer();
        for (int i = 0; i < maturities.size(); i++) maturities.get(i).active = i != m.maturityIndex();
        for (int i = 0; i < sell.length; i++) sell[i].visible = !buy && i < m.holdingCount();
        collect.visible = !buy;
    }

    static String pct(int milliPct) {
        return String.format(Locale.ROOT, "%.3f%%", milliPct / 1000.0);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
        Panels.inventory(g, leftPos, topPos, BondDeskMenu.INVENTORY_X, BondDeskMenu.INVENTORY_Y);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        BondDeskMenu m = getMenu();
        if (m.tab() == BondDeskMenu.TAB_BUY) buy(g, m);
        else holdings(g, m);
    }

    private void buy(GuiGraphicsExtractor g, BondDeskMenu m) {
        long cost = Money.roundUpToDime((long) Math.ceil(Bond.FACE_CENTS * 1.0025) * m.count());
        g.text(font, m.count() + " bonds", 98, 57, GREY, false);
        g.text(font, "for " + Money.format(cost), 62, 75, GREY, false);
        String issuer = BondPapers.issuerName(BondDeskMenu.ISSUERS.get(m.issuer()));
        g.text(font, issuer + ", " + m.maturity() + " quarters: $100 each, coupon " + Money.format(m.newCoupon()) + " a quarter",
                8, 92, BLUE, false);
        // The yield curve: today's yield a day for every issuer and maturity.
        int[] col = {8, 80, 130, 180};
        g.text(font, "Yield a day", col[0], 106, LIGHT_GREY, false);
        for (int q = 0; q < 3; q++) g.text(font, Bond.MATURITY_QUARTERS[q] + " qtrs", col[q + 1], 106, LIGHT_GREY, false);
        for (int i = 0; i < BondDeskMenu.ISSUERS.size(); i++) {
            int y = 115 + i * 7;
            String name = BondPapers.issuerName(BondDeskMenu.ISSUERS.get(i));
            g.text(font, name, col[0], y, i == m.issuer() ? BLUE : GREY, false);
            for (int q = 0; q < 3; q++) g.text(font, pct(m.yieldMilliPct(i, q)), col[q + 1], y, GREY, false);
        }
    }

    private void holdings(GuiGraphicsExtractor g, BondDeskMenu m) {
        g.text(font, "Due now on the bonds you carry: " + Money.format(m.waiting()), 8, 118, m.waiting() > 0 ? BLUE : LIGHT_GREY, false);
        if (m.holdingCount() == 0) {
            g.text(font, "You carry no bonds.", 8, 42, LIGHT_GREY, false);
            return;
        }
        for (int i = 0; i < m.holdingCount(); i++) {
            int y = 42 + i * 14;
            String name = BondPapers.issuerName(BondDeskMenu.ISSUERS.get(m.holdingIssuer(i)));
            String line = m.holdingBonds(i) + " x " + name + " (day " + m.holdingMaturityDay(i) + ")";
            g.text(font, Panels.trim(font, line, 118), 8, y, GREY, false);
            String price = m.holdingDefaulted(i) ? "DEFAULTED " + Money.format(m.holdingBid(i)) : Money.format(m.holdingBid(i)) + " each";
            g.text(font, price, 190 - 4 - font.width(price), y, m.holdingDefaulted(i) ? RED : GREY, false);
        }
    }
}
