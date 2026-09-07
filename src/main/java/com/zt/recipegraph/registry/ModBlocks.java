package com.zt.recipegraph.registry;

import com.zt.recipegraph.RecipeGraphMod;
import com.zt.recipegraph.blocks.GraphTerminalBlock;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Block registration. Currently only the Graph Terminal block.
 */
public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RecipeGraphMod.MOD_ID);

    public static final DeferredBlock<GraphTerminalBlock> GRAPH_TERMINAL =
        BLOCKS.registerBlock("graph_terminal", GraphTerminalBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLUE)
                .strength(2.0f, 6.0f)
                .noOcclusion());

    private ModBlocks() {}
}
