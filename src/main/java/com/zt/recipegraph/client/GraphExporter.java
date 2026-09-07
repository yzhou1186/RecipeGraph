package com.zt.recipegraph.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zt.recipegraph.graph.GraphEdge;
import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;
import com.zt.recipegraph.layout.LayoutResult;
import com.zt.recipegraph.layout.ModuleBox;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports the currently displayed graph:
 *   - SVG: self-contained vector file using the same module rectangles and orthogonal edge
 *     routes the screen shows (independent of camera). Item icons and fluid sprites are
 *     embedded as base64 PNG data URIs sampled from the texture atlases (fluids are tinted
 *     with the fluid's own colour — never rendered as buckets); other key types fall back
 *     to coloured squares.
 *   - JSON: dumps nodes (with resolved localised names), edges, module boxes and edge
 *     routes to {@code <gameDir>/recipegraph/recipetree-graph-<timestamp>.json}.
 */
public final class GraphExporter {

    private GraphExporter() {}

    /** Size we render icons at. */
    private static final int ICON_PX = 32;

    /**
     * Exports the graph to a standalone SVG file mirroring the on-screen hierarchical layout.
     */
    public static void exportSvg(GraphView view) {
        Minecraft mc = Minecraft.getInstance();
        PatternGraph g = view.getGraph();
        if (g == null || g.isEmpty()) {
            overlay(mc, Component.translatable("recipegraph.export.empty"));
            ClientToast.push(Component.translatable("recipegraph.export.empty"));
            return;
        }
        LayoutResult layout = view.getLayout();

        // Pre-build icon data URIs for every unique item / fluid sprite in the graph
        // so we don't re-render duplicate sprites. iconUriCache maps node id -> dedup key;
        // keyToUri maps dedup key -> base64 PNG data URI.
        Map<String, String> keyToUri = new HashMap<>();
        Map<String, String> iconUriCache = new HashMap<>();
        for (GraphNode n : g.getNodes()) {
            // recipe card icon = its primary output (material key), never the recipe id
            String pid = n.primaryKeyId();
            ItemStack stack = pid != null ? GraphRenderer.resolveStack(pid) : ItemStack.EMPTY;
            if (!stack.isEmpty()) {
                Item i = stack.getItem();
                ResourceLocation rl = BuiltInRegistries.ITEM.getKey(i);
                int compHash = stack.getComponents() != null ? stack.getComponents().hashCode() : 0;
                String key = "item:" + rl + "#" + compHash;
                keyToUri.computeIfAbsent(key, k -> renderItemIconToDataUri(mc, stack));
                iconUriCache.put(n.getId(), key);
            } else {
                // Fluids (and other generic keys): use the AE2 key render handler's own
                // fluid sprite — never a bucket. Gases/etc. without sprite access stay
                // as fallback coloured squares.
                AEKey aeKey = pid != null ? GraphRenderer.resolveKey(pid) : null;
                if (aeKey instanceof AEFluidKey fluidKey) {
                    ResourceLocation frl = BuiltInRegistries.FLUID.getKey(fluidKey.getFluid());
                    String key = "aefluid:" + frl;
                    keyToUri.computeIfAbsent(key, k -> renderFluidIconToDataUri(mc, fluidKey));
                    iconUriCache.put(n.getId(), key);
                }
            }
        }
        // Replace dedup keys with the actual data URIs.
        for (Map.Entry<String, String> link : iconUriCache.entrySet()) {
            String uri = keyToUri.get(link.getValue());
            if (uri != null && !uri.isEmpty()) {
                link.setValue(uri);
            } else {
                link.setValue("");
            }
        }

        try {
            double pad = 160.0;
            double minX = Math.min(layout.getMinX(), g.getMinX()) - pad;
            double minY = Math.min(layout.getMinY(), g.getMinY()) - pad;
            double maxX = Math.max(layout.getMaxX(), g.getMaxX()) + pad;
            double maxY = Math.max(layout.getMaxY(), g.getMaxY()) + pad;
            double W = Math.ceil(maxX - minX);
            double H = Math.ceil(maxY - minY);

            StringBuilder sb = new StringBuilder(1 << 19); // 512KB initial buffer
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
              .append("xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
              .append("width=\"").append((int) W).append("\" height=\"").append((int) H).append("\" ")
              .append("viewBox=\"0 0 ").append((int) W).append(" ").append((int) H).append("\">\n");
            sb.append("<defs>\n");
            // Bright cyan: edges inside the same module
            sb.append("  <marker id=\"arrowSame\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" ")
              .append("markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\" ")
              .append("markerUnits=\"userSpaceOnUse\">\n");
            sb.append("    <path d=\"M0,0 L10,5 L0,10 z\" fill=\"#4FC3F7\"/>\n");
            sb.append("  </marker>\n");
            // Cross-module edges kept subtle to not overwhelm the graph (per user request)
            sb.append("  <marker id=\"arrowCross\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" ")
              .append("markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\" ")
              .append("markerUnits=\"userSpaceOnUse\">\n");
            sb.append("    <path d=\"M0,0 L10,5 L0,10 z\" fill=\"#2C3340\"/>\n");
            sb.append("  </marker>\n");
            for (ModuleBox b : view.getBoxes()) {
                String col = toHex(GraphRenderer.clusterColor(b.id));
                sb.append("  <style>.c").append(b.id).append("-box { fill:").append(col)
                  .append("; fill-opacity:0.09; stroke:").append(col).append("; stroke-width:1.5; }</style>\n");
            }
            sb.append("</defs>\n");

            // background
            sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#0E1217\"/>\n");

            double ox = -minX;
            double oy = -minY;

            // --- module boxes (rect or collapsed square) with title ---
            for (ModuleBox b : view.getBoxes()) {
                double x = b.minX + ox;
                double y = b.minY + oy;
                sb.append("<rect x=\"").append(f(x)).append("\" y=\"").append(f(y)).append("\"")
                  .append(" width=\"").append(f(b.getWidth())).append("\" height=\"").append(f(b.getHeight())).append("\"")
                  .append(" rx=\"6\" ry=\"6\" class=\"c").append(b.id).append("-box\"/>\n");
                String col = toHex(GraphRenderer.clusterColor(b.id));
                String title = Component.translatable("recipegraph.module.title",
                        String.valueOf(b.id), String.valueOf(b.nodes.size())).getString();
                sb.append("<text x=\"").append(f(x + 5)).append("\" y=\"").append(f(y + 12))
                  .append("\" font-family=\"Microsoft YaHei, PingFang SC, sans-serif\" font-size=\"12\" fill=\"")
                  .append(col).append("\">").append(xmlEscape(title)).append("</text>\n");
            }

            // --- edges as routed polylines ---
            List<GraphEdge> edges = g.getEdges();
            for (int ei = 0; ei < edges.size(); ei++) {
                GraphEdge e = edges.get(ei);
                GraphNode a = e.getFrom();
                GraphNode b2 = e.getTo();
                double[] pts = layout.routeOf(ei);
                if (pts == null || pts.length < 4) continue;
                boolean sameModule = (a.getCluster() == b2.getCluster() && a.getCluster() >= 0);
                String stroke = sameModule ? "#4FC3F7" : "#2C3340";
                String marker = sameModule ? "url(#arrowSame)" : "url(#arrowCross)";
                sb.append("<polyline points=\"");
                for (int i = 0; i < pts.length; i += 2) {
                    if (i > 0) sb.append(' ');
                    sb.append(f(pts[i] + ox)).append(',').append(f(pts[i + 1] + oy));
                }
                sb.append("\" fill=\"none\" stroke=\"").append(stroke)
                  .append("\" stroke-width=\"1\" marker-end=\"").append(marker).append("\"/>\n");
            }

            // --- recipe cards: card rect (sized by the layout) + product icon + name ---
            double iconW = ICON_PX;
            for (GraphNode n : g.getNodes()) {
                double x = n.x + ox;
                double y = n.y + oy;
                double w = Math.max(120.0, n.width);
                double h = Math.max(60.0, n.height);
                String colHex = (n.getCluster() >= 0)
                    ? toHex(GraphRenderer.clusterColor(n.getCluster()))
                    : "#B0B8C0";

                // Card rect (dark fill, coloured outline — same look as the in-game card)
                sb.append("<rect x=\"").append(f(x - w / 2)).append("\" y=\"").append(f(y - h / 2))
                  .append("\" width=\"").append(f(w)).append("\" height=\"").append(f(h))
                  .append("\" rx=\"6\" ry=\"6\" fill=\"#232A38\" fill-opacity=\"0.92\"")
                  .append(" stroke=\"").append(colHex).append("\" stroke-width=\"1.5\"/>\n");

                // Product icon (if available) at card centre
                String uri = iconUriCache.get(n.getId());
                if (uri != null && uri.startsWith("data:image/png;base64,")) {
                    sb.append("<image x=\"").append(f(x - iconW / 2)).append("\" y=\"").append(f(y - iconW / 2))
                      .append("\" width=\"").append(f(iconW)).append("\" height=\"").append(f(iconW))
                      .append("\" href=\"").append(uri).append("\" preserveAspectRatio=\"xMidYMid meet\"/>\n");
                } else {
                    // fallback: coloured square for unknown types
                    sb.append("<rect x=\"").append(f(x - ICON_PX / 2.0)).append("\" y=\"").append(f(y - ICON_PX / 2.0))
                      .append("\" width=\"").append(f(ICON_PX)).append("\" height=\"").append(f(ICON_PX))
                      .append("\" fill=\"").append(colHex).append("\" stroke=\"#000\"/>\n");
                }

                // Product name at the top strip of the card
                String pid = n.primaryKeyId();
                ItemStack stack = pid != null ? GraphRenderer.resolveStack(pid) : ItemStack.EMPTY;
                String name = GraphRenderer.displayName(n, stack).getString();
                int maxChars = 12;
                if (name.length() > maxChars) name = name.substring(0, maxChars - 1) + "…";
                sb.append("<text x=\"").append(f(x)).append("\" y=\"").append(f(y - h / 2 + 12))
                  .append("\" text-anchor=\"middle\" dominant-baseline=\"middle\"")
                  .append(" font-family=\"Microsoft YaHei, PingFang SC, sans-serif\" font-size=\"12\" fill=\"#FFFFFF\"")
                  .append(">").append(xmlEscape(name)).append("</text>\n");

                sb.append("<title>").append(xmlEscape(GraphRenderer.displayName(n, stack).getString())).append("</title>\n");
            }

            sb.append("</svg>\n");

            File dir = new File(mc.gameDirectory, "recipegraph");
            Files.createDirectories(dir.toPath());
            Path file = new File(dir, "recipegraph-graph-" + System.currentTimeMillis() + ".svg").toPath();
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            Component ok = Component.translatable("recipegraph.export.svg.success", file.toString());
            overlay(mc, ok);
            ClientToast.push(ok);
        } catch (Throwable t) {
            Component err = Component.translatable("recipegraph.export.failed", String.valueOf(t.getMessage()));
            overlay(mc, err);
            ClientToast.push(err);
        }
    }

    /**
     * Renders one item stack icon by sampling its BakedModel's particle sprite directly
     * from the TextureAtlas that lives on the GPU. This is a pure "read pixels from a
     * known texture" operation — no GuiGraphics, no FBO switching, no projection matrix
     * fiddling. It works whenever the Minecraft renderer has already uploaded its atlas
     * textures (i.e. any time the player can see items in their inventory).
     */
    private static final Map<net.minecraft.resources.ResourceLocation, NativeImage> ATLAS_CACHE = new HashMap<>();

    private static String renderItemIconToDataUri(Minecraft mc, ItemStack stack) {
        try {
            BakedModel model = mc.getItemRenderer().getModel(stack, null, null, 0);
            TextureAtlasSprite sprite = model.getParticleIcon();
            if (sprite == null) return "";
            // Item sprites are already fully coloured — tint white (no recolour).
            return spriteToDataUri(mc, sprite, 0xFFFFFFFF);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Renders an AE2 fluid key as a data URI by sampling its still-texture sprite from the
     * block texture atlas (the same sprite AE2's FluidKeyRenderHandler blits in GUIs) and
     * applying the fluid's tint colour.
     */
    private static String renderFluidIconToDataUri(Minecraft mc, AEFluidKey key) {
        try {
            FluidStack fluidStack = key.toStack(1);
            IClientFluidTypeExtensions props = IClientFluidTypeExtensions.of(key.getFluid());
            ResourceLocation texture = props.getStillTexture(fluidStack);
            if (texture == null) return "";
            TextureAtlasSprite sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(texture);
            if (sprite == null) return "";
            return spriteToDataUri(mc, sprite, props.getTintColor(fluidStack) | 0xFF000000);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Crops one sprite out of the GPU-resident texture atlas it belongs to, optionally
     * multiplies every pixel by {@code tintARGB} (0xFFFFFFFF = no tint), scales it to
     * {@value #ICON_PX}px with nearest-neighbour and returns a base64 PNG data URI.
     */
    private static String spriteToDataUri(Minecraft mc, TextureAtlasSprite sprite, int tintARGB) {
        java.nio.file.Path tmpFile = null;
        NativeImage out = null;
        try {
            net.minecraft.resources.ResourceLocation atlasLoc = sprite.atlasLocation();
            int spriteW = sprite.contents().width();
            int spriteH = sprite.contents().height();
            if (spriteW <= 0 || spriteH <= 0) return "";

            // --- Download or retrieve the full atlas image (once per atlas location) ---
            NativeImage atlasImg = ATLAS_CACHE.get(atlasLoc);
            if (atlasImg == null) {
                AbstractTexture tex = (AbstractTexture) mc.getTextureManager().getTexture(atlasLoc);
                if (tex == null) return "";
                int texId = tex.getId();
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texId);
                int[] wbuf = new int[1];
                int[] hbuf = new int[1];
                org.lwjgl.opengl.GL11.glGetTexLevelParameteriv(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH, wbuf);
                org.lwjgl.opengl.GL11.glGetTexLevelParameteriv(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT, hbuf);
                int atlasW = wbuf[0];
                int atlasH = hbuf[0];
                if (atlasW <= 0 || atlasH <= 0) return "";
                atlasImg = new NativeImage(NativeImage.Format.RGBA, atlasW, atlasH, false);
                atlasImg.downloadTexture(0, false);
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
                ATLAS_CACHE.put(atlasLoc, atlasImg);
            }

            // Compute the pixel rectangle inside the atlas image (U/V in [0,1] of atlas dims)
            int ax0 = Math.round(sprite.getU0() * atlasImg.getWidth());
            int ax1 = Math.round(sprite.getU1() * atlasImg.getWidth());
            int ay0 = Math.round(sprite.getV0() * atlasImg.getHeight());
            int ay1 = Math.round(sprite.getV1() * atlasImg.getHeight());
            int srcW = Math.max(1, ax1 - ax0);
            int srcH = Math.max(1, ay1 - ay0);

            // Tint channels (ARGB). White = no recolour.
            int tA = (tintARGB >>> 24) & 0xFF;
            int tR = (tintARGB >> 16) & 0xFF;
            int tG = (tintARGB >> 8) & 0xFF;
            int tB = tintARGB & 0xFF;

            // Copy into a 32×32 output buffer, nearest-neighbour scaled (pixel-art safe).
            int outW = ICON_PX;
            int outH = ICON_PX;
            out = new NativeImage(NativeImage.Format.RGBA, outW, outH, true);
            for (int oy = 0; oy < outH; oy++) {
                for (int ox = 0; ox < outW; ox++) {
                    int sx = (ox * srcW) / outW;
                    int sy = (oy * srcH) / outH;
                    int atlasX = Math.min(atlasImg.getWidth() - 1, Math.max(0, ax0 + sx));
                    int atlasY = Math.min(atlasImg.getHeight() - 1, Math.max(0, ay0 + sy));
                    int rgba = atlasImg.getPixelRGBA(atlasX, atlasY); // ABGR packed int
                    int pA = (rgba >>> 24) & 0xFF;
                    int pB = (rgba >> 16) & 0xFF;
                    int pG = (rgba >> 8) & 0xFF;
                    int pR = rgba & 0xFF;
                    int oR = (pR * tR) / 255;
                    int oG = (pG * tG) / 255;
                    int oB = (pB * tB) / 255;
                    int oA = (pA * tA) / 255;
                    out.setPixelRGBA(ox, oy, (oA << 24) | (oB << 16) | (oG << 8) | oR);
                }
            }

            tmpFile = Files.createTempFile("rt_icon_", ".png");
            out.writeToFile(tmpFile);
            byte[] bytes = Files.readAllBytes(tmpFile);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Throwable t) {
            return "";
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
            if (tmpFile != null) try { Files.deleteIfExists(tmpFile); } catch (Throwable ignored) {}
        }
    }

    public static void exportJson(GraphView view) {
        Minecraft mc = Minecraft.getInstance();
        PatternGraph g = view.getGraph();
        if (g == null || g.isEmpty()) {
            overlay(mc, Component.translatable("recipegraph.export.empty"));
            ClientToast.push(Component.translatable("recipegraph.export.empty"));
            return;
        }
        LayoutResult layout = view.getLayout();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("exportedAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date()));
            JsonObject bounds = new JsonObject();
            bounds.addProperty("minX", g.getMinX()); bounds.addProperty("maxX", g.getMaxX());
            bounds.addProperty("minY", g.getMinY()); bounds.addProperty("maxY", g.getMaxY());
            root.add("bounds", bounds);

            // Module boxes
            JsonArray moduleArr = new JsonArray();
            for (ModuleBox b : view.getBoxes()) {
                JsonObject mo = new JsonObject();
                mo.addProperty("id", b.id);
                mo.addProperty("minX", b.minX); mo.addProperty("minY", b.minY);
                mo.addProperty("maxX", b.maxX); mo.addProperty("maxY", b.maxY);
                mo.addProperty("nodeCount", b.nodes.size());
                moduleArr.add(mo);
            }
            root.add("modules", moduleArr);

            JsonArray nodes = new JsonArray();
            for (GraphNode n : g.getNodes()) {
                JsonObject no = new JsonObject();
                no.addProperty("id", n.getId());
                ItemStack stack = GraphRenderer.resolveStack(n);
                no.addProperty("name", GraphRenderer.displayName(n, stack).getString());
                no.addProperty("x", n.x);
                no.addProperty("y", n.y);
                no.addProperty("module", n.getCluster());
                nodes.add(no);
            }
            root.add("nodes", nodes);

            JsonArray edges = new JsonArray();
            List<GraphEdge> edgeList = g.getEdges();
            for (int ei = 0; ei < edgeList.size(); ei++) {
                GraphEdge e = edgeList.get(ei);
                JsonObject eo = new JsonObject();
                eo.addProperty("from", e.getFrom().getId());
                eo.addProperty("to", e.getTo().getId());
                eo.addProperty("pattern", e.getPatternId());
                double[] pts = layout.routeOf(ei);
                if (pts != null) {
                    JsonArray arr = new JsonArray();
                    for (double p : pts) arr.add(p);
                    eo.add("route", arr);
                }
                edges.add(eo);
            }
            root.add("edges", edges);

            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);

            File dir = new File(mc.gameDirectory, "recipegraph");
            Files.createDirectories(dir.toPath());
            Path file = new File(dir, "recipegraph-graph-" + System.currentTimeMillis() + ".json").toPath();
            Files.writeString(file, json, StandardCharsets.UTF_8);

            Component ok = Component.translatable("recipegraph.export.json.success", file.toString());
            overlay(mc, ok);
            ClientToast.push(ok);
        } catch (Throwable t) {
            Component err = Component.translatable("recipegraph.export.failed", String.valueOf(t.getMessage()));
            overlay(mc, err);
            ClientToast.push(err);
        }
    }

    private static void overlay(Minecraft mc, Component msg) {
        if (mc.gui != null) mc.gui.setOverlayMessage(msg, false);
    }

    // --- Small helpers ----------------------------------------------------------

    private static String f(double d) {
        // 1 decimal digit of precision is enough for SVG paths and keeps files small
        if (d == Math.floor(d)) return Long.toString((long) d);
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    private static String xmlEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&'  -> out.append("&amp;");
                case '<'  -> out.append("&lt;");
                case '>'  -> out.append("&gt;");
                case '\"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default   -> {
                    if (ch < 0x20) {
                        // escape control chars
                        out.append("&#").append((int) ch).append(";");
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Converts an ARGB packed colour (NeoForge GuiGraphics convention) to #RRGGBB. */
    private static String toHex(int rgba) {
        int r = (rgba >> 16) & 0xFF;
        int g = (rgba >>  8) & 0xFF;
        int b =  rgba        & 0xFF;
        return String.format("#%02X%02X%02X", r, g, b);
    }
}
