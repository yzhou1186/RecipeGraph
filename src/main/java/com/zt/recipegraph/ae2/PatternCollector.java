package com.zt.recipegraph.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the RECIPE graph from an immutable snapshot of AE2 patterns.
 *
 * <p>Graph model: every pattern becomes one recipe {@link GraphNode} (id {@code recipe:N}).
 * Each recipe node owns material ports — an OUTPUT port on its left for the pattern's
 * PRIMARY product, and one INPUT port on its right per distinct consumed material.
 * Materials are NOT nodes: each occurrence is a local {@link Port} copy. When recipe A
 * outputs material X and recipe B consumes X, an edge A→B is created carrying X's key id;
 * all ports/edges sharing that key id stay logically associated for hover highlighting.</p>
 *
 * <p><b>Threading.</b> Every AE2 grid query — provider enumeration
 * ({@link ICraftingProvider#getAvailablePatterns()}), the crafting-service craftable
 * enumeration ({@code getCraftables}/{@code getCraftingFor}) — is performed by the caller
 * on the SERVER MAIN THREAD; the resulting deduplicated {@link IPatternDetails} list plus
 * the registry access are handed in as immutable data. {@link #collect()} then does pure
 * data extraction (pattern inputs/outputs, SNBT serialisation) on the worker thread and
 * never touches a grid node or service off-thread.</p>
 */
public final class PatternCollector {

    /** A provider and its owner's display name, captured on the server main thread. */
    public record ProviderEntry(ICraftingProvider provider, String ownerName) {}

    /** Immutable pattern union (direct providers + crafting-service fallback), main-thread. */
    private final List<IPatternDetails> patternSnapshot;

    /** Diagnostics captured on the main thread alongside the snapshot. */
    private final int patternCount;
    private final int craftableCount;
    private final int providerCount;
    private final List<String> providerDetails;

    /**
     * Registry access used to serialise non-item AEKeys (fluids, gases, mana, ...).
     * Captured on the server main thread before collection moves to the worker thread;
     * never read from an AE2 grid node's level inside the worker.
     */
    private final HolderLookup.Provider registries;

    public PatternCollector(List<IPatternDetails> patternSnapshot,
                            int providerCount, int craftableCount, List<String> providerDetails,
                            HolderLookup.Provider registries) {
        this.patternSnapshot = patternSnapshot == null ? List.of() : patternSnapshot;
        this.patternCount = this.patternSnapshot.size();
        this.providerCount = providerCount;
        this.craftableCount = craftableCount;
        this.providerDetails = providerDetails == null ? List.of() : List.copyOf(providerDetails);
        this.registries = registries;
    }

    public int getPatternCount() { return patternCount; }
    public int getCraftableCount() { return craftableCount; }
    public int getProviderCount() { return providerCount; }
    public List<String> getProviderDetails() { return providerDetails; }

    /** Builds the recipe graph from the main-thread pattern snapshot. */
    public PatternGraph collect() {
        PatternGraph graph = new PatternGraph();

        // --- 1. one recipe node per pattern, with material ports ---
        int recipeSeq = 0;
        Set<String> recipeSigs = new HashSet<>();
        // material key id -> recipe nodes that OUTPUT that material (its producers)
        Map<String, List<GraphNode>> producers = new HashMap<>();

        for (IPatternDetails pattern : patternSnapshot) {
            GenericStack primary = null;
            try {
                primary = pattern.getPrimaryOutput();
            } catch (Throwable ignored) {}
            if (primary == null || primary.what() == null) continue;
            String[] out = keyIdAndLabel(primary.what());
            if (out == null) continue;
            String outId = out[0];
            String outLabel = out[1];

            // Distinct input materials in slot order. An AE2 input slot may expose several
            // possible substitutes; keep every distinct material so alternatives are neither
            // silently dropped from the UI nor erased from the dedupe signature.
            List<List<String[]>> slotAlternatives = new ArrayList<>();
            IPatternDetails.IInput[] inputs = pattern.getInputs();
            if (inputs != null) {
                for (IPatternDetails.IInput in : inputs) {
                    GenericStack[] possible = in.getPossibleInputs();
                    if (possible == null || possible.length == 0) continue;
                    List<String[]> alternatives = new ArrayList<>();
                    for (GenericStack candidate : possible) {
                        if (candidate == null || candidate.what() == null) continue;
                        String[] ik = keyIdAndLabel(candidate.what());
                        if (ik != null) alternatives.add(ik);
                    }
                    if (!alternatives.isEmpty()) slotAlternatives.add(alternatives);
                }
            }

            // dedupe identical recipes (same product + same input materials)
            String sig = recipeSignature(outId, slotAlternatives);
            if (!recipeSigs.add(sig)) continue;

            GraphNode recipe = graph.getOrCreateNode("recipe:" + (recipeSeq++), outLabel);
            recipe.outputs.add(new Port(outId, outLabel));
            // Collapse repeated/distributed alternatives to one chip per distinct material.
            LinkedHashMap<String, String> seenInputs = new LinkedHashMap<>();
            for (List<String[]> alternatives : slotAlternatives) {
                for (String[] ik : alternatives) seenInputs.putIfAbsent(ik[0], ik[1]);
            }
            seenInputs.forEach((keyId, label) -> recipe.inputs.add(new Port(keyId, label)));
            producers.computeIfAbsent(outId, k -> new ArrayList<>()).add(recipe);
        }

        // --- 2. edges: producer output port X -> consumer input port X ---
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
     * Canonical, collision-free recipe signature: the product key id followed by every
     * input slot's full alternative set. Each element is length-prefixed so SNBT material
     * ids containing commas, pipes or braces can never be confused with separators.
     */
    private static String recipeSignature(String outId, List<List<String[]>> slotAlternatives) {
        List<String> groupStrings = new ArrayList<>();
        for (List<String[]> alternatives : slotAlternatives) {
            Set<String> unique = new HashSet<>();
            for (String[] ik : alternatives) unique.add(ik[0]);
            List<String> ids = new ArrayList<>(unique);
            java.util.Collections.sort(ids);
            StringBuilder group = new StringBuilder();
            appendCounted(group, Integer.toString(ids.size()));
            for (String id : ids) appendCounted(group, id);
            groupStrings.add(group.toString());
        }
        java.util.Collections.sort(groupStrings);
        StringBuilder sb = new StringBuilder();
        appendCounted(sb, outId);
        appendCounted(sb, Integer.toString(groupStrings.size()));
        for (String group : groupStrings) sb.append(group);
        return sb.toString();
    }

    private static void appendCounted(StringBuilder sb, String s) {
        sb.append(s.length()).append(':').append(s);
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
