package dev.combatdata.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Top-down replay. One zoom level for the whole replay so both players stay visible and the view doesn't jump. */
public final class Replay {
    public static final long SKIP_GAP_MS = 1000;
    public static final double MIN_SPAN = 10;

    private Replay() {
    }

    public static List<PositionEvent> frames(List<Event> ev, UUID opp, long from, long to) {
        List<PositionEvent> out = new ArrayList<>();
        if (opp == null) return out;
        for (Event e : ev) {
            if (e instanceof PositionEvent p && p.t() >= from && p.t() <= to && p.find(opp) != null) out.add(p);
        }
        return out;
    }

    public static int indexAt(List<PositionEvent> f, long t) {
        int lo = 0, hi = f.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (f.get(mid).t() <= t) lo = mid + 1;
            else hi = mid;
        }
        return lo - 1;
    }

    /**
     * View width that fits both players around their midpoint, using the 95th percentile of their distance
     * so rare outliers don't shrink the whole replay.
     */
    public static double span(List<PositionEvent> f, UUID opp) {
        if (f.isEmpty()) return MIN_SPAN;
        double[] d = new double[f.size()];
        for (int i = 0; i < d.length; i++) {
            PositionEvent p = f.get(i);
            PositionEvent.Other o = p.find(opp);
            d[i] = o == null ? 0 : Math.hypot(o.x() - p.x(), o.z() - p.z());
        }
        java.util.Arrays.sort(d);
        double p95 = d[Math.min(d.length - 1, (int) Math.floor(d.length * 0.95))];
        return Math.max(MIN_SPAN, p95 * 1.4 + 4);
    }

    public static final long GROUND_MS = 650;

    /**
     * Height above ground at frame i. Ground is the larger of two y minima: GROUND_MS before and GROUND_MS after.
     * On flat ground both are the ground level; on slopes and steps one equals the current y, so the result is 0.
     */
    public static double lift(List<PositionEvent> f, int i, UUID who) {
        if (i < 0 || i >= f.size()) return 0;
        double cur = y(f.get(i), who);
        if (Double.isNaN(cur)) return 0;
        double past = cur, next = cur;
        long t = f.get(i).t();
        for (int k = i - 1; k >= 0 && t - f.get(k).t() <= GROUND_MS; k--) {
            double v = y(f.get(k), who);
            if (!Double.isNaN(v)) past = Math.min(past, v);
        }
        for (int k = i + 1; k < f.size() && f.get(k).t() - t <= GROUND_MS; k++) {
            double v = y(f.get(k), who);
            if (!Double.isNaN(v)) next = Math.min(next, v);
        }
        return Math.max(0, cur - Math.max(past, next));
    }

    private static double y(PositionEvent p, UUID who) {
        if (who == null) return p.y();
        PositionEvent.Other o = p.find(who);
        return o == null ? Double.NaN : o.y();
    }

    public static long advance(List<PositionEvent> f, long t, long stepMs) {
        if (f.isEmpty()) return t;
        long next = t + stepMs;
        int i = indexAt(f, t);
        if (i + 1 < f.size() && f.get(i + 1).t() - Math.max(t, i >= 0 ? f.get(i).t() : t) > SKIP_GAP_MS && next < f.get(i + 1).t()) {
            next = f.get(i + 1).t();
        }
        return Math.min(next, f.get(f.size() - 1).t());
    }
}
