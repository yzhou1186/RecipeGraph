package com.zt.recipegraph.graph;

/**
 * A directed edge in the recipe graph.
 *
 * <p>An edge connects an OUTPUT port of a producing recipe (from) to an INPUT port of a
 * consuming recipe (to); both ports carry the SAME material, identified by
 * {@link #keyId} (the material's stable AEKey id — see {@link Port}). The edge therefore
 * represents "recipe A outputs material X → recipe B consumes material X". All edges
 * sharing a {@link #keyId} are logically associated: hovering any X port highlights them.
 */
public final class GraphEdge {
    private final GraphNode from;
    private final GraphNode to;
    /** Material AEKey id flowing through this edge (matches a Port.keyId on both sides). */
    private final String keyId;

    public GraphEdge(GraphNode from, GraphNode to, String keyId) {
        this.from = from;
        this.to = to;
        this.keyId = keyId;
    }

    public GraphNode getFrom() {
        return from;
    }

    public GraphNode getTo() {
        return to;
    }

    /** Material key id carried by this edge (same as the linked ports' keyId). */
    public String getKeyId() {
        return keyId;
    }
}
