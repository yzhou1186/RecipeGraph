package com.zt.recipegraph.layout;

import com.zt.recipegraph.RecipeGraphConfig;
import com.zt.recipegraph.graph.GraphEdge;
import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two-layer hierarchical layout ("meta-graph + module sub-graphs"):
 *
 * <p>Step 1 - business modules: Louvain cluster ids (computed server side) define the
 * business boxes.</p>
 *
 * <p>Step 2 - SCC condensation with an ADAPTIVE merge cap: Tarjan SCCs are computed over
 * the directed module graph. Small graphs are condensed fully (every SCC merged, meta
 * graph a guaranteed DAG, no cycle ever leaves a box); on large graphs the cap grows with
 * sqrt(scale) — small/medium SCCs merge into super modules, but an SCC that would produce
 * an unreadably huge box stays as separate modules and its cross-module back edges route
 * via bottom rails. Node-level cycles inside a merged SCC are enclosed in one box. Small
 * networks produce few meta nodes and short chains; large networks (500+ recipes) with
 * long dependency chains automatically produce more/larger super modules and more layers.</p>
 *
 * <p>Step 3 - dynamic longest-path layering: the meta DAG layers come from the longest
 * path (Kahn propagation): layer 0 = raw materials (no incoming flow), layer(v) =
 * max(layer(u)) + 1 over all u→v. The layer count is entirely data-driven — nothing is
 * hard-coded; the flow goes right → left (material boxes on the RIGHT, product boxes on
 * the LEFT). Y ordering comes from barycenter sweeps with per-layer overlap resolution.</p>
 *
 * <p>Step 4 - module internals: each box (including super modules) gets an INDEPENDENT
 * local layout — its own longest-path column ranks, never inheriting the external meta
 * layer. Inputs enter on the right edge, outputs leave on the left edge; internal cycle
 * edges (byproduct loops) route along the box's own top/bottom border and never leave it.</p>
 *
 * <p>Step 5 - global edge routing: cross-module edges run as horizontal/vertical polylines
 * through the vertical channel right of the target module (upstream module's LEFT output
 * port → downstream module's RIGHT input port), each edge on its own track to avoid
 * overlaps. (The bottom-rail back-edge path remains only as a defensive fallback; a fully
 * condensed DAG never produces back edges.)</p>
 */
public final class HierarchicalLayout {

    // --- geometry constants (world units = screen px at zoom 1) ---
    private static final double CARD_W = 120;     // recipe card width
    private static final double CARD_PAD_Y = 10;  // recipe card top/bottom inner padding
    private static final double PORT_ROW = 20;    // vertical distance between stacked material ports
    private static final int COL_GAP = 280;       // horizontal distance between recipe columns (cards + facing port chips)
    private static final double NODE_V_GAP = 16;  // vertical gap between stacked cards in a column
    private static final int PAD_X = 26;          // module inner left/right padding
    private static final int PAD_Y = 14;          // module inner top/bottom padding
    private static final int TITLE_H = 18;        // module title strip height
    private static final int LAYER_GAP = 150;     // horizontal gap between meta layers
    private static final int ROW_GAP_META = 42;   // vertical gap between modules in a layer
    private static final int DUMMY_H = 14;        // height of dummy nodes for long meta edges
    private static final int MARGIN = 130;        // free space around everything (rails live here)

    /** Card height for a recipe node: enough rows to stack its busiest port side. */
    public static double cardHeight(GraphNode n) {
        int rows = Math.max(1, Math.max(n.inputs.size(), n.outputs.size()));
        return CARD_PAD_Y * 2 + rows * PORT_ROW;
    }

    private final PatternGraph graph;
    private final LayoutResult result = new LayoutResult();

    public HierarchicalLayout(PatternGraph graph) {
        this.graph = graph;
    }

    public LayoutResult run() {
        if (graph.isEmpty()) return result;

        // ===== 1. modules: group by cluster, singletons for unclustered nodes =====
        Map<Integer, ModuleBox> boxes = result.boxes;
        int singletonId = -1;
        for (GraphNode n : graph.getNodes()) {
            // recipe card dimensions are driven by its port counts (fixed width)
            n.width = CARD_W;
            n.height = cardHeight(n);
            int id = n.getCluster();
            if (id < 0) id = singletonId--;
            ModuleBox b = boxes.computeIfAbsent(id, ModuleBox::new);
            b.nodes.add(n);
        }

        // ===== 2. SCC condensation of the directed module graph (ADAPTIVE cap) =====
        // Small graphs (<=8 modules / <=60 nodes): every SCC merges fully -> the meta
        // graph is a guaranteed DAG and no cycle ever leaves a box. Large graphs: the cap
        // scales with sqrt(scale) so small/medium SCCs still merge, but a mega SCC that
        // would produce an unreadably huge super module stays apart — its back edges are
        // detected (findBackEdges) and routed via bottom rails.
        Map<Integer, List<Integer>> metaAdj = buildMetaAdjacency();
        List<List<Integer>> sccs = tarjanSCC(metaAdj);
        int totalModules = boxes.size();
        long totalNodes = graph.getNodeCount();
        // moduleCap: scaled adaptive value, but capped by user config maxModuleSize
        int dynamicModuleCap = totalModules <= 8
            ? totalModules
            : Math.max(4, (int) Math.round(Math.sqrt(totalModules) * 1.6));
        int moduleCap = Math.min(dynamicModuleCap, RecipeGraphConfig.maxModuleSize());
        long nodeCap = totalNodes <= 60
            ? totalNodes
            : Math.max(60, Math.round(Math.sqrt(totalNodes) * 9.0));
        for (List<Integer> scc : sccs) {
            if (scc.size() < 2) continue;
            long mergedNodes = 0;
            for (int id : scc) {
                ModuleBox b0 = boxes.get(id);
                if (b0 != null) mergedNodes += b0.nodes.size();
            }
            if (scc.size() > moduleCap || mergedNodes > nodeCap) continue; // leave big SCCs apart
            int target = scc.stream().min(Integer::compare).orElse(scc.get(0));
            ModuleBox tb = boxes.get(target);
            for (int id : scc) {
                if (id == target) continue;
                ModuleBox b = boxes.get(id);
                tb.nodes.addAll(b.nodes);
                for (GraphNode n : b.nodes) n.setCluster(target);
                boxes.remove(id);
            }
            // nodes may appear twice if Louvain ids overlapped - dedupe
            LinkedHashSet<GraphNode> dedup = new LinkedHashSet<>(tb.nodes);
            tb.nodes.clear();
            tb.nodes.addAll(dedup);
        }

        // ===== 3. size every module from its internal columnar layout =====
        Map<Integer, InternalPlan> plans = new HashMap<>();
        for (ModuleBox b : boxes.values()) {
            InternalPlan p = planInternal(b);
            plans.put(b.id, p);
            int cols = Math.max(1, p.maxRank + 1);
            b.maxX = PAD_X * 2.0 + cols * COL_GAP;
            b.maxY = TITLE_H + PAD_Y * 2.0 + p.totalHeight;
            b.minX = 0;
            b.minY = 0;
        }

        // ===== 4. rebuild the meta digraph on the condensed modules (it is a DAG now) =====
        // findBackEdges is kept as defensive machinery: with full SCC condensation no
        // module-level cycle remains, so the returned set is empty and no rails are used.
        metaAdj = buildMetaAdjacency();
        Set<String> metaBack = findBackEdges(metaAdj);

        // ===== 5. Sugiyama on the meta-graph =====
        sugiyama(metaAdj, metaBack);

        // ===== 6. place nodes inside their module rectangles =====
        for (ModuleBox b : boxes.values()) {
            placeInternal(b, plans.get(b.id));
        }

        // ===== 6.5 post-layout AABB collision separation =====
        // Runs after intra-box placement but before edge routing so edge anchors
        // use the separated positions. Controlled by RecipeGraphConfig.aabbIterations().
        separateNodes(graph.getNodes(), RecipeGraphConfig.aabbIterations());

        // ===== 7. route every pattern edges =====
        routeEdges(metaBack);

        // world bounds normalisation: shift everything into positive space with margin
        normalise();

        graph.recomputeBounds();
        return result;
    }

    // ===================================================================
    // Step 1/2 helpers
    // ===================================================================

    private Map<Integer, List<Integer>> buildMetaAdjacency() {
        Map<Integer, List<Integer>> adj = new LinkedHashMap<>();
        for (ModuleBox b : result.boxes.values()) adj.put(b.id, new ArrayList<>());
        for (GraphEdge e : graph.getEdges()) {
            int a = e.getFrom().getCluster();
            int c = e.getTo().getCluster();
            if (a == c) continue;
            List<Integer> l = adj.get(a);
            if (l != null && !l.contains(c)) l.add(c);
        }
        return adj;
    }

    /** Iterative Tarjan SCC. Returns list of components (each a list of module ids). */
    private static List<List<Integer>> tarjanSCC(Map<Integer, List<Integer>> adj) {
        Map<Integer, Integer> index = new HashMap<>();
        Map<Integer, Integer> low = new HashMap<>();
        Map<Integer, Boolean> onStack = new HashMap<>();
        Deque<Integer> stack = new ArrayDeque<>();
        List<List<Integer>> out = new ArrayList<>();
        int[] counter = {0};

        for (Integer root : adj.keySet()) {
            if (index.containsKey(root)) continue;
            Deque<Integer> nodeStack = new ArrayDeque<>();
            Deque<java.util.Iterator<Integer>> iterStack = new ArrayDeque<>();
            nodeStack.push(root);
            iterStack.push(adj.get(root).iterator());
            index.put(root, counter[0]);
            low.put(root, counter[0]);
            counter[0]++;
            stack.push(root);
            onStack.put(root, true);

            while (!nodeStack.isEmpty()) {
                int v = nodeStack.peek();
                java.util.Iterator<Integer> it = iterStack.peek();
                if (it.hasNext()) {
                    int w = it.next();
                    if (!index.containsKey(w)) {
                        index.put(w, counter[0]);
                        low.put(w, counter[0]);
                        counter[0]++;
                        stack.push(w);
                        onStack.put(w, true);
                        nodeStack.push(w);
                        iterStack.push(adj.get(w).iterator());
                    } else if (onStack.getOrDefault(w, false)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                } else {
                    nodeStack.pop();
                    iterStack.pop();
                    if (!nodeStack.isEmpty()) {
                        int parent = nodeStack.peek();
                        low.put(parent, Math.min(low.get(parent), low.get(v)));
                    }
                    if (low.get(v).equals(index.get(v))) {
                        List<Integer> comp = new ArrayList<>();
                        int w;
                        do {
                            w = stack.pop();
                            onStack.put(w, false);
                            comp.add(w);
                        } while (w != v);
                        out.add(comp);
                    }
                }
            }
        }
        return out;
    }

    /** DFS cycle removal: returns the set of "from|to" meta edges flagged as back edges. */
    private static Set<String> findBackEdges(Map<Integer, List<Integer>> adj) {
        Set<String> back = new HashSet<>();
        Map<Integer, Integer> color = new HashMap<>(); // 0 white 1 gray 2 black
        for (Integer root : adj.keySet()) {
            if (color.getOrDefault(root, 0) != 0) continue;
            Deque<Integer> nodes = new ArrayDeque<>();
            Deque<java.util.Iterator<Integer>> iters = new ArrayDeque<>();
            nodes.push(root);
            iters.push(adj.get(root).iterator());
            color.put(root, 1);
            while (!nodes.isEmpty()) {
                int v = nodes.peek();
                java.util.Iterator<Integer> it = iters.peek();
                if (it.hasNext()) {
                    int w = it.next();
                    int cw = color.getOrDefault(w, 0);
                    if (cw == 0) {
                        color.put(w, 1);
                        nodes.push(w);
                        iters.push(adj.get(w).iterator());
                    } else if (cw == 1) {
                        back.add(v + "|" + w);
                    }
                } else {
                    color.put(v, 2);
                    nodes.pop();
                    iters.pop();
                }
            }
        }
        return back;
    }

    // ===================================================================
    // Step 3: internal plan (columns/rows and cycle marking) per module
    // ===================================================================

    /** Column/row plan of one module's internal orthogonal layout. */
    private static final class InternalPlan {
        final Map<String, int[]> colRow = new HashMap<>(); // node id -> [col, row]
        final Set<String> internalBack = new HashSet<>();  // "fromId|toId" cycle edges
        int maxRank;
        int maxRows;
        /** Tallest column's stacked card height incl. inter-card gaps (drives box height). */
        double totalHeight;

        int col(String nodeId) {
            int[] cr = colRow.get(nodeId);
            return cr == null ? 0 : cr[0];
        }

        int row(String nodeId) {
            int[] cr = colRow.get(nodeId);
            return cr == null ? 0 : cr[1];
        }
    }

    private InternalPlan planInternal(ModuleBox box) {
        InternalPlan plan = new InternalPlan();
        List<GraphNode> members = box.nodes;

        // internal directed edges
        List<GraphNode[]> internal = new ArrayList<>();
        Map<String, List<String>> succ = new HashMap<>();
        Map<String, List<String>> pred = new HashMap<>();
        for (GraphEdge e : graph.getEdges()) {
            GraphNode a = e.getFrom();
            GraphNode c = e.getTo();
            if (a.getCluster() != box.id || c.getCluster() != box.id) continue;
            if (a.getId().equals(c.getId())) continue;
            internal.add(new GraphNode[]{a, c});
            succ.computeIfAbsent(a.getId(), k -> new ArrayList<>()).add(c.getId());
            pred.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add(a.getId());
        }

        // internal cycle removal (DFS gray marking)
        Set<String> iBack = plan.internalBack;
        Map<String, Integer> col2 = new HashMap<>();
        for (GraphNode n : members) {
            if (col2.getOrDefault(n.getId(), 0) != 0) continue;
            Deque<String> ns = new ArrayDeque<>();
            Deque<java.util.Iterator<String>> is = new ArrayDeque<>();
            ns.push(n.getId());
            is.push(succ.getOrDefault(n.getId(), List.of()).iterator());
            col2.put(n.getId(), 1);
            while (!ns.isEmpty()) {
                String v = ns.peek();
                java.util.Iterator<String> it = is.peek();
                if (it.hasNext()) {
                    String w = it.next();
                    int cw = col2.getOrDefault(w, 0);
                    if (cw == 0) {
                        col2.put(w, 1);
                        ns.push(w);
                        is.push(succ.getOrDefault(w, List.of()).iterator());
                    } else if (cw == 1) {
                        iBack.add(v + "|" + w);
                    }
                } else {
                    col2.put(v, 2);
                    ns.pop();
                    is.pop();
                }
            }
        }

        // rank: longest path over non-back internal edges (Kahn topological order)
        Map<String, Integer> inDeg = new HashMap<>();
        for (GraphNode n : members) inDeg.put(n.getId(), 0);
        for (GraphNode[] ec : internal) {
            String u = ec[0].getId();
            String v = ec[1].getId();
            if (iBack.contains(u + "|" + v)) continue;
            inDeg.merge(v, 1, Integer::sum);
        }
        Map<String, Integer> rank = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (GraphNode n : members) {
            if (inDeg.get(n.getId()) == 0) {
                rank.put(n.getId(), 0);
                queue.add(n.getId());
            }
        }
        while (!queue.isEmpty()) {
            String u = queue.poll();
            for (String v : succ.getOrDefault(u, List.of())) {
                if (iBack.contains(u + "|" + v)) continue;
                rank.put(v, Math.max(rank.getOrDefault(v, 0), rank.get(u) + 1));
                inDeg.merge(v, -1, Integer::sum);
                if (inDeg.get(v) == 0) queue.add(v);
            }
        }
        // cycle-only members (all edges back): rank 0
        for (GraphNode n : members) rank.putIfAbsent(n.getId(), 0);

        // columns
        Map<Integer, List<String>> cols = new LinkedHashMap<>();
        for (GraphNode n : members) {
            int r = rank.get(n.getId());
            plan.maxRank = Math.max(plan.maxRank, r);
            cols.computeIfAbsent(r, k -> new ArrayList<>()).add(n.getId());
        }

        // barycenter ordering inside columns (2 forward + 1 backward sweep)
        List<List<String>> ordered = new ArrayList<>();
        for (int c = 0; c <= plan.maxRank; c++) ordered.add(cols.getOrDefault(c, new ArrayList<>()));
        for (int sweep = 0; sweep < 2; sweep++) {
            for (int c = 1; c < ordered.size(); c++) {
                List<String> col = ordered.get(c);
                List<String> ref = ordered.get(c - 1);
                col.sort((x, y) -> Double.compare(bary(pred.getOrDefault(x, List.of()), ref),
                        bary(pred.getOrDefault(y, List.of()), ref)));
            }
            if (ordered.size() < 2) break;
            for (int c = ordered.size() - 2; c >= 0; c--) {
                List<String> col = ordered.get(c);
                List<String> ref = ordered.get(c + 1);
                col.sort((x, y) -> Double.compare(bary(succ.getOrDefault(x, List.of()), ref),
                        bary(succ.getOrDefault(y, List.of()), ref)));
            }
        }
        // freeze plan + measure the tallest stacked column (cards vary in height with
        // their port counts; rows are stacked cumulatively at placement time)
        Map<Integer, Double> colHeight = new HashMap<>();
        int maxRows = 1;
        for (int c = 0; c < ordered.size(); c++) {
            List<String> col = ordered.get(c);
            maxRows = Math.max(maxRows, col.size());
            double stack = 0;
            for (int rI = 0; rI < col.size(); rI++) {
                String id = col.get(rI);
                plan.colRow.put(id, new int[]{c, rI});
                GraphNode n = nodeById(members, id);
                stack += (n != null ? n.height : 40) + (rI > 0 ? NODE_V_GAP : 0);
            }
            colHeight.put(c, stack);
        }
        plan.maxRows = maxRows;
        plan.totalHeight = colHeight.values().stream().mapToDouble(Double::doubleValue).max().orElse(40);
        return plan;
    }

    private static GraphNode nodeById(List<GraphNode> members, String id) {
        for (GraphNode n : members) if (n.getId().equals(id)) return n;
        return null;
    }

    private static double bary(List<String> ids, List<String> refCol) {
        if (ids.isEmpty() || refCol.isEmpty()) return Double.MAX_VALUE / 4;
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < refCol.size(); i++) idx.put(refCol.get(i), i);
        double sum = 0;
        int n = 0;
        for (String id : ids) {
            Integer i = idx.get(id);
            if (i != null) {
                sum += i;
                n++;
            }
        }
        return n == 0 ? Double.MAX_VALUE / 4 : sum / n;
    }

    // ===================================================================
    // Step 5: meta Sugiyama (layering + barycenter y placement)
    // ===================================================================

    private void sugiyama(Map<Integer, List<Integer>> metaAdj, Set<String> metaBack) {
        Map<Integer, ModuleBox> boxes = result.boxes;
        if (boxes.size() == 1) {
            ModuleBox only = boxes.values().iterator().next();
            only.minX = MARGIN;
            only.minY = MARGIN;
            only.maxX += MARGIN;
            only.maxY += MARGIN;
            return;
        }

        // --- layer assignment: longest path over the DAG (ignore back edges) ---
        Map<Integer, Integer> layer = new HashMap<>();
        Map<Integer, Integer> inDeg = new HashMap<>();
        for (Integer id : boxes.keySet()) inDeg.put(id, 0);
        for (Map.Entry<Integer, List<Integer>> en : metaAdj.entrySet()) {
            for (Integer to : en.getValue()) {
                if (!metaBack.contains(en.getKey() + "|" + to)) {
                    inDeg.merge(to, 1, Integer::sum);
                }
            }
        }
        Deque<Integer> q = new ArrayDeque<>();
        for (Map.Entry<Integer, Integer> en : inDeg.entrySet()) {
            if (en.getValue() == 0) {
                layer.put(en.getKey(), 0);
                q.add(en.getKey());
            }
        }
        while (!q.isEmpty()) {
            int u = q.poll();
            for (Integer v : metaAdj.getOrDefault(u, List.of())) {
                if (metaBack.contains(u + "|" + v)) continue;
                layer.put(v, Math.max(layer.getOrDefault(v, 0), layer.get(u) + 1));
                inDeg.merge(v, -1, Integer::sum);
                if (inDeg.get(v) == 0) q.add(v);
            }
        }
        // defensive: in a fully condensed DAG every module is reached from a layer-0 source
        // (following predecessors must terminate at an in-degree-zero raw-material module),
        // so this fallback never fires — it only guards against pathological data.
        for (Integer id : boxes.keySet()) layer.putIfAbsent(id, 0);
        int maxLayer = layer.values().stream().max(Integer::compare).orElse(0);

        // --- layered structure with dummy nodes for edges spanning >1 layer ---
        List<List<Item>> layers = new ArrayList<>();
        for (int l = 0; l <= maxLayer; l++) layers.add(new ArrayList<>());
        Map<Integer, Item> modItems = new LinkedHashMap<>();
        for (Map.Entry<Integer, ModuleBox> en : boxes.entrySet()) {
            Item it = Item.module(en.getKey(), en.getValue());
            modItems.put(en.getKey(), it);
            layers.get(layer.get(en.getKey())).add(it);
        }
        int dummySeq = 0;
        Map<Item, List<Item>> neigh = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> en : metaAdj.entrySet()) {
            Item from = modItems.get(en.getKey());
            for (Integer toId : en.getValue()) {
                boolean back = metaBack.contains(en.getKey() + "|" + toId);
                if (back) continue; // back edges are rails, not part of layer flow
                Item to = modItems.get(toId);
                if (to == null) continue;
                int lf = layer.get(en.getKey());
                int lt = layer.get(toId);
                Item prev = from;
                for (int l = lf + 1; l < lt; l++) {
                    Item d = Item.dummy(dummySeq++);
                    layers.get(l).add(d);
                    neigh.computeIfAbsent(prev, k -> new ArrayList<>()).add(d);
                    neigh.computeIfAbsent(d, k -> new ArrayList<>()).add(prev);
                    prev = d;
                }
                neigh.computeIfAbsent(prev, k -> new ArrayList<>()).add(to);
                neigh.computeIfAbsent(to, k -> new ArrayList<>()).add(prev);
            }
        }

        // --- initial y: order index ---
        Map<Item, Double> y = new HashMap<>();
        for (List<Item> l : layers) {
            for (int i = 0; i < l.size(); i++) y.put(l.get(i), (double) i);
        }

        java.util.function.ToDoubleFunction<Item> heightOf = it -> {
            if (it.dummy) return DUMMY_H;
            return Math.max(14, it.box.getHeight());
        };

        // --- barycenter sweeps + overlap resolution ---
        for (int sweep = 0; sweep < 4; sweep++) {
            boolean down = (sweep % 2) == 0;
            for (int li = down ? 1 : layers.size() - 2; down ? li < layers.size() : li >= 0; li += down ? 1 : -1) {
                List<Item> l = layers.get(li);
                l.sort((a, b2) -> Double.compare(avgY(neigh.getOrDefault(a, List.of()), y),
                        avgY(neigh.getOrDefault(b2, List.of()), y)));
                resolveOverlaps(l, y, heightOf);
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (List<Item> l : layers) {
                for (Item it : l) {
                    List<Item> ns = neigh.getOrDefault(it, List.of());
                    if (!ns.isEmpty()) {
                        double med = avgY(ns, y);
                        y.put(it, y.get(it) * 0.4 + med * 0.6);
                    }
                }
                resolveOverlaps(l, y, heightOf);
            }
        }

        // --- x per layer (accumulate widths leftward in flow order, then mirror) ---
        // Flow order: layer 0 = raw materials ends up on the RIGHT.
        double[] flowX = new double[layers.size()]; // left-edge offset in flow direction
        for (int l = 1; l < layers.size(); l++) {
            double maxPrev = 0;
            for (Item it : layers.get(l - 1)) maxPrev = Math.max(maxPrev, it.dummy ? DUMMY_H : it.box.getWidth());
            flowX[l] = flowX[l - 1] + maxPrev + LAYER_GAP;
        }
        double lastMax = 0;
        for (Item it : layers.get(layers.size() - 1)) {
            lastMax = Math.max(lastMax, it.dummy ? DUMMY_H : it.box.getWidth());
        }
        double totalW = flowX[layers.size() - 1] + lastMax;

        // --- commit module rectangles (mirror: layer 0 on the right) ---
        for (Map.Entry<Integer, Item> en : modItems.entrySet()) {
            Item it = en.getValue();
            ModuleBox b = it.box;
            double yy = y.get(it);
            int l = layer.get(en.getKey());
            double rightEdge = totalW - flowX[l];
            // capture size BEFORE writing: getWidth/getHeight read maxX-minX/maxY-minY
            double w = b.getWidth();
            double h = b.getHeight();
            b.maxX = MARGIN + rightEdge;
            b.minX = MARGIN + rightEdge - w;
            b.minY = MARGIN + yy - h / 2.0;
            b.maxY = b.minY + h;
        }
    }

    private static double avgY(List<Item> items, Map<Item, Double> y) {
        if (items.isEmpty()) return Double.MAX_VALUE / 4;
        double s = 0;
        for (Item it : items) s += y.getOrDefault(it, 0.0);
        return s / items.size();
    }

    private void resolveOverlaps(List<Item> layer, Map<Item, Double> y, java.util.function.ToDoubleFunction<Item> heightOf) {
        layer.sort((a, b) -> Double.compare(y.getOrDefault(a, 0.0), y.getOrDefault(b, 0.0)));
        double prevEnd = Double.NEGATIVE_INFINITY;
        for (Item it : layer) {
            double h = heightOf.applyAsDouble(it);
            double top = y.getOrDefault(it, 0.0) - h / 2.0;
            double newTop = Math.max(top, prevEnd + ROW_GAP_META * 0.4);
            y.put(it, newTop + h / 2.0);
            prevEnd = newTop + h;
        }
    }

    /** One slot in a meta layer: a real module or a dummy waypoint of a long edge. */
    private static final class Item {
        final boolean dummy;
        final int id;
        final ModuleBox box;

        private Item(boolean dummy, int id, ModuleBox box) {
            this.dummy = dummy;
            this.id = id;
            this.box = box;
        }

        static Item module(int id, ModuleBox box) {
            return new Item(false, id, box);
        }

        static Item dummy(int id) {
            return new Item(true, id, null);
        }
    }

    // ===================================================================
    // Step 6: node placement inside module rectangles (right-to-left columns)
    // ===================================================================

    private void placeInternal(ModuleBox b, InternalPlan plan) {
        double contentTop = b.minY + TITLE_H + PAD_Y;
        int cols = Math.max(1, plan.maxRank + 1);
        // X by longest-path column (col 0 = raw inputs on the box's RIGHT edge)
        for (GraphNode n : b.nodes) {
            int col = plan.col(n.getId());
            n.x = b.minX + PAD_X + (cols - col - 0.5) * COL_GAP;
        }
        // Y by cumulative stacking inside each column (cards grow with their port count)
        Map<Integer, double[]> cursor = new HashMap<>(); // col -> [nextTopY]
        List<GraphNode> ordered = new ArrayList<>(b.nodes);
        ordered.sort((a, c) -> {
            int cmp = Integer.compare(plan.col(a.getId()), plan.col(c.getId()));
            return cmp != 0 ? cmp : Integer.compare(plan.row(a.getId()), plan.row(c.getId()));
        });
        for (GraphNode n : ordered) {
            int col = plan.col(n.getId());
            double[] top = cursor.computeIfAbsent(col, k -> new double[]{contentTop});
            n.y = top[0] + n.height / 2.0;
            top[0] += n.height + NODE_V_GAP;
        }
    }

    // ===================================================================
    // Step 7: edge routing (right-to-left flow)
    // ===================================================================

    private void routeEdges(Set<String> metaBack) {
        Map<Integer, ModuleBox> boxes = result.boxes;
        int bottomTrack = 0;
        Map<Integer, Integer> perTarget = new HashMap<>();

        List<GraphEdge> edges = graph.getEdges();
        for (int ei = 0; ei < edges.size(); ei++) {
            GraphEdge e = edges.get(ei);
            GraphNode u = e.getFrom();
            GraphNode v = e.getTo();
            int mu = u.getCluster();
            int mv = v.getCluster();
            ModuleBox bu = boxes.get(mu);
            ModuleBox bv = boxes.get(mv);
            if (bu == null || bv == null) continue;

            // anchor points: producer's OUTPUT port (card LEFT edge) -> consumer's INPUT
            // port (card RIGHT edge), Y aligned to the material's own port row
            String key = e.getKeyId();
            double sx = u.portX(true);
            double sy = u.portY(true, key, PORT_ROW);
            double ex = v.portX(false);
            double ey = v.portY(false, key, PORT_ROW);

            double[] pts;
            if (mu == mv) {
                if (v.x > u.x + 1) {
                    // internal cycle (target sits to the RIGHT of source = backward in RTL flow):
                    // route along the box's top/bottom border, never leaving the box
                    boolean useTop = (ei & 1) == 0;
                    double railY = useTop
                        ? bu.minY + TITLE_H + 6
                        : bu.maxY - PAD_Y - 6;
                    pts = new double[]{
                        sx, sy,
                        sx, railY,
                        ex, railY,
                        ex, ey
                    };
                } else if (Math.abs(sy - ey) < 1) {
                    // forward flow: exit source LEFT port, enter target RIGHT port
                    pts = new double[]{sx, sy, ex, ey};
                } else {
                    double midX = (sx + ex) / 2.0;
                    pts = new double[]{
                        sx, sy,
                        midX, sy,
                        midX, ey,
                        ex, ey
                    };
                }
            } else if (metaBack.contains(mu + "|" + mv)) {
                // byproduct return flow: bottom rail around the canvas, enter target from the right
                int track = bottomTrack++;
                double railY = result.getMaxY() + 50 + track * 14.0;
                double chX = bv.maxX + LAYER_GAP * 0.5;
                pts = new double[]{
                    sx, sy,
                    sx, railY,
                    chX, railY,
                    chX, ey,
                    ex, ey
                };
                result.backEdgeIndices.add(ei);
            } else {
                // normal cross-module flow (right → left): channel right of the target,
                // one track per edge to avoid overlaps
                int k = perTarget.getOrDefault(mv, 0);
                perTarget.put(mv, k + 1);
                double offset = ((k + 1) / 2) * 9.0 * ((k % 2 == 0) ? -1 : 1);
                double chX = bv.maxX + LAYER_GAP * 0.5 + offset;
                if (Math.abs(sy - ey) < 1) {
                    pts = new double[]{sx, sy, ex, ey};
                } else {
                    pts = new double[]{sx, sy, chX, sy, chX, ey, ex, ey};
                }
            }
            result.routes.put(ei, pts);
        }
    }

    // ===================================================================
    // final normalisation
    // ===================================================================

    private void normalise() {
        double minX = result.getMinX();
        double minY = result.getMinY();
        double sx = MARGIN - minX;
        double sy = MARGIN - minY;
        if (Math.abs(sx) < 0.5 && Math.abs(sy) < 0.5) return;
        for (ModuleBox b : result.boxes.values()) {
            b.minX += sx;
            b.maxX += sx;
            b.minY += sy;
            b.maxY += sy;
        }
        for (GraphNode n : graph.getNodes()) {
            n.x += sx;
            n.y += sy;
        }
        for (Map.Entry<Integer, double[]> en : result.routes.entrySet()) {
            double[] p = en.getValue();
            for (int i = 0; i < p.length; i += 2) {
                p[i] += sx;
                p[i + 1] += sy;
            }
        }
    }

    // ===================================================================
    // Post-layout AABB collision separation
    // ===================================================================

    /**
     * Iteratively pushes overlapping recipe cards apart in world-space. Runs {@code iterations}
     * passes; more passes give cleaner separation at the cost of layout time. Since nodes are
     * already placed inside their module boxes with cumulative stacking (no intra-box overlaps
     * are produced by placeInternal), this mainly cleans up small floating-point overlaps and
     * any cross-module boundary nudges caused by box placement rounding.
     *
     * <p>Configurable via {@link RecipeGraphConfig#aabbIterations()} (range 50–200, default 50).
     */
    private static void separateNodes(java.util.Collection<GraphNode> nodes, int iterations) {
        int n = nodes.size();
        if (n < 2 || iterations <= 0) return;

        GraphNode[] arr = nodes.toArray(new GraphNode[0]);

        // Precompute AABB half-extents (cards vary in height with port count)
        double[] halfW = new double[n];
        double[] halfH = new double[n];
        for (int i = 0; i < n; i++) {
            GraphNode node = arr[i];
            halfW[i] = node.width * 0.5 + 2.0;   // 2px extra margin for comfort
            halfH[i] = node.height * 0.5 + 2.0;
        }

        // Spatial hash grid for O(kn) instead of O(n²) per iteration
        double cellSize = Math.max(CARD_W, PORT_ROW * 4);
        java.util.HashMap<Long, List<Integer>> grid = new java.util.HashMap<>();

        for (int iter = 0; iter < iterations; iter++) {
            // Rebuild spatial hash each pass — nodes move
            grid.clear();
            for (int i = 0; i < n; i++) {
                GraphNode node = arr[i];
                long cx = (long) Math.floor(node.x / cellSize);
                long cy = (long) Math.floor(node.y / cellSize);
                long key = cx * 73856093L ^ cy * 19349663L;
                grid.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }

            double totalDx = 0, totalDy = 0;

            for (Map.Entry<Long, List<Integer>> en : grid.entrySet()) {
                long key = en.getKey();
                long cx = key / 73856093L;
                long cy = key % 73856093L;

                // Check this cell and 8 neighbours
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        long nk = (cx + dx) * 73856093L ^ (cy + dy) * 19349663L;
                        List<Integer> other = grid.get(nk);
                        if (other == null) continue;

                        List<Integer> here = en.getValue();
                        for (int i : here) {
                            GraphNode ni = arr[i];
                            for (int j : other) {
                                if (j <= i) continue; // avoid double processing
                                GraphNode nj = arr[j];

                                // AABB overlap test
                                double ox = halfW[i] + halfW[j] - Math.abs(ni.x - nj.x);
                                double oy = halfH[i] + halfH[j] - Math.abs(ni.y - nj.y);
                                if (ox <= 0 || oy <= 0) continue; // no overlap

                                // Push apart along the axis of smaller overlap
                                if (ox < oy) {
                                    double sign = (ni.x < nj.x) ? -1 : 1;
                                    double push = ox * 0.5;
                                    ni.x += sign * push;
                                    nj.x -= sign * push;
                                    totalDx += push;
                                } else {
                                    double sign = (ni.y < nj.y) ? -1 : 1;
                                    double push = oy * 0.5;
                                    ni.y += sign * push;
                                    nj.y -= sign * push;
                                    totalDy += push;
                                }
                            }
                        }
                    }
                }
            }

            // Early exit if the layout has stabilised
            if (totalDx < 0.1 && totalDy < 0.1) break;
        }
    }
}
