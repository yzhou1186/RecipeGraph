package com.zt.recipegraph.layout;

import com.zt.recipegraph.RecipeGraphConfig;
import com.zt.recipegraph.RecipeGraphMod;
import com.zt.recipegraph.graph.GraphEdge;
import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
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
 * <p>Step 2 - module identity = Louvain communities, NEVER glued together: boxes are
 * exactly the Louvain clusters (plus singletons for unclustered nodes). Modules mutually
 * reachable in a directed SCC are NOT merged — in dense modpacks even a small ring SCC
 * ties together modules from completely different product lines via byproduct loops, so
 * SCC merging produced boxes full of unrelated items (storage cells + QIO + 充能棒 + 天枢
 * in one box). Rings are handled without merging: the product-anchored layering flags
 * loop-return edges and routes them via bottom rails. The "small ring stays in one box"
 * rule survives at the NODE level — splitOversizeBoxes() emits node-level SCCs (rings
 * WITHIN one community) atomically. Oversize boxes (large Louvain communities) are split
 * into consecutive chunks of at most RecipeGraphConfig.maxModuleSize() recipes — the
 * same number the UI shows in a box title.</p>
 *
 * <p>Step 3 - product-anchored layering: dense modpacks tie almost every module into one
 * giant SCC through byproduct loops, so module out-degree is never 0. Layer 0 anchors are
 * the PRODUCT side: modules containing a final-product recipe (a recipe node with no
 * outgoing edge) and genuine out-degree-0 sinks outside rings. Ring-exit nodes are NOT
 * anchored (splitting turns intra-box edges into inter-box edges, which made artificial
 * exits explode into dozens of false anchors). A reverse (consumer → producer) DFS rooted
 * at those anchors flags loop-return edges; longest-path relaxation over the remaining
 * edges places every box — final products FAR LEFT, raw-material sources RIGHT — with
 * unreachable recycler modules placed beyond all product chains. The layer count is
 * entirely data-driven. Y ordering comes from barycenter sweeps with per-layer overlap
 * resolution.</p>
 *
 * <p>Step 4 - module internals: each box gets an INDEPENDENT local layout — its own
 * longest-path column ranks, never inheriting the external meta layer. Inputs enter on
 * the right edge, outputs leave on the left edge; internal cycle edges (byproduct loops)
 * route along the box's own top/bottom border and never leave it.</p>
 *
 * <p>Step 5 - global edge routing: forward cross-module edges run as horizontal/vertical
 * polylines through the vertical channel between the two boxes (producer's LEFT output
 * port → consumer's RIGHT input port; producer sits RIGHT of consumer in the
 * product-anchored layout), each edge on its own track to avoid overlaps. Loop-return /
 * byproduct edges (railPairs) route along the bottom rail below all boxes.</p>
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

    /** Cross-module edge pairs ("producerModule|consumerModule") routed as bottom rails:
     *  feedback/loop-return edges and anything the layering proves non-forward. */
    private final Set<String> railPairs = new HashSet<>();

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

        // ===== 2. module identity = Louvain communities (NEVER glued together) =====
        // Module boxes are exactly the Louvain clusters (plus singletons for unclustered
        // nodes). Louvain already decides which recipes belong together by edge density;
        // merging modules that happen to be mutually reachable (a directed SCC) overrides
        // that decision and mixes unrelated tech lines — in dense modpacks even a SMALL
        // ring SCC ties together modules from completely different product lines via
        // byproduct loops, so an SCC merge produced boxes full of unrelated items
        // (e.g. storage cells + QIO + 充能棒 + 天枢 in one box). Rings are handled WITHOUT
        // merging: the anchor-rooted layering in sugiyama() flags loop-return edges and
        // routes them via bottom rails. The "小环固定" rule survives at the NODE level —
        // splitOversizeBoxes() emits node-level SCCs (rings WITHIN one community)
        // atomically so a small internal ring is never cut across chunk boundaries.
        int cap = RecipeGraphConfig.maxModuleSize();

        // ===== 3. internal column plan for every box (also feeds the oversize splitter) =====
        Map<Integer, InternalPlan> plans = new HashMap<>();
        for (ModuleBox b : boxes.values()) plans.put(b.id, planInternal(b));

        // ===== 3.5 split oversize boxes into chunks of at most `cap` recipes =====
        // Naturally-occurring large Louvain communities are split here so every box
        // stays within the recipe-count cap. Node-level SCCs (rings WITHIN one
        // community) smaller than the cap stay atomic — a small internal ring is
        // never cut across chunk boundaries.
        splitOversizeBoxes(boxes, plans, cap);

        // ===== 3.6 renumber boxes to 0..N-1 (split leaves negative ids) =====
        renumberBoxes(boxes);

        // ===== 3.7 final internal plan + box size (plans are keyed by the new ids) =====
        plans.clear();
        for (ModuleBox b : boxes.values()) {
            InternalPlan p = planInternal(b);
            plans.put(b.id, p);
            int cols = Math.max(1, p.maxRank + 1);
            b.maxX = PAD_X * 2.0 + cols * COL_GAP;
            b.maxY = TITLE_H + PAD_Y * 2.0 + p.totalHeight;
            b.minX = 0;
            b.minY = 0;
        }

        // ===== 4. rebuild the meta digraph on the final modules =====
        Map<Integer, List<Integer>> metaAdj = buildMetaAdjacency();

        // ===== 5. Sugiyama on the meta-graph (layering also computes railPairs:
        //         feedback/loop-return edges routed via bottom rails) =====
        sugiyama(metaAdj);

        // ===== 6. place nodes inside their module rectangles =====
        for (ModuleBox b : boxes.values()) {
            placeInternal(b, plans.get(b.id));
        }

        // ===== 6.5 post-layout AABB collision separation =====
        // Runs after intra-box placement but before edge routing so edge anchors
        // use the separated positions. Iteration count adapts to graph size: small
        // graphs converge in a few passes, large graphs get more (capped so a huge
        // network never stalls the layout thread).
        int nodeCount = graph.getNodes().size();
        int aabbIterations = Math.max(40, Math.min(200, 40 + nodeCount / 10));
        separateNodes(graph.getNodes(), aabbIterations);

        // ===== 7. route every pattern edge (railPairs from layering → bottom rails) =====
        routeEdges();

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

    /**
     * Splits every box holding more than {@code cap} recipes into consecutive chunks of at
     * most {@code cap} recipes, following the box's internal (column, row) flow order. The
     * emission units are node-level SCCs on the box's internal edges: a unit smaller than
     * the cap is emitted atomically (a small internal ring is never cut in half); bigger
     * units are cut like ordinary nodes. Chunk boxes get fresh (negative) ids which the
     * following renumber step makes contiguous again.
     */
    private void splitOversizeBoxes(Map<Integer, ModuleBox> boxes, Map<Integer, InternalPlan> plans, int cap) {
        List<ModuleBox> oversize = new ArrayList<>();
        for (ModuleBox b : boxes.values()) {
            if (b.nodes.size() > cap) oversize.add(b);
        }
        if (oversize.isEmpty()) return;

        int freshId = -1_000_000;
        for (ModuleBox b : oversize) {
            InternalPlan plan = plans.get(b.id);
            List<List<GraphNode>> units = emissionUnits(b, plan, cap);
            // deterministic (col, row) flow order by the unit's first node
            units.sort((u, v) -> {
                GraphNode a = u.get(0);
                GraphNode c = v.get(0);
                int cmp = Integer.compare(plan.col(a.getId()), plan.col(c.getId()));
                return cmp != 0 ? cmp : Integer.compare(plan.row(a.getId()), plan.row(c.getId()));
            });

            // greedy pack units into chunks of at most cap recipes
            List<List<GraphNode>> chunks = new ArrayList<>();
            List<GraphNode> cur = new ArrayList<>();
            for (List<GraphNode> unit : units) {
                if (!cur.isEmpty() && cur.size() + unit.size() > cap) {
                    chunks.add(cur);
                    cur = new ArrayList<>();
                }
                cur.addAll(unit);
            }
            if (!cur.isEmpty()) chunks.add(cur);

            boxes.remove(b.id);
            for (List<GraphNode> chunk : chunks) {
                ModuleBox nb = new ModuleBox(freshId--);
                nb.nodes.addAll(chunk);
                for (GraphNode n : chunk) n.setCluster(nb.id);
                boxes.put(nb.id, nb);
            }
        }
    }

    /**
     * Emission units of one box: node-level Tarjan SCCs over the box's internal edges.
     * Components smaller than {@code cap} stay atomic; components of {@code cap} or more
     * explode into individual nodes so they can be cut by the packer.
     */
    private List<List<GraphNode>> emissionUnits(ModuleBox box, InternalPlan plan, int cap) {
        List<GraphNode> members = box.nodes;
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < members.size(); i++) idx.put(members.get(i).getId(), i);

        Map<Integer, List<Integer>> adj = new LinkedHashMap<>();
        for (int i = 0; i < members.size(); i++) adj.put(i, new ArrayList<>());
        for (GraphEdge e : graph.getEdges()) {
            if (e.getFrom().getCluster() != box.id || e.getTo().getCluster() != box.id) continue;
            Integer a = idx.get(e.getFrom().getId());
            Integer c = idx.get(e.getTo().getId());
            if (a == null || c == null || a.intValue() == c.intValue()) continue;
            adj.get(a).add(c);
        }

        List<List<GraphNode>> units = new ArrayList<>();
        for (List<Integer> scc : tarjanSCC(adj)) {
            if (scc.size() < cap) {
                List<GraphNode> unit = new ArrayList<>(scc.size());
                for (int i : scc) unit.add(members.get(i));
                units.add(unit);
            } else {
                for (int i : scc) units.add(List.of(members.get(i)));
            }
        }
        return units;
    }

    /** Renumbers boxes to contiguous ids 0..N-1 (stable order by old id) and rewrites clusters. */
    private static void renumberBoxes(Map<Integer, ModuleBox> boxes) {
        List<ModuleBox> ordered = new ArrayList<>(boxes.values());
        ordered.sort(Comparator.comparingInt(b -> b.id));
        boxes.clear();
        int next = 0;
        for (ModuleBox b : ordered) {
            ModuleBox nb = new ModuleBox(next);
            nb.nodes.addAll(b.nodes);
            for (GraphNode n : nb.nodes) n.setCluster(next);
            boxes.put(next, nb);
            next++;
        }
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

    private void sugiyama(Map<Integer, List<Integer>> metaAdj) {
        Map<Integer, ModuleBox> boxes = result.boxes;
        railPairs.clear();
        if (boxes.size() == 1) {
            ModuleBox only = boxes.values().iterator().next();
            only.minX = MARGIN;
            only.minY = MARGIN;
            only.maxX += MARGIN;
            only.maxY += MARGIN;
            return;
        }

        // === layer assignment: PRODUCT-SIDE anchors at layer 0 (far LEFT) ===
        // Dense modpacks tie almost every module into one giant SCC via byproduct loops,
        // so module out-degree is never 0; seeding reverse Kahn on DFS-back-stripped
        // sinks lands on arbitrary ring-tail points and piles them all onto the left
        // column. Instead layer 0 anchors on the genuine product side:
        //   (a) a module that contains a FINAL-PRODUCT recipe (a recipe NODE with no
        //       outgoing edge — the user's literal 出度为0 配方);
        //   (c) a genuine sink outside rings (out-degree 0 and not in a multi-module SCC).
        // Ring EXIT nodes (a ring member feeding something outside its SCC) are NOT
        // anchored: the anchor-rooted reverse DFS reaches them at layer >= 1, which is
        // exactly right — they sit one column to the RIGHT of the products they feed.
        // Anchoring every ring exit was wrong in two ways: (1) it put dozens of ring
        // nodes on the far-left column, and (2) oversize SPLITTING turns formerly
        // intra-box edges into inter-box edges, which multiplies artificial "exits"
        // (observed: 45 false anchors / 48 boxes in column 0 after a split, vs 6 real
        // final-product modules). Feedback edges are found by a DFS rooted at the
        // anchors, walking REVERSE edges (consumer → producer): an edge back into a
        // grey ancestor is a loop return. Rooting the DFS at products (rather than an
        // arbitrary global DFS) makes the resulting DAG span the whole SCC from the
        // product side.
        Map<Integer, Integer> layer = new HashMap<>();
        Set<Integer> anchors = new HashSet<>();

        // (a) final-product recipes
        Set<String> producerIds = new HashSet<>();
        for (GraphEdge e : graph.getEdges()) producerIds.add(e.getFrom().getId());
        for (GraphNode n : graph.getNodes()) {
            if (!producerIds.contains(n.getId()) && boxes.containsKey(n.getCluster())) {
                anchors.add(n.getCluster());
            }
        }

        // SCC membership (for the genuine-sink rule; ring-exit nodes are intentionally
        // NOT anchored — see comment above)
        List<List<Integer>> sccs = tarjanSCC(metaAdj);
        Set<Integer> multi = new HashSet<>();
        for (List<Integer> comp : sccs) {
            if (comp.size() > 1) multi.addAll(comp);
        }

        // reverse adjacency: consumer module -> producer modules (sorted for a stable DFS)
        Map<Integer, List<Integer>> rev = new LinkedHashMap<>();
        for (Integer id : boxes.keySet()) rev.put(id, new ArrayList<>());
        for (Map.Entry<Integer, List<Integer>> en : metaAdj.entrySet()) {
            int u = en.getKey();
            for (int v : en.getValue()) {
                rev.computeIfAbsent(v, k -> new ArrayList<>()).add(u);
            }
        }
        rev.values().forEach(l -> l.sort(Integer::compare));

        // (c) genuine sinks outside rings
        Set<Integer> rootSet = new LinkedHashSet<>(anchors);
        for (Integer id : boxes.keySet()) {
            if (metaAdj.getOrDefault(id, List.of()).isEmpty() && !multi.contains(id)) {
                rootSet.add(id);
            }
        }
        List<Integer> roots = new ArrayList<>(rootSet);
        roots.sort(Integer::compare);

        // anchor-rooted DFS over reverse edges -> feedback (loop-return) pairs "u|v"
        Set<String> feedback = new HashSet<>();
        Map<Integer, Integer> color = new HashMap<>(); // 0 white, 1 grey, 2 black
        for (int root : roots) {
            if (color.getOrDefault(root, 0) != 0) continue;
            Deque<Integer> dn = new ArrayDeque<>();
            Deque<java.util.Iterator<Integer>> di = new ArrayDeque<>();
            color.put(root, 1);
            dn.push(root);
            di.push(rev.getOrDefault(root, List.of()).iterator());
            while (!dn.isEmpty()) {
                int v = dn.peek();
                java.util.Iterator<Integer> it = di.peek();
                boolean advanced = false;
                while (it.hasNext()) {
                    int u = it.next(); // u produces for v
                    int cu = color.getOrDefault(u, 0);
                    if (cu == 0) {
                        color.put(u, 1);
                        dn.push(u);
                        di.push(rev.getOrDefault(u, List.of()).iterator());
                        advanced = true;
                        break;
                    } else if (cu == 1) {
                        feedback.add(u + "|" + v); // forward edge u->v loops back
                    }
                }
                if (advanced) continue;
                color.put(v, 2);
                dn.pop();
                di.pop();
            }
        }

        // pass 1: longest-path relaxation from the roots over non-feedback reverse edges.
        // Anchors stay frozen at layer 0. The non-feedback graph is a DAG (feedback
        // removed exactly the DFS back edges), so the queue relaxation terminates.
        Deque<Integer> q = new ArrayDeque<>();
        Set<Integer> inQ = new HashSet<>();
        for (int r : roots) {
            layer.put(r, 0);
            q.add(r);
            inQ.add(r);
        }
        while (!q.isEmpty()) {
            int v = q.poll();
            inQ.remove(v);
            for (int u : rev.getOrDefault(v, List.of())) {
                if (feedback.contains(u + "|" + v)) continue;
                if (anchors.contains(u)) continue; // anchors never leave layer 0
                int cand = layer.get(v) + 1;
                if (cand > layer.getOrDefault(u, -1)) {
                    layer.put(u, cand);
                    if (inQ.add(u)) q.add(u);
                }
            }
        }
        int maxLayer = layer.values().stream().max(Integer::compare).orElse(0);

        // pass 2: modules unreachable from the product side (pure byproduct recyclers)
        // get penalty layers beyond every product chain. BFS shortest hops from the
        // boundary — first assignment wins, so product-path modules are never pushed right.
        List<Integer> unreached = new ArrayList<>();
        for (Integer id : boxes.keySet()) {
            if (!layer.containsKey(id)) unreached.add(id);
        }
        if (!unreached.isEmpty()) {
            int pen = maxLayer + 1;
            Set<Integer> uset = new HashSet<>(unreached);
            Deque<int[]> q2 = new ArrayDeque<>(); // {module, hopsFromBoundary}
            for (Map.Entry<Integer, List<Integer>> en : metaAdj.entrySet()) {
                int u = en.getKey();
                if (!uset.contains(u)) continue;
                for (int v : en.getValue()) {
                    if (!feedback.contains(u + "|" + v) && !uset.contains(v)) {
                        layer.put(u, pen);
                        q2.add(new int[]{u, 0});
                        break;
                    }
                }
            }
            while (!q2.isEmpty()) {
                int[] cur = q2.poll();
                int v = cur[0], d = cur[1];
                for (int u : rev.getOrDefault(v, List.of())) {
                    if (feedback.contains(u + "|" + v)) continue;
                    if (uset.contains(u) && !layer.containsKey(u)) {
                        layer.put(u, pen + d + 1);
                        q2.add(new int[]{u, d + 1});
                    }
                }
            }
            for (int u : unreached) layer.putIfAbsent(u, pen);
            maxLayer = layer.values().stream().max(Integer::compare).orElse(0);
        }

        // rail pairs: feedback edges plus anything the layering proves non-forward
        // (same-column or backward pairs = loop returns routed via bottom rails).
        railPairs.addAll(feedback);
        int railCount = feedback.size();
        for (Map.Entry<Integer, List<Integer>> en : metaAdj.entrySet()) {
            Integer lu = layer.get(en.getKey());
            for (int v : en.getValue()) {
                Integer lv = layer.get(v);
                if (lu == null || lv == null || lu <= lv) {
                    if (railPairs.add(en.getKey() + "|" + v)) railCount++;
                }
            }
        }

        // diagnostics: every layer-0 box must be a product anchor (leftButNotAnchor=[])
        int nodeSinkRecipes = (int) graph.getNodes().stream()
                .filter(n -> !producerIds.contains(n.getId())).count();
        List<Integer> layer0 = new ArrayList<>();
        List<Integer> leftButNotAnchor = new ArrayList<>();
        for (Integer id : boxes.keySet()) {
            if (layer.get(id) == 0) {
                layer0.add(id);
                if (!rootSet.contains(id)) leftButNotAnchor.add(id);
            }
        }
        RecipeGraphMod.LOGGER.info(
            "[RecipeGraph] layering: boxes={} columns={} sccs>1={} (sizes {}) nodeSinkRecipes={} rails={} layer0Boxes={} | leftButNotAnchor={}",
            boxes.size(), maxLayer + 1,
            sccs.stream().filter(c -> c.size() > 1).count(),
            sccs.stream().filter(c -> c.size() > 1).map(c -> String.valueOf(c.size())).toList(),
            nodeSinkRecipes, railCount, layer0.size(), leftButNotAnchor);

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
            int lf = layer.get(en.getKey());
            for (Integer toId : en.getValue()) {
                if (railPairs.contains(en.getKey() + "|" + toId)) continue; // rails: no dummies, no barycentre
                Item to = modItems.get(toId);
                if (to == null) continue;
                int lt = layer.get(toId);
                // surviving edges are strictly forward (lf > lt): producer to the right
                Item prev = from;
                for (int l = lt + 1; l < lf; l++) {
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

        // --- x per layer (direct: layer 0 = sinks on the LEFT, no mirroring) ---
        double[] flowX = new double[layers.size()]; // left-edge offset
        for (int l = 1; l < layers.size(); l++) {
            double maxPrev = 0;
            for (Item it : layers.get(l - 1)) maxPrev = Math.max(maxPrev, it.dummy ? DUMMY_H : it.box.getWidth());
            flowX[l] = flowX[l - 1] + maxPrev + LAYER_GAP;
        }

        // --- commit module rectangles (direct: layer 0 on the left) ---
        for (Map.Entry<Integer, Item> en : modItems.entrySet()) {
            Item it = en.getValue();
            ModuleBox b = it.box;
            double yy = y.get(it);
            int l = layer.get(en.getKey());
            // capture size BEFORE writing: getWidth/getHeight read maxX-minX/maxY-minY
            double w = b.getWidth();
            double h = b.getHeight();
            b.minX = MARGIN + flowX[l];
            b.maxX = b.minX + w;
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
    // Step 7: edge routing (producer RIGHT → consumer LEFT; loop returns via bottom rails)
    // ===================================================================

    private void routeEdges() {
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
            // port (card RIGHT edge), Y aligned to the material's own port row.
            // X is offset OUTWARD past the port chip so the line never runs through the
            // material icon/label (chips extend ~18px for the icon and ~76px for the full
            // chip from the card edge). Intra-module card gaps are tight (COL_GAP leaves
            // just enough room for facing labels) -> clear only the icon there; inter-box
            // gaps are wide -> clear the whole chip (icon + label).
            String key = e.getKeyId();
            double chipClear = (mu == mv) ? 20.0 : 80.0;
            double sx = u.portX(true) - chipClear;  // output chips extend LEFT
            double sy = u.portY(true, key, PORT_ROW);
            double ex = v.portX(false) + chipClear; // input chips extend RIGHT
            double ey = v.portY(false, key, PORT_ROW);

            double[] pts;
            if (mu == mv) {
                if (v.x > u.x + 1) {
                    // internal cycle (target sits to the RIGHT of source = backward in internal RTL flow):
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
            } else if (railPairs.contains(mu + "|" + mv)) {
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
                // normal cross-module flow (producer RIGHT → consumer LEFT):
                // channel right of the target (between the two boxes), one track per edge
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
     * <p>The spatial hash keeps the pair tests near O(kn): cell keys encode (cx, cy) reversibly
     * as {@code (cx << 32) | (cy & 0xFFFFFFFF)} and only the cell itself plus its four forward
     * neighbours are scanned, so every unordered cell pair is visited exactly once.
     *
     * <p>Iteration count adapts to graph size: {@code clamp(40 + n/10, 40, 200)} passes where
     * n is the recipe count — small graphs converge quickly, large graphs get more passes.
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
        // forward half-neighbourhood: self + {(1,0),(0,1),(1,1),(1,-1)}
        int[][] offs = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {1, -1}};

        for (int iter = 0; iter < iterations; iter++) {
            // Rebuild spatial hash each pass — nodes move
            grid.clear();
            for (int i = 0; i < n; i++) {
                GraphNode node = arr[i];
                long cx = (long) Math.floor(node.x / cellSize);
                long cy = (long) Math.floor(node.y / cellSize);
                grid.computeIfAbsent((cx << 32) | (cy & 0xFFFFFFFFL), k -> new ArrayList<>()).add(i);
            }

            double[] total = {0, 0};

            for (Map.Entry<Long, List<Integer>> en : grid.entrySet()) {
                long key = en.getKey();
                long cx = key >> 32;
                long cy = (key << 32) >> 32; // sign-extend the low 32 bits back

                for (int[] off : offs) {
                    boolean sameCell = off[0] == 0 && off[1] == 0;
                    List<Integer> other = sameCell
                        ? en.getValue()
                        : grid.get(((cx + off[0]) << 32) | ((cy + off[1]) & 0xFFFFFFFFL));
                    if (other == null) continue;

                    List<Integer> here = en.getValue();
                    for (int i : here) {
                        GraphNode ni = arr[i];
                        for (int j : other) {
                            if (sameCell && j <= i) continue; // same cell: each pair once
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
                                total[0] += push;
                            } else {
                                double sign = (ni.y < nj.y) ? -1 : 1;
                                double push = oy * 0.5;
                                ni.y += sign * push;
                                nj.y -= sign * push;
                                total[1] += push;
                            }
                        }
                    }
                }
            }

            // Early exit if the layout has stabilised
            if (total[0] < 0.1 && total[1] < 0.1) break;
        }
    }
}
