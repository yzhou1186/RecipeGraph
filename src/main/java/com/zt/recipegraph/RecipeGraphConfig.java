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

    /** Maximum number of recipes per module box (split cap). Range 3-100, default 42. */
    public static final ModConfigSpec.IntValue MAX_MODULE_SIZE = BUILDER
        .comment("Maximum number of recipes allowed in one module box (the \"(N 项)\" count " +
                "in a box title). Communities larger than this are split into chunks of at " +
                "most this size. Range [3, 100].")
        .defineInRange("maxModuleSize", 42, 3, 100);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RecipeGraphConfig() {}

    /** Registers the common config spec with the given mod container.
     *  Must be called from the mod constructor. The explicit file name pins the config to
     *  config/recipegraph.toml instead of the default recipegraph-common.toml. */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC, "recipegraph.toml");
    }

    /** Clamped accessor — always returns a value within the declared range even if the
     *  on-disk config was manually edited out of bounds. */
    public static int maxModuleSize() {
        return clamp(MAX_MODULE_SIZE.get(), 3, 100);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
