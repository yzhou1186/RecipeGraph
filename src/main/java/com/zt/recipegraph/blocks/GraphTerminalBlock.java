package com.zt.recipegraph.blocks;

import com.zt.recipegraph.RecipeGraphMod;
import com.zt.recipegraph.ae2.PatternCollector;
import com.zt.recipegraph.graph.GraphEdge;
import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;
import com.zt.recipegraph.menus.GraphTerminalMenu;
import com.zt.recipegraph.network.GraphDataPacket;

import net.minecraft.core.BlockPos;
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
 *   2. The graph is collected server-side (PatternCollector + Louvain clustering).
 *   3. It is sent to the client as a {@link GraphDataPacket}; the hierarchical layout
 *      (module boxes, Sugiyama, orthogonal routing) runs on the client.
 *
 * Because (1) and (3) are sent in that order on the same tick, the client is guaranteed to
 * have created the screen before the graph packet arrives.
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

            // 2. Build the graph and send it
            sendGraph(serverPlayer, level, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Collect + send the pattern graph. Reads patterns only from the AE2 network this
     * terminal is attached to. The server only gathers patterns and runs Louvain
     * community detection; the hierarchical layout (module boxes, Sugiyama, orthogonal
     * routing) is computed on the client.
     */
    public static void sendGraph(ServerPlayer player, Level level, BlockPos pos) {
        appeng.api.networking.IGrid grid = null;
        appeng.api.networking.IGridNode node = null;
        if (level.getBlockEntity(pos) instanceof GraphTerminalBlockEntity terminal) {
            node = terminal.getMainNode().getNode();
            grid = terminal.getGrid();
        }

        PatternCollector collector = new PatternCollector(grid);
        PatternGraph graph = collector.collect();
        if (!graph.isEmpty()) {
            new com.zt.recipegraph.clustering.LouvainClustering(graph).run();
        }

        // --- diagnostics (server log): pinpoints where the AE2 chain breaks ---
        if (node == null) {
            RecipeGraphMod.LOGGER.info("[GraphTerminal] {} no grid node (not created yet?)", pos);
        } else {
            RecipeGraphMod.LOGGER.info(
                "[GraphTerminal] {} node#{} grid={} booted={} active={} channels={}/{} connectedSides={} providers={} craftables={} patterns={} graphNodes={} graphEdges={}",
                pos, node.hashCode(), grid != null, node.hasGridBooted(), node.isActive(),
                node.getUsedChannels(), node.getMaxChannels(), node.getConnectedSides(),
                collector.getProviderCount(),
                collector.getCraftableCount(), collector.getPatternCount(),
                graph.getNodeCount(), graph.getEdgeCount());
            if (!collector.getProviderDetails().isEmpty()) {
                RecipeGraphMod.LOGGER.info("[GraphTerminal] providers: {}",
                    String.join(", ", collector.getProviderDetails()));
            }
        }

        int status;
        if (grid == null) {
            status = GraphDataPacket.STATUS_NO_NETWORK;
        } else if (graph.isEmpty()) {
            status = GraphDataPacket.STATUS_NO_PATTERNS;
        } else {
            status = GraphDataPacket.STATUS_OK;
        }

        List<GraphDataPacket.NodeData> nodes = new ArrayList<>(graph.getNodeCount());
        for (GraphNode n : graph.getNodes()) {
            // positions are computed client-side; only ids, labels, cluster ids and ports travel
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
            status,
            Math.max(0, collector.getPatternCount()),
            nodes, edges,
            graph.getMinX(), graph.getMaxX(), graph.getMinY(), graph.getMaxY()
        );
        PacketDistributor.sendToPlayer(player, pkt);
    }
}
