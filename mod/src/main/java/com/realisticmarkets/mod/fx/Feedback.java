package com.realisticmarkets.mod.fx;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;

/**
 * Sounds and particles for the economy, so trades and payouts are felt in the world, not only read in a screen. All
 * vanilla sounds and particles; server side only (both reach nearby players). Played at the block when a menu knows
 * where it is, else at the player.
 */
public final class Feedback {
    private Feedback() {}

    public enum Cue {
        /** Goods sold, papers sold back: coins in. */
        SALE(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5f, 1.25f, ParticleTypes.HAPPY_VILLAGER, 6),
        /** Goods or papers bought. */
        PURCHASE(SoundEvents.ITEM_PICKUP, 0.7f, 0.9f, null, 0),
        /** Dividends, coupons, maturities, settlements, payouts collected. */
        PAYOUT(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.3f, ParticleTypes.WAX_ON, 10),
        /** A contract signed or a position opened: a loan, a CD, a forward, a future, an option. */
        SIGNED(SoundEvents.VILLAGER_WORK_CARTOGRAPHER, 0.8f, 1.1f, ParticleTypes.ENCHANT, 12),
        /** A dawn mark or settlement in your favor. */
        GAIN(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.8f, 1.6f, ParticleTypes.HAPPY_VILLAGER, 8),
        /** A loss taken: a mark against you, a forfeit, a close-out, a default. */
        LOSS(SoundEvents.VILLAGER_NO, 0.7f, 0.8f, ParticleTypes.SMOKE, 10),
        /** A margin call. */
        ALARM(SoundEvents.BELL_BLOCK, 1.0f, 1.0f, ParticleTypes.ANGRY_VILLAGER, 5),
        /** An upgrade bought at the Almanac. */
        UNLOCK(SoundEvents.PLAYER_LEVELUP, 0.7f, 1.2f, ParticleTypes.END_ROD, 16),
        /** A quest completed. */
        QUEST(SoundEvents.PLAYER_LEVELUP, 0.6f, 1.6f, ParticleTypes.END_ROD, 10),
        /** Something linked, fitted or configured. */
        CLICK(SoundEvents.NOTE_BLOCK_BELL.value(), 0.6f, 1.8f, ParticleTypes.NOTE, 1);

        final SoundEvent sound;
        final float volume, pitch;
        final ParticleOptions particle;
        final int count;

        Cue(SoundEvent sound, float volume, float pitch, ParticleOptions particle, int count) {
            this.sound = sound;
            this.volume = volume;
            this.pitch = pitch;
            this.particle = particle;
            this.count = count;
        }
    }

    /** At the menu's block if it has one, else at the player. */
    public static void play(Player player, ContainerLevelAccess access, Cue cue) {
        if (player == null || cue == null || player.level().isClientSide()) return;
        boolean[] done = {false};
        access.execute((level, pos) -> {
            at(level, pos, cue);
            done[0] = true;
        });
        if (!done[0]) at(player, cue);
    }

    /** Around the player. */
    public static void at(Player player, Cue cue) {
        if (player == null || cue == null || !(player.level() instanceof ServerLevel level)) return;
        emit(level, player.getX(), player.getY() + 1.0, player.getZ(), cue);
    }

    /** On top of a block. */
    public static void at(Level level, BlockPos pos, Cue cue) {
        if (level instanceof ServerLevel server) emit(server, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, cue);
    }

    private static void emit(ServerLevel level, double x, double y, double z, Cue cue) {
        level.playSound(null, x, y, z, cue.sound, SoundSource.BLOCKS, cue.volume, cue.pitch);
        if (cue.particle != null && cue.count > 0) {
            level.sendParticles(cue.particle, false, false, x, y, z, cue.count, 0.3, 0.2, 0.3, 0.02);
        }
    }
}
