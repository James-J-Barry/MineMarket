package com.realisticmarkets.mod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.realisticmarkets.mod.block.PriceBoardBlock;
import com.realisticmarkets.mod.block.PriceBoardBlockEntity;
import com.realisticmarkets.mod.stocks.ShareCertificates;
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
 * Draws a Price Board's three rows, centred inside the frame: item icon (a Stock board shows the ticker), then two lines. The Dealer's board: "Bid $x ▲" and "Ask $y", the arrow
 * comparing the market price with Normal (above by 2%: ▲, below: ▼). Floor and Stock boards: "Last $x ▲" (against
 * today's open, by 0.5%) and "Bid $a / Ask $b" from the book. Text is full-bright so the board reads like a lit chalkboard.
 */
public class PriceBoardRenderer implements BlockEntityRenderer<PriceBoardBlockEntity, PriceBoardRenderer.State> {
    private static final int FULL_BRIGHT = 0xF000F0;
    // The face is 16 x 12 px with a 1 px frame: the rows live inside x, y in [-HALF_W, HALF_W] x [-HALF_H, HALF_H].
    private static final float HALF_W = 0.40f, HALF_H = 0.28f;
    private static final float ROW = 2 * HALF_H / PriceBoardBlockEntity.SLOTS;
    private static final float FRONT = -0.375f + 0.004f; // local z of the board face, nudged out
    private static final float TEXT_SCALE = 0.0055f, TICKER_SCALE = 0.0085f;
    private static final float ICON = 0.13f, GAP = 0.03f; // icon column width, space before the text
    private static final int TICKER_COLOR = 0xFFFFD27A;
    private static final int BID_COLOR = 0xFFE8F0D8, ASK_COLOR = 0xFFB8C8B0;
    private static final int UP = 0xFF7CD67C, DOWN = 0xFFE07070;

    public static class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        PriceBoardBlock.Kind kind = PriceBoardBlock.Kind.DEALER;
        final ItemStackRenderState[] items = new ItemStackRenderState[PriceBoardBlockEntity.SLOTS];
        final String[] bid = new String[PriceBoardBlockEntity.SLOTS];
        final String[] ask = new String[PriceBoardBlockEntity.SLOTS];
        final int[] arrow = new int[PriceBoardBlockEntity.SLOTS]; // +1 above normal, -1 below, 0 near
        final String[] ticker = new String[PriceBoardBlockEntity.SLOTS]; // Stock boards: drawn instead of the icon
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
            state.ticker[i] = state.kind == PriceBoardBlock.Kind.STOCK
                    ? ShareCertificates.read(board.item(i)).map(ShareCertificates.Paper::ticker).orElse("?") : null;
            if (state.ticker[i] == null) {
                itemModels.updateForTopItem(state.items[i], board.item(i), ItemDisplayContext.GUI, board.getLevel(), null, seed + i);
            }
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
        if (state.count == 0) return;
        // Size the text to the widest row, then centre the whole block (icon column + text) inside the frame.
        int widest = 0;
        for (int i = 0; i < state.count; i++) {
            widest = Math.max(widest, Math.max(font.width(firstLine(state, i)), font.width(state.ask[i])));
        }
        float scale = Math.min(TEXT_SCALE, (2 * HALF_W - ICON - GAP) / Math.max(1, widest));
        float left = -(ICON + GAP + widest * scale) / 2;
        float top = state.count * ROW / 2; // the rows as a group are centred vertically too

        pose.pushPose();
        pose.translate(0.5f, 0.5f, 0.5f);
        // Local frame: the board's face points to +z (as if facing south), text reads along +x.
        pose.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
        for (int i = 0; i < state.count; i++) {
            float mid = top - i * ROW - ROW / 2;

            pose.pushPose();
            if (state.ticker[i] != null) {
                float ts = Math.min(TICKER_SCALE, ICON / Math.max(1, font.width(state.ticker[i])));
                pose.translate(left + ICON / 2, mid + 4 * ts, FRONT);
                pose.scale(ts, -ts, ts);
                text(collector, pose, state.ticker[i], -font.width(state.ticker[i]) / 2f, TICKER_COLOR);
            } else {
                pose.translate(left + ICON / 2, mid, FRONT + 0.01f);
                pose.scale(ICON, ICON, 0.01f);
                state.items[i].submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            }
            pose.popPose();

            pose.pushPose();
            pose.translate(left + ICON + GAP, mid + 8.5f * scale, FRONT); // two 9-unit lines centred on the row
            pose.scale(scale, -scale, scale);
            text(collector, pose, state.bid[i], 0, BID_COLOR);
            if (state.arrow[i] != 0) {
                text(collector, pose, arrow(state, i), font.width(state.bid[i]), state.arrow[i] > 0 ? UP : DOWN);
            }
            pose.translate(0, 9, 0);
            text(collector, pose, state.ask[i], 0, ASK_COLOR);
            pose.popPose();
        }
        pose.popPose();
    }

    private static String arrow(State state, int i) {
        return state.arrow[i] > 0 ? " ▲" : state.arrow[i] < 0 ? " ▼" : "";
    }

    private static String firstLine(State state, int i) {
        return state.bid[i] + arrow(state, i);
    }

    private void text(SubmitNodeCollector collector, PoseStack pose, String s, float x, int color) {
        collector.submitText(pose, x, 0, Component.literal(s).getVisualOrderText(), false,
                Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT, color, 0, 0);
    }
}
