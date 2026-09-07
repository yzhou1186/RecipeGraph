package com.zt.recipegraph;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common-side configuration for Recipe Graph. Values are synced from the server config
 * and readable on both sides. Layout runs client-side but the config values should come
 * from the common config spec so defaults are authoritative.
 *
 * <p>NeoForge 1.21.x uses {@link ModContainer#registerConfig} instead of the old event-based
 * {@code RegisterConfigEvent}. The spec is registered directly on the mod container in the
 * main mod constructor.
 */
public final class RecipeGraphConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Maximum number of modules in a super-component (SCC merge cap). Range 3-100, default 42. */
    public static final ModConfigSpec.IntValue MAX_MODULE_SIZE = BUILDER
        .comment("Maximum number of modules allowed in a merged super-component (SCC). " +
                "Larger values pack more cycles into one box; smaller values keep SCCs compact " +
                "but may leave mega-SCCs unmerged. Range [3, 100].")
        .defineInRange("maxModuleSize", 42, 3, 100);

    /** Number of collision-separation iterations for node AABB resolution. Range 50-200, default 50. */
    public static final ModConfigSpec.IntValue AABB_ITERATIONS = BUILDER
        .comment("Number of AABB collision-separation iterations performed after layout. " +
                "More iterations give cleaner separation but take longer on large graphs. " +
                "Range [50, 200].")
        .defineInRange("aabbIterations", 50, 50, 200);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RecipeGraphConfig() {}

    /** Registers the common config spec with the given mod container.
     *  Must be called from the mod constructor. */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC);
    }

    /** Clamped accessor — always returns a value within the declared range even if the
     *  on-disk config was manually edited out of bounds. */
    public static int maxModuleSize() {
        return clamp(MAX_MODULE_SIZE.get(), 3, 100);
    }

    public static int aabbIterations() {
        return clamp(AABB_ITERATIONS.get(), 50, 200);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
