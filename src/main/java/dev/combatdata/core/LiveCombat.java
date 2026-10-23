package dev.combatdata.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Live counters for the HUD, fed by hits as they happen. Definitions follow the stats: a combo is confirmed hits on
 * one opponent at most COMBO_GAP_MS apart and ends on a hit taken; jump reset uses the same window as
 * JumpResetEvaluator. A jump before the hit means the player is already in the air, so "too early" is final at once.
 * Times are any monotonic milliseconds.
 */
public final class LiveCombat {
    public enum Jr { SUCCESS, EARLY, LATE }

    /** offMs: how far outside the window the jump was, 0 for a success. */
    public record JrVerdict(Jr result, long deltaMs, long offMs, long at) {
    }

    public record ThreeBlock(double opponentDistance, long at) {
    }

    private JumpResetEvaluator.Params params = JumpResetEvaluator.Params.DEFAULT;

    private int combo, lastCombo;
    private UUID comboTarget;
    private long lastHitAt = Long.MIN_VALUE / 2, comboEndedAt = Long.MIN_VALUE / 2, comboChangedAt = Long.MIN_VALUE / 2;
    private double lastReach = Double.NaN;
    private long lastReachAt = Long.MIN_VALUE / 2;
    private JrVerdict lastJr;
    private ThreeBlock lastThreeBlock;

    private long pendingHitAt = -1;
    private long lastJumpAt = Long.MIN_VALUE / 2;
    private boolean lastJumpFlyOut, lastJumpUsed = true;

    private int hits, swings, bestCombo, jrSuccess, jrAttempts, threeBlocks;

    public void setParams(JumpResetEvaluator.Params p) {
        if (p != null) params = p;
    }

    /** A confirmed hit. Returns true if it was a 3 block hit. */
    public boolean hit(long t, UUID target, double reach, boolean threeBlock, double opponentDistance) {
        hits++;
        swings++;
        if (combo > 0 && (t - lastHitAt > Stats.COMBO_GAP_MS || !Objects.equals(target, comboTarget))) endCombo(t);
        combo++;
        comboTarget = target;
        lastHitAt = t;
        comboChangedAt = t;
        bestCombo = Math.max(bestCombo, combo);
        if (Double.isFinite(reach)) {
            lastReach = reach;
            lastReachAt = t;
        }
        if (threeBlock) {
            threeBlocks++;
            lastThreeBlock = new ThreeBlock(opponentDistance, t);
        }
        return threeBlock;
    }

    /** A swing at a player that missed or was never confirmed by the server. */
    public void miss(long t) {
        swings++;
    }

    /** A hit taken. Returns a verdict if a jump right before it makes it "too early". */
    public JrVerdict taken(long t, boolean held) {
        endCombo(t);
        pendingHitAt = -1;
        if (params.excludeHeld() && held) return null;
        long delta = lastJumpAt - t;
        if (!lastJumpUsed && delta < 0 && -delta <= params.preMs()) {
            lastJumpUsed = true;
            if (lastJumpFlyOut && params.separateFlyOut()) return null;
            return verdict(delta, t);
        }
        pendingHitAt = t;
        return null;
    }

    /** A jump. Returns a verdict if it answers a hit taken within the window. */
    public JrVerdict jump(long t, boolean flyOut) {
        lastJumpAt = t;
        lastJumpFlyOut = flyOut;
        lastJumpUsed = false;
        if (pendingHitAt < 0 || t - pendingHitAt > params.postMs()) return null;
        long delta = t - pendingHitAt;
        pendingHitAt = -1;
        lastJumpUsed = true;
        if (flyOut && params.separateFlyOut()) return null;
        return verdict(delta, t);
    }

    public void tick(long t) {
        if (pendingHitAt >= 0 && t - pendingHitAt > params.postMs()) pendingHitAt = -1;
        if (combo > 0 && t - lastHitAt > Stats.COMBO_GAP_MS) endCombo(t);
    }

    private JrVerdict verdict(long delta, long at) {
        JumpResetEvaluator.Result r = JumpResetEvaluator.classify(delta, params);
        Jr jr = r == JumpResetEvaluator.Result.SUCCESS ? Jr.SUCCESS : r == JumpResetEvaluator.Result.TOO_EARLY ? Jr.EARLY : Jr.LATE;
        long off = jr == Jr.EARLY ? params.lowerMs() - delta : jr == Jr.LATE ? delta - params.upperMs() : 0;
        jrAttempts++;
        if (jr == Jr.SUCCESS) jrSuccess++;
        lastJr = new JrVerdict(jr, delta, off, at);
        return lastJr;
    }

    private void endCombo(long t) {
        if (combo > 0) {
            lastCombo = combo;
            comboEndedAt = t;
        }
        combo = 0;
        comboTarget = null;
    }

    public void resetSession() {
        hits = swings = bestCombo = jrSuccess = jrAttempts = threeBlocks = 0;
        combo = lastCombo = 0;
        comboTarget = null;
        lastReach = Double.NaN;
        lastJr = null;
        lastThreeBlock = null;
        pendingHitAt = -1;
        lastJumpUsed = true;
    }

    public int combo() { return combo; }

    public int lastCombo() { return lastCombo; }

    public long comboEndedAt() { return comboEndedAt; }

    public long comboChangedAt() { return comboChangedAt; }

    public double lastReach() { return lastReach; }

    public long lastReachAt() { return lastReachAt; }

    public JrVerdict lastJr() { return lastJr; }

    public ThreeBlock lastThreeBlock() { return lastThreeBlock; }

    public int hits() { return hits; }

    public int swings() { return swings; }

    public int bestCombo() { return bestCombo; }

    public int jrSuccess() { return jrSuccess; }

    public int jrAttempts() { return jrAttempts; }

    public int threeBlocks() { return threeBlocks; }

    public boolean waitingForJump() { return pendingHitAt >= 0; }

    /** Percent of swings that hit, NaN before the first swing. */
    public double accuracyPct() {
        return swings == 0 ? Double.NaN : 100.0 * hits / swings;
    }

    public double jrPct() {
        return jrAttempts == 0 ? Double.NaN : 100.0 * jrSuccess / jrAttempts;
    }
}
