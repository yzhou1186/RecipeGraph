package com.zt.recipegraph.network;

import com.zt.recipegraph.RecipeGraphMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Client -> Server packet asking for the pattern graph to be re-collected and resent.
 * The server re-runs the PatternCollector + Louvain clustering and replies with a fresh
 * {@link GraphDataPacket}. Layout happens on the client.
 */
public record RequestRebuildPacket(int containerId) implements CustomPacketPayload {

    public static final Type<RequestRebuildPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RecipeGraphMod.MOD_ID, "request_rebuild"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRebuildPacket> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public RequestRebuildPacket decode(RegistryFriendlyByteBuf buf) {
                return new RequestRebuildPacket(buf.readInt());
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, RequestRebuildPacket pkt) {
                buf.writeInt(pkt.containerId);
            }
        };

    /** Server-side handler. */
    public void handleServer(ServerPlayer player) {
        // Verify the player has the expected menu open
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu.containerId != containerId) return;
        if (!(menu instanceof com.zt.recipegraph.menus.GraphTerminalMenu gtMenu)) return;
        com.zt.recipegraph.blocks.GraphTerminalBlock.sendGraph(
            player, player.level(), gtMenu.getPos(), gtMenu.containerId, gtMenu.nextGeneration());
    }
}
