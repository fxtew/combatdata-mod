package dev.combatdata.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Calibrates the jump reset window from measured knockback. Each jump attempt has a delta (ms from hit to jump)
 * and the knockback distance over 0.5 s; the window position with the lowest mean knockback fits the current
 * server and ping best. Window width comes from settings, only its position is searched.
 */
public final class Calibration {
    public static final long BIN_MS = 20, SLIDE_MS = 10;
    public static final int MIN_SAMPLES = 15;
    public static final int MIN_WINDOW_SAMPLES = 6;

    private Calibration() {
    }

    public record Sample(long deltaMs, double kb) {
    }

    public record Bin(long fromMs, long toMs, int n, double meanKb) {
    }

    public record Result(List<Bin> bins, int samples, double baselineKb, int baselineN, double curKb, int curN,
                         long recLower, long recUpper, double recKb, int recN, boolean hasRecommendation) {
    }

    public static List<Sample> samples(List<JumpResetEvaluator.Attempt> attempts, JumpResetEvaluator.Params p) {
        List<Sample> out = new ArrayList<>();
        for (JumpResetEvaluator.Attempt a : attempts) {
            if (!a.hasJump() || !a.hit().knockbackApplied() || !Double.isFinite(a.hit().kbAway())) continue;
            if (p.excludeHeld() && a.held()) continue;
            if (a.result() == JumpResetEvaluator.Result.FLY_OUT) continue;
            out.add(new Sample(a.deltaMs(), a.hit().kbAway()));
        }
        return out;
    }

    public static Result analyze(List<JumpResetEvaluator.Attempt> attempts, JumpResetEvaluator.Params p) {
        List<Sample> s = samples(attempts, p);
        double baseSum = 0;
        int baseN = 0;
        for (JumpResetEvaluator.Attempt a : attempts) {
            if (a.result() == JumpResetEvaluator.Result.NO_ATTEMPT && a.hit().knockbackApplied()
                    && Double.isFinite(a.hit().kbAway())) {
                baseSum += a.hit().kbAway();
                baseN++;
            }
        }
        List<Bin> bins = new ArrayList<>();
        for (long from = -p.preMs(); from < p.postMs(); from += BIN_MS) {
            long to = from + BIN_MS;
            double[] m = mean(s, from, to - 1);
            bins.add(new Bin(from, to, (int) m[1], m[0]));
        }
        double[] cur = mean(s, p.lowerMs(), p.upperMs());
        long width = Math.max(BIN_MS, p.upperMs() - p.lowerMs());
        long bestLo = 0;
        double bestKb = Double.NaN;
        int bestN = 0;
        if (s.size() >= MIN_SAMPLES) {
            for (long lo = -p.preMs(); lo + width <= p.postMs(); lo += SLIDE_MS) {
                double[] m = mean(s, lo, lo + width);
                if (m[1] < MIN_WINDOW_SAMPLES) continue;
                boolean better = Double.isNaN(bestKb) || m[0] < bestKb - 1e-9
                        || (Math.abs(m[0] - bestKb) <= 1e-9 && Math.abs(lo - p.lowerMs()) < Math.abs(bestLo - p.lowerMs()));
                if (better) {
                    bestKb = m[0];
                    bestLo = lo;
                    bestN = (int) m[1];
                }
            }
        }
        boolean rec = !Double.isNaN(bestKb);
        return new Result(bins, s.size(), baseN == 0 ? Double.NaN : baseSum / baseN, baseN, cur[0], (int) cur[1],
                rec ? bestLo : 0, rec ? bestLo + width : 0, bestKb, bestN, rec);
    }

    private static double[] mean(List<Sample> s, long lo, long hi) {
        double sum = 0;
        int n = 0;
        for (Sample x : s) {
            if (x.deltaMs() >= lo && x.deltaMs() <= hi) {
                sum += x.kb();
                n++;
            }
        }
        return new double[]{n == 0 ? Double.NaN : sum / n, n};
    }
}
