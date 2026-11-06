package dev.combatdata.core;

import java.util.*;

/**
 * Opponent behaviour after the fact: signs of cheats that matter in sword PvP, each one a count of concrete cases.
 * Not a verdict and no percentages; a check is flagged only by repeated cases. Only melee hits on us are checked.
 *
 * A, criticals without falling. Vanilla needs fallDistance > 0 and not on ground (Player.canCriticalAttack), so an
 * honest crit comes after a fall and the height changes by far more than critFlat. "Packet" criticals fake the fall
 * in packets between two ticks; the server broadcasts only the end of the tick, so the height looks flat.
 * The onGround flag is not required: a faked packet may leave it false.
 *
 * B, no slowdown after a sprint hit. Player.causeExtraKnockback multiplies the attacker's horizontal speed by 0.6.
 * On ground with friction 0.546 that leaves about 0.78 of the speed in the next tick and 0.88 in the one after, 0.83
 * over the two; KeepSprint keeps 1.0. Speed comes from packet positions (OtherStateEvent), not the sprint flag, which
 * an honest client turns back on the next tick. Two packets on each side, because single packets jitter with arrival
 * time. Only fully charged hits count: sprint knockback needs a charge above 0.9 (Cooldown.CHARGED), and the
 * attacker's charge is estimated from their previous swing.
 */
public final class Behavior {
    public enum Check { CRITICALS, KEEP_SPRINT }

    public enum Level { NONE, ODD, MANY }

    /** Why a check could not run at all. */
    public enum Missing { NONE, NO_DATA, TOO_FEW }

    public record Params(int critMin, double critFlat, long critLookMs, int ksMin, int ksSharePct, int ksRatioPct) {
        public static final Params DEFAULT = new Params(3, 0.01, 500, 5, 80, 95);
    }

    /** value: A the height change before the crit (blocks), B the speed ratio after/before. groundStates: A only. */
    public record Case(long t, double value, double reach, int pingMs, int attackerPingMs, int groundStates, int states) {
    }

    /** checked: hits the check could rate; skipped: hits without opponent data. */
    public record Result(Check check, List<Case> cases, int checked, int skipped, boolean flagged, int maxPerFight,
                         Missing missing) {
    }

    public record Report(UUID id, String name, Level level, List<Result> results) {
        public Result get(Check c) {
            for (Result r : results) if (r.check() == c) return r;
            return null;
        }

        public boolean anyCases() {
            for (Result r : results) if (!r.cases().isEmpty()) return true;
            return false;
        }
    }

    /** Opponent data older than this before a hit does not describe the hit. */
    static final long STALE_MS = 2000;
    /** B: speeds per tick below this are not a sprint (on-ground sprint is about 0.28). */
    static final double SPRINT_SPEED = 0.2;
    /** B: our own confirmed hit on the attacker this close changes their speed by knockback. */
    static final long OWN_HIT_MS = 300;
    /** B: the first packet after the hit must come within this time. */
    static final long AFTER_MS = 150;
    static final double FLAT = 0.01;

    private Behavior() {
    }

    public static Map<UUID, Report> analyze(List<Event> events, Params p) {
        Map<UUID, List<OtherStateEvent>> states = new HashMap<>();
        Map<UUID, List<IncomingHitEvent>> hits = new LinkedHashMap<>();
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, List<Long>> ownHits = new HashMap<>(), swings = new HashMap<>();
        for (Event e : events) {
            if (e instanceof OtherStateEvent o) states.computeIfAbsent(o.id(), k -> new ArrayList<>()).add(o);
            else if (e instanceof OtherAnimEvent w && w.anim() == OtherAnimEvent.Anim.SWING) {
                swings.computeIfAbsent(w.id(), k -> new ArrayList<>()).add(w.t());
            }
            else if (e instanceof IncomingHitEvent h && h.attacker() != null && h.kindOr() == IncomingHitEvent.Kind.MELEE) {
                hits.computeIfAbsent(h.attacker(), k -> new ArrayList<>()).add(h);
                if (h.attackerName() != null) names.put(h.attacker(), h.attackerName());
            } else if (e instanceof AttackEvent a && a.outcome() == Outcome.HIT_CONFIRMED && a.target() != null) {
                ownHits.computeIfAbsent(a.target(), k -> new ArrayList<>()).add(a.t());
            }
        }
        for (List<OtherStateEvent> l : states.values()) l.sort(Comparator.comparingLong(OtherStateEvent::t));
        List<Stats.Fight> fights = Stats.fights(events, Stats.FIGHT_GAP_MS);
        Map<UUID, Report> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<IncomingHitEvent>> en : hits.entrySet()) {
            UUID id = en.getKey();
            List<OtherStateEvent> st = states.getOrDefault(id, List.of());
            Result a = criticals(en.getValue(), st, fights, p);
            Result b = keepSprint(en.getValue(), st, ownHits.getOrDefault(id, List.of()), swings.getOrDefault(id, List.of()), p);
            List<Result> rs = List.of(a, b);
            out.put(id, new Report(id, names.get(id), level(rs, p), rs));
        }
        return out;
    }

    /** One flagged check is "odd", two or a crit count twice the threshold in one fight is "many". */
    static Level level(List<Result> rs, Params p) {
        int flagged = 0;
        boolean strong = false;
        for (Result r : rs) {
            if (!r.flagged()) continue;
            flagged++;
            if (r.check() == Check.CRITICALS && r.maxPerFight() >= 2 * p.critMin()) strong = true;
        }
        return flagged >= 2 || strong ? Level.MANY : flagged == 1 ? Level.ODD : Level.NONE;
    }

    static Result criticals(List<IncomingHitEvent> hits, List<OtherStateEvent> st, List<Stats.Fight> fights, Params p) {
        List<Case> cases = new ArrayList<>();
        int checked = 0, skipped = 0, crits = 0;
        int[] perFight = new int[Math.max(1, fights.size())];
        for (IncomingHitEvent h : hits) {
            if (h.attackerCrit() != 1) continue;
            crits++;
            int last = lastAtOrBefore(st, h.t());
            if (last < 0 || h.t() - st.get(last).t() > STALE_MS) {
                skipped++;
                continue;
            }
            checked++;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            int ground = 0, n = 0;
            // The state in effect when the window opens counts too: samples are written only on change.
            for (int i = last; i >= 0; i--) {
                OtherStateEvent o = st.get(i);
                lo = Math.min(lo, o.y());
                hi = Math.max(hi, o.y());
                n++;
                if (o.onGround()) ground++;
                if (o.t() < h.t() - p.critLookMs()) break;
            }
            double range = hi - lo;
            if (range <= p.critFlat() + 1e-9) {
                cases.add(new Case(h.t(), range, h.reach(), h.pingMs(), h.attackerPingMs(), ground, n));
                perFight[fightIndex(fights, h.t())]++;
            }
        }
        int max = 0;
        for (int c : perFight) max = Math.max(max, c);
        Missing m = crits == 0 ? Missing.TOO_FEW : checked == 0 ? Missing.NO_DATA : Missing.NONE;
        return new Result(Check.CRITICALS, List.copyOf(cases), checked, skipped, max >= p.critMin(), max, m);
    }

    static Result keepSprint(List<IncomingHitEvent> hits, List<OtherStateEvent> st, List<Long> ownHits, List<Long> swings,
                             Params p) {
        List<Case> cases = new ArrayList<>();
        int checked = 0, skipped = 0;
        for (IncomingHitEvent h : hits) {
            int i = lastAtOrBefore(st, h.t());
            if (i < 2 || h.t() - st.get(i).t() > STALE_MS) {
                skipped++;
                continue;
            }
            if (i + 2 >= st.size() || st.get(i + 2).t() - h.t() > 2 * AFTER_MS || !st.get(i).sprinting()) continue;
            boolean flat = true;
            for (int k = i - 2; k <= i + 2; k++) {
                OtherStateEvent o = st.get(k);
                if (!o.onGround() || Math.abs(o.y() - st.get(i).y()) > FLAT) flat = false;
            }
            if (!flat || near(ownHits, h.t(), OWN_HIT_MS) || !charged(h, swings)) continue;
            // Packets bunched by lag land in the same tick and fake a speed jump: both sides must span the same ticks.
            int tb = st.get(i).tick() - st.get(i - 2).tick(), ta = st.get(i + 2).tick() - st.get(i).tick();
            if (tb != ta || tb <= 0) continue;
            double before = speed(st.get(i - 2), st.get(i)), after = speed(st.get(i), st.get(i + 2));
            if (!(before >= SPRINT_SPEED) || !Double.isFinite(after)) continue;
            checked++;
            double ratio = after / before;
            if (ratio > p.ksRatioPct() / 100.0) cases.add(new Case(h.t(), ratio, h.reach(), h.pingMs(), h.attackerPingMs(), -1, 5));
        }
        boolean flagged = checked >= p.ksMin() && cases.size() * 100 >= p.ksSharePct() * checked;
        Missing m = checked == 0 && skipped > 0 ? Missing.NO_DATA : checked < p.ksMin() ? Missing.TOO_FEW : Missing.NONE;
        return new Result(Check.KEEP_SPRINT, List.copyOf(cases), checked, skipped, flagged, cases.size(), m);
    }

    /**
     * Full charge from the time since the attacker's previous swing. The hit's own swing packet comes right after the
     * damage event, so swings within SWING_SELF_MS before the hit are skipped. No earlier swing counts as charged.
     */
    static boolean charged(IncomingHitEvent h, List<Long> swings) {
        double cd = cooldownTicks(h.weaponOr(), h.attackerItem());
        if (Double.isNaN(cd)) return true;
        long prev = -1;
        for (long t : swings) {
            if (t >= h.t() - SWING_SELF_MS) break;
            prev = t;
        }
        return prev < 0 || (h.t() - prev) >= Cooldown.CHARGED * cd * Cooldown.TICK_MS;
    }

    /** 20 / attack speed (Player.getCurrentItemAttackStrengthDelay) for vanilla items; NaN if unknown. */
    static double cooldownTicks(Weapon w, String item) {
        return switch (w) {
            case SWORD -> 20 / 1.6;
            case HAND -> 20 / 4.0;
            case MACE -> 20 / 0.6;
            case AXE -> item == null ? Double.NaN : item.contains("wooden") || item.contains("stone") ? 20 / 0.8
                    : item.contains("iron") ? 20 / 0.9 : 20 / 1.0;
            default -> Double.NaN;
        };
    }

    static final long SWING_SELF_MS = 25;

    /** Horizontal blocks per tick between two samples; ticks come from our client tick count, at least 1. */
    static double speed(OtherStateEvent a, OtherStateEvent b) {
        int ticks = Math.max(1, b.tick() - a.tick());
        return Math.hypot(b.x() - a.x(), b.z() - a.z()) / ticks;
    }

    static int lastAtOrBefore(List<OtherStateEvent> st, long t) {
        int lo = 0, hi = st.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (st.get(mid).t() <= t) {
                best = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        return best;
    }

    private static boolean near(List<Long> times, long t, long ms) {
        for (long x : times) if (Math.abs(x - t) <= ms) return true;
        return false;
    }

    private static int fightIndex(List<Stats.Fight> fights, long t) {
        for (int i = 0; i < fights.size(); i++) if (t >= fights.get(i).startMs() && t <= fights.get(i).endMs()) return i;
        return 0;
    }
}
