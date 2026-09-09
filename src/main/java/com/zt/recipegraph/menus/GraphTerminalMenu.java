package com.zt.recipegraph.menus;

import com.zt.recipegraph.blocks.GraphTerminalBlock;
import com.zt.recipegraph.network.RequestRebuildPacket;
import com.zt.recipegraph.network.PacketHandler;
import com.zt.recipegraph.registry.ModMenus;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side container menu for the Graph Terminal.
 *
 * The menu carries no inventory slots (it's a pure viewer). The graph data is sent out-of-band
 * via a {@link com.zt.recipegraph.network.GraphDataPacket} from
 * {@link GraphTerminalBlock#useWithoutItem} when the menu is opened, and again when the client
 * requests a rebuild.
 */
public class GraphTerminalMenu extends AbstractContainerMenu {
    private final BlockPos pos;
    /**
     * Server-side rebuild generation. Each collection response for this menu carries the
     * generation it was started with; the client discards responses whose generation is
     * older than the newest it has already applied. The initial graph sent when the menu
     * opens uses generation 0.
     */
    private int generation;

    /** Server constructor. */
    public GraphTerminalMenu(int containerId, Inventory playerInv, BlockPos pos) {
        super(ModMenus.GRAPH_TERMINAL.get(), containerId);
        this.pos = pos;
    }

    /** Client constructor (called by the menu type from the buffer the server sent). */
    public GraphTerminalMenu(int containerId, Inventory playerInv, FriendlyByteBuf buf) {
        this(containerId, playerInv, buf.readBlockPos());
    }

    /**
     * The menu stays valid only while the terminal block entity still exists and the player
     * is within 8 blocks of it (vanilla-style interaction range check).
     */
    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(pos) instanceof com.zt.recipegraph.blocks.GraphTerminalBlockEntity
            && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    public BlockPos getPos() {
        return pos;
    }

    /** Current generation of this menu (0 for the graph sent at open time). */
    public int getGeneration() {
        return generation;
    }

    /** Advances to the next generation and returns it. Server side only. */
    public int nextGeneration() {
        return ++generation;
    }

    /**
     * Triggered by the rebuild button on the client screen. Sends a C2S packet asking the
     * server to re-collect patterns and resend the graph.
     */
    public void requestRebuild() {
        PacketDistributor.sendToServer(new RequestRebuildPacket(containerId));
    }
}
