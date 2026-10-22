package dev.combatdata.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Fill-only primitives (no textures, shaders or OpenGL) to stay compatible with VulkanMod. */
public final class Draw {
    private Draw() {
    }

    public static void round(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    public static void roundBordered(GuiGraphics g, int x, int y, int w, int h, int r, int border, int fill) {
        round(g, x, y, w, h, r, border);
        round(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
    }

    public static void circle(GuiGraphics g, int x, int y, int d, int color) {
        double r = d / 2.0;
        for (int row = 0; row < d; row++) {
            double cy = row + 0.5 - r;
            double hw = Math.sqrt(Math.max(0, r * r - cy * cy));
            int x1 = (int) Math.round(r - hw), x2 = (int) Math.round(r + hw);
            if (x2 > x1) g.fill(x + x1, y + row, x + x2, y + row + 1, color);
        }
    }

    public static void dashH(GuiGraphics g, int x1, int x2, int y, int dash, int gap, int color) {
        for (int x = x1; x < x2; x += dash + gap) g.fill(x, y, Math.min(x + dash, x2), y + 1, color);
    }

    /** 1px line from fills: a vertical segment per x column so steep parts don't break up. */
    public static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        if (x2 < x1) {
            int tx = x1, ty = y1;
            x1 = x2;
            y1 = y2;
            x2 = tx;
            y2 = ty;
        }
        if (x1 == x2) {
            g.fill(x1, Math.min(y1, y2), x1 + 1, Math.max(y1, y2) + 1, color);
            return;
        }
        int prev = y1;
        for (int x = x1; x <= x2; x++) {
            int y = (int) Math.round(y1 + (double) (y2 - y1) * (x - x1) / (x2 - x1));
            g.fill(x, Math.min(prev, y), x + 1, Math.max(prev, y) + 1, color);
            prev = y;
        }
    }

    public static void arrow(GuiGraphics g, int cx, int y, boolean up, int color) {
        for (int i = 0; i < 3; i++) {
            int ry = up ? y + i : y - i;
            g.fill(cx - i, ry, cx + i + 1, ry + 1, color);
        }
    }

    public static String ellipsize(Font font, String s, int maxW) {
        if (s == null) return "";
        if (font.width(s) <= maxW) return s;
        String dots = "...";
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end) + dots) > maxW) end--;
        return s.substring(0, end) + dots;
    }
}
