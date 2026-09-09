package com.zt.recipegraph.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /** Module rectangles, keyed by the final module id after renumbering (0..N-1). */
    public final Map<Integer, ModuleBox> boxes = new LinkedHashMap<>();

    /** Orthogonal (or straight) polyline per edge index: [x0,y0, x1,y1, ...]. */
    public final Map<Integer, double[]> routes = new LinkedHashMap<>();

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

    /**
     * Bounds of everything actually drawn: module rectangles AND every routed edge
     * polyline. Bottom rail-return edges run at {@code maxY + 50 + track*14} and channel
     * segments stick out past box sides, so the box-only bounds (and the node-center
     * bounds in PatternGraph) crop them on a freshly auto-fitted screen. Used for camera
     * fitting; the box-only getters above stay for layout internals that position rails
     * relative to the boxes. Returns {@code [minX, minY, maxX, maxY]}.
     */
    public double[] contentBounds() {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (ModuleBox b : boxes.values()) {
            minX = Math.min(minX, b.minX);
            minY = Math.min(minY, b.minY);
            maxX = Math.max(maxX, b.maxX);
            maxY = Math.max(maxY, b.maxY);
        }
        for (double[] p : routes.values()) {
            for (int i = 0; i + 1 < p.length; i += 2) {
                minX = Math.min(minX, p[i]);
                minY = Math.min(minY, p[i + 1]);
                maxX = Math.max(maxX, p[i]);
                maxY = Math.max(maxY, p[i + 1]);
            }
        }
        return new double[]{minX, minY, maxX, maxY};
    }
}
