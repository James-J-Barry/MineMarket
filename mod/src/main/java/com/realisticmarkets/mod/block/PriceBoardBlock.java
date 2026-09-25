package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A thin board hung on a wall, in three kinds: the Dealer's, the Trading Floor's and the Stock Exchange's prices.
 * Right-click with an item (or, for shares, a certificate) to add it (up to three); right-click
 * empty-handed to take the last one back. The face shows each item's public bid and ask.
 */
public class PriceBoardBlock extends Block implements EntityBlock {
    /** Which market a board quotes: the Dealer (Basic Exchange), the Trading Floor, or the Stock Exchange. */
    public enum Kind { DEALER, FLOOR, STOCK }

    private final Kind kind;

    public Kind kind() { return kind; }

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape NORTH = Block.box(0, 2, 14, 16, 14, 16);
    private static final VoxelShape SOUTH = Block.box(0, 2, 0, 16, 14, 2);
    private static final VoxelShape EAST = Block.box(0, 2, 0, 2, 14, 16);
    private static final VoxelShape WEST = Block.box(14, 2, 0, 16, 14, 16);

    public PriceBoardBlock(Properties properties) {
        this(properties, Kind.DEALER);
    }

    public PriceBoardBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Hangs on the clicked wall, facing out. Can't go on floors or ceilings. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        return face.getAxis().isHorizontal() ? defaultBlockState().setValue(FACING, face) : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            case WEST -> WEST;
            default -> NORTH;
        };
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof PriceBoardBlockEntity board)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        String why = board.tryAdd(stack);
        if (why != null) {
            if (player instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal(why));
            return InteractionResult.FAIL;
        }
        // The Dealer's board keeps the item it shows (you get it back); the others only note which book or company.
        if (kind == Kind.DEALER && !player.isCreative()) stack.shrink(1);
        board.refreshNow();
        com.realisticmarkets.mod.fx.Feedback.at(level, pos, com.realisticmarkets.mod.fx.Feedback.Cue.CLICK);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof PriceBoardBlockEntity board)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        ItemStack taken = board.removeLast();
        if (!taken.isEmpty() && kind == Kind.DEALER) player.getInventory().placeItemBackInInventory(taken);
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PriceBoardBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.PRICE_BOARD) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<PriceBoardBlockEntity>) (l, p, s, be) -> be.serverTick();
    }
}
