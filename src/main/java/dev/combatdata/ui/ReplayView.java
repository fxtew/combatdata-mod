package dev.combatdata.ui;

import dev.combatdata.core.AttackEvent;
import dev.combatdata.core.JumpResetEvaluator;
import dev.combatdata.core.PositionEvent;
import dev.combatdata.core.Replay;
import net.minecraft.client.gui.GuiGraphics;
import static dev.combatdata.core.Lang.t;

import java.util.List;
import java.util.UUID;

final class ReplayView {
    private static final String[] SPEEDS = {"0.25x", "0.5x", "1x", "2x"};
    private static final double[] SPEED = {0.25, 0.5, 1, 2};
    private static final int BAR = 18, TRAIL_MS = 1000, FLASH_MS = 300, TAG_MS = 900;
    private static final int SHADOW = 0x90000000;
    private static final double MIN_ZOOM = 0.15, MAX_ZOOM = 4;

    private List<PositionEvent> frames = List.of();
    private List<JumpResetEvaluator.Attempt> attempts = List.of();
    private UUID opp;
    private double span = Replay.MIN_SPAN, zoom = 1;
    private long time = -1;
    private boolean playing, scrubbing;
    private int speed = 2;
    private long lastNs;
    private int sx, sy, sw, vx, vy, vw, vh;

    void set(List<PositionEvent> f, UUID opponent, List<JumpResetEvaluator.Attempt> jr) {
        frames = f;
        opp = opponent;
        attempts = jr;
        span = Replay.span(f, opponent);
        if (f.isEmpty()) {
            time = -1;
            playing = false;
        } else if (time < start() || time > end()) time = start();
    }

    long time() {
        return time;
    }

    void seek(long t) {
        if (frames.isEmpty()) return;
        time = Math.max(start(), Math.min(end(), t));
        playing = false;
    }

    private long start() {
        return frames.isEmpty() ? 0 : frames.get(0).t();
    }

    private long end() {
        return frames.isEmpty() ? 0 : frames.get(frames.size() - 1).t();
    }

    boolean scrolled(double mx, double my, double dy) {
        if (frames.isEmpty() || mx < vx || mx >= vx + vw || my < vy || my >= vy + vh) return false;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * (dy > 0 ? 0.8 : 1.25)));
        return true;
    }

    boolean pressed(double mx, double my) {
        if (frames.isEmpty() || mx < sx - 2 || mx > sx + sw + 2 || my < sy - 4 || my > sy + 8) return false;
        scrubbing = true;
        scrub(mx);
        return true;
    }

    void dragged(double mx) {
        if (scrubbing) scrub(mx);
    }

    boolean released() {
        boolean was = scrubbing;
        scrubbing = false;
        return was;
    }

    private void scrub(double mx) {
        double k = Math.max(0, Math.min(1, (mx - sx) / Math.max(1, sw)));
        seek(start() + Math.round(k * (end() - start())));
    }

    void render(Ui ui, int x, int y, int w, int h, List<Chart.Point> points, double range, String oppName) {
        var g = ui.g;
        Draw.roundBordered(g, x, y, w, h, Theme.R_PANEL, Theme.BORDER, Theme.INSET);
        if (frames.isEmpty()) {
            ui.textCenter(t("Нет позиций для повтора", "No positions to replay"), x + w / 2, y + h / 2 - 4, Theme.DIM);
            return;
        }
        long now = System.nanoTime();
        if (playing) {
            long step = Math.round((now - lastNs) / 1_000_000.0 * SPEED[speed]);
            time = Replay.advance(frames, time, Math.max(0, Math.min(step, 250)));
            if (time >= end()) playing = false;
        }
        lastNs = now;
        int mapH = h - BAR - 4;
        vx = x + 1;
        vy = y + 1;
        vw = w - 2;
        vh = mapH - 1;
        map(ui, vx, vy, vw, vh, points, range, oppName);
        controls(ui, x + 4, y + h - BAR - 1, w - 8, points);
    }

    private record View(int cx, int cy, double wx, double wz, double scale) {
        int x(double v) {
            return cx + (int) Math.round((v - wx) * scale);
        }

        int y(double v) {
            return cy + (int) Math.round((v - wz) * scale);
        }
    }

    private void map(Ui ui, int x, int y, int w, int h, List<Chart.Point> points, double range, String oppName) {
        var g = ui.g;
        int i = Replay.indexAt(frames, time);
        PositionEvent f = i >= 0 ? frames.get(i) : null;
        if (f == null || time - f.t() > Replay.SKIP_GAP_MS / 2) {
            ui.textCenter(t("В этот момент позиций нет", "No positions at this moment"), x + w / 2, y + h / 2 - 4, Theme.DIM);
            return;
        }
        PositionEvent.Other o = f.find(opp);
        View v = new View(x + w / 2, y + h / 2, o == null ? f.x() : (f.x() + o.x()) / 2, o == null ? f.z() : (f.z() + o.z()) / 2,
                Math.min(w, h) / (span * zoom));
        g.enableScissor(x, y, x + w, y + h);
        int cell = grid(g, v, x, y, w, h);
        int td = Math.max(2, (int) Math.round(v.scale() * 0.15));
        for (int k = i - 1; k >= 0 && f.t() - frames.get(k).t() <= TRAIL_MS; k -= 2) {
            PositionEvent p = frames.get(k);
            square(g, v.x(p.x()), v.y(p.z()), td, Theme.faded(Theme.GREEN, 0.5));
            PositionEvent.Other po = p.find(opp);
            if (po != null) square(g, v.x(po.x()), v.y(po.z()), td, Theme.faded(Theme.RED, 0.5));
        }
        int mx = v.x(f.x()), mz = v.y(f.z());
        double r = range * v.scale();
        int segs = (int) Math.max(32, Math.min(360, r * 0.8));
        int px0 = mx + (int) Math.round(r), py0 = mz;
        for (int a = 1; a <= segs; a++) {
            double ang = a * 2 * Math.PI / segs;
            int px = mx + (int) Math.round(Math.cos(ang) * r), py = mz + (int) Math.round(Math.sin(ang) * r);
            Draw.line(g, px0, py0, px, py, Theme.CTRL_HOVER);
            px0 = px;
            py0 = py;
        }
        int body = Math.max(7, (int) Math.round(PositionEvent.HALF_W * 2 * v.scale()));
        double myLift = Replay.lift(frames, i, null), oppLift = o == null ? 0 : Replay.lift(frames, i, opp);
        for (Chart.Point p : points) {
            if (p.t() > time || time - p.t() > FLASH_MS) continue;
            boolean mine = p.key() instanceof AttackEvent;
            if (mine && o == null) continue;
            int fx = mine ? v.x(o.x()) : mx, fz = mine ? v.y(o.z()) : mz;
            double lift = mine ? oppLift : myLift;
            int d = body + 10;
            Draw.circle(g, fx - d / 2, fz - raise(lift, v) - d / 2, d, (p.color() & 0x00FFFFFF) | 0xA0000000);
        }
        for (PositionEvent.Other other : f.others()) {
            if (other.id().equals(opp)) continue;
            player(g, v, other.x(), other.z(), other.yaw(), Replay.lift(frames, i, other.id()), body, Theme.DIM);
        }
        if (o != null) {
            player(g, v, o.x(), o.z(), o.yaw(), oppLift, body, Theme.RED);
            int ox = v.x(o.x()), oz = v.y(o.z()) - raise(oppLift, v);
            if (oppName != null) ui.textCenter(Draw.ellipsize(ui.font, oppName, 90), ox, oz - body / 2 - 11, Theme.MUTED);
            ui.textCenter(Fmt.num(f.distanceTo(o), 2) + t(" бл.", " bl."), (ox + mx) / 2, (oz + mz) / 2 - 10, Theme.TEXT);
        }
        player(g, v, f.x(), f.z(), f.yaw(), myLift, body, Theme.GREEN);
        jumpTag(ui, mx, mz - raise(myLift, v) - body / 2 - 11);
        g.disableScissor();
        ui.text(t("клетка: ", "cell: ") + cell + t(cell == 1 ? " блок" : " блоков", cell == 1 ? " block" : " blocks")
                + t(" · колесо: масштаб", " · wheel: zoom"), x + 4, y + 3, Theme.DIM);
    }

    private static int grid(GuiGraphics g, View v, int x, int y, int w, int h) {
        int step = v.scale() >= 6 ? 1 : 5;
        double halfW = w / v.scale() / 2 + step, halfH = h / v.scale() / 2 + step;
        for (long bx = (long) Math.floor((v.wx() - halfW) / step) * step; bx <= v.wx() + halfW; bx += step) {
            int px = v.x(bx);
            g.fill(px, y, px + 1, y + h, Math.floorMod(bx, 5) == 0 && step == 1 ? Theme.GRID_5 : Theme.GRID);
        }
        for (long bz = (long) Math.floor((v.wz() - halfH) / step) * step; bz <= v.wz() + halfH; bz += step) {
            int py = v.y(bz);
            g.fill(x, py, x + w, py + 1, Math.floorMod(bz, 5) == 0 && step == 1 ? Theme.GRID_5 : Theme.GRID);
        }
        return step;
    }

    private static int raise(double lift, View v) {
        if (lift < 0.05) return 0;
        return (int) Math.round(Math.max(lift * v.scale() * 0.7, lift * 8));
    }

    private static void square(GuiGraphics g, int px, int py, int d, int color) {
        g.fill(px - d / 2, py - d / 2, px - d / 2 + d, py - d / 2 + d, color);
    }

    /** Minecraft yaw 0 faces +Z (down in top view), 90 faces -X. */
    private static void player(GuiGraphics g, View v, double wx, double wz, float yaw, double lift, int body, int color) {
        int px = v.x(wx), py = v.y(wz), up = raise(lift, v);
        if (up > 0) square(g, px, py, body, SHADOW);
        py -= up;
        double rad = Math.toRadians(yaw);
        int len = Math.max(10, (int) Math.round(v.scale() * 1.2));
        Draw.line(g, px, py, px + (int) Math.round(-Math.sin(rad) * len), py + (int) Math.round(Math.cos(rad) * len), color);
        square(g, px, py, body, color);
        if (up > 0) {
            int s = body + 2, x0 = px - s / 2, y0 = py - s / 2;
            g.fill(x0, y0, x0 + s, y0 + 1, Theme.TEXT_STRONG);
            g.fill(x0, y0 + s - 1, x0 + s, y0 + s, Theme.TEXT_STRONG);
            g.fill(x0, y0, x0 + 1, y0 + s, Theme.TEXT_STRONG);
            g.fill(x0 + s - 1, y0, x0 + s, y0 + s, Theme.TEXT_STRONG);
        }
    }

    private void jumpTag(Ui ui, int x, int y) {
        JumpResetEvaluator.Attempt best = null;
        for (JumpResetEvaluator.Attempt a : attempts) {
            long at = a.hasJump() ? Math.max(a.jumpT(), a.hit().t()) : a.hit().t();
            if (at <= time && time - at <= TAG_MS) best = a;
        }
        if (best == null) return;
        String ms = Fmt.signed(best.deltaMs(), 0) + t(" мс", " ms");
        String text;
        int color;
        switch (best.result()) {
            case SUCCESS -> { text = "JR ✔ " + ms; color = Theme.GREEN; }
            case TOO_EARLY -> { text = t("JR рано ", "JR early ") + ms; color = Theme.RED; }
            case TOO_LATE -> { text = t("JR поздно ", "JR late ") + ms; color = Theme.RED; }
            case FLY_OUT -> { text = "fly out " + ms; color = Theme.BLUE; }
            case NO_ATTEMPT -> { text = t("без прыжка", "no jump"); color = Theme.MUTED; }
            default -> { return; }
        }
        int tw = ui.font.width(text) + 6;
        Draw.round(ui.g, x - tw / 2, y - 2, tw, 11, 2, Theme.alpha(Theme.WINDOW, 0xD0));
        ui.textCenter(text, x, y, color);
    }

    private void controls(Ui ui, int x, int y, int w, List<Chart.Point> points) {
        int bx = x;
        ui.button(bx, y + 1, 16, 16, "‹", Ui.Kind.GHOST, true, () -> step(-1));
        bx += 18;
        boolean atEnd = time >= end();
        ui.button(bx, y + 1, 20, 16, playing ? "II" : "▶", Ui.Kind.SECONDARY, true, () -> {
            if (!playing && atEnd) time = start();
            playing = !playing;
            lastNs = System.nanoTime();
        });
        bx += 22;
        ui.button(bx, y + 1, 16, 16, "›", Ui.Kind.GHOST, true, () -> step(1));
        bx += 20;
        int segW = ui.segmentedWidth(SPEEDS);
        if (w - (bx - x) - segW > 120) {
            ui.segmented(bx, y + 1, SPEEDS, speed, i -> speed = i);
            bx += segW + 6;
        }
        String tl = Fmt.clockMs(time) + " / " + Fmt.clock(end());
        int tw = ui.font.width(tl);
        boolean showTime = w - (bx - x) - tw > 60;
        int right = x + w - (showTime ? tw + 6 : 0);
        if (showTime) ui.text(tl, right + 6, Ui.textY(y, 18), Theme.MUTED);
        sx = bx;
        sw = Math.max(10, right - bx);
        sy = y + 7;
        var g = ui.g;
        g.fill(sx, sy, sx + sw, sy + 3, Theme.RAISED_HOVER);
        long s = start(), len = Math.max(1, end() - s);
        for (Chart.Point p : points) {
            if (p.t() < s || p.t() > end()) continue;
            int px = sx + (int) ((p.t() - s) * sw / len);
            g.fill(px, sy - 2, px + 1, sy + 5, p.color());
        }
        int kx = sx + (int) ((time - s) * sw / len);
        g.fill(sx, sy, kx, sy + 3, Theme.ACCENT);
        Draw.circle(g, kx - 3, sy - 2, 7, ui.hover(sx - 2, sy - 4, sw + 4, 12) || scrubbing ? Theme.TEXT_STRONG : Theme.ACCENT);
    }

    private void step(int dir) {
        playing = false;
        int i = Replay.indexAt(frames, time);
        if (dir > 0) i = Math.min(frames.size() - 1, i + 1);
        else if (i > 0 && frames.get(i).t() == time) i--;
        if (i >= 0) time = frames.get(i).t();
    }
}
