package com.zt.recipegraph.layout;

import com.zt.recipegraph.graph.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A business module (Meta-Node): the visual rectangle that groups a set of material nodes.
 *
 * Modules are produced by the hierarchical layout: Louvain clusters are condensed by SCC
 * (cross-module cycles merge into super modules) and placed by the meta-graph Sugiyama pass
 * with raw materials on the right and products on the left.
 */
public final class ModuleBox {
    public final int id;
    public final List<GraphNode> nodes = new ArrayList<>();

    // Rectangle (world coordinates)
    public double minX, minY, maxX, maxY;

    public ModuleBox(int id) {
        this.id = id;
    }

    public double getWidth() {
        return maxX - minX;
    }

    public double getHeight() {
        return maxY - minY;
    }

    public double getCenterX() {
        return (minX + maxX) * 0.5;
    }

    public double getCenterY() {
        return (minY + maxY) * 0.5;
    }

    public boolean contains(double x, double y) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }
}
