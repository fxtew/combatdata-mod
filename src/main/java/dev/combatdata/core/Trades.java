package dev.combatdata.core;

import java.util.*;

/**
 * A trade is a run of confirmed hits between us and one opponent; a new one starts after gapMs without hits.
 * Loss cause: our last action before the first hit taken (miss, uncharged hit), a 3 block hit on us,
 * or a failed jump reset. Input events must already be filtered.
 */
public final class Trades {
    public static final long DEFAULT_GAP_MS = 1500;

    public record Params(long gapMs, boolean byWeight) {
        public static final Params DEFAULT = new Params(DEFAULT_GAP_MS, false);
    }

    /** Vanilla damage scale by charge (Player.baseDamageScaleFactor = 0.2 + 0.8 * charge^2), crit * 1.5. Sprint hits add knockback, not damage. */
    public static double weight(AttackEvent a) {
        double s = Math.max(0, Math.min(1, a.strength()));
        return (0.2 + 0.8 * s * s) * (a.type() == HitType.CRIT ? 1.5 : 1.0);
    }

    /** The opponent's charge is not visible to the client, so 1, or 1.5 for a crit (CRITICAL_HIT packet). */
    public static double weight(IncomingHitEvent h) {
        return h.attackerCrit() == 1 ? 1.5 : 1.0;
    }

    private Trades() {
    }

    public enum Side { ME, OPPONENT }

    public enum Result { WIN, LOSS, DRAW }

    public enum Cause { MISS, WEAK, CAUGHT, FAILED_JR, NONE }

    public record Trade(UUID opponent, String name, long startMs, long endMs, Side first, int mine, int theirs,
                        Cause cause, double mineWeight, double theirsWeight, boolean byWeight) {
        public Trade(UUID opponent, String name, long startMs, long endMs, Side first, int mine, int theirs, Cause cause) {
            this(opponent, name, startMs, endMs, first, mine, theirs, cause, mine, theirs, false);
        }

        public Result result() {
            if (byWeight) {
                double d = mineWeight - theirsWeight;
                return d > 1e-6 ? Result.WIN : d < -1e-6 ? Result.LOSS : Result.DRAW;
            }
            return mine > theirs ? Result.WIN : mine < theirs ? Result.LOSS : Result.DRAW;
        }
    }

    public record Summary(int total, int first, int countFirst, int winsFirst, int countSecond, int winsSecond, int wins,
                          int losses, int draws, Map<Cause, Integer> lossCauses, double weightMine, double weightTheirs) {
        public Summary(int total, int first, int countFirst, int winsFirst, int countSecond, int winsSecond, int wins,
                       int losses, int draws, Map<Cause, Integer> lossCauses) {
            this(total, first, countFirst, winsFirst, countSecond, winsSecond, wins, losses, draws, lossCauses, 0, 0);
        }

        public static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());

        public double firstPct() {
            return total == 0 ? Double.NaN : 100.0 * first / total;
        }

        public double winFirstPct() {
            return countFirst == 0 ? Double.NaN : 100.0 * winsFirst / countFirst;
        }

        public double winSecondPct() {
            return countSecond == 0 ? Double.NaN : 100.0 * winsSecond / countSecond;
        }
    }

    private static final class Open {
        final UUID opp;
        String name;
        final long start;
        long last;
        final Side first;
        int mine, theirs;
        double mineW, theirsW;
        IncomingHitEvent firstIncoming;
        AttackEvent causeAttack;
        final List<IncomingHitEvent> incoming = new ArrayList<>();

        Open(UUID opp, String name, long t, Side first) {
            this.opp = opp;
            this.name = name;
            this.start = this.last = t;
            this.first = first;
        }
    }

    public static List<Trade> split(List<Event> ev, long gapMs, StatFilter sf, JumpResetEvaluator.Params p) {
        return split(ev, new Params(gapMs, false), sf, JumpResetEvaluator.evaluate(ev, p));
    }

    public static List<Trade> split(List<Event> ev, Params tp, StatFilter sf, JumpResetEvaluator.Params p) {
        return split(ev, tp, sf, JumpResetEvaluator.evaluate(ev, p));
    }

    public static List<Trade> split(List<Event> ev, Params tp, StatFilter sf, List<JumpResetEvaluator.Attempt> attempts) {
        long gapMs = tp.gapMs();
        Map<IncomingHitEvent, JumpResetEvaluator.Result> jr = new IdentityHashMap<>();
        for (JumpResetEvaluator.Attempt a : attempts) jr.put(a.hit(), a.result());
        Map<UUID, Open> open = new HashMap<>();
        Map<UUID, AttackEvent> lastAttack = new HashMap<>();
        List<Trade> out = new ArrayList<>();
        for (Event e : ev) {
            UUID who;
            Side side;
            String name;
            if (e instanceof AttackEvent a) {
                if (a.target() == null) continue;
                if (a.outcome() != Outcome.HIT_CONFIRMED) {
                    lastAttack.put(a.target(), a);
                    continue;
                }
                who = a.target();
                side = Side.ME;
                name = a.targetName();
            } else if (e instanceof IncomingHitEvent h) {
                if (h.attacker() == null) continue;
                who = h.attacker();
                side = Side.OPPONENT;
                name = h.attackerName();
            } else continue;
            Open o = open.get(who);
            if (o != null && e.t() - o.last > gapMs) {
                out.add(close(o, jr, tp));
                open.remove(who);
                o = null;
            }
            if (o == null) {
                if (side == Side.ME && !sf.opensTrade((AttackEvent) e)) {
                    lastAttack.put(who, (AttackEvent) e);
                    continue;
                }
                o = new Open(who, name, e.t(), side);
                open.put(who, o);
            }
            o.last = e.t();
            if (name != null) o.name = name;
            if (side == Side.ME) {
                o.mine++;
                o.mineW += weight((AttackEvent) e);
                lastAttack.put(who, (AttackEvent) e);
            } else {
                IncomingHitEvent h = (IncomingHitEvent) e;
                o.theirs++;
                o.theirsW += weight(h);
                o.incoming.add(h);
                if (o.firstIncoming == null) {
                    o.firstIncoming = h;
                    // The loss cause is our last action before the first hit taken, so remember it now.
                    o.causeAttack = lastAttack.get(who);
                }
            }
        }
        for (Open o : open.values()) out.add(close(o, jr, tp));
        out.sort(Comparator.comparingLong(Trade::startMs));
        return out;
    }

    private static Trade close(Open o, Map<IncomingHitEvent, JumpResetEvaluator.Result> jr, Params tp) {
        Cause cause = null;
        Trade t = new Trade(o.opp, o.name, o.start, o.last, o.first, o.mine, o.theirs, null, o.mineW, o.theirsW, tp.byWeight());
        if (t.result() == Result.LOSS) cause = cause(o, jr, tp.gapMs());
        return new Trade(o.opp, o.name, o.start, o.last, o.first, o.mine, o.theirs, cause, o.mineW, o.theirsW, tp.byWeight());
    }

    private static Cause cause(Open o, Map<IncomingHitEvent, JumpResetEvaluator.Result> jr, long gapMs) {
        AttackEvent a = o.causeAttack;
        if (a != null && o.firstIncoming.t() - a.t() <= gapMs) {
            if (a.outcome() == Outcome.MISS) return Cause.MISS;
            if (a.outcome() == Outcome.HIT_CONFIRMED && !Cooldown.charged(a.strength())) return Cause.WEAK;
        }
        if (o.firstIncoming.caught()) return Cause.CAUGHT;
        for (IncomingHitEvent h : o.incoming) {
            JumpResetEvaluator.Result r = jr.get(h);
            if (r == JumpResetEvaluator.Result.TOO_EARLY || r == JumpResetEvaluator.Result.TOO_LATE
                    || r == JumpResetEvaluator.Result.NO_ATTEMPT) return Cause.FAILED_JR;
        }
        return Cause.NONE;
    }

    public static Summary summary(List<Trade> trades) {
        int first = 0, cf = 0, wf = 0, cs = 0, ws = 0, w = 0, l = 0, d = 0;
        double wm = 0, wt = 0;
        Map<Cause, Integer> causes = new EnumMap<>(Cause.class);
        for (Trade t : trades) {
            boolean win = t.result() == Result.WIN;
            wm += t.mineWeight();
            wt += t.theirsWeight();
            if (t.first() == Side.ME) {
                first++;
                cf++;
                if (win) wf++;
            } else {
                cs++;
                if (win) ws++;
            }
            switch (t.result()) {
                case WIN -> w++;
                case LOSS -> {
                    l++;
                    causes.merge(t.cause(), 1, Integer::sum);
                }
                case DRAW -> d++;
            }
        }
        return new Summary(trades.size(), first, cf, wf, cs, ws, w, l, d, causes, wm, wt);
    }
}
