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
        return classifiable(a, interpMs) && opponentDistance(a, interpMs) > a.rangeOr(AttackEvent.DEFAULT_RANGE);
    }
}
