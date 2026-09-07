package com.zt.recipegraph.clustering;

import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Louvain community detection algorithm (Blondel et al., 2008).
 *
 * Louvain is a greedy, modularity-based method that repeatedly:
 * 1. Local phase: For each node, evaluate the modularity gain of moving it from its current
 *    community to a neighbour's community. Move it to the one giving the greatest positive gain.
 * 2. Aggregation phase: Build a new weighted graph where each community becomes a single node,
 *    and edges between communities become weighted edges. Self-loops capture internal edge weights.
 *
 * The two phases iterate until modularity stops improving.
 *
 * Implementation notes:
 * - Treats the graph as undirected and weighted. The {@link PatternGraph} already stores symmetric
 *   edge weights between pairs of nodes.
 * - The implementation is purely in-memory and operates on the ids returned by {@link GraphNode#getId()}.
 * - Result is written into {@link GraphNode#setCluster(int)}.
 *
 * This class is intentionally self-contained (no external Java features beyond collections and
 * {@link Random}) so it can be transplanted to other Java versions (8+) without changes.
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

    /**
     * Runs the algorithm and returns the number of distinct communities found.
     * The result is assigned to {@link GraphNode#setCluster(int)} on every node.
     */
    public int run() {
        graph.resetClusters();
        if (graph.isEmpty()) {
            return 0;
        }

        // Build internal node index
        List<String> nodeIds = new ArrayList<>();
        Map<String, Integer> indexOf = new HashMap<>();
        for (GraphNode n : graph.getNodes()) {
            indexOf.put(n.getId(), nodeIds.size());
            nodeIds.add(n.getId());
        }
        int n = nodeIds.size();

        // Build neighbour list and weighted degrees using the (symmetric) weights from the graph
        // (so a directed edge from A->B contributes the same as B->A).
        double[] k = new double[n]; // weighted degree of each node
        double m2 = 0;               // 2 * total weight (sum of degrees)
        // Use adjacency list of nodes
        List<List<Integer>> neighbours = new ArrayList<>(n);
        List<List<Double>> weights = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            neighbours.add(new ArrayList<>());
            weights.add(new ArrayList<>());
        }

        // Add an edge for every ordered pair from the underlying undirected graph
        for (GraphNode node : graph.getNodes()) {
            int i = indexOf.get(node.getId());
            for (GraphNode nb : graph.getNeighbours(node.getId())) {
                int j = indexOf.get(nb.getId());
                if (i == j) continue;
                double w = graph.getEdgeWeight(node.getId(), nb.getId());
                // Deduplicate (graph adjacency stores both directions once per edge)
                if (!neighbours.get(i).contains(j)) {
                    neighbours.get(i).add(j);
                    weights.get(i).add(w);
                    k[i] += w;
                    m2 += w;
                }
            }
        }

        // The aggregation above may double-count: we walk both endpoints of every edge.
        // In practice, with the symmetric weight map in PatternGraph, each (i, j) pair only
        // gets registered once because adjacency lists contain j when walking from i, and we
        // avoid re-adding by checking contains. The total m2 is then sum of degrees' half:
        double m = m2 / 2.0; // total weight
        if (m == 0) {
            // No edges: each node is its own cluster
            int c = 0;
            for (GraphNode node : graph.getNodes()) {
                node.setCluster(c++);
            }
            return c;
        }

        // Initial: every node in its own community
        int[] community = new int[n];
        for (int i = 0; i < n; i++) community[i] = i;

        // Sum of weights of edges inside community c
        double[] sigmaIn = new double[n];
        // Sum of weights of edges incident to community c
        double[] sigmaTot = new double[n];
        for (int i = 0; i < n; i++) {
            sigmaIn[i] = 0; // no self-loops initially
            sigmaTot[i] = k[i];
        }

        boolean improved = true;
        int iterations = 0;
        while (improved && iterations < 20) {
            improved = false;
            iterations++;
            // Random order
            List<Integer> order = new ArrayList<>(n);
            for (int i = 0; i < n; i++) order.add(i);
            Collections.shuffle(order, random);

            for (int idx : order) {
                int bestCommunity = community[idx];
                double bestGain = 0;
                int currentCommunity = community[idx];

                // Sum of weights from i to nodes in community c
                Map<Integer, Double> d = new HashMap<>();
                for (int e = 0; e < neighbours.get(idx).size(); e++) {
                    int j = neighbours.get(idx).get(e);
                    double w = weights.get(idx).get(e);
                    int jc = community[j];
                    d.merge(jc, w, Double::sum);
                }

                // Pre-compute currentSigmaIn removals
                double k_i = k[idx];
                // d_{i, currentCommunity} = weights to current community (minus self)
                double d_i_current = d.getOrDefault(currentCommunity, 0.0);

                // Compute removal contribution: ΔQ_remove = -[ (sigmaIn - d_i_current)/(2m) -
                //   ((sigmaTot - k_i)/(2m))^2 + (sigmaIn/(2m) - (sigmaTot/(2m))^2 - (k_i/(2m))^2) ]
                // The standard formulation picks the best neighbouring community by computing
                // the gain of moving i into c. The simplified update gain used here is:
                //   gain = d_{i,c}/m - (sigmaTot[c] * k_i) / (2 m^2)
                // compared against the gain of staying.

                // Candidate communities (the ones neighbouring i)
                for (int c : d.keySet()) {
                    if (c == currentCommunity) continue;
                    double d_i_c = d.get(c);
                    double gainRemove = d_i_current - (sigmaTot[currentCommunity] * k_i) / (2 * m * m);
                    double gainAdd = d_i_c - (sigmaTot[c] * k_i) / (2 * m * m);
                    double gain = gainAdd - gainRemove;
                    if (gain > bestGain + 1e-12) {
                        bestGain = gain;
                        bestCommunity = c;
                    }
                }

                if (bestCommunity != currentCommunity) {
                    // Move i from currentCommunity -> bestCommunity
                    sigmaIn[currentCommunity] -= 2 * d_i_current; // edges from i inside current community
                    sigmaTot[currentCommunity] -= k_i;
                    double d_i_best = d.getOrDefault(bestCommunity, 0.0);
                    sigmaIn[bestCommunity] += 2 * d_i_best;
                    sigmaTot[bestCommunity] += k_i;
                    community[idx] = bestCommunity;
                    improved = true;
                }
            }
        }

        // Renumber communities to be 0..k-1 contiguous (since some may be empty after moves)
        Map<Integer, Integer> remap = new HashMap<>();
        int nextId = 0;
        for (int i = 0; i < n; i++) {
            Integer mapped = remap.get(community[i]);
            if (mapped == null) {
                remap.put(community[i], nextId);
                community[i] = nextId;
                nextId++;
            } else {
                community[i] = mapped;
            }
        }
        // Apply back to graph nodes
        for (GraphNode node : graph.getNodes()) {
            int i = indexOf.get(node.getId());
            node.setCluster(community[i]);
        }
        return remap.size();
    }
}
