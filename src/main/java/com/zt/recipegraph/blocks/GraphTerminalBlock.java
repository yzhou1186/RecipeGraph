package com.zt.recipegraph.blocks;

import com.zt.recipegraph.RecipeGraphMod;
import com.zt.recipegraph.ae2.PatternCollector;
import com.zt.recipegraph.graph.GraphEdge;
import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;
import com.zt.recipegraph.menus.GraphTerminalMenu;
import com.zt.recipegraph.network.GraphDataPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A simple decorative block that opens the AE2 pattern graph terminal when right-clicked.
 *
 * When the player right-clicks the block:
 *   1. The menu is opened server-side (which sends the open-screen packet to the client).
 *   2. {@link #sendGraph} enumerates patterns via the AE2 grid/provider/crafting services
 *      on the main thread (AE2 grid state must never be read off-thread), then builds the
 *      recipe graph and runs Louvain clustering on a worker thread from the immutable
 *      pattern snapshot (large modpacks can hold thousands of patterns — doing this
 *      synchronously would stall the server tick).
 *   3. The worker hands the finished packet back to the server thread via
 *      {@code executeIfPossible}, where diagnostics are logged and the packet is sent.
 *
 * The graph packet may therefore arrive a tick or two AFTER the screen opens; the client
 * screen polls {@link com.zt.recipegraph.client.ClientGraphState} every tick and shows the
 * graph as soon as it lands. The hierarchical layout (module boxes, Sugiyama, orthogonal
 * routing) runs on the client, also off the render thread.
 */
public class GraphTerminalBlock extends Block implements EntityBlock {
    public GraphTerminalBlock(BlockBehaviour.Properties props) {
        super(props);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GraphTerminalBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // No ticker needed; everything happens in response to player interaction.
        return null;
    }

    // NOTE: no neighborChanged handling is needed — AE2 establishes node connections
    // when the adjacent cable's own node boots (and tears them down when it is removed).

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            // 1. Open the menu
            serverPlayer.openMenu(new MenuProvider() {
                @Override
                public Component getDisplayName() {
                    return Component.translatable("recipegraph.screen.graph_terminal.title");
                }

                @Override
                public AbstractContainerMenu createMenu(int containerId, Inventory inv, Player p) {
                    return new GraphTerminalMenu(containerId, inv, pos);
                }
            }, buf -> buf.writeBlockPos(pos));

            // 2. Build the graph and send it. openMenu() installs the new menu on the
            // player synchronously, so its container id is the generation-0 request target.
            if (serverPlayer.containerMenu instanceof GraphTerminalMenu terminalMenu) {
                sendGraph(serverPlayer, level, pos, terminalMenu.containerId, terminalMenu.getGeneration());
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Collect + send the pattern graph. Reads patterns only from the AE2 network this
     * terminal is attached to.
     *
     * <p>Threading: ALL AE2 grid queries happen synchronously on the calling (main) thread —
     * grid/provider/service references, the provider pattern enumeration and the
     * crafting-service craftable enumeration are resolved there into an immutable pattern
     * snapshot. The worker thread only extracts data from those patterns (ports, SNBT key
     * ids) and runs Louvain clustering; the finished packet is handed back to the server
     * thread via {@code executeIfPossible} for logging and sending. Collection can take
     * hundreds of milliseconds on large modpacks and the heavy work must never run on the
     * server tick thread, while grid nodes/services must never be queried off-thread.</p>
     */
    public static void sendGraph(ServerPlayer player, Level level, BlockPos pos, int containerId, int generation) {
        // --- main thread: capture AE2 references AND enumerate every readable pattern ---
        // AE2 grid nodes/services must not be queried off-thread, so the provider and
        // crafting-service enumeration happens HERE; the worker only parses the immutable
        // pattern snapshot into graph nodes/ports and runs clustering.
        appeng.api.networking.IGrid grid = null;
        appeng.api.networking.IGridNode node = null;
        List<PatternCollector.ProviderEntry> providerSnapshot = List.of();
        appeng.api.networking.crafting.ICraftingService craftingService = null;
        HolderLookup.Provider registries = level != null ? level.registryAccess() : null;
        if (level.getBlockEntity(pos) instanceof GraphTerminalBlockEntity terminal) {
            node = terminal.getMainNode().getNode();
            grid = terminal.getGrid();
            if (grid != null) {
                providerSnapshot = new ArrayList<>();
                for (appeng.api.networking.IGridNode n : grid.getNodes()) {
                    appeng.api.networking.crafting.ICraftingProvider provider = null;
                    try {
                        provider = n.getService(appeng.api.networking.crafting.ICraftingProvider.class);
                    } catch (Throwable ignored) {}
                    if (provider == null && n.getOwner() instanceof appeng.api.networking.crafting.ICraftingProvider owner) {
                        provider = owner;
                    }
                    if (provider != null) {
                        String ownerName = n.getOwner() == null
                            ? provider.getClass().getSimpleName()
                            : n.getOwner().getClass().getSimpleName();
                        providerSnapshot.add(new PatternCollector.ProviderEntry(provider, ownerName));
                    }
                }
                craftingService = grid.getCraftingService();
            }
        }

        // enumerate patterns on the main thread: direct ICraftingProvider nodes first
        // (vanilla providers, ME interfaces, addon devices), then the crafting-service
        // fallback as a union; deduplicate by identity into an insertion-ordered snapshot.
        java.util.Set<appeng.api.crafting.IPatternDetails> patternSet = new java.util.LinkedHashSet<>();
        List<String> providerDetails = new ArrayList<>();
        int providers = 0;
        for (PatternCollector.ProviderEntry entry : providerSnapshot) {
            List<appeng.api.crafting.IPatternDetails> patterns = null;
            try {
                patterns = entry.provider().getAvailablePatterns();
            } catch (Throwable ignored) {}
            if (patterns == null || patterns.isEmpty()) continue;
            providers++;
            providerDetails.add(entry.ownerName() + ":" + patterns.size());
            patternSet.addAll(patterns);
        }
        int craftables = 0;
        if (craftingService != null) {
            for (appeng.api.stacks.AEKey craftable : craftingService.getCraftables(key -> true)) {
                craftables++;
                try {
                    patternSet.addAll(craftingService.getCraftingFor(craftable));
                } catch (Throwable ignored) {}
            }
        }
        List<appeng.api.crafting.IPatternDetails> patternSnapshot = List.copyOf(patternSet);

        final appeng.api.networking.IGrid fGrid = grid;
        final appeng.api.networking.IGridNode fNode = node;
        final int fProviderCount = providers;
        final int fCraftableCount = craftables;
        final List<String> fProviderDetails = List.copyOf(providerDetails);
        final List<appeng.api.crafting.IPatternDetails> fPatternSnapshot = patternSnapshot;
        final ServerPlayer fPlayer = player;
        final int fContainerId = containerId;
        final int fGeneration = generation;
        final HolderLookup.Provider fRegistries = registries;

        Thread worker = new Thread(() -> {
            try {
                PatternCollector collector = new PatternCollector(
                    fPatternSnapshot, fProviderCount, fCraftableCount, fProviderDetails, fRegistries);
                PatternGraph graph = collector.collect();
                if (!graph.isEmpty()) {
                    new com.zt.recipegraph.clustering.LouvainClustering(graph).run();
                }

                int status;
                if (fGrid == null) {
                    status = GraphDataPacket.STATUS_NO_NETWORK;
                } else if (graph.isEmpty()) {
                    status = GraphDataPacket.STATUS_NO_PATTERNS;
                } else {
                    status = GraphDataPacket.STATUS_OK;
                }

                // positions are computed client-side; only ids, labels, cluster ids and ports travel
                List<GraphDataPacket.NodeData> nodes = new ArrayList<>(graph.getNodeCount());
                for (GraphNode n : graph.getNodes()) {
                    List<String> inKeys = new ArrayList<>();
                    List<String> inLabels = new ArrayList<>();
                    for (com.zt.recipegraph.graph.Port p : n.getInputs()) {
                        inKeys.add(p.keyId);
                        inLabels.add(p.keyLabel);
                    }
                    List<String> outKeys = new ArrayList<>();
                    List<String> outLabels = new ArrayList<>();
                    for (com.zt.recipegraph.graph.Port p : n.getOutputs()) {
                        outKeys.add(p.keyId);
                        outLabels.add(p.keyLabel);
                    }
                    nodes.add(new GraphDataPacket.NodeData(n.getId(), n.getLabel(), 0, 0, n.getCluster(),
                        inKeys, inLabels, outKeys, outLabels));
                }
                List<GraphDataPacket.EdgeData> edges = new ArrayList<>(graph.getEdgeCount());
                for (GraphEdge e : graph.getEdges()) {
                    edges.add(new GraphDataPacket.EdgeData(
                        e.getFrom().getId(), e.getTo().getId(), e.getKeyId()));
                }
                GraphDataPacket pkt = new GraphDataPacket(
                    fContainerId,
                    fGeneration,
                    status,
                    Math.max(0, collector.getPatternCount()),
                    nodes, edges);

                var server = fPlayer.getServer();
                if (server == null) return;

                // --- back on the server thread: diagnostics + send ---
                server.executeIfPossible(() -> {
                    if (fNode == null) {
                        RecipeGraphMod.LOGGER.info("[GraphTerminal] {} no grid node (not created yet?)", pos);
                    } else {
                        RecipeGraphMod.LOGGER.info(
                            "[GraphTerminal] {} node#{} grid={} booted={} active={} channels={}/{} connectedSides={} providers={} craftables={} patterns={} graphNodes={} graphEdges={}",
                            pos, fNode.hashCode(), fGrid != null, fNode.hasGridBooted(), fNode.isActive(),
                            fNode.getUsedChannels(), fNode.getMaxChannels(), fNode.getConnectedSides(),
                            collector.getProviderCount(),
                            collector.getCraftableCount(), collector.getPatternCount(),
                            graph.getNodeCount(), graph.getEdgeCount());
                        if (!collector.getProviderDetails().isEmpty()) {
                            RecipeGraphMod.LOGGER.info("[GraphTerminal] providers: {}",
                                String.join(", ", collector.getProviderDetails()));
                        }
                    }
                    PacketDistributor.sendToPlayer(fPlayer, pkt);
                });
            } catch (Throwable t) {
                RecipeGraphMod.LOGGER.error("[GraphTerminal] {} graph collection failed", pos, t);
                // Never leave the client waiting forever: report an explicit error status.
                var server = fPlayer.getServer();
                if (server != null) {
                    server.executeIfPossible(() -> PacketDistributor.sendToPlayer(fPlayer, new GraphDataPacket(
                        fContainerId, fGeneration, GraphDataPacket.STATUS_ERROR, -1, List.of(), List.of())));
                }
            }
        }, "RecipeGraph-Collector");
        worker.setDaemon(true);
        worker.start();
    }
}
