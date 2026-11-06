package dev.combatdata.core;

/**
 * 3 block hit: our hit lands while, on the target's screen, we are beyond their attack range.
 * The target sees us with delay L, i.e. where we were L ago:
 * d_opp ≈ d_ray + L * (v_self·û + v_target·û), L = (our ping + target ping) / 2 + interpolation.
 * Running at each other cancels out; chasing a fleeing target adds up.
 */
public final class ThreeBlockHit {
    public static final int DEFAULT_INTERP_MS = 100;

    private ThreeBlockHit() {
    }

    public static double latencyMs(AttackEvent a, int interpMs) {
        if (a.targetPingMs() < 0) return -1;
        return (Math.max(0, a.pingMs()) + a.targetPingMs()) / 2.0 + Math.max(0, interpMs);
    }

    public static double opponentDistance(AttackEvent a, int interpMs) {
        double l = latencyMs(a, interpMs);
        if (l < 0 || !Double.isFinite(a.selfTowardBps()) || !Double.isFinite(a.targetAwayBps())
                || !Double.isFinite(a.reach())) return Double.NaN;
        return a.reach() + l / 1000.0 * (a.selfTowardBps() + a.targetAwayBps());
    }

    public static boolean classifiable(AttackEvent a, int interpMs) {
        return a.outcome() == Outcome.HIT_CONFIRMED && !Double.isNaN(opponentDistance(a, interpMs));
    }

    /** Assumes the target's range equals ours (3.0 if unknown). */
    public static boolean is(AttackEvent a, int interpMs) {
        return is(a, interpMs, Double.NaN);
    }

    /** oppRange: the target's range from the server profile; NaN means the same as ours. */
    public static boolean is(AttackEvent a, int interpMs, double oppRange) {
        double range = Double.isFinite(oppRange) && oppRange > 0 ? oppRange : a.rangeOr(AttackEvent.DEFAULT_RANGE);
        return classifiable(a, interpMs) && opponentDistance(a, interpMs) > range;
    }

    public static boolean is(AttackEvent a, Rules rules) {
        Rules.Part p = rules.at(a.t());
        return is(a, p.interpMs(), p.oppRange());
    }

    public static double opponentDistance(AttackEvent a, Rules rules) {
        return opponentDistance(a, rules.at(a.t()).interpMs());
    }
}
