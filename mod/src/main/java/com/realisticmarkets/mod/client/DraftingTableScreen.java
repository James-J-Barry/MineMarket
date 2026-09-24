package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;
import static com.realisticmarkets.mod.client.Panels.RED;

import com.realisticmarkets.mod.menu.DraftingTableMenu;
import com.realisticmarkets.mod.progression.Materials;
import com.realisticmarkets.progression.Blueprint;
import java.util.List;
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

/** Blueprint list on the left, materials (have/need) and craft buttons on the right. */
public class DraftingTableScreen extends AbstractContainerScreen<DraftingTableMenu> {
    private static final int LIST_X = 8, LIST_Y = 18, ROW = 18, ROWS = 4, LIST_W = 80;
    private static final int DETAIL_X = 92, DETAIL_RIGHT = 168;
    private static final List<Blueprint> BLUEPRINTS = DraftingTableMenu.BLUEPRINTS;

    private final Inventory inventory;
    private Button craftOne, craftMax;
    private int scroll;

    public DraftingTableScreen(DraftingTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, DraftingTableMenu.WIDTH, DraftingTableMenu.HEIGHT);
        this.inventory = inventory;
    }

    @Override
    protected void init() {
        super.init();
        craftOne = addRenderableWidget(Button.builder(Component.literal("x1"), b -> click(DraftingTableMenu.BUTTON_CRAFT_ONE))
                .bounds(leftPos + DETAIL_X, topPos + DraftingTableMenu.OUTPUT_Y + 1, 24, 14).build());
        craftMax = addRenderableWidget(Button.builder(Component.literal("Max"), b -> click(DraftingTableMenu.BUTTON_CRAFT_MAX))
                .bounds(leftPos + DETAIL_X + 26, topPos + DraftingTableMenu.OUTPUT_Y + 1, 28, 14).build());
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
        if (craftOne == null) return;
        int sel = getMenu().selected();
        boolean can = sel >= 0 && sel < BLUEPRINTS.size() && getMenu().unlocked(sel) && maxCraftable(sel) > 0;
        craftOne.visible = craftMax.visible = sel >= 0;
        craftOne.active = craftMax.active = can;
    }

    private int maxCraftable(int i) {
        return BLUEPRINTS.get(i).maxCraftable(Materials.counts(inventory, BLUEPRINTS.get(i)));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int row = rowAt(event.x(), event.y());
        if (row >= 0) {
            click(DraftingTableMenu.BUTTON_SELECT_BASE + row);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, BLUEPRINTS.size() - ROWS);
        if (inList(mouseX, mouseY)) {
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean inList(double mx, double my) {
        double x = mx - leftPos, y = my - topPos;
        return x >= LIST_X && x < LIST_X + LIST_W && y >= LIST_Y && y < LIST_Y + ROWS * ROW;
    }

    private int rowAt(double mx, double my) {
        if (!inList(mx, my)) return -1;
        int i = scroll + (int) ((my - topPos - LIST_Y) / ROW);
        return i < BLUEPRINTS.size() ? i : -1;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int x = leftPos, y = topPos;
        Panels.panel(g, x, y, imageWidth, imageHeight);
        Panels.well(g, x + LIST_X, y + LIST_Y, LIST_W, ROWS * ROW);
        int sel = getMenu().selected(), hover = rowAt(mouseX, mouseY);
        for (int r = 0; r < ROWS && scroll + r < BLUEPRINTS.size(); r++) {
            int i = scroll + r;
            int color = i == sel ? 0xFFC6D7F0 : i == hover ? 0xFFB8B8B8 : 0xFF9E9E9E;
            g.fill(x + LIST_X + 1, y + LIST_Y + r * ROW + 1, x + LIST_X + LIST_W - 1, y + LIST_Y + (r + 1) * ROW - 1, color);
        }
        Panels.slot(g, x + DraftingTableMenu.OUTPUT_X, y + DraftingTableMenu.OUTPUT_Y);
        Panels.inventory(g, x, y, DraftingTableMenu.INVENTORY_Y);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        DraftingTableMenu m = getMenu();
        for (int r = 0; r < ROWS && scroll + r < BLUEPRINTS.size(); r++) {
            int i = scroll + r;
            ItemStack result = new ItemStack(DraftingTableMenu.resultItem(BLUEPRINTS.get(i)));
            int ry = LIST_Y + r * ROW;
            g.item(result, LIST_X + 1, ry + 1);
            String name = Panels.trim(font, result.getHoverName().getString(), LIST_W - 22);
            g.text(font, name, LIST_X + 19, ry + 5, m.unlocked(i) ? GREY : LIGHT_GREY, false);
        }

        int sel = m.selected();
        if (sel < 0 || sel >= BLUEPRINTS.size()) {
            g.text(font, "Pick a blueprint", DETAIL_X, LIST_Y + 2, LIGHT_GREY, false);
            return;
        }
        Blueprint bp = BLUEPRINTS.get(sel);
        if (!m.unlocked(sel)) {
            g.text(font, "Unlock at the", DETAIL_X, LIST_Y + 2, RED, false);
            g.text(font, "Almanac", DETAIL_X, LIST_Y + 12, RED, false);
        } else {
            g.text(font, "Materials", DETAIL_X, LIST_Y + 2, LIGHT_GREY, false);
        }
        int ly = LIST_Y + (m.unlocked(sel) ? 13 : 24);
        for (Blueprint.Material mat : bp.materials()) {
            int have = Materials.count(inventory, mat.item());
            String count = Math.min(have, 999) + "/" + mat.count();
            String name = Panels.trim(font, materialName(mat.item()), DETAIL_RIGHT - DETAIL_X - font.width(count) - 4);
            g.text(font, name, DETAIL_X, ly, GREY, false);
            g.text(font, count, DETAIL_RIGHT - font.width(count), ly, have >= mat.count() ? GREEN : RED, false);
            ly += 10;
        }
    }

    private static String materialName(String id) {
        if (id.equals("#minecraft:planks")) return "Any Planks";
        if (id.startsWith("#")) return id.substring(id.indexOf(':') + 1);
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id))).getHoverName().getString();
    }
}
