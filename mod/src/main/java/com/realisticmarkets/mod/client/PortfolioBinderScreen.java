package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.BLUE;
import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;

import com.realisticmarkets.mod.menu.PortfolioBinderMenu;
import com.realisticmarkets.mod.stocks.Securities;
import com.realisticmarkets.money.Money;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** The binder's 27 slots, then each kind of holding with its value and the total at live prices. */
public class PortfolioBinderScreen extends AbstractContainerScreen<PortfolioBinderMenu> {
    public PortfolioBinderScreen(PortfolioBinderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, PortfolioBinderMenu.WIDTH, PortfolioBinderMenu.HEIGHT);
        inventoryLabelY = PortfolioBinderMenu.INVENTORY_Y - 11;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) Panels.slot(g, leftPos + 8 + c * 18, topPos + PortfolioBinderMenu.BINDER_Y + r * 18);
        }
        Panels.inventory(g, leftPos, topPos, PortfolioBinderMenu.INVENTORY_Y);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        PortfolioBinderMenu m = getMenu();
        int y = 76, col2 = 70, col3 = imageWidth - 8;
        boolean any = false;
        for (int k = 0; k < PortfolioBinderMenu.KINDS.size() && y < 128; k++) {
            long q = m.quantity(k);
            if (q == 0) continue;
            any = true;
            String kind = PortfolioBinderMenu.KINDS.get(k);
            String qty = kind.equals(Securities.CDS) || kind.equals(Securities.LOAN_NOTES) ? q + "" : q + " shares";
            g.text(font, kind, 8, y, GREY, false);
            g.text(font, qty, col2, y, LIGHT_GREY, false);
            String v = kind.equals(Securities.LOAN_NOTES) ? "(debt)" : Money.format(m.valueCents(k));
            g.text(font, v, col3 - font.width(v), y, GREY, false);
            y += 9;
        }
        if (!any) g.text(font, "Put Share Certificates and CDs here.", 8, 78, LIGHT_GREY, false);
        String total = "Total " + Money.format(m.totalCents());
        g.text(font, total, col3 - font.width(total), 130, any ? GREEN : LIGHT_GREY, false);
        g.text(font, "at live prices", 8, 130, BLUE, false);
    }
}
