package com.realisticmarkets.mod.records;

import com.realisticmarkets.mod.block.OwnedBlockEntity;
import com.realisticmarkets.mod.block.RecordsTerminalBlockEntity;
import com.realisticmarkets.mod.registry.ModItems;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Record Link: right-click a Records Terminal to pick it, then a Bank Vault, Safe Deposit Box or Trade Route Crate
 * to link it (right-click a linked block again to unlink). Both must be yours, in the same dimension, within 64
 * blocks. Handled before the blocks' own screens open, so a plain right-click works.
 */
public class RecordLinkItem extends Item {
    public RecordLinkItem(Properties properties) {
        super(properties);
    }

    /** {@code UseBlockCallback}: links when the player holds a Record Link, else lets the click through. */
    public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(ModItems.RECORD_LINK) || player.isSpectator()) return InteractionResult.PASS;
        BlockEntity be = level.getBlockEntity(hit.getBlockPos());
        if (!(be instanceof RecordsTerminalBlockEntity) && !RecordsTerminalBlockEntity.linkable(be)
                && !(be instanceof com.realisticmarkets.mod.block.LedgerDisplayBlockEntity)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        String msg = use(player, level, hit.getBlockPos(), stack);
        com.realisticmarkets.mod.fx.Feedback.at(level, hit.getBlockPos(), com.realisticmarkets.mod.fx.Feedback.Cue.CLICK);
        if (player instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal(msg));
        return InteractionResult.SUCCESS;
    }

    /** Server side: picks a terminal or toggles a link. Returns the message for the player. */
    public static String use(Player player, Level level, BlockPos pos, ItemStack stack) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RecordsTerminalBlockEntity terminal) {
            if (terminal.owner() == null) terminal.setOwner(player);
            if (!terminal.isOwner(player)) return "This terminal belongs to " + terminal.ownerName();
            select(stack, level, pos);
            return "Record Link set to this terminal (" + terminal.linkedBlocks().size() + " of "
                    + RecordsTerminalBlockEntity.MAX_LINKS + " links). Now right-click a vault, box or crate.";
        }
        if (be instanceof com.realisticmarkets.mod.block.LedgerDisplayBlockEntity display) {
            Optional<BlockPos> sel = selected(stack, level);
            if (sel.isEmpty() || !(level.getBlockEntity(sel.get()) instanceof RecordsTerminalBlockEntity terminal)) {
                return "Right-click a Records Terminal first";
            }
            if (!terminal.isOwner(player)) return "That terminal belongs to " + terminal.ownerName();
            if (display.owner() != null && !display.isOwner(player)) return "This display belongs to " + display.ownerName();
            if (display.owner() == null) display.setOwner(player);
            if (!terminal.inRange(pos)) return "Too far from the terminal (" + RecordsTerminalBlockEntity.RANGE + " blocks at most)";
            display.link(sel.get());
            return "Ledger Display shows this terminal";
        }
        if (!(be instanceof OwnedBlockEntity target) || !RecordsTerminalBlockEntity.linkable(be)) return "Nothing to link here";
        Optional<BlockPos> selected = selected(stack, level);
        if (selected.isEmpty()) return "Right-click a Records Terminal first";
        if (!(level.getBlockEntity(selected.get()) instanceof RecordsTerminalBlockEntity terminal)) {
            clear(stack);
            return "That terminal is gone: right-click a Records Terminal first";
        }
        if (!terminal.isOwner(player)) return "That terminal belongs to " + terminal.ownerName();
        if (target.owner() != null && !target.isOwner(player)) return "This belongs to " + target.ownerName();
        String why = terminal.toggle(pos);
        if (why != null) return why;
        return terminal.isLinked(pos) ? "Linked (" + terminal.links().size() + " of " + RecordsTerminalBlockEntity.MAX_LINKS + ")"
                : "Unlinked";
    }

    private static void select(ItemStack stack, Level level, BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", level.dimension().identifier().toString());
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Terminal at " + pos.getX() + ", " + pos.getY() + ", "
                + pos.getZ()).withStyle(st -> st.withColor(ChatFormatting.GRAY).withItalic(false)))));
    }

    private static void clear(ItemStack stack) {
        stack.remove(DataComponents.CUSTOM_DATA);
        stack.remove(DataComponents.LORE);
    }

    /** The terminal this link points at, if it's in {@code level}'s dimension. */
    public static Optional<BlockPos> selected(ItemStack stack, Level level) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        String dim = tag.getStringOr("dimension", "");
        if (dim.isEmpty() || !dim.equals(level.dimension().identifier().toString())) return Optional.empty();
        return Optional.of(new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0)));
    }
}
