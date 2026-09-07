package com.zt.recipegraph.graph;

import java.util.List;

/**
 * A material port on a recipe node.
 *
 * <p>Every pattern (recipe) node has output ports on its LEFT side (the primary product)
 * and input ports on its RIGHT side (consumed materials). Each port is a "local copy" of
 * a material (one {@link appeng.api.stacks.AEKey}) — the same material therefore appears
 * as a port on every recipe that produces or consumes it, rather than existing as one
 * global material node. Ports carrying the same {@link #keyId} are logically linked:
 * hovering one highlights all of them plus the edges that carry that material.</p>
 */
public final class Port {
    /** Stable material key id (e.g. "item:minecraft:iron_ingot" or "aekey:{...}"). */
    public final String keyId;
    /** Localised material display name (server-side resolved). */
    public final String keyLabel;

    public Port(String keyId, String keyLabel) {
        this.keyId = keyId;
        this.keyLabel = keyLabel;
    }

    /** Index of the port carrying {@code keyId} on the given side, or -1 if absent. */
    public static int indexOf(List<Port> side, String keyId) {
        if (side == null || keyId == null) return -1;
        for (int i = 0; i < side.size(); i++) {
            if (keyId.equals(side.get(i).keyId)) return i;
        }
        return -1;
    }
}
