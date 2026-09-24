package com.realisticmarkets.mod.client;

import com.realisticmarkets.mod.menu.BillClipMenu;
import com.realisticmarkets.money.Money;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class BillClipScreen extends AbstractContainerScreen<BillClipMenu> {
    public BillClipScreen(BillClipMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BillClipMenu.WIDTH, BillClipMenu.HEIGHT);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (int i = 0; i < 9; i++) Panels.slot(g, leftPos + 8 + i * 18, topPos + BillClipMenu.CLIP_Y);
        Panels.inventory(g, leftPos, topPos, BillClipMenu.INVENTORY_Y);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        String total = "Holds " + Money.format(getMenu().cents());
        g.text(font, total, imageWidth - 8 - font.width(total), titleLabelY, Panels.GREY, false);
    }
}
