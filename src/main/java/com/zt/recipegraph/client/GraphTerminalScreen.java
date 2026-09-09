package com.zt.recipegraph.client;

import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;
import com.zt.recipegraph.graph.Port;
import com.zt.recipegraph.menus.GraphTerminalMenu;
import com.zt.recipegraph.network.GraphDataPacket;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side screen for the Graph Terminal.
 *
 * Fullscreen display of the AE2 pattern graph: module rectangles, orthogonal edge routes
 * and recipe cards with material ports. Control buttons stack down the top-right corner;
 * export feedback appears as toasts at the top-centre. Clicking an edge or a right-side
 * input port jumps the camera to the relevant recipe.
 *
 * <p>This extends {@link Screen} directly (NOT AbstractContainerScreen) on purpose: JEI,
 * FTB-library sidebar buttons and other container-GUI overlays decide to attach based on
 * {@code AbstractContainerScreen}, and they would cover this fullscreen graph. The paired
 * {@link GraphTerminalMenu} is kept manually and closed the same way vanilla does.</p>
 */
public class GraphTerminalScreen extends Screen implements MenuAccess<GraphTerminalMenu> {
    private static final int PADDING_TOP = 24;
    private static final int PADDING_BOTTOM = 8;
    private static final int PADDING_X = 4;

    private final GraphTerminalMenu menu;
    private final GraphView view = new GraphView();

    private boolean dragging = false;
    private double lastDragX, lastDragY;
    private GraphRenderer.Hit hit = GraphRenderer.Hit.NONE;

    // Ctrl+F search
    private EditBox searchBox;
    private List<GraphNode> searchMatches = List.of();
    private int searchIndex = 0;

    // Function menu popup
    private boolean showFunctionMenu = false;
    /** Currently active layout mode name (shown in popup and header). */
    private String layoutModeName = "recipegraph.layout_mode.recipe_material";
    private static final String[] LAYOUT_MODES = {
        "recipegraph.layout_mode.recipe_material"  // Recipe-Material mode (default)
    };

    public GraphTerminalScreen(GraphTerminalMenu menu, Inventory inv, Component title) {
        super(title);
        this.menu = menu;
    }

    @Override
    public GraphTerminalMenu getMenu() {
        return menu;
    }

    /** Never pause singleplayer while the terminal is open (network keeps ticking). */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        ClientGraphState.activate(menu.containerId);
        // Fullscreen: use the entire window
        // Canvas area below the top strip (buttons + toasts)
        view.setViewport(this.width - PADDING_X * 2, this.height - PADDING_TOP - PADDING_BOTTOM);
        refreshFromState();

        // Single "Functions" button in the top-right corner; clicking it toggles
        // a popup menu listing all actions
        int bw = 96;
        int bx = this.width - 4 - bw;
        int by = 4;
        this.addRenderableWidget(Button.builder(
            Component.translatable("recipegraph.button.functions"),
            b -> { showFunctionMenu = !showFunctionMenu; })
            .pos(bx, by).size(bw, 18).build());

        // Ctrl+F search box (top-centre, hidden until toggled)
        searchBox = new EditBox(this.font, this.width / 2 - 120, 6, 240, 16,
            Component.translatable("recipegraph.search.hint"));
        searchBox.setMaxLength(64);
        searchBox.setBordered(true);
        searchBox.setHint(Component.translatable("recipegraph.search.hint"));
        searchBox.setVisible(false);
        searchBox.setResponder(s -> {
            searchMatches = List.of();
            searchIndex = 0;
        });
        this.addRenderableWidget(searchBox);
    }

    public void refreshFromState() {
        PatternGraph g = ClientGraphState.current(menu.containerId);
        if (g != null && g != view.getGraph()) {
            view.setGraph(g, ClientGraphState.layoutFor(menu.containerId, g));
            hit = GraphRenderer.Hit.NONE; // stale hover indices must not leak into the new graph
        }
    }

    @Override
    public void tick() {
        if (this.minecraft == null || this.minecraft.player == null) return;
        // Mirror AbstractContainerScreen.tick: force-close if the player is gone
        if (!this.minecraft.player.isAlive() || this.minecraft.player.isRemoved()) {
            this.minecraft.player.closeContainer();
            return;
        }
        PatternGraph current = ClientGraphState.current(menu.containerId);
        if (current != null && current != view.getGraph()) {
            view.setGraph(current, ClientGraphState.layoutFor(menu.containerId, current));
            hit = GraphRenderer.Hit.NONE; // stale hover indices must not leak into the new graph
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Fullscreen dark background (do NOT call super: no vanilla blur/menu panorama)
        g.fill(0, 0, this.width, this.height, 0xFF0E1217);
    }

    @Override
    public void onClose() {
        // Mirror AbstractContainerScreen.onClose (1.21.1): sends the container-close packet
        // and resets the player's active menu back to the inventory menu
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.closeContainer();
        }
        super.onClose();
    }

    @Override
    public void removed() {
        // Mirror AbstractContainerScreen.removed (1.21.1)
        if (this.minecraft != null && this.minecraft.player != null) {
            menu.removed(this.minecraft.player);
        }
        ClientGraphState.deactivate(menu.containerId);
        super.removed();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        // Title (left-aligned so it never collides with the top-right buttons)
        g.drawString(this.font, this.title, PADDING_X + 2, 8, 0xFFFFFFFF);

        // Total pattern count read from the attached network (next to the title)
        int patternCount = ClientGraphState.patternCount(menu.containerId);
        if (patternCount >= 0) {
            Component countText = Component.translatable(
                "recipegraph.screen.graph_terminal.pattern_count", patternCount);
            int countX = PADDING_X + 2 + this.font.width(this.title) + 12;
            g.drawString(this.font, countText, countX, 8, 0xFF9AA7B4);
        }

        // Graph canvas
        renderGraph(g, mouseX, mouseY);

        // Export toasts (top-centre)
        ClientToast.render(g, this.font, this.width);

        // Hover tooltip: hovered material port first, then recipe card, then edge target
        if (hit.portKey() != null) {
            ItemStack portStack = GraphRenderer.resolveStack(hit.portKey());
            if (!portStack.isEmpty()) {
                g.renderTooltip(this.font, portStack, mouseX, mouseY);
            } else {
                g.renderTooltip(this.font, GraphRenderer.displayNameForKey(hit.portKey(), hit.portKey()), mouseX, mouseY);
            }
        } else {
            GraphNode hovered = hit.node();
            if (hovered == null && hit.edge() >= 0 && view.getGraph() != null
                    && hit.edge() < view.getGraph().getEdges().size()) {
                hovered = view.getGraph().getEdges().get(hit.edge()).getTo();
            }
            if (hovered != null) {
                ItemStack stack = GraphRenderer.resolveStack(hovered);
                if (!stack.isEmpty()) {
                    g.renderTooltip(this.font, stack, mouseX, mouseY);
                } else {
                    g.renderTooltip(this.font, GraphRenderer.displayName(hovered), mouseX, mouseY);
                }
            }
        }

        // Operation help, bottom-right
        String[] helpKeys = {
            "recipegraph.help.zoom",
            "recipegraph.help.jump",
            "recipegraph.help.pan",
            "recipegraph.help.search"
        };
        int helpLineH = 11;
        int helpTop = this.height - 6 - helpKeys.length * helpLineH;
        for (int i = 0; i < helpKeys.length; i++) {
            Component line = Component.translatable(helpKeys[i]);
            int w = this.font.width(line);
            g.drawString(this.font, line, this.width - 6 - w, helpTop + i * helpLineH, 0xFF7A8290);
        }

        // Function menu popup (drawn last so it sits on top)
        if (showFunctionMenu) {
            renderFunctionMenu(g, mouseX, mouseY);
        }
    }

    // === Function menu popup ===

    private static final int MENU_ITEM_W = 160;
    private static final int MENU_ITEM_H = 18;
    private static final int MENU_BG_PAD = 2;
    private static final String[] MENU_ITEM_KEYS = {
        "recipegraph.button.rebuild",
        "recipegraph.button.cluster_toggle",
        null, // separator
        "recipegraph.button.layout_mode",
        "recipegraph.button.export_svg",
        "recipegraph.button.export_json"
    };

    /** Returns the screen coordinates of the menu's anchor (top-right, just below the Functions button). */
    private int menuAnchorX() {
        return this.width - 4 - 96; // same x as the Functions button
    }
    private int menuAnchorY() {
        return 4 + 18 + 2; // below Functions button with small gap
    }

    private void renderFunctionMenu(GuiGraphics g, int mouseX, int mouseY) {
        int ax = menuAnchorX();
        int ay = menuAnchorY();
        int totalH = MENU_ITEM_KEYS.length * MENU_ITEM_H + MENU_BG_PAD * 2;
        // Clip to window bottom
        if (ay + totalH > this.height - 4) {
            ay = this.height - 4 - totalH;
        }
        // Background
        g.fill(ax - MENU_BG_PAD, ay - MENU_BG_PAD,
            ax + MENU_ITEM_W + MENU_BG_PAD, ay + totalH + MENU_BG_PAD,
            0xFF1C2230);
        g.renderOutline(ax - MENU_BG_PAD, ay - MENU_BG_PAD,
            MENU_ITEM_W + MENU_BG_PAD * 2, totalH + MENU_BG_PAD * 2,
            0xFF4A5568);
        for (int i = 0; i < MENU_ITEM_KEYS.length; i++) {
            int y = ay + i * MENU_ITEM_H;
            String key = MENU_ITEM_KEYS[i];
            if (key == null) {
                // separator
                g.fill(ax + 4, y + MENU_ITEM_H / 2 - 1,
                    ax + MENU_ITEM_W - 4, y + MENU_ITEM_H / 2 + 1,
                    0xFF374151);
                continue;
            }
            boolean hovered = mouseX >= ax && mouseX <= ax + MENU_ITEM_W
                && mouseY >= y && mouseY <= y + MENU_ITEM_H;
            int bgColor = hovered ? 0xFF3B4758 : 0x00000000;
            if (bgColor != 0) g.fill(ax, y, ax + MENU_ITEM_W, y + MENU_ITEM_H, bgColor);

            String label = Component.translatable(key).getString();
            // Special: layout mode item shows current mode name
            if ("recipegraph.button.layout_mode".equals(key)) {
                label = label + ": " + Component.translatable(layoutModeName).getString();
            }
            int textY = y + (MENU_ITEM_H - this.font.lineHeight) / 2;
            g.drawString(this.font, label, ax + 6, textY, hovered ? 0xFFFFFFFF : 0xFFD1D5DB);
        }
    }

    /** Handles a click inside the function menu popup. Returns true if consumed. */
    private boolean handleFunctionMenuClick(double mouseX, double mouseY) {
        if (!showFunctionMenu) return false;
        int ax = menuAnchorX();
        int ay = menuAnchorY();
        int totalH = MENU_ITEM_KEYS.length * MENU_ITEM_H + MENU_BG_PAD * 2;
        if (ay + totalH > this.height - 4) ay = this.height - 4 - totalH;
        boolean clickedInside = mouseX >= ax - MENU_BG_PAD && mouseX <= ax + MENU_ITEM_W + MENU_BG_PAD
            && mouseY >= ay - MENU_BG_PAD && mouseY <= ay + totalH + MENU_BG_PAD;
        boolean clickedOutside = !clickedInside;
        showFunctionMenu = false; // close on any click (inside handled below)
        if (clickedOutside) return true; // consume the click
        // Find which item
        for (int i = 0; i < MENU_ITEM_KEYS.length; i++) {
            int y = ay + i * MENU_ITEM_H;
            if (mouseX >= ax && mouseX <= ax + MENU_ITEM_W && mouseY >= y && mouseY <= y + MENU_ITEM_H) {
                String key = MENU_ITEM_KEYS[i];
                if (key == null) break; // separator
                switch (key) {
                    case "recipegraph.button.rebuild" -> getMenu().requestRebuild();
                    case "recipegraph.button.cluster_toggle" -> view.toggleClusters();
                    case "recipegraph.button.layout_mode" -> cycleLayoutMode();
                    case "recipegraph.button.export_svg" -> GraphExporter.exportSvg(view);
                    case "recipegraph.button.export_json" -> GraphExporter.exportJson(view);
                }
                return true;
            }
        }
        return true; // consume
    }

    private void cycleLayoutMode() {
        // Only one mode exists right now, but the machinery is ready for more.
        if (LAYOUT_MODES.length <= 1) {
            ClientToast.push(Component.translatable("recipegraph.layout_mode.only_one"));
            return;
        }
        for (int i = 0; i < LAYOUT_MODES.length; i++) {
            if (LAYOUT_MODES[i].equals(layoutModeName)) {
                layoutModeName = LAYOUT_MODES[(i + 1) % LAYOUT_MODES.length];
                break;
            }
        }
    }

    private void renderGraph(GuiGraphics g, int mouseX, int mouseY) {
        PatternGraph graph = view.getGraph();
        if (graph == null || graph.isEmpty()) {
            // A freshly received non-empty graph is laid out in the background; don't tell
            // the player "no patterns found" while that is happening.
            Component msg;
            if (ClientGraphState.layoutPending(menu.containerId)
                    || !ClientGraphState.hasState(menu.containerId)) {
                msg = Component.translatable("recipegraph.screen.graph_terminal.loading");
            } else {
                msg = switch (ClientGraphState.status(menu.containerId)) {
                    case GraphDataPacket.STATUS_NO_NETWORK ->
                        Component.translatable("recipegraph.screen.graph_terminal.no_network");
                    case GraphDataPacket.STATUS_NO_PATTERNS ->
                        Component.translatable("recipegraph.screen.graph_terminal.no_patterns");
                    case GraphDataPacket.STATUS_ERROR ->
                        Component.translatable("recipegraph.screen.graph_terminal.collect_error");
                    default ->
                        Component.translatable("recipegraph.screen.graph_terminal.empty");
                };
            }
            g.drawCenteredString(this.font, msg, this.width / 2, this.height / 2, 0xFF7A8290);
            return;
        }

        int canvasX0 = PADDING_X;
        int canvasY0 = PADDING_TOP;
        int canvasX1 = this.width - PADDING_X;
        int canvasY1 = this.height - PADDING_BOTTOM;

        // screenX = offX + worldX * zoom, where offX = screenCentreX + panX * zoom
        double offX = (canvasX0 + canvasX1) / 2.0 + view.panX * view.zoom;
        double offY = (canvasY0 + canvasY1) / 2.0 + view.panY * view.zoom;

        hit = GraphRenderer.draw(g, this.font, view,
            offX, offY, view.zoom,
            canvasX0, canvasY0, canvasX1, canvasY1,
            mouseX, mouseY);
    }

    // === Input handling: edge navigation, pan, zoom ===

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Function menu popup takes priority over canvas interaction
        if (showFunctionMenu) {
            if (handleFunctionMenuClick(mouseX, mouseY)) return true;
        }
        if (inCanvas((int) mouseX, (int) mouseY) && !isOverButton(mouseX, mouseY)) {
            // Left click on a RIGHT-side INPUT port: jump to that material's crafting recipe
            if (button == 0 && hit.portKey() != null) {
                if (!hit.portOutput()) {
                    jumpToProducer(hit.portKey());
                }
                return true;
            }
            // Left click on a highlighted edge jumps the camera to its downstream node
            if (button == 0 && hit.edge() >= 0 && view.getGraph() != null
                    && hit.edge() < view.getGraph().getEdges().size()) {
                GraphNode target = view.getGraph().getEdges().get(hit.edge()).getTo();
                view.focusOn(target);
                return true;
            }
            // Right button (hold) drags the canvas
            if (button == 1) {
                dragging = true;
                lastDragX = mouseX;
                lastDragY = mouseY;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Moves the camera to the recipe node that produces the given material key id (the
     * producer = a recipe card whose LEFT output port carries that key). Raw materials
     * without any producer in the network show a toast instead.
     */
    private void jumpToProducer(String keyId) {
        PatternGraph g = view.getGraph();
        if (g == null) return;
        GraphNode maker = null;
        for (GraphNode n : g.getNodes()) {
            for (Port p : n.getOutputs()) {
                if (p.keyId.equals(keyId)) { maker = n; break; }
            }
            if (maker != null) break;
        }
        String name = GraphRenderer.displayNameForKey(keyId, keyId).getString();
        if (maker != null) {
            view.focusOn(maker);
            ClientToast.push(Component.translatable("recipegraph.search.jump", name));
        } else {
            ClientToast.push(Component.translatable("recipegraph.search.no_recipe", name));
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 1 && dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging && button == 1) {
            view.panBy(mouseX - lastDragX, mouseY - lastDragY);
            lastDragX = mouseX;
            lastDragY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && inCanvas((int) mouseX, (int) mouseY)) {
            double factor = scrollY > 0 ? 1.15 : 1.0 / 1.15;
            int cx = this.width / 2;
            int cy = this.height / 2;
            view.zoomBy(factor, (int) mouseX - cx, (int) mouseY - cy);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // === Keyboard: Ctrl+F search ===

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        // Ctrl+F toggles the search bar regardless of current focus
        if (ctrl && keyCode == GLFW.GLFW_KEY_F) {
            toggleSearch();
            return true;
        }
        if (searchBox != null && searchBox.isVisible()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                runSearch();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeSearch();
                return true;
            }
            // let the focused EditBox consume navigation/editing keys
            if (searchBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchBox != null && searchBox.isVisible() && searchBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void toggleSearch() {
        if (searchBox.isVisible()) {
            closeSearch();
        } else {
            searchBox.setVisible(true);
            searchBox.setValue("");
            searchBox.setFocused(true);
            this.setFocused(searchBox);
            searchMatches = List.of();
            searchIndex = 0;
        }
    }

    private void closeSearch() {
        searchBox.setVisible(false);
        searchBox.setFocused(false);
        this.setFocused(null);
        searchMatches = List.of();
        searchIndex = 0;
    }

    /**
     * Collects recipe nodes whose PRODUCT name matches the query first, then recipes
     * consuming a matching material (jump target = that material's producer). Repeated
     * Enter presses cycle through the matches.
     */
    private void runSearch() {
        PatternGraph g = view.getGraph();
        if (g == null) return;
        String q = searchBox.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) return;

        if (searchMatches.isEmpty()) {
            // material key id -> producer recipe node
            Map<String, GraphNode> producers = new HashMap<>();
            for (GraphNode n : g.getNodes()) {
                for (Port p : n.getOutputs()) {
                    producers.putIfAbsent(p.keyId, n);
                }
            }
            Set<GraphNode> matched = new LinkedHashSet<>();
            // priority 1: product (output) name matches -> the recipe itself
            for (GraphNode n : g.getNodes()) {
                String label = materialName(n.primaryKeyId(), n.getLabel()).toLowerCase(java.util.Locale.ROOT);
                if (label.contains(q)) matched.add(n);
            }
            // priority 2: consumed material name matches -> jump to its producer recipe
            for (GraphNode n : g.getNodes()) {
                for (Port p : n.getInputs()) {
                    String label = materialName(p.keyId, p.keyLabel).toLowerCase(java.util.Locale.ROOT);
                    if (label.contains(q)) {
                        GraphNode maker = producers.get(p.keyId);
                        if (maker != null) matched.add(maker);
                    }
                }
            }
            searchMatches = new ArrayList<>(matched);
            searchIndex = 0;
        }

        if (searchMatches.isEmpty()) {
            ClientToast.push(Component.translatable("recipegraph.search.none", searchBox.getValue().trim()));
            return;
        }
        int shown = searchIndex % searchMatches.size();
        GraphNode target = searchMatches.get(shown);
        searchIndex++;
        view.focusOn(target);
        String name = materialName(target.primaryKeyId(), target.getLabel());
        ClientToast.push(Component.translatable("recipegraph.search.jump",
            "(" + (shown + 1) + "/" + searchMatches.size() + ") " + name));
    }

    /** Localised display name for a material key id, falling back to the given literal. */
    private static String materialName(String keyId, String fallback) {
        if (keyId == null) return fallback == null ? "?" : fallback;
        return GraphRenderer.displayNameForKey(keyId, fallback).getString();
    }

    private boolean inCanvas(int x, int y) {
        return x >= PADDING_X && x < this.width - PADDING_X
            && y >= PADDING_TOP && y < this.height - PADDING_BOTTOM;
    }

    private boolean isOverButton(double x, double y) {
        // Also detect popup menu region so canvas clicks don't fire while menu is open
        if (showFunctionMenu) {
            int ax = menuAnchorX();
            int ay = menuAnchorY();
            int totalH = MENU_ITEM_KEYS.length * MENU_ITEM_H + MENU_BG_PAD * 2;
            if (ay + totalH > this.height - 4) ay = this.height - 4 - totalH;
            if (x >= ax - MENU_BG_PAD && x <= ax + MENU_ITEM_W + MENU_BG_PAD
                && y >= ay - MENU_BG_PAD && y <= ay + totalH + MENU_BG_PAD) {
                return true;
            }
        }
        return this.children().stream().anyMatch(c -> {
            if (!(c instanceof Button b)) return false;
            return x >= b.getX() && x <= b.getX() + b.getWidth()
                && y >= b.getY() && y <= b.getY() + b.getHeight();
        });
    }
}
