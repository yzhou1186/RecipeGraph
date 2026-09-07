package com.zt.recipegraph.client;

import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;
import com.zt.recipegraph.network.GraphDataPacket;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds the most recently received graph data on the client. The screen reads from here when
 * it needs to render.
 *
 * The state is keyed by containerId so that multiple terminals could in principle be open at
 * the same time, though in practice there's only one player.
 */
public final class ClientGraphState {
    private static final Map<Integer, PatternGraph> STATES = new HashMap<>();
    /** Collection status of the last received packet (GraphDataPacket.STATUS_*). */
    private static int lastStatus = GraphDataPacket.STATUS_OK;
    /** Total number of patterns read from the network in the last collection (-1 = none yet). */
    private static int lastPatternCount = -1;

    private ClientGraphState() {}

    public static void receiveGraph(GraphDataPacket pkt) {
        PatternGraph g = rebuild(pkt);
        // We don't have the containerId in the packet; use 0 as the singleton key.
        // The screen always reads key 0.
        STATES.put(0, g);
        lastStatus = pkt.status();
        lastPatternCount = pkt.patterns();
    }

    /** Collection status of the last received graph (0=ok, 1=no AE2 network, 2=no patterns). */
    public static int status() {
        return lastStatus;
    }

    /** Total patterns read in the last collection (-1 before the first packet). */
    public static int patternCount() {
        return lastPatternCount;
    }

    public static PatternGraph get(int containerId) {
        return STATES.get(containerId);
    }

    public static PatternGraph current() {
        return STATES.get(0);
    }

    public static PatternGraph rebuild(GraphDataPacket pkt) {
        PatternGraph g = new PatternGraph();
        Map<String, GraphNode> byId = new HashMap<>();
        for (GraphDataPacket.NodeData nd : pkt.nodes()) {
            GraphNode node = g.getOrCreateNode(nd.id(), nd.label());
            node.x = nd.x();
            node.y = nd.y();
            node.cluster = nd.cluster();
            // material ports: outputs on the LEFT (primary product), inputs on the RIGHT
            for (int i = 0; i < nd.outKeys().size(); i++) {
                String k = nd.outKeys().get(i);
                String l = i < nd.outLabels().size() ? nd.outLabels().get(i) : k;
                node.outputs.add(new com.zt.recipegraph.graph.Port(k, l));
            }
            for (int i = 0; i < nd.inKeys().size(); i++) {
                String k = nd.inKeys().get(i);
                String l = i < nd.inLabels().size() ? nd.inLabels().get(i) : k;
                node.inputs.add(new com.zt.recipegraph.graph.Port(k, l));
            }
            byId.put(nd.id(), node);
        }
        for (GraphDataPacket.EdgeData ed : pkt.edges()) {
            GraphNode from = byId.get(ed.fromId());
            GraphNode to = byId.get(ed.toId());
            if (from == null || to == null) continue;
            g.addEdge(from, to, ed.materialKey());
        }
        g.recomputeBounds();
        return g;
    }
}
