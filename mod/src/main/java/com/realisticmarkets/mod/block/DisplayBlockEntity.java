package com.realisticmarkets.mod.block;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A wall display: a title and lines of "left text\tright text" with a color each, worked out on the server every
 * {@link #REFRESH_TICKS} ticks and sent to clients with the block update (the renderer draws them on the face).
 */
public abstract class DisplayBlockEntity extends OwnedBlockEntity {
    public static final int REFRESH_TICKS = 100;
    public static final int WHITE = 0xFFE8F0D8, DIM = 0xFFA8B8A0, UP = 0xFF7CD67C, DOWN = 0xFFE07070, GOLD = 0xFFE8C860;

    private String title = "";
    private final List<String> lines = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>();

    protected DisplayBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public String title() { return title; }
    public List<String> lines() { return List.copyOf(lines); }
    public int color(int i) { return i < colors.size() ? colors.get(i) : WHITE; }

    /** Server: work out what to show now. */
    protected abstract void refresh(MinecraftServer server);

    public void serverTick() {
        if (!(level instanceof ServerLevel server)) return;
        if (Math.floorMod(level.getGameTime() + worldPosition.hashCode(), REFRESH_TICKS) != 0) return;
        refresh(server.getServer());
    }

    /** Shows {@code newLines} under {@code newTitle}; sends an update only if something changed. */
    protected void show(String newTitle, List<String> newLines, List<Integer> newColors) {
        if (newTitle.equals(title) && newLines.equals(lines) && newColors.equals(colors)) return;
        title = newTitle;
        lines.clear();
        lines.addAll(newLines);
        colors.clear();
        colors.addAll(newColors);
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    /** Collects lines and colors. */
    protected static final class Lines {
        final List<String> text = new ArrayList<>();
        final List<Integer> color = new ArrayList<>();

        public Lines add(String left, String right, int c) {
            text.add(right == null || right.isEmpty() ? left : left + "\t" + right);
            color.add(c);
            return this;
        }
    }

    protected void show(String newTitle, Lines l) {
        show(newTitle, l.text, l.color);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putString("Title", title);
        out.putString("Lines", String.join("\n", lines));
        StringBuilder sb = new StringBuilder();
        for (int c : colors) sb.append(sb.isEmpty() ? "" : ",").append(c);
        out.putString("Colors", sb.toString());
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        title = in.getStringOr("Title", "");
        lines.clear();
        String l = in.getStringOr("Lines", "");
        if (!l.isEmpty()) lines.addAll(List.of(l.split("\n", -1)));
        colors.clear();
        String c = in.getStringOr("Colors", "");
        if (!c.isEmpty()) for (String x : c.split(",")) colors.add(Integer.parseInt(x));
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }
}
