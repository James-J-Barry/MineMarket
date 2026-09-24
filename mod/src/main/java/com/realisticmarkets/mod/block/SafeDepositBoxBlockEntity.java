package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 54 slots of security papers and currency, belonging to whoever placed the box. */
public class SafeDepositBoxBlockEntity extends OwnedBlockEntity {
    public static final int SLOTS = 54;

    private final SimpleContainer contents = new SimpleContainer(SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            SafeDepositBoxBlockEntity.this.setChanged();
        }
    };

    public SafeDepositBoxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SAFE_DEPOSIT_BOX, pos, state);
    }

    public SimpleContainer contents() {
        return contents;
    }

    public boolean isEmpty() {
        return contents.isEmpty();
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        ContainerHelper.saveAllItems(out.child("Contents"), contents.getItems());
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        ContainerHelper.loadAllItems(in.childOrEmpty("Contents"), contents.getItems());
    }

    /** Normally empty when broken; a creative-mode break drops whatever was left rather than deleting it. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null) net.minecraft.world.Containers.dropContents(level, pos, contents);
    }
}
