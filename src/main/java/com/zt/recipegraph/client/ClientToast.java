package com.zt.recipegraph.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Transient on-screen notifications (toasts) shown at the top-centre of the graph terminal.
 *
 * The vanilla HUD overlay message is invisible while a container screen is open, so export
 * success/failure feedback is routed through this instead.
 */
public final class ClientToast {
    private static final long DURATION_MS = 4000;
    private static final List<Toast> ACTIVE = new ArrayList<>();

    private record Toast(Component msg, long until) {}

    private ClientToast() {}

    public static void push(Component msg) {
        ACTIVE.add(new Toast(msg, System.currentTimeMillis() + DURATION_MS));
        if (ACTIVE.size() > 4) ACTIVE.remove(0);
    }

    public static void render(GuiGraphics g, Font font, int screenWidth) {
        long now = System.currentTimeMillis();
        Iterator<Toast> it = ACTIVE.iterator();
        int line = 0;
        while (it.hasNext()) {
            Toast t = it.next();
            if (t.until() < now) {
                it.remove();
                continue;
            }
            String text = t.msg().getString();
            int w = font.width(text) + 10;
            int x = (screenWidth - w) / 2;
            int y = 18 + line * 14;
            g.fill(x, y, x + w, y + 12, 0xB0101418);
            g.renderOutline(x, y, w, 12, 0xFF3A4350);
            g.drawString(font, text, x + 5, y + 2, 0xFFFFFFFF);
            line++;
        }
    }

    public static void clear() {
        ACTIVE.clear();
    }
}
