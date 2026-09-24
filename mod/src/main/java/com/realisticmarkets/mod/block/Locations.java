package com.realisticmarkets.mod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Block positions saved as "dimension|x|y|z" strings (shipments, vault locations). */
public final class Locations {
    private Locations() {}

    public static String of(Level level, BlockPos pos) {
        String dim = level == null ? "?" : level.dimension().identifier().toString();
        return dim + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    /** The block entity of that type at the location, if its chunk is loaded; else null. */
    public static <T extends BlockEntity> T find(MinecraftServer server, String location, Class<T> type) {
        if (location == null) return null;
        String[] p = location.split("\\|");
        if (p.length != 4) return null;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(p[0])));
        if (level == null) return null;
        BlockPos pos = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        if (!level.isLoaded(pos)) return null;
        BlockEntity be = level.getBlockEntity(pos);
        return type.isInstance(be) ? type.cast(be) : null;
    }

    /** True if the chunk isn't loaded (we can't tell, so assume the block is still there) or the block is there. */
    public static boolean mayStillStand(MinecraftServer server, String location, Class<? extends BlockEntity> type) {
        if (location == null) return false;
        String[] p = location.split("\\|");
        if (p.length != 4) return false;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(p[0])));
        if (level == null) return false;
        BlockPos pos = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        return !level.isLoaded(pos) || type.isInstance(level.getBlockEntity(pos));
    }
}
