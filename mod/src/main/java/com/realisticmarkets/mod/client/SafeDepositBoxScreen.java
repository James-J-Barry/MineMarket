package com.realisticmarkets.mod.client;

import com.realisticmarkets.mod.menu.SafeDepositBoxMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** A large-chest-style screen; deliberately no totals (that's the Portfolio Binder's job). */
public class SafeDepositBoxScreen extends AbstractContainerScreen<SafeDepositBoxMenu> {
    public SafeDepositBoxScreen(SafeDepositBoxMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, SafeDepositBoxMenu.WIDTH, SafeDepositBoxMenu.HEIGHT);
        inventoryLabelY = SafeDepositBoxMenu.INVENTORY_Y - 11;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 9; c++) Panels.slot(g, leftPos + 8 + c * 18, topPos + SafeDepositBoxMenu.BOX_Y + r * 18);
        }
        Panels.inventory(g, leftPos, topPos, SafeDepositBoxMenu.INVENTORY_Y);
    }
}
