package com.zt.recipegraph.registry;

import com.zt.recipegraph.RecipeGraphMod;
import com.zt.recipegraph.blocks.GraphTerminalBlockEntity;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Block entity registration.
 */
public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, RecipeGraphMod.MOD_ID);

    public static final Supplier<BlockEntityType<GraphTerminalBlockEntity>> GRAPH_TERMINAL =
        BLOCK_ENTITIES.register("graph_terminal",
            () -> BlockEntityType.Builder.of(
                GraphTerminalBlockEntity::new,
                ModBlocks.GRAPH_TERMINAL.get())
            .build(null));

    private ModBlockEntities() {}
}
