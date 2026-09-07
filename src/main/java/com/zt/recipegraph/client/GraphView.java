package com.zt.recipegraph.client;

import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;
import com.zt.recipegraph.layout.HierarchicalLayout;
import com.zt.recipegraph.layout.LayoutResult;
import com.zt.recipegraph.layout.ModuleBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side view state for the graph terminal screen.
 *
 * The layout itself now runs on the client: the server only sends the collected pattern
 * graph (nodes + edges + Louvain cluster ids), and {@link HierarchicalLayout} produces
 * module rectangles, node positions and orthogonal edge routes (right-to-left flow).
 */
public final class GraphView {
    private PatternGraph graph;
    private LayoutResult layout = new LayoutResult();
    /** Sorted module list, rebuilt only on relayout — the renderer reads it every frame. */
    private List<ModuleBox> boxesCache = List.of();

    // Camera
    public double panX = 0;
    public double panY = 0;
    public double zoom = 1.0;

    private boolean showClusters = true;

    // Viewport dimensions used for auto-fit zooming
    private double fitW, fitH;

    /** Records the canvas size so the next graph can be auto-fitted to the screen. */
    public void setViewport(double w, double h) {
        this.fitW = w;
        this.fitH = h;
    }

    public void setGraph(PatternGraph g) {
        this.graph = g;
        relayout();
    }

    /** Runs the hierarchical layout and fits the camera to the graph. */
    public void relayout() {
        if (graph == null || graph.isEmpty()) {
            layout = new LayoutResult();
            boxesCache = List.of();
            return;
        }
        layout = new HierarchicalLayout(graph).run();
        graph.recomputeBounds();
        rebuildBoxesCache();

        double cx = (graph.getMinX() + graph.getMaxX()) * 0.5;
        double cy = (graph.getMinY() + graph.getMaxY()) * 0.5;
        panX = -cx;
        panY = -cy;
        if (fitW > 0 && fitH > 0) {
            double gw = Math.max(1.0, graph.getMaxX() - graph.getMinX());
            double gh = Math.max(1.0, graph.getMaxY() - graph.getMinY());
            double z = Math.min(fitW / (gw + 260.0), fitH / (gh + 260.0));
            zoom = Math.max(0.05, Math.min(1.2, z));
        }
    }

    /** Centers the camera on a node (used by edge-click navigation). */
    public void focusOn(GraphNode n) {
        panX = -n.x;
        panY = -n.y;
    }

    public PatternGraph getGraph() {
        return graph;
    }

    public LayoutResult getLayout() {
        return layout;
    }

    /** Module boxes in stable id order (for drawing). Cached; rebuilt on relayout only. */
    public List<ModuleBox> getBoxes() {
        return boxesCache;
    }

    private void rebuildBoxesCache() {
        List<ModuleBox> out = new ArrayList<>(layout.boxes.values());
        out.sort((a, b) -> Integer.compare(a.id, b.id));
        boxesCache = out;
    }

    public boolean isShowClusters() {
        return showClusters;
    }

    public void toggleClusters() {
        showClusters = !showClusters;
    }

    public void zoomBy(double factor, double centerX, double centerY) {
        double newZoom = Math.max(0.05, Math.min(8.0, zoom * factor));
        // Keep the point under the cursor stationary during zoom
        double worldX = (centerX / zoom) - panX;
        double worldY = (centerY / zoom) - panY;
        zoom = newZoom;
        panX = (centerX / zoom) - worldX;
        panY = (centerY / zoom) - worldY;
    }

    public void panBy(double dxScreen, double dyScreen) {
        panX += dxScreen / zoom;
        panY += dyScreen / zoom;
    }
}
