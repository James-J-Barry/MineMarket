package com.realisticmarkets.mod.client;

import com.realisticmarkets.mod.RealisticMarkets;
import com.realisticmarkets.mod.menu.BasicExchangeMenu;
import com.realisticmarkets.money.Money;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Basic Exchange screen. Minecraft 26.1 renamed GUI drawing: GuiGraphics -> GuiGraphicsExtractor,
 * renderBg -> extractBackground, renderLabels -> extractLabels.
 */
public class BasicExchangeScreen extends AbstractContainerScreen<BasicExchangeMenu> {
    private static final Identifier TEXTURE = RealisticMarkets.id("textures/gui/container/basic_exchange.png");

    // ARGB: 26.1 text needs an explicit opaque alpha or it is invisible
    private static final int GREY = 0xFF404040;
    private static final int GREEN = 0xFF1E6B2E;
    private static final int RED = 0xFFA01010;

    private Button sellButton;

    public BasicExchangeScreen(BasicExchangeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void init() {
        super.init();
        sellButton = addRenderableWidget(Button.builder(Component.translatable("gui.realisticmarkets.sell"), b -> sell())
                .bounds(leftPos + 50, topPos + 37, 50, 20)
                .build());
        sellButton.active = false;
    }

    private void sell() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryButtonClick(getMenu().containerId, BasicExchangeMenu.BUTTON_SELL);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (sellButton != null) sellButton.active = getMenu().status() == BasicExchangeMenu.STATUS_OK;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        BasicExchangeMenu menu = getMenu();
        String line;
        int color;
        String each = null;
        switch (menu.status()) {
            case BasicExchangeMenu.STATUS_OK -> {
                long cents = menu.quoteCents();
                line = "Dealer pays " + Money.format(cents);
                color = GREEN;
                ItemStack in = menu.inputStack();
                if (!in.isEmpty() && in.getCount() > 1) {
                    each = String.format(Locale.ROOT, "(%s each)", formatUnit(cents / 100.0 / in.getCount()));
                }
            }
            case BasicExchangeMenu.STATUS_NOT_TRADED -> {
                line = "Dealer doesn't buy this";
                color = RED;
            }
            case BasicExchangeMenu.STATUS_COLLAPSED -> {
                line = "Price collapsed; wait";
                color = RED;
            }
            case BasicExchangeMenu.STATUS_MONEY -> {
                line = "That's already money";
                color = RED;
            }
            default -> {
                line = "Place items to sell";
                color = GREY;
            }
        }
        g.text(font, line, 8, 20, color, false);
        if (each != null) g.text(font, each, 8, 60, GREY, false);
    }

    private static String formatUnit(double dollars) {
        return dollars >= 1 ? String.format(Locale.ROOT, "$%.2f", dollars) : String.format(Locale.ROOT, "$%.3f", dollars);
    }
}
