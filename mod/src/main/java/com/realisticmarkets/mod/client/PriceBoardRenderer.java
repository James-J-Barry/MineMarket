package com.realisticmarkets.mod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.realisticmarkets.mod.block.PriceBoardBlock;
import com.realisticmarkets.mod.block.PriceBoardBlockEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a Price Board's three rows: item icon, then two lines. The Dealer's board: "Bid $x ▲" and "Ask $y", the arrow
 * comparing the market price with Normal (above by 2%: ▲, below: ▼). Floor and Stock boards: "Last $x ▲" (against
 * today's open, by 0.5%) and "Bid $a / Ask $b" from the book. Text is full-bright so the board reads like a lit chalkboard.
 */
public class PriceBoardRenderer implements BlockEntityRenderer<PriceBoardBlockEntity, PriceBoardRenderer.State> {
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final float ROW = 0.25f;            // board is 12 px tall = 0.75 blocks, three rows
    private static final float TOP = 0.375f - 0.02f;   // local y of the top row's top edge
    private static final float FRONT = -0.375f + 0.004f; // local z of the board face, nudged out
    private static final float TEXT_SCALE = 0.0075f;
    private static final int BID_COLOR = 0xFFE8F0D8, ASK_COLOR = 0xFFB8C8B0;
    private static final int UP = 0xFF7CD67C, DOWN = 0xFFE07070;

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        PriceBoardBlock.Kind kind = PriceBoardBlock.Kind.DEALER;
        final ItemStackRenderState[] items = new ItemStackRenderState[PriceBoardBlockEntity.SLOTS];
        final String[] bid = new String[PriceBoardBlockEntity.SLOTS];
        final String[] ask = new String[PriceBoardBlockEntity.SLOTS];
        final int[] arrow = new int[PriceBoardBlockEntity.SLOTS]; // +1 above normal, -1 below, 0 near
        int count;

        State() {
            for (int i = 0; i < items.length; i++) items[i] = new ItemStackRenderState();
        }
    }

    private final ItemModelResolver itemModels;
    private final Font font;

    public PriceBoardRenderer(BlockEntityRendererProvider.Context context) {
        this.itemModels = context.itemModelResolver();
        this.font = context.font();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(PriceBoardBlockEntity board, State state, float partialTick, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(board, state, partialTick, cameraPos, crumbling);
        state.facing = board.getBlockState().getValue(PriceBoardBlock.FACING);
        state.count = board.count();
        state.kind = board.kind();
        int seed = (int) board.getBlockPos().asLong();
        for (int i = 0; i < state.count; i++) {
            itemModels.updateForTopItem(state.items[i], board.item(i), ItemDisplayContext.GUI, board.getLevel(), null, seed + i);
            long mid = board.midMills(i), fair = board.fairMills(i);
            if (state.kind == PriceBoardBlock.Kind.DEALER) {
                state.bid[i] = "Bid " + BasicExchangeScreen.formatMills(board.bidMills(i));
                state.ask[i] = "Ask " + BasicExchangeScreen.formatMills(board.askMills(i));
                state.arrow[i] = fair == 0 ? 0 : mid > fair * 1.02 ? 1 : mid < fair * 0.98 ? -1 : 0;
            } else {
                state.bid[i] = "Last " + BasicExchangeScreen.formatMills(mid);
                String b = board.bidMills(i) > 0 ? BasicExchangeScreen.formatMills(board.bidMills(i)) : "-";
                String a = board.askMills(i) > 0 ? BasicExchangeScreen.formatMills(board.askMills(i)) : "-";
                state.ask[i] = b + " / " + a;
                state.arrow[i] = fair == 0 ? 0 : mid > fair * 1.005 ? 1 : mid < fair * 0.995 ? -1 : 0;
            }
        }
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        pose.pushPose();
        pose.translate(0.5f, 0.5f, 0.5f);
        // Local frame: the board's face points to +z (as if facing south), text reads along +x.
        pose.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
        for (int i = 0; i < state.count; i++) {
            float rowTop = TOP - i * ROW;

            pose.pushPose();
            pose.translate(-0.5f + 0.13f, rowTop - ROW / 2 + 0.01f, FRONT + 0.01f);
            pose.scale(0.19f, 0.19f, 0.01f);
            state.items[i].submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();

            pose.pushPose();
            pose.translate(-0.5f + 0.26f, rowTop - 0.05f, FRONT); // two lines centred in the taller row
            pose.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);
            text(collector, pose, state.bid[i], 0, BID_COLOR);
            if (state.arrow[i] != 0) {
                String a = state.arrow[i] > 0 ? " ▲" : " ▼";
                text(collector, pose, a, font.width(state.bid[i]), state.arrow[i] > 0 ? UP : DOWN);
            }
            pose.translate(0, 10, 0);
            text(collector, pose, state.ask[i], 0, ASK_COLOR);
            pose.popPose();
        }
        pose.popPose();
    }

    private void text(SubmitNodeCollector collector, PoseStack pose, String s, float x, int color) {
        collector.submitText(pose, x, 0, Component.literal(s).getVisualOrderText(), false,
                Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT, color, 0, 0);
    }
}
