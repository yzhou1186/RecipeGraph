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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Main entrypoint for the Recipe Tree mod.
 *
 * The mod's purpose is to enumerate all Applied Energistics 2 patterns, build a directed graph
 * of their input -> output relationships, and visualize that graph using a constrained
 * force-directed layout inside an in-game terminal screen.
 *
 * Compatible with NeoForge on Minecraft 1.21.1. The Java source level is 17 to keep the
 * bytecode as broadly consumable as possible while still satisfying NeoForge's runtime floor.
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

        // Re-collect patterns on server start (the resulting graph is cached client-side after sync)
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
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

    private void onServerStarted(ServerStartedEvent event) {
        // Nothing to do here yet; collection happens on-demand from the terminal to avoid
        // holding large graphs in memory when the terminal is unused.
    }

    private void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.GRAPH_TERMINAL);
        }
    }
}
