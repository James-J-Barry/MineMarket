package com.realisticmarkets.mod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.realisticmarkets.mod.block.DisplayBlockEntity;
import com.realisticmarkets.mod.block.WallDisplayBlock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a wall display's title and lines on its face, full-bright like a lit board: left text on the left, right text
 * right-aligned. More lines than fit are shown a page at a time, turning every few seconds.
 */
public class WallDisplayRenderer<T extends DisplayBlockEntity> implements BlockEntityRenderer<T, WallDisplayRenderer.State> {
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final float SCALE = 0.0085f, FRONT = -0.375f + 0.004f; // the face of a 2-pixel board, nudged out
    private static final int WIDTH = 108, LINE = 10, ROWS = 9, PAGE_TICKS = 80;

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        String title = "";
        final List<String> left = new ArrayList<>(), right = new ArrayList<>();
        final List<Integer> color = new ArrayList<>();
    }

    private final Font font;

    public WallDisplayRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(T display, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(display, state, partialTick, cameraPos, crumbling);
        state.facing = display.getBlockState().getValue(WallDisplayBlock.FACING);
        state.title = display.title();
        state.left.clear();
        state.right.clear();
        state.color.clear();
        List<String> lines = display.lines();
        int pages = Math.max(1, (lines.size() + ROWS - 1) / ROWS);
        long time = display.getLevel() == null ? 0 : display.getLevel().getGameTime();
        int page = (int) ((time / PAGE_TICKS) % pages);
        for (int i = page * ROWS; i < Math.min(lines.size(), page * ROWS + ROWS); i++) {
            String[] parts = lines.get(i).split("\t", 2);
            state.left.add(parts[0]);
            state.right.add(parts.length > 1 ? parts[1] : "");
            state.color.add(display.color(i));
        }
        if (pages > 1) state.title = state.title + "  " + (page + 1) + "/" + pages;
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        pose.pushPose();
        pose.translate(0.5f, 0.5f, 0.5f);
        pose.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
        pose.translate(-0.5f + 0.04f, 0.5f - 0.04f, FRONT);
        pose.scale(SCALE, -SCALE, SCALE);
        text(collector, pose, trim(state.title, WIDTH), 0, 0, DisplayBlockEntity.GOLD);
        for (int i = 0; i < state.left.size(); i++) {
            float y = (i + 1) * LINE + 2;
            String right = state.right.get(i);
            int rw = font.width(right);
            text(collector, pose, trim(state.left.get(i), WIDTH - rw - (rw > 0 ? 4 : 0)), 0, y, state.color.get(i));
            if (rw > 0) text(collector, pose, right, WIDTH - rw, y, state.color.get(i));
        }
        pose.popPose();
    }

    private String trim(String s, int width) {
        if (font.width(s) <= width) return s;
        while (!s.isEmpty() && font.width(s + "..") > width) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    private void text(SubmitNodeCollector collector, PoseStack pose, String s, float x, float y, int color) {
        collector.submitText(pose, x, y, Component.literal(s).getVisualOrderText(), false, Font.DisplayMode.POLYGON_OFFSET,
                FULL_BRIGHT, color, 0, 0);
    }
}
