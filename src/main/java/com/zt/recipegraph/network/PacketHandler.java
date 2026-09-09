package com.zt.recipegraph.network;

import com.zt.recipegraph.RecipeGraphMod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the custom packet payloads used by the mod.
 *
 * Two payloads:
 *  - {@link GraphDataPacket}: S->C, full graph snapshot sent on menu open and on rebuild.
 *  - {@link RequestRebuildPacket}: C->S, ask the server to rebuild + resend with a different layout mode.
 *
 * The handler dispatch is split via {@link IPayloadContext#flow()}: client payloads are
 * processed on the client thread, server payloads on the server thread.
 */
public final class PacketHandler {
    private PacketHandler() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(PacketHandler::onRegister);
    }

    private static void onRegister(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(RecipeGraphMod.MOD_ID)
            // Bumped for the 0.4 wire change: GraphDataPacket now carries containerId,
            // generation and a string dictionary instead of raw repeated strings.
            .versioned("2")
            .optional();

        registrar.playToClient(
            GraphDataPacket.TYPE,
            GraphDataPacket.STREAM_CODEC,
            (pkt, ctx) -> pkt.handleClient());

        registrar.playToServer(
            RequestRebuildPacket.TYPE,
            RequestRebuildPacket.STREAM_CODEC,
            (pkt, ctx) -> {
                var player = ctx.player();
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    pkt.handleServer(sp);
                }
            });
    }
}
