package com.zt.recipegraph.client;

import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;
import com.zt.recipegraph.layout.HierarchicalLayout;
import com.zt.recipegraph.layout.LayoutResult;
import com.zt.recipegraph.network.GraphDataPacket;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;

/**
 * Holds the most recently received graph data on the client. The screen reads from here when
 * it needs to render.
 *
 * <p>Threading: packets arrive on the client main thread (NeoForge default). Rebuilding the
 * {@link PatternGraph} is cheap, but the hierarchical layout is not — so layout runs on a
 * daemon worker thread and the finished (graph, layout) pair is published back to the main
 * thread via {@link Minecraft#execute}. The screen never sees a half-laid-out graph because
 * the pair is only swapped on the main thread after the layout has fully finished.</p>
 *
 * <p>State is kept per container id so a late response from a previously opened terminal
 * can never overwrite the graph of the currently open one. Each server response also
 * carries a menu-local generation; responses older than the one already applied for the
 * same container are dropped (this covers two rebuild clicks finishing out of order).</p>
 */
public final class ClientGraphState {

    /** An atomically published (graph, layout) pair; layout is null for empty graphs. */
    private record Snapshot(PatternGraph graph, LayoutResult layout) {}

    /** All mutable per-container state. Touched only on the client main thread. */
    private static final class ContainerState {
        /** Generation of the newest applied server response (-1 = none yet). */
        int generation = -1;
        Snapshot current = null;
        int lastStatus = GraphDataPacket.STATUS_OK;
        int lastPatternCount = -1;
        /** True while a received non-empty graph is still being laid out on the worker. */
        boolean layoutPending = false;
        /** Monotonic per-container sequence so a slow layout can never overwrite a newer one. */
        final AtomicInteger layoutSeq = new AtomicInteger();
    }

    private static final Map<Integer, ContainerState> STATES = new HashMap<>();
    /** Container id whose screen is currently open, or -1. Used to reset state when a menu opens. */
    private static int activeContainer = -1;

    private ClientGraphState() {}

    public static void receiveGraph(int containerId, int generation, GraphDataPacket pkt) {
        ContainerState cs = STATES.computeIfAbsent(containerId, k -> new ContainerState());
        synchronized (cs) {
            // A stale response from an earlier rebuild of the same menu must never replace
            // a newer one that already arrived.
            if (generation < cs.generation) return;
            cs.generation = generation;
        }
        // Material identity and display names are tied to the world/registries in use.
        // Drop old resolutions whenever a new collection arrives so nothing leaks across
        // resource packs, worlds or servers.
        GraphRenderer.clearCaches();

        PatternGraph g = rebuild(pkt);
        cs.lastStatus = pkt.status();
        cs.lastPatternCount = pkt.patterns();

        if (g.isEmpty()) {
            // Also bump the layout sequence here: an older non-empty layout that is still
            // running must not publish over this fresh "no network / no patterns" result.
            cs.layoutSeq.incrementAndGet();
            cs.layoutPending = false;
            cs.current = new Snapshot(g, null);
            return;
        }

        final ContainerState fCs = cs;
        final int seq = cs.layoutSeq.incrementAndGet();
        cs.layoutPending = true;
        Thread worker = new Thread(() -> {
            try {
                LayoutResult layout = new HierarchicalLayout(g).run();
                Minecraft.getInstance().execute(() -> {
                    synchronized (fCs) {
                        // publish only if no newer packet/layout has arrived meanwhile
                        if (fCs.layoutSeq.get() == seq) {
                            fCs.layoutPending = false;
                            fCs.current = new Snapshot(g, layout);
                        }
                    }
                });
            } catch (Throwable t) {
                com.zt.recipegraph.RecipeGraphMod.LOGGER.error("[RecipeGraph] client layout failed", t);
                Minecraft.getInstance().execute(() -> {
                    synchronized (fCs) {
                        if (fCs.layoutSeq.get() == seq) fCs.layoutPending = false;
                    }
                });
            }
        }, "RecipeGraph-Layout");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Called when a graph terminal screen opens. A new menu (even one that happens to reuse
     * a container id after a server restart) starts from a clean slate.
     */
    public static void activate(int containerId) {
        if (activeContainer != containerId) {
            STATES.remove(containerId);
            activeContainer = containerId;
        }
    }

    /** Called when the graph terminal screen closes. */
    public static void deactivate(int containerId) {
        if (activeContainer == containerId) {
            STATES.remove(containerId);
            activeContainer = -1;
        }
    }

    private static ContainerState state(int containerId) {
        return STATES.get(containerId);
    }

    /** Whether any graph packet (including an empty/error status) has arrived. */
    public static boolean hasState(int containerId) {
        return state(containerId) != null;
    }

    /** Collection status of the last received graph (0=ok, 1=no AE2 network, 2=no patterns). */
    public static int status(int containerId) {
        ContainerState cs = state(containerId);
        return cs == null ? GraphDataPacket.STATUS_OK : cs.lastStatus;
    }

    /** Total patterns read in the last collection (-1 before the first packet). */
    public static int patternCount(int containerId) {
        ContainerState cs = state(containerId);
        return cs == null ? -1 : cs.lastPatternCount;
    }

    /** Whether a non-empty packet has arrived but its background layout has not finished. */
    public static boolean layoutPending(int containerId) {
        ContainerState cs = state(containerId);
        return cs != null && cs.layoutPending;
    }

    public static PatternGraph current(int containerId) {
        ContainerState cs = state(containerId);
        if (cs == null) return null;
        Snapshot s = cs.current;
        return s == null ? null : s.graph();
    }

    /**
     * Returns the layout computed for {@code g}, or null when {@code g} is not the currently
     * published graph (checked atomically against the same snapshot). The screen uses this to
     * pair the graph reference with ITS layout in one consistent read.
     */
    public static LayoutResult layoutFor(int containerId, PatternGraph g) {
        ContainerState cs = state(containerId);
        if (cs == null) return null;
        Snapshot s = cs.current;
        return (s != null && s.graph() == g) ? s.layout() : null;
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
