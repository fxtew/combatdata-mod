package dev.combatdata.core;

/**
 * HUD placement that survives window resizes: an element is tied to one of nine screen points
 * (0 = top left, 4 = center, 8 = bottom right, row by row) by the same point of its own box, plus an offset.
 * Integer halves keep place() and fromBox() exact inverses.
 */
public final class HudAnchor {
    public static final int TOP_LEFT = 0, CENTER = 4, MIDDLE_LEFT = 3, BOTTOM_CENTER = 7;

    private HudAnchor() {
    }

    /** Top-left corner {x, y} of a w x h box on a sw x sh screen, kept on screen. */
    public static int[] place(int anchor, int dx, int dy, int w, int h, int sw, int sh) {
        int col = col(anchor), row = row(anchor);
        int x = col * sw / 2 + dx - col * w / 2;
        int y = row * sh / 2 + dy - row * h / 2;
        return new int[]{clamp(x, sw - w), clamp(y, sh - h)};
    }

    /** {anchor, dx, dy} for a box at x, y: the anchor is the screen third its center falls into. */
    public static int[] fromBox(int x, int y, int w, int h, int sw, int sh) {
        double cx = x + w / 2.0, cy = y + h / 2.0;
        int col = cx < sw / 3.0 ? 0 : cx > sw * 2 / 3.0 ? 2 : 1;
        int row = cy < sh / 3.0 ? 0 : cy > sh * 2 / 3.0 ? 2 : 1;
        return new int[]{row * 3 + col, x - col * sw / 2 + col * w / 2, y - row * sh / 2 + row * h / 2};
    }

    public static int col(int anchor) {
        return Math.floorMod(anchor, 3);
    }

    public static int row(int anchor) {
        return Math.max(0, Math.min(2, anchor / 3));
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, Math.max(0, max)));
    }
}
