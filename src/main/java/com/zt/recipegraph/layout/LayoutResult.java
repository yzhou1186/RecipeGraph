package com.zt.recipegraph.layout;

import com.zt.recipegraph.graph.GraphEdge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of the hierarchical layout: module rectangles plus per-edge polyline routes
 * in world coordinates.
 *
 * Routes are keyed by the edge's index in {@code graph.getEdges()} (stable for a given
 * graph instance). Every edge receives a route; edges without a special route get a
 * straight two-point polyline.
 */
public final class LayoutResult {

    /** Module rectangles, keyed by module id (merged cluster id). */
    public final Map<Integer, ModuleBox> boxes = new HashMap<>();

    /** Orthogonal (or straight) polyline per edge index: [x0,y0, x1,y1, ...]. */
    public final Map<Integer, double[]> routes = new HashMap<>();

    /** Edges flagged as "back edges" (byproduct return flows routed via top/bottom rails). */
    public final List<Integer> backEdgeIndices = new ArrayList<>();

    public ModuleBox boxOf(int moduleId) {
        return boxes.get(moduleId);
    }

    public double[] routeOf(int edgeIndex) {
        return routes.get(edgeIndex);
    }

    /** Global bounding box over all module rectangles. */
    public double getMinX() {
        double v = Double.POSITIVE_INFINITY;
        for (ModuleBox b : boxes.values()) v = Math.min(v, b.minX);
        return v;
    }

    public double getMinY() {
        double v = Double.POSITIVE_INFINITY;
        for (ModuleBox b : boxes.values()) v = Math.min(v, b.minY);
        return v;
    }

    public double getMaxX() {
        double v = Double.NEGATIVE_INFINITY;
        for (ModuleBox b : boxes.values()) v = Math.max(v, b.maxX);
        return v;
    }

    public double getMaxY() {
        double v = Double.NEGATIVE_INFINITY;
        for (ModuleBox b : boxes.values()) v = Math.max(v, b.maxY);
        return v;
    }

    /** Bottom rail Y for back-edge routing at the given track index (below all boxes). */
    public double bottomRail(int track) {
        return getMaxY() + 60 + track * 14.0;
    }

    /** Top rail Y for back-edge routing at the given track index (above all boxes). */
    public double topRail(int track) {
        return getMinY() - 60 - track * 14.0;
    }
}
