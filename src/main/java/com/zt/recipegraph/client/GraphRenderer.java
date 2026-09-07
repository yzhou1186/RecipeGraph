package com.zt.recipegraph.client;

import com.zt.recipegraph.graph.GraphEdge;
import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;
import com.zt.recipegraph.graph.Port;
import com.zt.recipegraph.layout.LayoutResult;
import com.zt.recipegraph.layout.ModuleBox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared graph drawing routine for the on-screen terminal view. Coordinates are mapped
 * with {@code screenX = offX + worldX * zoom}.
 *
 * <p>Nodes are RECIPE cards: a card shows the product icon/name and carries material ports
 * — OUTPUT ports on its LEFT (primary product) and INPUT ports on its RIGHT (consumed
 * materials). Each port is a small icon + name chip. The same material exists as a port on
 * every recipe that makes or consumes it: hovering one X port highlights EVERY X port and
 * all edges carrying X (bright amber, everything else dimmed). Edges are orthogonal
 * polylines routed by the layout, anchored at port coordinates.</p>
 */
public final class GraphRenderer {

    /**
     * What the cursor is currently over.
     *
     * @param node        recipe card body under the cursor (null when over a port/edge)
     * @param edge        edge index under the cursor, or -1
     * @param portKey     material key id of the hovered port, or null
     * @param portNode    recipe node owning the hovered port, or null
     * @param portOutput  true = LEFT/output port, false = RIGHT/input port (valid when portKey != null)
     */
    public record Hit(GraphNode node, int edge, String portKey, GraphNode portNode, boolean portOutput) {
        public static final Hit NONE = new Hit(null, -1, null, null, false);
    }

    private static final Map<String, ItemStack> STACK_CACHE = new HashMap<>();
    private static final Map<String, Component> NAME_CACHE = new HashMap<>();
    /** Reconstructed generic AEKeys (gases, mana, data, ...) keyed by material key id; empty = none. */
    private static final Map<String, Optional<AEKey>> KEY_CACHE = new HashMap<>();
    /** Vertical distance between stacked ports, world units (must match HierarchicalLayout.PORT_ROW). */
    private static final double PORT_ROW = 20.0;
    private static final int EDGE_HIT_PX = 6;
    private static final int CHIP_W = 74;  // horizontal hit zone of a port chip (icon + label)
    private static final int CHIP_H = 18;
    private static final int CHIP_LABEL_W = 54;

    private static final int EDGE_SAME = 0xFF4FC3F7;   // intra-module: bright cyan
    private static final int EDGE_CROSS = 0xFF2C3340;  // cross-module: subtle grey
    private static final int EDGE_HIGHLIGHT = 0xFFFFD54F; // hover/association: bright amber
    private static final int CARD_FILL = 0xEE232A38;
    private static final int DIM_ALPHA = 0x48;

    private GraphRenderer() {}

    /**
     * Draws the full graph.
     *
     * @param mouseX/mouseY pass -1 to disable hover hit-testing
     * @return what is under the cursor (port key, card node, edge index, or none)
     */
    public static Hit draw(GuiGraphics g, Font font, GraphView view,
                           double offX, double offY, double zoom,
                           int canvasX0, int canvasY0, int canvasX1, int canvasY1,
                           int mouseX, int mouseY) {
        PatternGraph graph = view.getGraph();
        if (graph == null) return Hit.NONE;
        LayoutResult layout = view.getLayout();
        List<ModuleBox> boxes = view.isShowClusters() ? view.getBoxes() : List.of();

        // 1. Module rectangles
        for (ModuleBox b : boxes) {
            int bx0 = (int) (offX + b.minX * zoom);
            int by0 = (int) (offY + b.minY * zoom);
            int bx1 = (int) (offX + b.maxX * zoom);
            int by1 = (int) (offY + b.maxY * zoom);
            if (bx1 < canvasX0 || bx0 > canvasX1 || by1 < canvasY0 || by0 > canvasY1) continue;
            int color = clusterColor(b.id);
            g.fill(bx0, by0, bx1, by1, (color & 0x00FFFFFF) | 0x16000000);
            g.renderOutline(bx0, by0, bx1 - bx0, by1 - by0, (color & 0x00FFFFFF) | 0xFF000000);
            String title = Component.translatable("recipegraph.module.title",
                    String.valueOf(b.id), String.valueOf(b.nodes.size())).getString();
            g.drawString(font, title, bx0 + 4, by0 + 3, (color & 0x00FFFFFF) | 0xFF000000);
        }

        // 2. Hover hit-test: port chips first (smallest target), then card bodies, then edges
        String hoverPortKey = null;
        GraphNode hoverPortNode = null;
        boolean hoverPortOutput = false;
        GraphNode hoveredNode = null;
        if (mouseX >= 0) {
            for (GraphNode n : graph.getNodes()) {
                int cx = (int) (offX + n.x * zoom);
                int cy = (int) (offY + n.y * zoom);
                if (cx < canvasX0 - 200 || cx > canvasX1 + 200 || cy < canvasY0 - 200 || cy > canvasY1 + 200) continue;
                // port chips: RIGHT input ports first, then LEFT output ports
                String hit = hitPort(n, n.inputs, false, offX, offY, zoom, mouseX, mouseY);
                boolean outSide = false;
                if (hit == null) {
                    hit = hitPort(n, n.outputs, true, offX, offY, zoom, mouseX, mouseY);
                    outSide = hit != null;
                }
                if (hit != null) {
                    hoverPortKey = hit;
                    hoverPortNode = n;
                    hoverPortOutput = outSide;
                    break;
                }
                // card body
                double hw = n.width * zoom / 2.0, hh = n.height * zoom / 2.0;
                if (mouseX >= cx - hw && mouseX <= cx + hw && mouseY >= cy - hh && mouseY <= cy + hh) {
                    hoveredNode = n;
                    break;
                }
            }
        }
        int hoveredEdge = -1;
        if (mouseX >= 0 && hoverPortKey == null && hoveredNode == null) {
            hoveredEdge = hitEdge(graph, layout, offX, offY, zoom, mouseX, mouseY,
                    canvasX0, canvasY0, canvasX1, canvasY1);
        }
        String hoverKey = hoverPortKey;
        boolean anyHover = hoverKey != null || hoveredNode != null || hoveredEdge >= 0;

        // 3a. CROSS-MODULE edges first (bottom layer) — these route outside boxes and
        // must not obscure intra-module edges drawn inside boxes on top
        List<GraphEdge> edges = graph.getEdges();
        for (int ei = 0; ei < edges.size(); ei++) {
            GraphEdge e = edges.get(ei);
            GraphNode a = e.getFrom();
            GraphNode b2 = e.getTo();
            boolean sameModule = (a.getCluster() == b2.getCluster() && a.getCluster() >= 0);
            if (sameModule) continue; // draw intra-module later
            drawEdge(g, font, a, b2, e, ei, edges, layout, offX, offY, zoom,
                canvasX0, canvasY0, canvasX1, canvasY1, hoverKey, hoveredNode, hoveredEdge, anyHover, false);
        }

        // 3b. INTRA-MODULE edges on top (they live inside boxes and must be visible
        // above any cross-module edges that might happen to cross over)
        for (int ei = 0; ei < edges.size(); ei++) {
            GraphEdge e = edges.get(ei);
            GraphNode a = e.getFrom();
            GraphNode b2 = e.getTo();
            boolean sameModule = (a.getCluster() == b2.getCluster() && a.getCluster() >= 0);
            if (!sameModule) continue;
            drawEdge(g, font, a, b2, e, ei, edges, layout, offX, offY, zoom,
                canvasX0, canvasY0, canvasX1, canvasY1, hoverKey, hoveredNode, hoveredEdge, anyHover, true);
        }

        // 4. Recipe cards with port chips
        for (GraphNode n : graph.getNodes()) {
            int cx = (int) (offX + n.x * zoom);
            int cy = (int) (offY + n.y * zoom);
            if (cx < canvasX0 - 200 || cx > canvasX1 + 200 || cy < canvasY0 - 200 || cy > canvasY1 + 200) continue;

            int x0 = (int) (cx - n.width * zoom / 2.0);
            int y0 = (int) (cy - n.height * zoom / 2.0);
            int x1 = (int) (cx + n.width * zoom / 2.0);
            int y1 = (int) (cy + n.height * zoom / 2.0);
            int cardBorder = (n.getCluster() >= 0) ? clusterColor(n.getCluster()) : 0xFFB0B8C0;
            g.fill(x0, y0, x1, y1, CARD_FILL);
            g.renderOutline(x0, y0, x1 - x0, y1 - y0,
                    (n == hoveredNode) ? EDGE_HIGHLIGHT : (cardBorder & 0x00FFFFFF) | 0xCC000000);

            // product icon + name at card centre
            String productId = n.primaryKeyId();
            ItemStack pStack = productId != null ? resolveStack(productId) : ItemStack.EMPTY;
            AEKey pKey = productId != null ? resolveKey(productId) : null;
            if (!pStack.isEmpty()) {
                g.renderItem(pStack, cx - 8, cy - 8);
            } else if (pKey != null && drawKey(g, cx - 8, cy - 8, pKey)) {
                // drawn by AE2's key render handler
            } else {
                g.fill(cx - 6, cy - 6, cx + 6, cy + 6, cardBorder);
                g.renderOutline(cx - 6, cy - 6, 12, 12, 0xFF000000);
            }
            String name = truncate(font, n.getLabel(), (int) (n.width * zoom) - 12);
            int tw = font.width(name);
            g.drawString(font, name, cx - tw / 2, y0 + 4, 0xFFFFFFFF);

            // port chips: inputs on the RIGHT edge, outputs on the LEFT edge
            drawChips(g, font, n, n.inputs, false, offX, offY, zoom, hoverKey, anyHover);
            drawChips(g, font, n, n.outputs, true, offX, offY, zoom, hoverKey, anyHover);
        }
        return new Hit(hoveredNode, hoveredEdge, hoverPortKey, hoverPortNode, hoverPortOutput);
    }

    /** Draws one side's port chips. Output side (left edge) or input side (right edge). */
    private static void drawChips(GuiGraphics g, Font font, GraphNode n, List<Port> ports,
                                  boolean outputSide, double offX, double offY, double zoom,
                                  String hoverKey, boolean anyHover) {
        for (int i = 0; i < ports.size(); i++) {
            Port p = ports.get(i);
            double wx = n.x + (outputSide ? -n.width / 2.0 : n.width / 2.0);
            double wy = n.y + (i - (ports.size() - 1) / 2.0) * PORT_ROW;
            int cx = (int) (offX + wx * zoom);
            int cy = (int) (offY + wy * zoom);
            boolean linked = hoverKey != null && hoverKey.equals(p.keyId);
            boolean dim = anyHover && !linked;

            int iconX = outputSide ? cx - 18 : cx + 2;
            int iconY = cy - 8;
            int textX;
            String label = truncate(font, p.keyLabel, CHIP_LABEL_W);
            if (outputSide) {
                textX = cx - 22 - font.width(label);
            } else {
                textX = cx + 20;
            }
            int textY = cy - 4;

            if (linked) {
                int zoneX0 = outputSide ? cx - CHIP_W : cx - 2;
                g.fill(zoneX0, cy - CHIP_H / 2, zoneX0 + CHIP_W, cy + CHIP_H / 2, 0x66FFD54F);
                g.renderOutline(zoneX0, cy - CHIP_H / 2, CHIP_W, CHIP_H, EDGE_HIGHLIGHT);
            }

            ItemStack stack = resolveStack(p.keyId);
            AEKey key = resolveKey(p.keyId);
            if (!stack.isEmpty()) {
                g.renderItem(stack, iconX, iconY);
            } else if (key != null && drawKey(g, iconX, iconY, key)) {
                // AE2 key icon (gas, fluid, mana, ...)
            } else {
                g.fill(iconX + 2, iconY + 2, iconX + 14, iconY + 14, linked ? EDGE_HIGHLIGHT : 0xFF8E99A8);
            }
            int textColor = dim ? 0x70A0A8B8 : (linked ? 0xFFFFD54F : 0xFFFFFFFF);
            g.drawString(font, label, textX, textY, textColor);
        }
    }

    /** Hit-test for port chips on one side; returns the linked material key id or null. */
    private static String hitPort(GraphNode n, List<Port> ports, boolean outputSide,
                                  double offX, double offY, double zoom, int mx, int my) {
        for (int i = 0; i < ports.size(); i++) {
            Port p = ports.get(i);
            double wx = n.x + (outputSide ? -n.width / 2.0 : n.width / 2.0);
            double wy = n.y + (i - (ports.size() - 1) / 2.0) * PORT_ROW;
            int cx = (int) (offX + wx * zoom);
            int cy = (int) (offY + wy * zoom);
            int zoneX0 = outputSide ? cx - CHIP_W : cx - 2;
            if (mx >= zoneX0 && mx <= zoneX0 + CHIP_W
                    && my >= cy - CHIP_H / 2 && my <= cy + CHIP_H / 2) {
                return p.keyId;
            }
        }
        return null;
    }

    /** Truncates a label to the given pixel width, adding an ellipsis when cut. */
    private static String truncate(Font font, String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "?";
        if (font.width(text) <= maxWidth) return text;
        String dots = "…";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (font.width(sb.toString() + text.charAt(i) + dots) > maxWidth) break;
            sb.append(text.charAt(i));
        }
        return sb + dots;
    }

    /**
     * Edge hit-test: minimum distance from the cursor to any polyline segment (screen space),
     * ignoring segments fully outside the canvas. Returns the edge index or -1.
     */
    private static int hitEdge(PatternGraph graph, LayoutResult layout,
                               double offX, double offY, double zoom,
                               int mx, int my,
                               int canvasX0, int canvasY0, int canvasX1, int canvasY1) {
        List<GraphEdge> edges = graph.getEdges();
        double bestDist = EDGE_HIT_PX;
        int best = -1;
        for (int ei = 0; ei < edges.size(); ei++) {
            double[] pts = layout.routeOf(ei);
            if (pts == null || pts.length < 4) continue;
            int n = pts.length / 2;
            for (int i = 0; i < n - 1; i++) {
                double x1 = offX + pts[i * 2] * zoom;
                double y1 = offY + pts[i * 2 + 1] * zoom;
                double x2 = offX + pts[i * 2 + 2] * zoom;
                double y2 = offY + pts[i * 2 + 3] * zoom;
                if (Math.max(x1, x2) < canvasX0 - 40 || Math.min(x1, x2) > canvasX1 + 40) continue;
                if (Math.max(y1, y2) < canvasY0 - 40 || Math.min(y1, y2) > canvasY1 + 40) continue;
                double d = pointSegmentDist(mx, my, x1, y1, x2, y2);
                if (d < bestDist) {
                    bestDist = d;
                    best = ei;
                }
            }
        }
        return best;
    }

    private static double pointSegmentDist(double px, double py,
                                           double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-9) {
            double ex = px - x1;
            double ey = py - y1;
            return Math.sqrt(ex * ex + ey * ey);
        }
        double t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = x1 + t * dx;
        double cy = y1 + t * dy;
        double ex = px - cx;
        double ey = py - cy;
        return Math.sqrt(ex * ex + ey * ey);
    }

    /** Resolves the display name for a node (item/fluid/key localised name, or literal label).
     *  Cached per node id — called every frame for every visible node. */
    public static Component displayName(GraphNode n, ItemStack stack) {
        return displayName(n.getId(), n.getLabel(), stack, resolveKey(n.primaryKeyId() != null ? n.primaryKeyId() : n.getId()));
    }

    /** Material name for a port key id, falling back to the supplied literal label. */
    public static Component displayNameForKey(String keyId, String fallbackLabel) {
        ItemStack stack = resolveStack(keyId);
        if (!stack.isEmpty()) return stack.getHoverName();
        AEKey key = resolveKey(keyId);
        if (key != null) return key.getDisplayName();
        return Component.literal(fallbackLabel);
    }

    private static Component displayName(String id, String label, ItemStack stack, AEKey aeKey) {
        return NAME_CACHE.computeIfAbsent(id, k -> {
            if (!stack.isEmpty()) {
                return stack.getHoverName();
            }
            if (aeKey != null) {
                return aeKey.getDisplayName();
            }
            // Recipe nodes and unresolvable generic keys carry a literal localised label.
            return Component.literal(label);
        });
    }

    /**
     * Rebuilds a generic {@link AEKey} from an id of the form {@code aekey:<SNBT>}, where
     * the SNBT payload is the key's AE2 codec form (written server-side via toTagGeneric,
     * including the full data-component map for items). Returns null for legacy ids or
     * when the key type is unavailable on the client.
     */
    public static AEKey resolveKey(String id) {
        if (id == null || !id.startsWith("aekey:")) return null;
        Optional<AEKey> cached = KEY_CACHE.get(id);
        if (cached != null) return cached.orElse(null);
        AEKey key = null;
        try {
            Tag tag = TagParser.parseTag(id.substring(6));
            if (tag instanceof CompoundTag compoundTag) {
                var level = Minecraft.getInstance().level;
                if (level != null) {
                    key = AEKey.fromTagGeneric(level.registryAccess(), compoundTag);
                }
            }
        } catch (Throwable ignored) {
            // Legacy "aekey:<type>:<id>" fallback ids or unloaded key types → null
        }
        KEY_CACHE.put(id, Optional.ofNullable(key));
        return key;
    }

    /** Draws a generic AEKey's 16x16 icon via AE2's registered render handler. Returns false when no handler exists. */
    private static boolean drawKey(GuiGraphics g, int x, int y, AEKey key) {
        try {
            AEKeyRendering.drawInGui(Minecraft.getInstance(), g, x, y, key);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** ItemStack used to render an item material key; EMPTY for fluids, gases and other AEKeys. */
    public static ItemStack resolveStack(GraphNode node) {
        return resolveStack(node.primaryKeyId() != null ? node.primaryKeyId() : node.getId());
    }

    /**
     * ItemStack for a material key id. Handles both the legacy {@code item:<registry id>}
     * form and the canonical {@code aekey:<SNBT>} form: an item key is rebuilt with its
     * FULL data-component map via {@link AEItemKey#toStack()}, so NBT/component variants
     * (potions, enchanted books, renamed items, ...) render with the correct icon, name
     * and tooltip. Returns EMPTY for fluids, gases and other non-item keys.
     */
    public static ItemStack resolveStack(String keyId) {
        return STACK_CACHE.computeIfAbsent(keyId == null ? "" : keyId, id -> {
            if (id.startsWith("item:")) {
                try {
                    ResourceLocation rl = ResourceLocation.parse(id.substring(5));
                    Item item = BuiltInRegistries.ITEM.get(rl);
                    if (item != Items.AIR) return new ItemStack(item);
                } catch (Throwable ignored) {}
            } else if (id.startsWith("aekey:")) {
                AEKey key = resolveKey(id);
                if (key instanceof AEItemKey itemKey) {
                    try {
                        // toStack() reapplies the component map (potion contents, enchantments, …)
                        return itemKey.toStack();
                    } catch (Throwable ignored) {}
                }
            }
            return ItemStack.EMPTY;
        });
    }

    /** Arrowhead at the end of a segment (25 degree spread). */
    private static void drawArrowHead(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 1e-3) return;
        double ux = dx / dist;
        double uy = dy / dist;
        int endX = x2;
        int endY = y2;
        int startX = (int) (x2 - ux * 10);
        int startY = (int) (y2 - uy * 10);
        int arrowLen = 7;
        double angle = Math.atan2(dy, dx);
        double spread = Math.toRadians(25);
        int ax1 = (int) (endX - arrowLen * Math.cos(angle - spread));
        int ay1 = (int) (endY - arrowLen * Math.sin(angle - spread));
        int ax2 = (int) (endX - arrowLen * Math.cos(angle + spread));
        int ay2 = (int) (endY - arrowLen * Math.sin(angle + spread));
        drawLine(g, startX, startY, endX, endY, color);
        drawLine(g, endX, endY, ax1, ay1, color);
        drawLine(g, endX, endY, ax2, ay2, color);
    }

    /** Draws one edge as orthogonal polylines. Used for both cross-module and intra-module passes. */
    private static void drawEdge(GuiGraphics g, Font font, GraphNode a, GraphNode b2, GraphEdge e, int ei,
                                  List<GraphEdge> edges, LayoutResult layout,
                                  double offX, double offY, double zoom,
                                  int canvasX0, int canvasY0, int canvasX1, int canvasY1,
                                  String hoverKey, GraphNode hoveredNode, int hoveredEdge, boolean anyHover,
                                  boolean sameModule) {
        int base = sameModule ? EDGE_SAME : EDGE_CROSS;
        boolean highlighted =
                (hoverKey != null && hoverKey.equals(e.getKeyId()))
                || (hoveredNode != null && (a == hoveredNode || b2 == hoveredNode))
                || hoveredEdge == ei;
        int color = highlighted ? EDGE_HIGHLIGHT
                : (anyHover ? ((base & 0x00FFFFFF) | (DIM_ALPHA << 24)) : base);
        double[] pts = layout.routeOf(ei);
        if (pts == null || pts.length < 4) {
            pts = new double[]{a.x, a.y, b2.x, b2.y};
        }
        int n = pts.length / 2;
        for (int i = 0; i < n - 1; i++) {
            int x1 = (int) (offX + pts[i * 2] * zoom);
            int y1 = (int) (offY + pts[i * 2 + 1] * zoom);
            int x2 = (int) (offX + pts[i * 2 + 2] * zoom);
            int y2 = (int) (offY + pts[i * 2 + 3] * zoom);
            if (Math.max(x1, x2) < canvasX0 || Math.min(x1, x2) > canvasX1) continue;
            if (Math.max(y1, y2) < canvasY0 || Math.min(y1, y2) > canvasY1) continue;
            drawLine(g, x1, y1, x2, y2, color);
            if (i == n - 2) {
                double lastLen = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
                if (lastLen >= 8) {
                    drawArrowHead(g, x1, y1, x2, y2, color);
                }
            }
        }
    }

    private static void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        // Orthogonal segments (99% of routed edges) draw as a single fill instead of
        // per-pixel fills — this is the main frame-time optimisation.
        if (y1 == y2) {
            g.fill(Math.min(x1, x2), y1, Math.max(x1, x2) + 1, y1 + 1, color);
        } else if (x1 == x2) {
            g.fill(x1, Math.min(y1, y2), x1 + 1, Math.max(y1, y2) + 1, color);
        } else {
            int dx = x2 - x1;
            int dy = y2 - y1;
            int steps = Math.max(Math.abs(dx), Math.abs(dy));
            double stepX = (double) dx / steps;
            double stepY = (double) dy / steps;
            for (int i = 0; i <= steps; i++) {
                int x = x1 + (int) (stepX * i);
                int y = y1 + (int) (stepY * i);
                g.fill(x, y, x + 1, y + 1, color);
            }
        }
    }

    /** Stable colour per cluster/module id, picked from a small palette. */
    public static int clusterColor(int id) {
        int[] palette = {
            0xFFE57373, 0xFF81C784, 0xFF64B5F6, 0xFFFFD54F, 0xFFBA68C8,
            0xFF4DB6AC, 0xFFFF8A65, 0xFFF06292, 0xFF9575CD, 0xFFA1887F,
            0xFF90A4AE, 0xFFAED581, 0xFFFFD180, 0xFFB39DDB, 0xFF80CBC4,
            0xFFF48FB1, 0xFFCE93D8, 0xFFB0BEC5, 0xFFFFAB91, 0xFFA5D6A7
        };
        if (id < 0) return 0xFFB0B8C0;
        return palette[((id % palette.length) + palette.length) % palette.length];
    }
}
