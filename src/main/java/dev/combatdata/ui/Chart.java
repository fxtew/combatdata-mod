package dev.combatdata.ui;

import dev.combatdata.core.ChartMath;

import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

/** Overlays are drawn after nextStratum(), so the chart must be drawn last on its tab. */
final class Chart {
    /** key identifies a point across frames so a pinned tooltip survives recomputation. */
    record Point(long t, double v, int color, boolean axisMark, String tip, Supplier<List<String[]>> details, Object key) {
    }

    record Axis(double min, double max, double step, DoubleFunction<String> label, double bandLo, double bandHi,
                int bandColor, double[] dashes, int[] dashColors) {
    }

    private static final int DOT = 5, DOT_HOVER = 9, HIT_RADIUS = 5, LEFT = 30, BOTTOM = 12;
    private static final long MIN_SPAN_MS = 5_000;

    private ChartMath.View view;
    private long total = -1;
    long cursor = -1;
    java.util.function.LongConsumer onTimeClick;
    private Object pinned;
    private boolean dragging, moved;
    private double pressX;
    private int gx, gy, gw, gh;
    private double[] xs = new double[0], ys = new double[0];
    private List<Point> points = List.of();

    void total(long durationMs) {
        long t = Math.max(1, durationMs);
        if (view == null || total < 0 || view.equals(ChartMath.View.all(total))) view = ChartMath.View.all(t);
        else view = ChartMath.pan(view, 0, t);
        total = t;
    }

    void resetView() {
        view = ChartMath.View.all(total);
    }

    boolean zoomed() {
        return view != null && !view.equals(ChartMath.View.all(total));
    }

    ChartMath.View view() {
        return view;
    }

    void unpin() {
        pinned = null;
    }

    record Line(long[] t, double[] v, int color, long gapMs) {
    }

    void render(Ui ui, int x, int y, int w, int h, List<Point> pts, Axis axis) {
        render(ui, x, y, w, h, pts, axis, null);
    }

    /**
     * Drawn per pixel column: samples in the same column become one min-max segment,
     * so thousands of ticks cost a few fills per pixel.
     */
    private void drawLine(Ui ui, Line line, Axis axis) {
        var g = ui.g;
        int colX = Integer.MIN_VALUE, colMin = 0, colMax = 0, prevX = 0, prevY = 0;
        boolean has = false;
        long prevT = 0;
        int i0 = 0;
        while (i0 < line.t().length && line.t()[i0] < view.from() - line.gapMs()) i0++;
        for (int i = i0; i < line.t().length; i++) {
            long t = line.t()[i];
            if (t > view.to() + line.gapMs()) break;
            int px = (int) Math.round(ChartMath.timeToPx(t, view, gx, gw));
            int py = (int) Math.round(ChartMath.valueToPx(line.v()[i], axis.min(), axis.max(), gy, gh));
            boolean broken = !has || t - prevT > line.gapMs();
            if (!broken && px == colX) {
                colMin = Math.min(colMin, py);
                colMax = Math.max(colMax, py);
            } else {
                if (has) g.fill(colX, colMin, colX + 1, colMax + 1, line.color());
                if (!broken) Draw.line(g, prevX, prevY, px, py, line.color());
                colX = px;
                colMin = colMax = py;
            }
            prevX = px;
            prevY = py;
            prevT = t;
            has = true;
        }
        if (has) g.fill(colX, colMin, colX + 1, colMax + 1, line.color());
    }

    void render(Ui ui, int x, int y, int w, int h, List<Point> pts, Axis axis, Line line) {
        points = pts;
        gx = x + LEFT;
        gy = y + 4;
        gw = Math.max(10, w - LEFT - 6);
        gh = Math.max(10, h - BOTTOM - 8);
        var g = ui.g;
        Draw.roundBordered(g, x, y, w, h, Theme.R_PANEL, Theme.BORDER, Theme.INSET);
        if (axis.bandHi() > axis.bandLo()) {
            int b1 = (int) ChartMath.valueToPx(axis.bandHi(), axis.min(), axis.max(), gy, gh);
            int b2 = (int) ChartMath.valueToPx(axis.bandLo(), axis.min(), axis.max(), gy, gh);
            g.fill(gx, b1, gx + gw, Math.max(b1 + 1, b2), axis.bandColor());
        }
        for (double v = Math.ceil(axis.min() / axis.step()) * axis.step(); v <= axis.max() + 1e-9; v += axis.step()) {
            int ly = (int) ChartMath.valueToPx(v, axis.min(), axis.max(), gy, gh);
            g.fill(gx, ly, gx + gw, ly + 1, Theme.RAISED);
            ui.textRight(axis.label().apply(v), gx - 3, ly - 4, Theme.DIM);
        }
        long step = ChartMath.timeStep(view.span(), gw, 44);
        for (long t = ChartMath.firstTick(view.from(), step); t <= view.to(); t += step) {
            int lx = (int) ChartMath.timeToPx(t, view, gx, gw);
            g.fill(lx, gy, lx + 1, gy + gh, Theme.RAISED);
            String s = Fmt.clock(t);
            int sw = ui.font.width(s);
            if (lx - sw / 2 >= gx - 4 && lx + sw / 2 <= gx + gw + 4) ui.textCenter(s, lx, gy + gh + 3, Theme.DIM);
        }
        for (int i = 0; i < axis.dashes().length; i++) {
            int ly = (int) ChartMath.valueToPx(axis.dashes()[i], axis.min(), axis.max(), gy, gh);
            Draw.dashH(g, gx, gx + gw, ly, 3, 2, axis.dashColors()[i]);
        }

        xs = new double[pts.size()];
        ys = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            Point p = pts.get(i);
            if (p.t() < view.from() || p.t() > view.to()) {
                xs[i] = ys[i] = Double.NaN;
                continue;
            }
            xs[i] = ChartMath.timeToPx(p.t(), view, gx, gw);
            ys[i] = p.axisMark() ? gy + gh - 2 : ChartMath.valueToPx(p.v(), axis.min(), axis.max(), gy, gh);
        }
        boolean inPlot = ui.hover(gx - HIT_RADIUS, gy - HIT_RADIUS, gw + HIT_RADIUS * 2, gh + HIT_RADIUS * 2);
        int hover = inPlot && !(dragging && moved) ? ChartMath.nearest(xs, ys, ui.mx, ui.my, HIT_RADIUS) : -1;
        int pin = -1;
        g.enableScissor(gx - DOT_HOVER, gy - DOT_HOVER, gx + gw + DOT_HOVER, gy + gh + DOT_HOVER);
        if (cursor >= view.from() && cursor <= view.to()) {
            int cx = (int) Math.round(ChartMath.timeToPx(cursor, view, gx, gw));
            g.fill(cx, gy, cx + 1, gy + gh, Theme.ACCENT);
        }
        if (line != null) {
            // scissorStack: inner scissors are popped, outer ones stay.
            g.enableScissor(gx, gy, gx + gw, gy + gh);
            drawLine(ui, line, axis);
            g.disableScissor();
        }
        // Long recordings stack thousands of points; skip a point at the same pixel and color as the previous one (fills are expensive).
        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastColor = 0;
        for (int i = 0; i < pts.size(); i++) {
            if (Double.isNaN(xs[i])) continue;
            Point p = pts.get(i);
            if (pinned != null && pinned.equals(p.key())) pin = i;
            int cx = (int) Math.round(xs[i]), cy = (int) Math.round(ys[i]);
            if (cx == lastX && cy == lastY && p.color() == lastColor) continue;
            lastX = cx;
            lastY = cy;
            lastColor = p.color();
            if (p.axisMark()) {
                g.fill(cx, gy + gh - 4, cx + 1, gy + gh, p.color());
                continue;
            }
            Draw.circle(g, cx - DOT / 2, cy - DOT / 2, DOT, p.color());
            if (ChartMath.outside(p.v(), axis.min(), axis.max())) {
                boolean top = p.v() > axis.max();
                Draw.arrow(g, cx, top ? cy - 6 : cy + 6, top, p.color());
            }
        }
        for (int i : new int[]{pin, hover}) {
            if (i < 0) continue;
            int cx = (int) Math.round(xs[i]), cy = (int) Math.round(ys[i]);
            Draw.circle(g, cx - DOT_HOVER / 2, cy - DOT_HOVER / 2, DOT_HOVER, Theme.TEXT_STRONG);
            Draw.circle(g, cx - (DOT_HOVER - 2) / 2, cy - (DOT_HOVER - 2) / 2, DOT_HOVER - 2, pts.get(i).color());
        }
        g.disableScissor();
        if (pinned != null && pin < 0 && pts.stream().noneMatch(p -> pinned.equals(p.key()))) pinned = null;

        if (pin >= 0) {
            g.nextStratum();
            popup(ui, xs[pin], ys[pin], pts.get(pin).details().get(), x, y, w, h);
        } else if (hover >= 0) {
            g.nextStratum();
            tooltip(ui, xs[hover], ys[hover], pts.get(hover).tip(), x, w);
        }
    }

    private void tooltip(Ui ui, double px, double py, String tip, int x, int w) {
        int tw = ui.font.width(tip) + 8, th = 12;
        int tx = (int) Math.max(x + 2, Math.min(px - tw / 2.0, x + w - tw - 2)), ty = (int) py - th - 6;
        if (ty < gy) ty = (int) py + 8;
        Draw.roundBordered(ui.g, tx, ty, tw, th, Theme.R_CTRL, Theme.BORDER, Theme.WINDOW);
        ui.text(tip, tx + 4, ty + 2, Theme.TEXT);
    }

    private void popup(Ui ui, double px, double py, List<String[]> rows, int x, int y, int w, int h) {
        int fit = Math.max(1, (h - 16) / 11);
        if (rows.size() > fit && rows.size() > 2) {
            twoColumns(ui, px, py, rows, x, y, w, h, fit);
            return;
        }
        int lw = 0, vw = 0;
        for (String[] r : rows) {
            lw = Math.max(lw, ui.font.width(r[0]));
            vw = Math.max(vw, ui.font.width(r[1]));
        }
        int pw = Math.min(w - 8, lw + vw + 22), ph = rows.size() * 11 + 8;
        int bx = (int) px + 10, by = (int) py - ph / 2;
        if (bx + pw > x + w - 4) bx = (int) px - 10 - pw;
        bx = Math.max(x + 4, bx);
        by = Math.max(y + 4, Math.min(by, y + h - ph - 4));
        Draw.roundBordered(ui.g, bx, by, pw, ph, Theme.R_PANEL, Theme.ACCENT, Theme.WINDOW);
        int ry = by + 5;
        for (String[] r : rows) {
            if (r[1].isEmpty()) ui.text(Draw.ellipsize(ui.font, r[0], pw - 12), bx + 6, ry, Theme.TEXT_STRONG);
            else {
                ui.text(r[0], bx + 6, ry, Theme.MUTED);
                ui.textRight(Draw.ellipsize(ui.font, r[1], pw - lw - 18), bx + pw - 6, ry, Theme.TEXT);
            }
            ry += 11;
        }
    }

    private void twoColumns(Ui ui, double px, double py, List<String[]> rows, int x, int y, int w, int h, int fit) {
        boolean title = rows.get(0)[1].isEmpty();
        List<String[]> body = title ? rows.subList(1, rows.size()) : rows;
        int perCol = (body.size() + 1) / 2, maxPer = Math.max(1, fit - (title ? 1 : 0));
        perCol = Math.min(perCol, maxPer);
        int pw = w - 8, ph = ((title ? 1 : 0) + perCol) * 11 + 8;
        int bx = x + 4, by = (int) Math.max(y + 4, Math.min(py - ph / 2.0, y + h - ph - 4));
        Draw.roundBordered(ui.g, bx, by, pw, ph, Theme.R_PANEL, Theme.ACCENT, Theme.WINDOW);
        int ry = by + 5;
        if (title) {
            ui.text(Draw.ellipsize(ui.font, rows.get(0)[0], pw - 12), bx + 6, ry, Theme.TEXT_STRONG);
            ry += 11;
        }
        int colW = (pw - 18) / 2;
        for (int i = 0; i < body.size() && i < perCol * 2; i++) {
            String[] r = body.get(i);
            int cx = bx + 6 + (i / perCol) * (colW + 6), cy = ry + (i % perCol) * 11;
            int vw = Math.min(ui.font.width(r[1]), colW / 2);
            ui.text(Draw.ellipsize(ui.font, r[0], colW - vw - 4), cx, cy, Theme.MUTED);
            ui.textRight(Draw.ellipsize(ui.font, r[1], colW - Math.min(ui.font.width(r[0]), colW / 2) - 4), cx + colW, cy, Theme.TEXT);
        }
    }

    private boolean inChart(double mx, double my) {
        return mx >= gx - HIT_RADIUS && mx < gx + gw + HIT_RADIUS && my >= gy - HIT_RADIUS && my < gy + gh + HIT_RADIUS;
    }

    boolean scrolled(double mx, double my, double dy) {
        if (view == null || !inChart(mx, my)) return false;
        long anchor = ChartMath.pxToTime(Math.max(gx, Math.min(gx + gw, mx)), view, gx, gw);
        view = ChartMath.zoom(view, anchor, dy > 0 ? 0.8 : 1.25, MIN_SPAN_MS, total);
        return true;
    }

    boolean pressed(double mx, double my) {
        if (view == null || !inChart(mx, my)) return false;
        dragging = true;
        moved = false;
        pressX = mx;
        return true;
    }

    void dragged(double mx, double dx) {
        if (!dragging) return;
        if (Math.abs(mx - pressX) > 3) moved = true;
        if (moved) view = ChartMath.pan(view, -Math.round(dx * view.span() / gw), total);
    }

    void released(double mx, double my) {
        if (!dragging) return;
        dragging = false;
        if (moved) return;
        int i = ChartMath.nearest(xs, ys, mx, my, HIT_RADIUS);
        pinned = i >= 0 && i < points.size() ? points.get(i).key() : null;
        if (pinned == null && onTimeClick != null) onTimeClick.accept(ChartMath.pxToTime(Math.max(gx, Math.min(gx + gw, mx)), view, gx, gw));
    }
}
