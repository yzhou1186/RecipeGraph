package com.zt.recipegraph.network;

import com.zt.recipegraph.RecipeGraphMod;
import com.zt.recipegraph.client.ClientGraphState;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    int containerId,
    int generation,
    int status,
    int patterns,
    List<NodeData> nodes,
    List<EdgeData> edges
) implements CustomPacketPayload {

    /** Sanity caps for decoding hostile/corrupt packets (prevents OOM on absurd counts). */
    private static final int MAX_NODES = 100_000;
    private static final int MAX_EDGES = 400_000;
    private static final int MAX_PORTS_PER_NODE = 4_096;
    private static final int MAX_DICT_STRINGS = 1_000_000;

    /** Graph data is present. */
    public static final int STATUS_OK = 0;
    /** The terminal is not attached to any AE2 network (node missing or grid unreachable). */
    public static final int STATUS_NO_NETWORK = 1;
    /** The terminal IS attached to a network, but that network holds no readable patterns. */
    public static final int STATUS_NO_PATTERNS = 2;
    /** Pattern collection/layout reported an unrecoverable error. */
    public static final int STATUS_ERROR = 3;

    public static final Type<GraphDataPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RecipeGraphMod.MOD_ID, "graph_data"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Manual stream codec: read and write the node/edge lists using the primitive
     * {@link RegistryFriendlyByteBuf} accessors. This avoids needing to compose list codecs.
     *
     * <p>All strings go through a per-packet dictionary. Material key ids are long SNBT
     * strings that otherwise repeat on every port and edge carrying the same material; the
     * dictionary keeps each unique string on the wire exactly once and the payload much
     * smaller on dense networks.</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, GraphDataPacket> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public GraphDataPacket decode(RegistryFriendlyByteBuf buf) {
                int containerId = buf.readVarInt();
                int generation = buf.readVarInt();
                int status = buf.readVarInt();
                int patterns = buf.readVarInt();
                int dictCount = buf.readVarInt();
                if (dictCount < 0 || dictCount > MAX_DICT_STRINGS) {
                    throw new IllegalArgumentException("GraphDataPacket dictionary out of range: " + dictCount);
                }
                String[] dict = new String[dictCount];
                for (int i = 0; i < dictCount; i++) dict[i] = buf.readUtf();
                int nodeCount = buf.readVarInt();
                if (nodeCount < 0 || nodeCount > MAX_NODES) {
                    throw new IllegalArgumentException("GraphDataPacket nodeCount out of range: " + nodeCount);
                }
                List<NodeData> nodes = new ArrayList<>(Math.min(nodeCount, 4_096));
                for (int i = 0; i < nodeCount; i++) {
                    String id = readDictString(buf, dict);
                    String label = readDictString(buf, dict);
                    double x = buf.readDouble();
                    double y = buf.readDouble();
                    int cluster = buf.readInt();
                    List<String> inKeys = readStrList(buf, dict, MAX_PORTS_PER_NODE);
                    List<String> inLabels = readStrList(buf, dict, MAX_PORTS_PER_NODE);
                    List<String> outKeys = readStrList(buf, dict, MAX_PORTS_PER_NODE);
                    List<String> outLabels = readStrList(buf, dict, MAX_PORTS_PER_NODE);
                    nodes.add(new NodeData(id, label, x, y, cluster,
                        inKeys, inLabels, outKeys, outLabels));
                }
                int edgeCount = buf.readVarInt();
                if (edgeCount < 0 || edgeCount > MAX_EDGES) {
                    throw new IllegalArgumentException("GraphDataPacket edgeCount out of range: " + edgeCount);
                }
                List<EdgeData> edges = new ArrayList<>(Math.min(edgeCount, 4_096));
                for (int i = 0; i < edgeCount; i++) {
                    String from = readDictString(buf, dict);
                    String to = readDictString(buf, dict);
                    String materialKey = readDictString(buf, dict);
                    edges.add(new EdgeData(from, to, materialKey));
                }
                return new GraphDataPacket(containerId, generation, status, patterns, nodes, edges);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, GraphDataPacket pkt) {
                buf.writeVarInt(pkt.containerId);
                buf.writeVarInt(pkt.generation);
                buf.writeVarInt(pkt.status);
                buf.writeVarInt(pkt.patterns);

                // Build the string dictionary (order = first occurrence in the packet).
                List<String> dict = new ArrayList<>();
                Map<String, Integer> dictIndex = new HashMap<>();
                for (NodeData n : pkt.nodes) {
                    addDictString(dict, dictIndex, n.id);
                    addDictString(dict, dictIndex, n.label);
                    addDictStrings(dict, dictIndex, n.inKeys);
                    addDictStrings(dict, dictIndex, n.inLabels);
                    addDictStrings(dict, dictIndex, n.outKeys);
                    addDictStrings(dict, dictIndex, n.outLabels);
                }
                for (EdgeData e : pkt.edges) {
                    addDictString(dict, dictIndex, e.fromId);
                    addDictString(dict, dictIndex, e.toId);
                    addDictString(dict, dictIndex, e.materialKey);
                }
                buf.writeVarInt(dict.size());
                for (String s : dict) buf.writeUtf(s);

                buf.writeVarInt(pkt.nodes.size());
                for (NodeData n : pkt.nodes) {
                    writeDictString(buf, dictIndex, n.id);
                    writeDictString(buf, dictIndex, n.label);
                    buf.writeDouble(n.x);
                    buf.writeDouble(n.y);
                    buf.writeInt(n.cluster);
                    writeStrList(buf, dictIndex, n.inKeys);
                    writeStrList(buf, dictIndex, n.inLabels);
                    writeStrList(buf, dictIndex, n.outKeys);
                    writeStrList(buf, dictIndex, n.outLabels);
                }
                buf.writeVarInt(pkt.edges.size());
                for (EdgeData e : pkt.edges) {
                    writeDictString(buf, dictIndex, e.fromId);
                    writeDictString(buf, dictIndex, e.toId);
                    writeDictString(buf, dictIndex, e.materialKey);
                }
            }

        };

    /** Reads a string resolved through the per-packet dictionary. */
    private static String readDictString(RegistryFriendlyByteBuf buf, String[] dict) {
        int idx = buf.readVarInt();
        if (idx < 0 || idx >= dict.length) {
            throw new IllegalArgumentException("GraphDataPacket string index out of range: " + idx);
        }
        return dict[idx];
    }

    /** Reads a string list, guarding the declared size against hostile values. */
    private static List<String> readStrList(RegistryFriendlyByteBuf buf, String[] dict, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw new IllegalArgumentException("GraphDataPacket list size out of range: " + count);
        }
        List<String> out = new ArrayList<>(Math.min(count, 256));
        for (int i = 0; i < count; i++) out.add(readDictString(buf, dict));
        return out;
    }

    private static int addDictString(List<String> dict, Map<String, Integer> dictIndex, String s) {
        Integer existing = dictIndex.get(s);
        if (existing != null) return existing;
        int idx = dict.size();
        dict.add(s);
        dictIndex.put(s, idx);
        return idx;
    }

    private static void addDictStrings(List<String> dict, Map<String, Integer> dictIndex, List<String> strings) {
        for (String s : strings) addDictString(dict, dictIndex, s);
    }

    private static void writeDictString(RegistryFriendlyByteBuf buf, Map<String, Integer> dictIndex, String s) {
        Integer idx = dictIndex.get(s);
        if (idx == null) {
            throw new IllegalStateException("GraphDataPacket string missing from dictionary: " + s);
        }
        buf.writeVarInt(idx);
    }

    private static void writeStrList(RegistryFriendlyByteBuf buf, Map<String, Integer> dictIndex, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) writeDictString(buf, dictIndex, s);
    }

    /** One recipe node with its material ports (in* = RIGHT input ports, out* = LEFT outputs). */
    public record NodeData(String id, String label, double x, double y, int cluster,
                           List<String> inKeys, List<String> inLabels,
                           List<String> outKeys, List<String> outLabels) {}

    /** A material flow from a producer recipe to a consumer recipe; materialKey links same-material ports. */
    public record EdgeData(String fromId, String toId, String materialKey) {}

    /** Client-side handler. */
    public void handleClient() {
        ClientGraphState.receiveGraph(containerId(), generation(), this);
    }
}
