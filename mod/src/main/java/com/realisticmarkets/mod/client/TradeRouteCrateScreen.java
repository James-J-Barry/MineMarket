package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;
import static com.realisticmarkets.mod.client.Panels.RED;

import com.realisticmarkets.mod.menu.TradeRouteCrateMenu;
import com.realisticmarkets.money.Money;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class TradeRouteCrateScreen extends AbstractContainerScreen<TradeRouteCrateMenu> {
    private Button ship;

    public TradeRouteCrateScreen(TradeRouteCrateMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TradeRouteCrateMenu.WIDTH, TradeRouteCrateMenu.HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        ship = addRenderableWidget(Button.builder(Component.literal("Ship"), b -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(getMenu().containerId, TradeRouteCrateMenu.BUTTON_SHIP);
        }).bounds(leftPos + 72, topPos + 36, 48, 20).build());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (ship != null) ship.active = getMenu().status() == TradeRouteCrateMenu.STATUS_READY;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int x = leftPos, y = topPos;
        Panels.panel(g, x, y, imageWidth, imageHeight);
        for (int i = 0; i < 9; i++) {
            Panels.slot(g, x + TradeRouteCrateMenu.CARGO_X + (i % 3) * 18, y + TradeRouteCrateMenu.CARGO_Y + (i / 3) * 18);
        }
        for (int i = 0; i < 4; i++) {
            Panels.slot(g, x + TradeRouteCrateMenu.DRAWER_X + (i % 2) * 18, y + TradeRouteCrateMenu.DRAWER_Y + (i / 2) * 18);
        }
        Panels.inventory(g, x, y, TradeRouteCrateMenu.INVENTORY_Y);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        TradeRouteCrateMenu m = getMenu();
        g.text(font, "Paid here", TradeRouteCrateMenu.DRAWER_X - 2, TradeRouteCrateMenu.DRAWER_Y + 38, LIGHT_GREY, false);
        int y = 76;
        switch (m.status()) {
            case TradeRouteCrateMenu.STATUS_READY -> {
                long est = m.estimateCents(), local = m.localCents();
                g.text(font, "Capital pays about " + Money.format(est), 8, y, est > local ? GREEN : RED, false);
                g.text(font, "Local Dealer: " + Money.format(local) + "  (5% freight)", 8, y + 10, GREY, false);
            }
            case TradeRouteCrateMenu.STATUS_IN_TRANSIT -> {
                g.text(font, "On the road: arrives in " + m.minutesLeft() + " min", 8, y, GREY, false);
                g.text(font, "Capital now: about " + Money.format(m.estimateCents()), 8, y + 10, LIGHT_GREY, false);
            }
            case TradeRouteCrateMenu.STATUS_NOT_BOUGHT -> g.text(font, "The Capital won't buy something here", 8, y, RED, false);
            default -> g.text(font, "Load goods to ship to the Capital", 8, y, LIGHT_GREY, false);
        }
    }
}
