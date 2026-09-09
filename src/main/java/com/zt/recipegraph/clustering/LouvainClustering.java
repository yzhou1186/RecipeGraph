package com.zt.recipegraph.clustering;

import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Louvain-style community detection — the LOCAL MOVING phase only (Blondel et al., 2008).
 *
 * <p>Repeated random-order sweeps visit each node and evaluate the modularity gain of moving
 * it from its current community into each neighbour community; the node moves to the one
 * giving the greatest strictly-positive gain. Sweeps repeat until a full sweep moves nothing
 * (a locally optimal partition) or {@link #MAX_LOCAL_SWEEPS} is reached. The graph is treated
 * as undirected and weighted: PatternGraph stores a symmetric weight per node pair (parallel
 * pattern edges add up).</p>
 *
 * <p><b>Why the aggregation phase is intentionally absent.</b> Classic Louvain follows the
 * local phase with a contraction phase (communities become super-nodes, then local moving
 * repeats on the quotient graph). Contracting correctly requires carrying intra-community
 * edge weights as super-node SELF-LOOPS — they are the communities' internal mass and must
 * stay inside {@code sigmaTot} and {@code m}; otherwise the modularity penalty
 * {@code sigmaTot[c]·k_i/(2m²)} is computed as if communities had no internal edges, and from
 * the second level on merging almost any two connected super-nodes shows a positive gain.
 * Measured on the 605-recipe / 1395-edge modpack graph, the level-1 partition coarsens
 * 145 → 28 → 7 → 2 → 1 community in four levels (every recipe ends in ONE box, which the
 * oversize splitter then slices into cap-sized chunks, cutting ~98% of intra-community edges
 * and leaving nearly every chunk a single unrelated column). The level-0 local-moving
 * partition, by contrast, is the fine-grained grouping the whole layout (box cap, meta
 * Sugiyama layering, routing) is built for — ~150 small coherent recipe groups — and is a
 * fully valid Louvain phase-1 optimum. We therefore stop there rather than retain self-loops
 * and coarsen into a handful of oversized communities.</p>
 *
 * <p>Determinism is provided by a fixed random seed ({@code 42}); all other ordering is
 * stable.</p>
 */
public final class LouvainClustering {
    private final PatternGraph graph;
    private final Random random;

    public LouvainClustering(PatternGraph graph) {
        this(graph, 42);
    }

    public LouvainClustering(PatternGraph graph, long seed) {
        this.graph = graph;
        this.random = new Random(seed);
    }

    /** Maximum full local-phase sweeps. */
    private static final int MAX_LOCAL_SWEEPS = 50;

    /**
     * Runs the local moving phase and returns the number of distinct communities found.
     * The result is assigned to {@link GraphNode#setCluster(int)} on every node.
     */
    public int run() {
        graph.resetClusters();
        List<GraphNode> nodes = new ArrayList<>(graph.getNodes());
        int n = nodes.size();
        if (n == 0) return 0;

        Map<String, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < n; i++) indexOf.put(nodes.get(i).getId(), i);

        // Undirected weighted adjacency of the ORIGINAL nodes. getNeighbours() may contain
        // duplicate entries (parallel pattern edges add each endpoint repeatedly); weights
        // come from PatternGraph's symmetric pair-weight index (parallel edges summed).
        List<List<Integer>> adj = new ArrayList<>(n);
        List<List<Double>> weights = new ArrayList<>(n);
        double[] deg = new double[n];
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
            weights.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            Set<Integer> seen = new HashSet<>();
            for (GraphNode nb : graph.getNeighbours(nodes.get(i).getId())) {
                int j = indexOf.get(nb.getId());
                if (j == i || !seen.add(j)) continue;
                double w = graph.getEdgeWeight(nodes.get(i).getId(), nodes.get(j).getId());
                adj.get(i).add(j);
                weights.get(i).add(w);
                deg[i] += w;
            }
        }

        double m = 0;
        for (double d : deg) m += d;
        m /= 2.0;

        int[] community = new int[n];
        for (int i = 0; i < n; i++) community[i] = i;

        if (m > 0) {
            localPhase(adj, weights, deg, community, m);
        }
        int communityCount = remap(community);
        for (int i = 0; i < n; i++) nodes.get(i).setCluster(community[i]);
        return communityCount;
    }

    /**
     * Greedy local phase. Repeated random-order sweeps move each node into the neighbour
     * community with the greatest positive modularity gain until no move occurs.
     */
    private boolean localPhase(List<List<Integer>> adj, List<List<Double>> weights, double[] deg,
                               int[] community, double m) {
        int curN = adj.size();
        double[] sigmaTot = deg.clone();
        boolean anyMoved = false;

        for (int sweep = 0; sweep < MAX_LOCAL_SWEEPS; sweep++) {
            boolean movedThisSweep = false;
            List<Integer> order = new ArrayList<>(curN);
            for (int i = 0; i < curN; i++) order.add(i);
            Collections.shuffle(order, random);

            for (int idx : order) {
                int currentCommunity = community[idx];
                // Sum of weights from idx to nodes in each neighbouring community.
                Map<Integer, Double> d = new HashMap<>();
                for (int e = 0; e < adj.get(idx).size(); e++) {
                    int j = adj.get(idx).get(e);
                    double w = weights.get(idx).get(e);
                    d.merge(community[j], w, Double::sum);
                }
                double kI = deg[idx];
                double dCurrent = d.getOrDefault(currentCommunity, 0.0);
                double curTotAfterRemove = sigmaTot[currentCommunity] - kI;

                int bestCommunity = currentCommunity;
                double bestGain = 0;
                for (Map.Entry<Integer, Double> en : d.entrySet()) {
                    int c = en.getKey();
                    if (c == currentCommunity) continue;
                    double gain = (en.getValue() - dCurrent) / m
                        - (sigmaTot[c] - curTotAfterRemove) * kI / (2 * m * m);
                    if (gain > bestGain + 1e-12) {
                        bestGain = gain;
                        bestCommunity = c;
                    }
                }

                if (bestCommunity != currentCommunity) {
                    sigmaTot[currentCommunity] -= kI;
                    sigmaTot[bestCommunity] += kI;
                    community[idx] = bestCommunity;
                    movedThisSweep = true;
                }
            }

            anyMoved |= movedThisSweep;
            if (!movedThisSweep) break;
        }
        return anyMoved;
    }

    /** Renumbers community labels to 0..count-1 and returns the number of communities. */
    private static int remap(int[] community) {
        Map<Integer, Integer> remap = new HashMap<>();
        int next = 0;
        for (int i = 0; i < community.length; i++) {
            Integer mapped = remap.get(community[i]);
            if (mapped == null) {
                remap.put(community[i], next);
                community[i] = next;
                next++;
            } else {
                community[i] = mapped;
            }
        }
        return next;
    }
}
