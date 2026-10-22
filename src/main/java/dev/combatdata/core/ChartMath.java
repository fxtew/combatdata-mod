package dev.combatdata.core;

public final class ChartMath {
    static final long[] TIME_STEPS = {1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000,
            600_000, 900_000, 1_800_000, 3_600_000};

    private ChartMath() {
    }

    public record View(long from, long to) {
        public long span() {
            return Math.max(1, to - from);
        }

        public static View all(long durationMs) {
            return new View(0, Math.max(1, durationMs));
        }
    }

    public static double timeToPx(long t, View v, int x, int w) {
        return x + (double) (t - v.from()) * w / v.span();
    }

    public static long pxToTime(double px, View v, int x, int w) {
        return v.from() + Math.round((px - x) * v.span() / Math.max(1, w));
    }

    public static double valueToPx(double val, double min, double max, int y, int h) {
        double c = Math.max(min, Math.min(max, val));
        return y + h - (c - min) * h / Math.max(1e-9, max - min);
    }

    public static boolean outside(double val, double min, double max) {
        return val < min || val > max;
    }

    /** Zoom around anchor (factor < 1 zooms in), keeping anchor at the same screen position. */
    public static View zoom(View v, long anchor, double factor, long minSpan, long total) {
        total = Math.max(1, total);
        long span = Math.round(v.span() * factor);
        span = Math.max(Math.min(minSpan, total), Math.min(total, span));
        double k = (double) (anchor - v.from()) / v.span();
        long from = anchor - Math.round(k * span);
        return clamp(from, span, total);
    }

    public static View pan(View v, long deltaMs, long total) {
        return clamp(v.from() + deltaMs, v.span(), Math.max(1, total));
    }

    private static View clamp(long from, long span, long total) {
        span = Math.min(span, total);
        from = Math.max(0, Math.min(from, total - span));
        return new View(from, from + span);
    }

    public static long timeStep(long spanMs, int widthPx, int minPx) {
        for (long s : TIME_STEPS) {
            if ((double) s * widthPx / Math.max(1, spanMs) >= minPx) return s;
        }
        return TIME_STEPS[TIME_STEPS.length - 1];
    }

    public static long firstTick(long from, long step) {
        return Math.floorDiv(from + step - 1, step) * step;
    }

    public static int nearest(double[] xs, double[] ys, double mx, double my, double radius) {
        int best = -1;
        double bd = radius * radius;
        for (int i = 0; i < xs.length; i++) {
            if (Double.isNaN(xs[i]) || Double.isNaN(ys[i])) continue;
            double dx = xs[i] - mx, dy = ys[i] - my, d = dx * dx + dy * dy;
            if (d <= bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }
}
