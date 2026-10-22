package dev.combatdata.ui;

final class Flow {
    final int x0, w;
    int cx, cy;

    Flow(int x, int y, int w) {
        x0 = cx = x;
        cy = y;
        this.w = w;
    }

    int[] place(int iw, boolean optional) {
        if (cx > x0 && cx + iw > x0 + w) {
            if (optional) return null;
            cx = x0;
            cy += 20;
        }
        int[] p = {cx, cy};
        cx += iw + 4;
        return p;
    }

    int bottom() {
        return cy + 18;
    }
}
