package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;
import static com.realisticmarkets.mod.client.Panels.RED;

import com.realisticmarkets.mod.menu.TickerTapeMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class TickerTapeScreen extends AbstractContainerScreen<TickerTapeMenu> {
    private Button print;

    public TickerTapeScreen(TickerTapeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TickerTapeMenu.WIDTH, TickerTapeMenu.HEIGHT);
        inventoryLabelY = TickerTapeMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        print = addRenderableWidget(Button.builder(Component.literal("Print chart"), b -> click(TickerTapeMenu.BUTTON_PRINT))
                .bounds(leftPos + 8, topPos + 58, 80, 16).build());
    }

    private void click(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(getMenu().containerId, id);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (print != null) print.active = getMenu().canAfford();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x() - leftPos - TickerTapeMenu.GRID_X, y = event.y() - topPos - TickerTapeMenu.GRID_Y;
        if (x >= 0 && y >= 0 && x < TickerTapeMenu.COLS * 18 && y < 2 * 18) {
            int i = (int) (y / 18) * TickerTapeMenu.COLS + (int) (x / 18);
            if (i < TickerTapeMenu.BOOKS.size()) {
                click(TickerTapeMenu.BUTTON_BOOK_BASE + i);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private static ItemStack icon(int book) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(TickerTapeMenu.BOOKS.get(book).item())));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int x = leftPos, y = topPos, c = TickerTapeMenu.COLS;
        Panels.panel(g, x, y, imageWidth, imageHeight);
        Panels.well(g, x + TickerTapeMenu.GRID_X, y + TickerTapeMenu.GRID_Y, c * 18, 2 * 18);
        int sel = getMenu().book();
        int sx = x + TickerTapeMenu.GRID_X + (sel % c) * 18, sy = y + TickerTapeMenu.GRID_Y + (sel / c) * 18;
        g.fill(sx, sy, sx + 18, sy + 18, 0xFFC6D7F0);
        Panels.slot(g, x + TickerTapeMenu.OUTPUT_X, y + TickerTapeMenu.OUTPUT_Y);
        Panels.inventory(g, x, y, TickerTapeMenu.INVENTORY_Y);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        TickerTapeMenu m = getMenu();
        int c = TickerTapeMenu.COLS;
        for (int i = 0; i < TickerTapeMenu.BOOKS.size(); i++) {
            g.item(icon(i), TickerTapeMenu.GRID_X + (i % c) * 18 + 1, TickerTapeMenu.GRID_Y + (i / c) * 18 + 1);
        }
        g.text(font, Panels.trim(font, icon(m.book()).getHoverName().getString(), 70), 94, 62, GREY, false);
        g.text(font, "1 Ledger Paper + 1 Ink Bottle", 8, 78, m.canAfford() ? LIGHT_GREY : RED, false);
    }
}
