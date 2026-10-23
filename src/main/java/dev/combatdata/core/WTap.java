package dev.combatdata.core;

import java.util.*;

/**
 * In 26.1.2 a knockback hit (Player.causeExtraKnockback) multiplies the attacker's horizontal speed by 0.6 and
 * stops sprinting. Holding W and the sprint key restores it next tick (AUTO); otherwise the player must re-press W
 * (W_TAP), tap S (S_TAP) or press the sprint key (SPRINT_KEY). Needs InputEvent data.
 */
public final class WTap {
    private WTap() {
    }

    public enum Method { W_TAP, S_TAP, SPRINT_KEY, AUTO, NONE }

    public record Hit(AttackEvent hit, long sincePrevMs, Method method, boolean reset, long releaseMs, long resetMs) {
    }

    public record Result(boolean hasInput, List<Hit> hits, int sprintHits, int afterReset, Map<Method, Integer> methods,
                         double releaseMeanMs, double resetMeanMs, double[] resetDurations, int combos, int brokenNoSprint,
                         int noSprintHits) {
        public double afterResetPct() {
            return sprintHits == 0 ? Double.NaN : 100.0 * afterReset / sprintHits;
        }
    }

    public static Result analyze(List<Event> ev) {
        List<InputEvent> inputs = new ArrayList<>();
        for (Event e : ev) if (e instanceof InputEvent in) inputs.add(in);
        boolean hasInput = !inputs.isEmpty();
        List<Hit> hits = new ArrayList<>();
        Map<Method, Integer> methods = new EnumMap<>(Method.class);
        int sprintHits = 0, afterReset = 0, combos = 0, broken = 0, noSprint = 0, streak = 0;
        List<Double> release = new ArrayList<>(), reset = new ArrayList<>();
        AttackEvent prev = null, lastHit = null;
        for (Event e : ev) {
            if (e instanceof IncomingHitEvent) {
                // Only counts as broken if our previous hit was recent, not from an earlier engagement.
                if (streak > 0 && lastHit != null && e.t() - lastHit.t() <= Cooldown.MAX_INTERVAL_MS) {
                    combos++;
                    if (lastHit != null && lastHit.type() != HitType.SPRINT) broken++;
                }
                streak = 0;
                continue;
            }
            if (!(e instanceof AttackEvent a)) continue;
            if (a.outcome() == Outcome.HIT_CONFIRMED) {
                streak++;
                lastHit = a;
                if (hasInput && prev != null && a.t() - prev.t() <= Cooldown.MAX_INTERVAL_MS) {
                    Hit h = between(prev, a, inputs);
                    hits.add(h);
                    methods.merge(h.method(), 1, Integer::sum);
                    if (a.type() == HitType.SPRINT) {
                        sprintHits++;
                        if (h.reset()) afterReset++;
                    } else if (a.type() != HitType.CRIT && !a.flags().sprinting()) noSprint++;
                    if (h.releaseMs() >= 0) release.add((double) h.releaseMs());
                    if (h.resetMs() >= 0) reset.add((double) h.resetMs());
                }
                // Sprint is lost on a hit, not on a miss, so the reset is looked for since the previous hit.
                prev = a;
            }
        }
        double[] rs = reset.stream().mapToDouble(Double::doubleValue).toArray();
        return new Result(hasInput, hits, sprintHits, afterReset, methods, mean(release), mean(reset), rs, combos, broken, noSprint);
    }

    static Hit between(AttackEvent prev, AttackEvent a, List<InputEvent> inputs) {
        boolean reset = false;
        long wOff = -1, wOn = -1, sOn = -1, sOff = -1;
        boolean sprintKey = false;
        for (InputEvent in : inputs) {
            if (in.t() <= prev.t()) continue;
            if (in.t() > a.t()) break;
            switch (in.what()) {
                case SPRINTING -> { if (in.on()) reset = true; }
                case FORWARD -> {
                    if (!in.on() && wOff < 0) wOff = in.t();
                    else if (in.on() && wOff >= 0 && wOn < 0) wOn = in.t();
                }
                case BACK -> {
                    if (in.on() && sOn < 0) sOn = in.t();
                    else if (!in.on() && sOn >= 0 && sOff < 0) sOff = in.t();
                }
                case SPRINT_KEY -> { if (in.on()) sprintKey = true; }
            }
        }
        long since = a.t() - prev.t();
        if (wOff >= 0 && wOn >= 0) return new Hit(a, since, Method.W_TAP, reset, wOff - prev.t(), wOn - wOff);
        if (sOn >= 0) return new Hit(a, since, Method.S_TAP, reset, sOn - prev.t(), sOff >= 0 ? sOff - sOn : -1);
        if (sprintKey) return new Hit(a, since, Method.SPRINT_KEY, reset, -1, -1);
        return new Hit(a, since, reset ? Method.AUTO : Method.NONE, reset, -1, -1);
    }

    private static double mean(List<Double> l) {
        if (l.isEmpty()) return Double.NaN;
        double s = 0;
        for (double v : l) s += v;
        return s / l.size();
    }
}
