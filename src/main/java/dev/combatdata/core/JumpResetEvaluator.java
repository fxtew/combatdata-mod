package dev.combatdata.core;

import java.util.ArrayList;
import java.util.List;

/** Delta = T_jump - T_hit (ms). Success window [lower; upper] after the hit. */
public final class JumpResetEvaluator {
    private JumpResetEvaluator() {
    }

    public enum Result { SUCCESS, TOO_EARLY, TOO_LATE, NO_ATTEMPT, NOT_APPLICABLE, FLY_OUT }

    public record Params(long lowerMs, long upperMs, long preMs, long postMs, boolean excludeHeld, boolean separateFlyOut) {
        public static final Params DEFAULT = new Params(0, 80, 200, 200, false, true);

        public Params(long lowerMs, long upperMs, long preMs, long postMs, boolean excludeHeld) {
            this(lowerMs, upperMs, preMs, postMs, excludeHeld, true);
        }
    }

    public record Attempt(IncomingHitEvent hit, boolean hasJump, long jumpT, long deltaMs, Result result, boolean held) {
    }

    public static Result classify(long delta, Params p) {
        if (delta < p.lowerMs()) return Result.TOO_EARLY;
        if (delta > p.upperMs()) return Result.TOO_LATE;
        return Result.SUCCESS;
    }

    /**
     * Jumps are looked up by binary search in [t - preMs; t + postMs], so this is O((hits + jumps) log n) instead of
     * scanning every jump per hit. Matters for the live buffer, which is recomputed every second.
     */
    public static List<Attempt> evaluate(List<Event> events, Params p) {
        List<IncomingHitEvent> hits = new ArrayList<>();
        List<JumpEvent> jumps = new ArrayList<>();
        for (Event e : events) {
            if (e instanceof IncomingHitEvent h) hits.add(h);
            else if (e instanceof JumpEvent j) jumps.add(j);
        }
        jumps.sort((a, b) -> Long.compare(a.t(), b.t()));
        long[] times = new long[jumps.size()];
        for (int i = 0; i < times.length; i++) times[i] = jumps.get(i).t();
        boolean[] used = new boolean[jumps.size()];
        List<Attempt> out = new ArrayList<>(hits.size());
        for (IncomingHitEvent h : hits) {
            if (!h.knockbackApplied()) {
                out.add(new Attempt(h, false, -1, 0, Result.NOT_APPLICABLE, h.jumpHeld()));
                continue;
            }
            int best = -1;
            double bestDist = Double.MAX_VALUE;
            long bestAbs = Long.MAX_VALUE;
            for (int i = lowerBound(times, h.t() - p.preMs()); i < times.length && times[i] - h.t() <= p.postMs(); i++) {
                if (used[i]) continue;
                long d = times[i] - h.t();
                double dist = d < p.lowerMs() ? p.lowerMs() - d : d > p.upperMs() ? d - p.upperMs() : 0;
                long abs = Math.abs(d);
                if (dist < bestDist || (dist == bestDist && abs < bestAbs)) {
                    best = i;
                    bestDist = dist;
                    bestAbs = abs;
                }
            }
            if (best >= 0) {
                used[best] = true;
                long d = jumps.get(best).t() - h.t();
                Result r = p.separateFlyOut() && jumps.get(best).flyOut() ? Result.FLY_OUT : classify(d, p);
                out.add(new Attempt(h, true, jumps.get(best).t(), d, r, h.jumpHeld()));
            } else {
                out.add(new Attempt(h, false, -1, 0, h.groundedAtHit() ? Result.NO_ATTEMPT : Result.NOT_APPLICABLE,
                        h.jumpHeld()));
            }
        }
        return out;
    }

    static int lowerBound(long[] times, long t) {
        int lo = 0, hi = times.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] < t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
