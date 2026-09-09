package com.zt.recipegraph;

import com.zt.recipegraph.network.PacketHandler;
import com.zt.recipegraph.registry.ModBlocks;
import com.zt.recipegraph.registry.ModBlockEntities;
import com.zt.recipegraph.registry.ModItems;
import com.zt.recipegraph.registry.ModMenus;

import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * Main entrypoint for the Recipe Tree mod.
 *
 * The mod's purpose is to enumerate all Applied Energistics 2 patterns, build a directed graph
 * of their input -> output relationships, cluster it into module boxes (Louvain) and lay it
 * out with a hierarchical (Sugiyama-style) layout inside an in-game terminal screen.
 *
 * Compatible with NeoForge on Minecraft 1.21.1 (Java 21, AE2 19.x).
 */
@Mod(RecipeGraphMod.MOD_ID)
public final class RecipeGraphMod {
    public static final String MOD_ID = "recipegraph";
    public static final org.slf4j.Logger LOGGER =
        com.mojang.logging.LogUtils.getLogger();

    public RecipeGraphMod(ModContainer container, IEventBus modBus) {
        // Register deferred registries
        ModItems.ITEMS.register(modBus);
        ModBlocks.BLOCKS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModMenus.MENUS.register(modBus);

        // Expose AE2 capabilities for our block entities (REQUIRED for grid attachment!)
        modBus.addListener(this::onRegisterCapabilities);

        // Add items to creative tab
        modBus.addListener(this::onBuildCreativeTab);

        // Client-side screen registration
        ModMenus.registerScreens(modBus);

        // Config — register directly via ModContainer (NeoForge 1.21.x API)
        RecipeGraphConfig.register(container);

        // Network channel
        PacketHandler.register(modBus);
    }

    /**
     * AE2 19.x discovers grid-node hosts through the NeoForge capability
     * {@link AECapabilities#IN_WORLD_GRID_NODE_HOST} — implementing the interface alone is
     * not enough. Without this registration, AE2 cables can never see the terminal and it
     * stays an isolated single-node network.
     */
    private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
            ModBlockEntities.GRAPH_TERMINAL.get(),
            (be, side) -> be);
    }

    private void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.GRAPH_TERMINAL);
        }
    }
}
