package com.zt.recipegraph.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pattern graph. Contains nodes and edges, and supports indexing for fast lookup by id.
 *
 * The graph is an undirected (treated as undirected by the layout algorithm) graph for the
 * purpose of clustering. The {@link GraphEdge} preserves directionality for rendering.
 */
public final class PatternGraph {
    private final Map<String, GraphNode> nodesById = new LinkedHashMap<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    // Adjacency list keyed by node id for fast neighbour lookup (undirected: includes both ways)
    private final Map<String, List<GraphNode>> adjacency = new HashMap<>();
    // Weight map between nodes, keyed by "idA|idB" with idA < idB (lexicographically) for symmetry.
    private final Map<String, Double> edgeWeights = new HashMap<>();

    private double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
    private double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;

    public GraphNode getOrCreateNode(String id, String label) {
        return getOrCreateNode(id, label, id);
    }

    public GraphNode getOrCreateNode(String id, String label, String displayId) {
        return nodesById.computeIfAbsent(id, k -> new GraphNode(id, label, displayId));
    }

    public GraphNode getNode(String id) {
        return nodesById.get(id);
    }

    public Collection<GraphNode> getNodes() {
        return Collections.unmodifiableCollection(nodesById.values());
    }

    public int getNodeCount() {
        return nodesById.size();
    }

    public List<GraphEdge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    public int getEdgeCount() {
        return edges.size();
    }

    public List<GraphNode> getNeighbours(String id) {
        return adjacency.getOrDefault(id, Collections.emptyList());
    }

    public double getEdgeWeight(String idA, String idB) {
        String key = key(idA, idB);
        return edgeWeights.getOrDefault(key, 0.0);
    }

    private static String key(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /** Adds an edge between from and to. Updates adjacency and weight index. */
    public void addEdge(GraphNode from, GraphNode to, String patternId) {
        if (from == null || to == null || from.getId().equals(to.getId())) {
            // Ignore null or self-loops to keep the layout well-behaved.
            return;
        }
        GraphEdge edge = new GraphEdge(from, to, patternId);
        edges.add(edge);

        String key = key(from.getId(), to.getId());
        edgeWeights.merge(key, 1.0, Double::sum);

        adjacency.computeIfAbsent(from.getId(), k -> new ArrayList<>()).add(to);
        adjacency.computeIfAbsent(to.getId(), k -> new ArrayList<>()).add(from);

        from.outDegree++;
        to.inDegree++;
    }

    /** Resets the cluster id of every node to -1. Useful before running a new clustering pass. */
    public void resetClusters() {
        for (GraphNode n : nodesById.values()) {
            n.cluster = -1;
        }
    }

    /** Recomputes bounding box from current node positions. Call after layout is done. */
    public void recomputeBounds() {
        minX = Double.POSITIVE_INFINITY; maxX = Double.NEGATIVE_INFINITY;
        minY = Double.POSITIVE_INFINITY; maxY = Double.NEGATIVE_INFINITY;
        for (GraphNode n : nodesById.values()) {
            if (n.x < minX) minX = n.x;
            if (n.x > maxX) maxX = n.x;
            if (n.y < minY) minY = n.y;
            if (n.y > maxY) maxY = n.y;
        }
    }

    public double getMinX() { return minX; }
    public double getMaxX() { return maxX; }
    public double getMinY() { return minY; }
    public double getMaxY() { return maxY; }

    public boolean isEmpty() {
        return nodesById.isEmpty();
    }
}
