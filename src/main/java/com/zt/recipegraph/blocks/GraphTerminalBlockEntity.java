package com.zt.recipegraph.blocks;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IManagedGridNode;
import appeng.api.util.AECableType;
import appeng.me.helpers.BlockEntityNodeListener;
import appeng.me.helpers.IGridConnectedBlockEntity;

import com.zt.recipegraph.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Graph Terminal — a full AE2 network host.
 *
 * <p>The terminal attaches its own grid node to the ME network it is cabled into:
 *   - it occupies exactly one channel ({@link GridFlags#REQUIRE_CHANNEL});
 *   - it forwards the dense channel capacity on every face
 *     ({@link GridFlags#DENSE_CAPACITY} — AE2's highest per-connection capacity,
 *     so every face can carry a full dense-cable line of channels);
 *   - all six faces can connect cables and carry the network onward
 *     (connection type {@link AECableType#DENSE_SMART}).</p>
 *
 * <p>Patterns are read exclusively from this attached network.</p>
 */
public class GraphTerminalBlockEntity extends BlockEntity implements IGridConnectedBlockEntity {

    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, BlockEntityNodeListener.INSTANCE)
        .setFlags(GridFlags.REQUIRE_CHANNEL, GridFlags.DENSE_CAPACITY)
        // CRITICAL: without setInWorldNode(true), ManagedGridNode creates a plain GridNode
        // whose findInWorldConnections() is EMPTY — the node can never connect to adjacent
        // AE2 devices. Only InWorldGridNode scans neighbors and forms connections.
        .setInWorldNode(true)
        .setIdlePowerUsage(1.0);

    public GraphTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GRAPH_TERMINAL.get(), pos, state);
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level != null && !level.isClientSide) {
            GridHelper.onFirstTick(this, be -> be.mainNode.create(be.level, be.worldPosition));
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        mainNode.destroy();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        mainNode.destroy();
    }

    @Override
    public IManagedGridNode getMainNode() {
        return mainNode;
    }

    @Override
    public void saveChanges() {
        setChanged();
    }

    @Override
    public void setOwner(Player player) {
        if (level != null && !level.isClientSide && player != null) {
            int playerId = appeng.api.features.IPlayerRegistry.getPlayerId((net.minecraft.server.level.ServerPlayer) player);
            if (playerId >= 0) {
                mainNode.setOwningPlayerId(playerId);
            }
        }
    }

    @Override
    public appeng.api.networking.IGridNode getGridNode(Direction dir) {
        return mainNode.getNode();
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.DENSE_SMART;
    }

    @Override
    public appeng.api.networking.IGridNode getActionableNode() {
        return mainNode.getNode();
    }

    /**
     * @return the AE2 grid this terminal is attached to, or null when offline/unconnected.
     *
     * <p>Every created AE2 node automatically forms its own single-node "island" grid
     * (GridNode.getInternalGrid() calls Grid.create(this) when unconnected), and that
     * island grid also reports as booted — so a non-null booted grid does NOT mean the
     * terminal is wired to anything. The deterministic "attached" condition is having at
     * least one in-world connection (a cable/controller/pattern provider next to a face).</p>
     */
    public appeng.api.networking.IGrid getGrid() {
        appeng.api.networking.IGridNode node = mainNode.getNode();
        if (node == null) return null;
        if (node.getConnectedSides().isEmpty()) return null; // isolated node — not on a network
        appeng.api.networking.IGrid grid = node.getGrid();
        return (grid != null && node.hasGridBooted()) ? grid : null;
    }
}
