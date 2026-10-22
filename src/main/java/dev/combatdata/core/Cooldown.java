package dev.combatdata.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Attack cooldown as in Player (26.1.2): delay = 20 / ATTACK_SPEED ticks (sword 1.6 -> 12.5 ticks = 625 ms).
 * Charge is (attackStrengthTicker + 0.5) / delay, and a hit is charged above 0.9. The ticker is also reset by
 * misses and item switches, so "charged" comes from the recorded strength; the interval is only displayed.
 */
public final class Cooldown {
    public static final double DEFAULT_TICKS = 12.5;
    public static final double CHARGED = 0.9;
    public static final long TICK_MS = 50;
    public static final long MAX_INTERVAL_MS = 2000;

    private Cooldown() {
    }

    /**
     * Smallest ticker k with (k + 0.5) / delay > 0.9, i.e. floor(0.9 * delay - 0.5) + 1. Not ceil: when
     * 0.9 * delay - 0.5 is an integer (delay 15) the charge is exactly 0.9, which is not enough.
     */
    public static int chargedTicks(double delayTicks) {
        return (int) Math.max(0, Math.floor(CHARGED * delayTicks - 0.5 + 1e-9) + 1);
    }

    public static long chargedMs(double delayTicks) {
        return chargedTicks(delayTicks) * TICK_MS;
    }

    public static long fullMs(double delayTicks) {
        return Math.round(delayTicks * TICK_MS);
    }

    public static boolean charged(float strength) {
        return strength > CHARGED;
    }

    public record Interval(AttackEvent attack, long ms) {
    }

    public static List<Interval> intervals(List<Event> events) {
        List<Interval> out = new ArrayList<>();
        AttackEvent prev = null;
        for (Event e : events) {
            if (!(e instanceof AttackEvent a)) continue;
            if (prev != null) {
                long d = a.t() - prev.t();
                if (d >= 0 && d <= MAX_INTERVAL_MS) out.add(new Interval(a, d));
            }
            prev = a;
        }
        return out;
    }

    public record Summary(int n, int rushed, double rushedPct, double meanMs, double waitMs) {
    }

    public static Summary summary(List<Interval> list) {
        int rushed = 0, waitN = 0;
        double sum = 0, wait = 0;
        for (Interval i : list) {
            sum += i.ms();
            if (!charged(i.attack().strength())) rushed++;
            else {
                wait += Math.max(0, i.ms() - chargedMs(i.attack().cooldownOr()));
                waitN++;
            }
        }
        int n = list.size();
        return new Summary(n, rushed, n == 0 ? 0 : 100.0 * rushed / n, n == 0 ? Double.NaN : sum / n,
                waitN == 0 ? Double.NaN : wait / waitN);
    }
}
