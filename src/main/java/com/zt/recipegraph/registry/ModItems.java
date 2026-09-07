package com.zt.recipegraph.registry;

import com.zt.recipegraph.RecipeGraphMod;
import com.zt.recipegraph.blocks.GraphTerminalBlock;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Item registration. Currently just the Graph Terminal block item.
 */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RecipeGraphMod.MOD_ID);

    public static final DeferredItem<BlockItem> GRAPH_TERMINAL = ITEMS.registerItem(
        "graph_terminal",
        properties -> new BlockItem(ModBlocks.GRAPH_TERMINAL.get(), properties),
        new Item.Properties());

    private ModItems() {}
}
