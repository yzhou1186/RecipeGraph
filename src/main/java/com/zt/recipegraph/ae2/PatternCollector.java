package com.zt.recipegraph.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import com.zt.recipegraph.graph.GraphNode;
import com.zt.recipegraph.graph.PatternGraph;
import com.zt.recipegraph.graph.Port;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads every accessible pattern from an AE2 network and builds the RECIPE graph.
 *
 * <p>Graph model: every pattern becomes one recipe {@link GraphNode} (id {@code recipe:N}).
 * Each recipe node owns material ports — an OUTPUT port on its left for the pattern's
 * PRIMARY product, and one INPUT port on its right per distinct consumed material.
 * Materials are NOT nodes: each occurrence is a local {@link Port} copy. When recipe A
 * outputs material X and recipe B consumes X, an edge A→B is created carrying X's key id;
 * all ports/edges sharing that key id stay logically associated for hover highlighting.</p>
 *
 * <p>Pattern sources: every {@link ICraftingProvider} reachable from the grid nodes
 * (vanilla Pattern Providers, ME Interfaces and addon providers), unioned with the
 * patterns exposed through the crafting service as a fallback.</p>
 */
public final class PatternCollector {

    private final IGrid grid;

    /** Diagnostics: number of patterns seen across the network (-1 = not run yet). */
    private int patternCount = -1;
    private int craftableCount = -1;
    private int providerCount = -1;

    /** e.g. "PatternProviderBlockEntity:12" — one entry per provider that had patterns. */
    private final List<String> providerDetails = new ArrayList<>();

    /** Registry access used to serialise non-item AEKeys (fluids, gases, mana, ...). */
    private HolderLookup.Provider registries;

    public PatternCollector(IGrid grid) {
        this.grid = grid;
    }

    public int getPatternCount() { return patternCount; }
    public int getCraftableCount() { return craftableCount; }
    public int getProviderCount() { return providerCount; }
    public List<String> getProviderDetails() { return providerDetails; }

    /** Collects all patterns on the grid and builds the recipe graph. */
    public PatternGraph collect() {
        PatternGraph graph = new PatternGraph();
        craftableCount = -1;
        patternCount = -1;
        providerCount = -1;
        providerDetails.clear();
        if (grid == null) return graph;

        Set<IPatternDetails> allPatterns = new HashSet<>();

        // --- 1. enumerate ICraftingProvider nodes directly (covers vanilla providers,
        //       ME interfaces and addon devices) ---
        int providers = 0;
        for (IGridNode node : grid.getNodes()) {
            if (registries == null && node.getLevel() != null) {
                registries = node.getLevel().registryAccess();
            }
            ICraftingProvider provider = null;
            try {
                provider = node.getService(ICraftingProvider.class);
            } catch (Throwable ignored) {}
            if (provider == null && node.getOwner() instanceof ICraftingProvider owner) {
                provider = owner;
            }
            if (provider == null) continue;

            List<IPatternDetails> patterns = null;
            try {
                patterns = provider.getAvailablePatterns();
            } catch (Throwable ignored) {}
            if (patterns == null || patterns.isEmpty()) continue;

            providers++;
            providerDetails.add(node.getOwner().getClass().getSimpleName() + ":" + patterns.size());
            allPatterns.addAll(patterns);
        }
        providerCount = providers;

        // --- 2. crafting-service fallback (kept as a unioned fallback) ---
        ICraftingService craftingService = grid.getCraftingService();
        int craftables = -1;
        if (craftingService != null) {
            craftables = 0;
            for (AEKey craftable : craftingService.getCraftables(key -> true)) {
                craftables++;
                try {
                    allPatterns.addAll(craftingService.getCraftingFor(craftable));
                } catch (Throwable ignored) {}
            }
        }
        craftableCount = craftables;
        patternCount = allPatterns.size();

        // --- 3. one recipe node per pattern, with material ports ---
        int recipeSeq = 0;
        Set<String> recipeSigs = new HashSet<>();
        // material key id -> recipe nodes that OUTPUT that material (its producers)
        Map<String, List<GraphNode>> producers = new HashMap<>();

        for (IPatternDetails pattern : allPatterns) {
            GenericStack primary = null;
            try {
                primary = pattern.getPrimaryOutput();
            } catch (Throwable ignored) {}
            if (primary == null || primary.what() == null) continue;
            String[] out = keyIdAndLabel(primary.what());
            if (out == null) continue;
            String outId = out[0];
            String outLabel = out[1];

            // distinct input materials in slot order
            LinkedHashSet<String> inputIds = new LinkedHashSet<>();
            List<String> inputLabels = new ArrayList<>();
            IPatternDetails.IInput[] inputs = pattern.getInputs();
            if (inputs != null) {
                for (IPatternDetails.IInput in : inputs) {
                    GenericStack[] possible = in.getPossibleInputs();
                    if (possible == null || possible.length == 0 || possible[0].what() == null) continue;
                    String[] ik = keyIdAndLabel(possible[0].what());
                    if (ik == null || inputIds.contains(ik[0])) continue;
                    inputIds.add(ik[0]);
                    inputLabels.add(ik[0] + "\0" + ik[1]);
                }
            }

            // dedupe identical recipes (same product + same input materials)
            String sig = outId + "|" + String.join(",", inputIds);
            if (!recipeSigs.add(sig)) continue;

            GraphNode recipe = graph.getOrCreateNode("recipe:" + (recipeSeq++), outLabel);
            recipe.outputs.add(new Port(outId, outLabel));
            for (String encoded : inputLabels) {
                int sep = encoded.indexOf('\0');
                recipe.inputs.add(new Port(encoded.substring(0, sep), encoded.substring(sep + 1)));
            }
            producers.computeIfAbsent(outId, k -> new ArrayList<>()).add(recipe);
        }

        // --- 4. edges: producer output port X -> consumer input port X ---
        for (GraphNode consumer : new ArrayList<>(graph.getNodes())) {
            for (Port inPort : consumer.inputs) {
                List<GraphNode> makers = producers.get(inPort.keyId);
                if (makers == null) continue; // raw material: no producer in the network
                for (GraphNode maker : makers) {
                    if (maker == consumer) continue; // recipe re-feeds itself
                    graph.addEdge(maker, consumer, inPort.keyId);
                }
            }
        }

        return graph;
    }

    /**
     * Stable serialisation of an AEKey into [id, displayName].
     *
     * <p>Every key type — items included — goes through {@link AEKey#toTagGeneric}. For
     * items the tag carries the full data-component map, so variants of the same base item
     * (potions, enchanted books, renamed/NBT items, ...) get DISTINCT ids instead of
     * collapsing to one node. Note {@code dropSecondary()} must NOT be used here: for
     * AEItemKey it replaces the stack with the item's default instance, wiping every
     * component. Client rebuilds via AEKey.fromTagGeneric / AEItemKey.toStack().</p>
     */
    private String[] keyIdAndLabel(AEKey key) {
        if (key == null) return null;
        String label;
        try {
            label = key.getDisplayName().getString();
        } catch (Throwable t) {
            label = String.valueOf(key.getId());
        }
        try {
            CompoundTag tag = registries != null ? key.toTagGeneric(registries) : null;
            if (tag != null && !tag.isEmpty()) {
                return new String[]{"aekey:" + tag.getAsString(), label};
            }
        } catch (Throwable ignored) {}
        return new String[]{"aekey:" + key.getType().getId() + ":" + key.getId(), label};
    }
}
