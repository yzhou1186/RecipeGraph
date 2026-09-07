package com.zt.recipegraph.network;

import com.zt.recipegraph.RecipeGraphMod;
import com.zt.recipegraph.client.ClientGraphState;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> Client packet containing the entire recipe graph.
 *
 * Each {@link NodeData} is one recipe node with its material ports:
 * {@code outKeys/outLabels} for its LEFT output ports (the primary product) and
 * {@code inKeys/inLabels} for its RIGHT input ports (consumed materials). An edge links a
 * producer recipe to a consumer recipe and carries the shared {@code materialKey} (the
 * AEKey id of the material flowing through the linked ports).
 *
 * The packet is intentionally "dumb": it doesn't know about AE2 types - just ids, labels
 * and ports - which keeps the wire format stable across AE2 version updates.
 */
public record GraphDataPacket(
    int status,
    int patterns,
    List<NodeData> nodes,
    List<EdgeData> edges,
    double minX, double maxX, double minY, double maxY
) implements CustomPacketPayload {

    /** Graph data is present. */
    public static final int STATUS_OK = 0;
    /** The terminal is not attached to any AE2 network (node missing or grid unreachable). */
    public static final int STATUS_NO_NETWORK = 1;
    /** The terminal IS attached to a network, but that network holds no readable patterns. */
    public static final int STATUS_NO_PATTERNS = 2;

    public static final Type<GraphDataPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RecipeGraphMod.MOD_ID, "graph_data"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Manual stream codec: read and write the node/edge lists using the primitive
     * {@link RegistryFriendlyByteBuf} accessors. This avoids needing to compose list codecs.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, GraphDataPacket> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public GraphDataPacket decode(RegistryFriendlyByteBuf buf) {
                int status = buf.readVarInt();
                int patterns = buf.readVarInt();
                int nodeCount = buf.readInt();
                List<NodeData> nodes = new ArrayList<>(nodeCount);
                for (int i = 0; i < nodeCount; i++) {
                    String id = buf.readUtf();
                    String label = buf.readUtf();
                    double x = buf.readDouble();
                    double y = buf.readDouble();
                    int cluster = buf.readInt();
                    List<String> inKeys = readStrList(buf);
                    List<String> inLabels = readStrList(buf);
                    List<String> outKeys = readStrList(buf);
                    List<String> outLabels = readStrList(buf);
                    nodes.add(new NodeData(id, label, x, y, cluster,
                        inKeys, inLabels, outKeys, outLabels));
                }
                int edgeCount = buf.readInt();
                List<EdgeData> edges = new ArrayList<>(edgeCount);
                for (int i = 0; i < edgeCount; i++) {
                    String from = buf.readUtf();
                    String to = buf.readUtf();
                    String materialKey = buf.readUtf();
                    edges.add(new EdgeData(from, to, materialKey));
                }
                double minX = buf.readDouble();
                double maxX = buf.readDouble();
                double minY = buf.readDouble();
                double maxY = buf.readDouble();
                return new GraphDataPacket(status, patterns, nodes, edges, minX, maxX, minY, maxY);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, GraphDataPacket pkt) {
                buf.writeVarInt(pkt.status);
                buf.writeVarInt(pkt.patterns);
                buf.writeInt(pkt.nodes.size());
                for (NodeData n : pkt.nodes) {
                    buf.writeUtf(n.id);
                    buf.writeUtf(n.label);
                    buf.writeDouble(n.x);
                    buf.writeDouble(n.y);
                    buf.writeInt(n.cluster);
                    writeStrList(buf, n.inKeys);
                    writeStrList(buf, n.inLabels);
                    writeStrList(buf, n.outKeys);
                    writeStrList(buf, n.outLabels);
                }
                buf.writeInt(pkt.edges.size());
                for (EdgeData e : pkt.edges) {
                    buf.writeUtf(e.fromId);
                    buf.writeUtf(e.toId);
                    buf.writeUtf(e.materialKey);
                }
                buf.writeDouble(pkt.minX);
                buf.writeDouble(pkt.maxX);
                buf.writeDouble(pkt.minY);
                buf.writeDouble(pkt.maxY);
            }

            private List<String> readStrList(RegistryFriendlyByteBuf buf) {
                int count = buf.readVarInt();
                List<String> out = new ArrayList<>(count);
                for (int i = 0; i < count; i++) out.add(buf.readUtf());
                return out;
            }

            private void writeStrList(RegistryFriendlyByteBuf buf, List<String> list) {
                buf.writeVarInt(list.size());
                for (String s : list) buf.writeUtf(s);
            }
        };

    /** One recipe node with its material ports (in* = RIGHT input ports, out* = LEFT outputs). */
    public record NodeData(String id, String label, double x, double y, int cluster,
                           List<String> inKeys, List<String> inLabels,
                           List<String> outKeys, List<String> outLabels) {}

    /** A material flow from a producer recipe to a consumer recipe; materialKey links same-material ports. */
    public record EdgeData(String fromId, String toId, String materialKey) {}

    /** Client-side handler. */
    public void handleClient() {
        ClientGraphState.receiveGraph(this);
    }
}
