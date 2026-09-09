package com.zt.recipegraph.layout;

import com.zt.recipegraph.graph.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A business module: the visual rectangle that groups a set of recipe nodes.
 *
 * Modules are exactly the Louvain phase-1 communities (plus singleton boxes for
 * unclustered nodes); communities larger than the configured cap are split into
 * chunks. The meta-graph Sugiyama pass places the boxes with raw materials on the
 * right and final products on the left.
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
