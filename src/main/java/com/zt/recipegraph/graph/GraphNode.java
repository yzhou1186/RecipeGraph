package com.zt.recipegraph.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the pattern graph. A node represents one RECIPE (pattern): it owns a set of
 * material ports — {@link #outputs} on the LEFT (the pattern's primary product) and
 * {@link #inputs} on the RIGHT (consumed materials). Materials are not nodes themselves;
 * each occurrence of a material is a local port copy (see {@link Port}).
 *
 * <p>The id uniquely identifies the recipe (e.g. "recipe:12"); the label is the human
 * readable name of its product used in the UI.
 *
 * Layout positions (x, y) and card dimensions (width/height) are kept here so the graph
 * can be cheaply rendered and re-laid out.
 */
public final class GraphNode {
    private final String id;
    private final String label;

    // Material ports (local copies of AEKeys). Outputs = LEFT side, inputs = RIGHT side.
    public final List<Port> outputs = new ArrayList<>();
    public final List<Port> inputs = new ArrayList<>();

    // Layout state (mutated by the layout algorithm)
    public double x;
    public double y;
    /** Card rectangle size in world units (set by the layout from port counts). */
    public double width = 120;
    public double height = 40;

    // Cluster id assigned by the community detection algorithm.
    public int cluster = -1;

    public GraphNode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public List<Port> getInputs() {
        return inputs;
    }

    public List<Port> getOutputs() {
        return outputs;
    }

    /** First output port's material key id (the recipe product), or null. */
    public String primaryKeyId() {
        return outputs.isEmpty() ? null : outputs.get(0).keyId;
    }

    /**
     * World-space Y of the port carrying {@code keyId} on the given side. Ports are stacked
     * vertically and centred on the node middle. Returns the node centre Y when not found.
     */
    public double portY(boolean outputSide, String keyId, double portRow) {
        List<Port> side = outputSide ? outputs : inputs;
        int idx = Port.indexOf(side, keyId);
        if (idx < 0) return y;
        return y + (idx - (side.size() - 1) / 2.0) * portRow;
    }

    /** World X where ports of a side attach (outputs = left edge, inputs = right edge). */
    public double portX(boolean outputSide) {
        return x + (outputSide ? -width / 2.0 : width / 2.0);
    }

    public int getCluster() {
        return cluster;
    }

    public void setCluster(int cluster) {
        this.cluster = cluster;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GraphNode)) return false;
        return id.equals(((GraphNode) o).id);
    }

    @Override
    public String toString() {
        return "Node{" + id + ", cluster=" + cluster + "}";
    }
}
