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
 *
 * C, no knockback. After our confirmed hit the opponent must fly off about as far as we do on this server (measured
 * knockback in the server profile, normal or sprint). Their movement reaches us later than our hit by our ping plus
 * theirs (the server sends them the knockback, their client moves, the server relays it), so the window starts at
 * our ping and takes the farthest point within their ping + 100 ms + KB_MEASURE_MS: in their favour. Hits followed
 * by their jump reset, or taken while running at us, honestly fly short and are not rated.
 *
 * D, reach. ThreeBlockHit the other way round: on their screen we were where we were up to L ago. Only corrections
 * that shorten the distance are applied (our movement away from them, theirs towards us), with the longest plausible
 * delay, so the estimate is in their favour whatever the exact delays were. Spears are skipped (longer reach).
 *
 * E, hits outside the hitbox. Their look from packets (steps of 360/256 degrees, truncated) against our hitbox anywhere
 * within the delay; the smallest miss over those moments and both eye heights (standing, sneaking) counts.
 *
 * F, too even timing, and G, jump reset: see timing and jumpReset. G is information only.
 */
public final class Behavior {
    /** JUMP_RESET is information only: it never counts towards the level. */
    public enum Check { CRITICALS, KEEP_SPRINT, VELOCITY, REACH, HITBOX, TIMING, JUMP_RESET }

    public enum Level { NONE, ODD, MANY }

    /**
     * Why a check could not run at all. NO_KB: the server profile has too little measured knockback.
     * KB_LIKE_JUMP: this server's knockback lifts as high as a jump, so a jump reset cannot be told apart.
     */
    public enum Missing { NONE, NO_DATA, TOO_FEW, NO_KB, KB_LIKE_JUMP }

    public record Params(int critMin, double critFlat, long critLookMs, int ksMin, int ksSharePct, int ksRatioPct,
                         int velMin, int velSharePct, int velPct, int reachMin, int reachSharePct, double reachExtra,
                         int hbMin, double hbExtraDeg, int timMin, int timTopPct, double timLateTicks, int jrMin,
                         int jrPct) {
        public static final Params DEFAULT = new Params(3, 0.01, 500, 5, 80, 95, 8, 70, 30, 3, 20, 0.3, 3, 10,
                20, 75, 1, 15, 95);

        public Params(int critMin, double critFlat, long critLookMs, int ksMin, int ksSharePct, int ksRatioPct,
                      int velMin, int velSharePct, int velPct, int reachMin, int reachSharePct, double reachExtra,
                      int hbMin, double hbExtraDeg) {
            this(critMin, critFlat, critLookMs, ksMin, ksSharePct, ksRatioPct, velMin, velSharePct, velPct, reachMin,
                    reachSharePct, reachExtra, hbMin, hbExtraDeg, 20, 75, 1, 15, 95);
        }

        public Params(int critMin, double critFlat, long critLookMs, int ksMin, int ksSharePct, int ksRatioPct) {
            this(critMin, critFlat, critLookMs, ksMin, ksSharePct, ksRatioPct, 8, 70, 30, 3, 20, 0.3, 3, 10);
        }
    }

    /**
     * value: A height change (blocks), B speed ratio, C knockback share of expected, D distance on their screen
     * (blocks), E miss angle (degrees), G jump time against their usual knockback lift (ms, minus is earlier; NaN if
     * unknown). groundStates: A only.
     */
    public record Case(long t, double value, double reach, int pingMs, int attackerPingMs, int groundStates, int states) {
    }

    /**
     * checked: hits the check could rate; skipped: hits without data; values: the rated value of every checked hit.
     * maxPerFight: most cases in one fight; for JUMP_RESET the resets on the knockback tick.
     */
    public record Result(Check check, List<Case> cases, int checked, int skipped, boolean flagged, int maxPerFight,
                         Missing missing, double[] values) {
        public Result(Check check, List<Case> cases, int checked, int skipped, boolean flagged, int maxPerFight, Missing missing) {
            this(check, cases, checked, skipped, flagged, maxPerFight, missing, new double[0]);
        }
    }

    /**
     * What looks like cheating but is lag: their ping range and jumps of PING_JUMP_MS or more, ours, and teleports in
     * their positions (more than TELEPORT blocks within two ticks).
     */
    public record Context(int oppPingMin, int oppPingMax, int oppPingJumps, int ourPingMin, int ourPingMax,
                          List<Long> teleports) {
    }

    public record Report(UUID id, String name, Level level, List<Result> results, Context context) {
        public Report(UUID id, String name, Level level, List<Result> results) {
            this(id, name, level, results, new Context(-1, -1, 0, -1, -1, List.of()));
        }

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
        return analyze(events, p, Rules.single(JumpResetEvaluator.Params.DEFAULT, ThreeBlockHit.DEFAULT_INTERP_MS));
    }

    /** rules give each moment its server profile: measured knockback (C), delay and opponent range (D, E). */
    public static Map<UUID, Report> analyze(List<Event> events, Params p, Rules rules) {
        Map<UUID, List<OtherStateEvent>> states = new HashMap<>();
        Map<UUID, List<IncomingHitEvent>> hits = new LinkedHashMap<>();
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, List<Long>> ownHits = new HashMap<>(), swings = new HashMap<>(), hurts = new HashMap<>();
        Map<UUID, List<AttackEvent>> myHits = new HashMap<>();
        List<PositionEvent> pos = new ArrayList<>();
        List<Integer> ourPings = new ArrayList<>();
        for (Event e : events) {
            if (e instanceof OtherStateEvent o) states.computeIfAbsent(o.id(), k -> new ArrayList<>()).add(o);
            else if (e instanceof OtherAnimEvent w && w.anim() == OtherAnimEvent.Anim.SWING) {
                swings.computeIfAbsent(w.id(), k -> new ArrayList<>()).add(w.t());
            } else if (e instanceof OtherAnimEvent w && w.anim() == OtherAnimEvent.Anim.HURT) {
                hurts.computeIfAbsent(w.id(), k -> new ArrayList<>()).add(w.t());
            } else if (e instanceof IncomingHitEvent h && h.attacker() != null && h.kindOr() == IncomingHitEvent.Kind.MELEE) {
                hits.computeIfAbsent(h.attacker(), k -> new ArrayList<>()).add(h);
                if (h.attackerName() != null) names.put(h.attacker(), h.attackerName());
            } else if (e instanceof AttackEvent a && a.outcome() == Outcome.HIT_CONFIRMED && a.target() != null) {
                ownHits.computeIfAbsent(a.target(), k -> new ArrayList<>()).add(a.t());
                myHits.computeIfAbsent(a.target(), k -> new ArrayList<>()).add(a);
            } else if (e instanceof PositionEvent ps) pos.add(ps);
            else if (e instanceof PingEvent pe && pe.pingMs() > 0) ourPings.add(pe.pingMs());
        }
        for (List<OtherStateEvent> l : states.values()) l.sort(Comparator.comparingLong(OtherStateEvent::t));
        // Lookups below search these by time.
        for (Map<UUID, List<Long>> m : List.of(ownHits, swings, hurts)) for (List<Long> l : m.values()) l.sort(null);
        pos.sort(Comparator.comparingLong(PositionEvent::t));
        List<Stats.Fight> fights = Stats.fights(events, Stats.FIGHT_GAP_MS);
        Map<UUID, Report> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<IncomingHitEvent>> en : hits.entrySet()) {
            UUID id = en.getKey();
            List<OtherStateEvent> st = states.getOrDefault(id, List.of());
            List<AttackEvent> mine = myHits.getOrDefault(id, List.of());
            List<Result> rs = List.of(
                    criticals(en.getValue(), st, fights, p),
                    keepSprint(en.getValue(), st, ownHits.getOrDefault(id, List.of()), swings.getOrDefault(id, List.of()), p),
                    velocity(mine, st, pos, rules, fights, p),
                    reach(en.getValue(), rules, fights, p),
                    hitbox(en.getValue(), st, pos, rules, fights, p),
                    timing(en.getValue(), swings.getOrDefault(id, List.of()), p),
                    jumpReset(mine, st, hurts.getOrDefault(id, List.of()), rules, p));
            out.put(id, new Report(id, names.get(id), level(rs, p), rs, context(en.getValue(), mine, st, ourPings)));
        }
        return out;
    }

    static final int PING_JUMP_MS = 100;
    static final double TELEPORT = 2.0;
    /** Unknown opponent ping: assume a high one, in their favour. */
    static final int UNKNOWN_PING_MS = 250;
    /** E: packet angles are truncated to steps of 360/256 degrees in yaw and in pitch. */
    static final double ANGLE_STEP_DEG = 360.0 / 256;
    static final double EYE_SNEAK = 1.27;

    static Context context(List<IncomingHitEvent> hits, List<AttackEvent> mine, List<OtherStateEvent> st, List<Integer> ourPings) {
        List<long[]> pings = new ArrayList<>();
        for (IncomingHitEvent h : hits) if (h.attackerPingMs() >= 0) pings.add(new long[]{h.t(), h.attackerPingMs()});
        for (AttackEvent a : mine) if (a.targetPingMs() >= 0) pings.add(new long[]{a.t(), a.targetPingMs()});
        pings.sort(Comparator.comparingLong(x -> x[0]));
        int min = -1, max = -1, jumps = 0;
        for (int i = 0; i < pings.size(); i++) {
            int v = (int) pings.get(i)[1];
            min = min < 0 ? v : Math.min(min, v);
            max = Math.max(max, v);
            if (i > 0 && Math.abs(v - pings.get(i - 1)[1]) >= PING_JUMP_MS) jumps++;
        }
        int omin = -1, omax = -1;
        for (int v : ourPings) {
            omin = omin < 0 ? v : Math.min(omin, v);
            omax = Math.max(omax, v);
        }
        List<Long> tp = new ArrayList<>();
        for (int i = 1; i < st.size(); i++) {
            OtherStateEvent a = st.get(i - 1), b = st.get(i);
            if (b.tick() - a.tick() > 2 || b.t() - a.t() > 150) continue;
            if (Math.hypot(b.x() - a.x(), b.z() - a.z()) > TELEPORT || Math.abs(b.y() - a.y()) > TELEPORT) tp.add(b.t());
        }
        return new Context(min, max, jumps, omin, omax, List.copyOf(tp));
    }

    /** Expected knockback from the profile in effect at t; NaN if it has too few samples or should not be used. */
    static double expectedKb(Rules rules, long t, boolean sprint) {
        ServerProfile prof = rules.at(t).profile();
        if (prof == null || !prof.useMeasuredKb) return Double.NaN;
        ServerProfile.Knockback k = prof.kb;
        if (sprint && k.sprintN() >= ServerCalibration.MIN_KB) return k.sprintH();
        if (!sprint && k.normalN() >= ServerCalibration.MIN_KB) return k.normalH();
        return k.allN() >= ServerCalibration.MIN_KB ? k.allH() : Double.NaN;
    }

    static Result velocity(List<AttackEvent> mine, List<OtherStateEvent> st, List<PositionEvent> pos, Rules rules,
                           List<Stats.Fight> fights, Params p) {
        List<Case> cases = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        int checked = 0, skipped = 0, noKb = 0;
        for (int n = 0; n < mine.size(); n++) {
            AttackEvent a = mine.get(n);
            double exp = expectedKb(rules, a.t(), a.type() == HitType.SPRINT);
            if (!(exp > 0.05)) {
                noKb++;
                continue;
            }
            int ourPing = Math.max(0, a.pingMs()), oppPing = a.targetPingMs() >= 0 ? a.targetPingMs() : UNKNOWN_PING_MS;
            long t0 = a.t() + ourPing, t1 = t0 + oppPing + 100 + IncomingHitEvent.KB_MEASURE_MS;
            // Another hit of ours inside the window adds its own knockback.
            if (n + 1 < mine.size() && mine.get(n + 1).t() <= t1) continue;
            int i0 = lastAtOrBefore(st, t0);
            PositionEvent me = lastPos(pos, a.t());
            if (i0 < 0 || t0 - st.get(i0).t() > STALE_MS || me == null || a.t() - me.t() > STALE_MS) {
                skipped++;
                continue;
            }
            OtherStateEvent s0 = st.get(i0);
            if (!s0.onGround()) continue;
            // A jump reset honestly cuts the knockback; the server profile is measured without jumps too.
            int up = firstAirborne(st, i0, t1);
            if (up >= 0 && takeOff(st.get(up).y() - st.get(up - 1).y(), st.get(up).tick() - st.get(up - 1).tick()) == 1) continue;
            double ux = s0.x() - me.x(), uz = s0.z() - me.z(), len = Math.hypot(ux, uz);
            if (len < 1e-6) continue;
            ux /= len;
            uz /= len;
            if (towardUs(st, i0, ux, uz) > VEL_TOWARD) continue;
            double far = 0;
            int seen = 0;
            for (int k = i0 + 1; k < st.size() && st.get(k).t() <= t1; k++) {
                far = Math.max(far, (st.get(k).x() - s0.x()) * ux + (st.get(k).z() - s0.z()) * uz);
                seen++;
            }
            // No packets after the hit is missing data (lag), not "stood still".
            if (seen == 0) {
                skipped++;
                continue;
            }
            checked++;
            double share = far / exp;
            values.add(share);
            if (share < p.velPct() / 100.0) cases.add(new Case(a.t(), share, a.reach(), ourPing, a.targetPingMs(), -1, 0));
        }
        boolean flagged = checked >= p.velMin() && cases.size() * 100 >= p.velSharePct() * checked;
        Missing m = checked > 0 ? (checked < p.velMin() ? Missing.TOO_FEW : Missing.NONE)
                : noKb > 0 ? Missing.NO_KB : skipped > 0 ? Missing.NO_DATA : Missing.TOO_FEW;
        return new Result(Check.VELOCITY, List.copyOf(cases), checked, skipped, flagged, cases.size(), m, arr(values));
    }

    /**
     * C: knockback only halves the speed a player already has (LivingEntity.knockback), so one running into us flies
     * off much less while still holding forward. Hits faster than this towards us (blocks per tick) are not rated.
     */
    static final double VEL_TOWARD = 0.1;

    /** Speed towards us before sample i, from the sample at least two ticks earlier; (ux, uz) points away from us. */
    static double towardUs(List<OtherStateEvent> st, int i, double ux, double uz) {
        OtherStateEvent b = st.get(i);
        for (int k = i - 1; k >= 0; k--) {
            OtherStateEvent a = st.get(k);
            int ticks = b.tick() - a.tick();
            if (b.t() - a.t() > STALE_MS) break;
            if (ticks >= 2) return -((b.x() - a.x()) * ux + (b.z() - a.z()) * uz) / ticks;
        }
        return 0;
    }

    /** Weapons with the usual 3 block reach; spears and unknown items are skipped. */
    static boolean shortReach(IncomingHitEvent h) {
        Weapon w = h.weaponOr();
        return w == Weapon.SWORD || w == Weapon.AXE || w == Weapon.HAND || w == Weapon.MACE;
    }

    static Result reach(List<IncomingHitEvent> hits, Rules rules, List<Stats.Fight> fights, Params p) {
        List<Case> cases = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        int checked = 0, skipped = 0;
        int[] perFight = new int[Math.max(1, fights.size())];
        for (IncomingHitEvent h : hits) {
            if (!shortReach(h)) continue;
            if (!h.rated() || !Double.isFinite(h.attackerTowardBps()) || !Double.isFinite(h.selfAwayBps())) {
                skipped++;
                continue;
            }
            Rules.Part part = rules.at(h.t());
            double range = Double.isFinite(part.oppRange()) && part.oppRange() > 0 ? part.oppRange() : h.rangeOr(AttackEvent.DEFAULT_RANGE);
            double d = opponentReach(h, part.interpMs());
            checked++;
            values.add(d);
            if (d > range + p.reachExtra()) {
                cases.add(new Case(h.t(), d, h.reach(), h.pingMs(), h.attackerPingMs(), -1, 0));
                perFight[fightIndex(fights, h.t())]++;
            }
        }
        int max = 0;
        for (int c : perFight) max = Math.max(max, c);
        boolean flagged = max >= p.reachMin() && cases.size() * 100 >= p.reachSharePct() * checked;
        Missing m = checked == 0 ? (skipped > 0 ? Missing.NO_DATA : Missing.TOO_FEW) : Missing.NONE;
        return new Result(Check.REACH, List.copyOf(cases), checked, skipped, flagged, max, m, arr(values));
    }

    /** Distance on the attacker's screen, in their favour: see the class comment (D). */
    public static double opponentReach(IncomingHitEvent h, int interpMs) {
        int ourPing = Math.max(0, h.pingMs()), attPing = h.attackerPingMs() >= 0 ? h.attackerPingMs() : UNKNOWN_PING_MS;
        double lSelf = (ourPing + attPing + Math.max(0, interpMs) + Cooldown.TICK_MS) / 1000.0;
        double lAtt = (attPing + Math.max(0, interpMs) + Cooldown.TICK_MS) / 1000.0;
        return h.reach() - lSelf * Math.max(0, h.selfAwayBps()) - lAtt * Math.max(0, h.attackerTowardBps());
    }

    static Result hitbox(List<IncomingHitEvent> hits, List<OtherStateEvent> st, List<PositionEvent> pos, Rules rules,
                         List<Stats.Fight> fights, Params p) {
        List<Case> cases = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        int checked = 0, skipped = 0;
        int[] perFight = new int[Math.max(1, fights.size())];
        double tol = 2 * ANGLE_STEP_DEG + p.hbExtraDeg();
        for (IncomingHitEvent h : hits) {
            if (!shortReach(h)) continue;
            int last = lastAtOrBefore(st, h.t());
            if (last < 0 || h.t() - st.get(last).t() > STALE_MS) {
                skipped++;
                continue;
            }
            int attPing = h.attackerPingMs() >= 0 ? h.attackerPingMs() : UNKNOWN_PING_MS;
            long delay = Math.max(0, h.pingMs()) + attPing + rules.at(h.t()).interpMs() + 2 * Cooldown.TICK_MS;
            List<Geometry.Aabb> me = new ArrayList<>();
            for (int i = lastPosIndex(pos, h.t()); i >= 0 && h.t() - pos.get(i).t() <= delay; i--) me.add(box(pos.get(i)));
            if (me.isEmpty()) {
                skipped++;
                continue;
            }
            // A fast flick turns tens of degrees per tick and the attack falls between two rotation packets: the
            // packet after the hit counts too, and the turn between neighbouring packets is filled in.
            int from = last, to = Math.min(st.size() - 1, last + 1);
            while (from > 0 && h.t() - st.get(from - 1).t() <= 3 * Cooldown.TICK_MS) from--;
            if (st.get(to).t() - h.t() > 2 * Cooldown.TICK_MS) to = last;
            double best = Double.MAX_VALUE;
            for (int k = from; k <= to && best > 0; k++) {
                OtherStateEvent o = st.get(k), next = k < to ? st.get(k + 1) : o;
                double turn = Geometry.wrap180(next.yaw() - o.yaw());
                for (double f = 0; f <= 1.0001 && best > 0; f += k < to ? 0.125 : 2) {
                    Geometry.V3 look = look((float) (o.yaw() + turn * f), (float) (o.pitch() + (next.pitch() - o.pitch()) * f));
                    double ex = o.x() + (next.x() - o.x()) * f, ey = o.y() + (next.y() - o.y()) * f, ez = o.z() + (next.z() - o.z()) * f;
                    for (int ei = 0; ei < 2; ei++) {
                        Geometry.V3 e = new Geometry.V3(ex, ey + (ei == 0 ? PositionEvent.EYE : EYE_SNEAK), ez);
                        for (Geometry.Aabb q : me) {
                            // The exact angle is costly; skip boxes that cannot beat the best one even in the best case.
                            if (missDegLowerBound(e, look, q) >= best) continue;
                            best = Math.min(best, missDeg(e, look, q));
                        }
                    }
                }
            }
            checked++;
            values.add(best);
            if (best > tol) {
                cases.add(new Case(h.t(), best, h.reach(), h.pingMs(), h.attackerPingMs(), -1, 0));
                perFight[fightIndex(fights, h.t())]++;
            }
        }
        int max = 0;
        for (int c : perFight) max = Math.max(max, c);
        Missing m = checked == 0 ? (skipped > 0 ? Missing.NO_DATA : Missing.TOO_FEW) : Missing.NONE;
        return new Result(Check.HITBOX, List.copyOf(cases), checked, skipped, max >= p.hbMin(), max, m, arr(values));
    }

    /** Minecraft view vector: yaw 0 looks south (+z), positive pitch looks down. */
    static Geometry.V3 look(float yawDeg, float pitchDeg) {
        double y = Math.toRadians(yawDeg), pt = Math.toRadians(pitchDeg);
        return new Geometry.V3(-Math.sin(y) * Math.cos(pt), -Math.sin(pt), Math.cos(y) * Math.cos(pt));
    }

    static Geometry.Aabb box(PositionEvent p) {
        return new Geometry.Aabb(p.x() - PositionEvent.HALF_W, p.y(), p.z() - PositionEvent.HALF_W,
                p.x() + PositionEvent.HALF_W, p.y() + PositionEvent.HEIGHT, p.z() + PositionEvent.HALF_W);
    }

    /**
     * Angle between the look ray and the hitbox, 0 if the ray hits it. Seen from the eye the box is a convex polygon on
     * the sphere of directions, the hull of its 8 corners; the angle to it is the smallest angle to an arc between two
     * corners (every such arc lies inside the hull, and the hull's edges are among them).
     */
    static double missDeg(Geometry.V3 eye, Geometry.V3 look, Geometry.Aabb box) {
        if (Double.isFinite(box.rayDistance(eye, look))) return 0;
        Geometry.V3 v = look.norm();
        Geometry.V3[] c = new Geometry.V3[8];
        for (int i = 0; i < 8; i++) {
            c[i] = new Geometry.V3((i & 1) == 0 ? box.minX() : box.maxX(), (i & 2) == 0 ? box.minY() : box.maxY(),
                    (i & 4) == 0 ? box.minZ() : box.maxZ()).sub(eye).norm();
        }
        // The smallest angle is the largest cosine, so acos runs once at the end.
        double best = -1;
        for (int i = 0; i < 8; i++) {
            best = Math.max(best, v.dot(c[i]));
            for (int j = i + 1; j < 8; j++) best = Math.max(best, arcCos(v, c[i], c[j]));
        }
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, best))));
    }

    /**
     * Never more than missDeg: the box lies inside the sphere around its centre through its corners, and every
     * direction into that sphere is within asin(r / d) of the direction to the centre. 0 when the eye is inside it.
     */
    static double missDegLowerBound(Geometry.V3 eye, Geometry.V3 look, Geometry.Aabb box) {
        double hx = (box.maxX() - box.minX()) / 2, hy = (box.maxY() - box.minY()) / 2, hz = (box.maxZ() - box.minZ()) / 2;
        double cx = box.minX() + hx - eye.x(), cy = box.minY() + hy - eye.y(), cz = box.minZ() + hz - eye.z();
        double r = Math.sqrt(hx * hx + hy * hy + hz * hz), d = Math.sqrt(cx * cx + cy * cy + cz * cz);
        double ll = look.len();
        if (d <= r || ll < 1e-12) return 0;
        double cos = (cx * look.x() + cy * look.y() + cz * look.z()) / (d * ll);
        return Math.max(0, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))) - Math.asin(r / d)));
    }

    /** Cosine of the angle from unit v to the great-circle arc between unit a and b (the shorter arc). */
    private static double arcCos(Geometry.V3 v, Geometry.V3 a, Geometry.V3 b) {
        Geometry.V3 n = cross(a, b);
        double nl = n.len();
        if (nl < 1e-12) return Math.max(v.dot(a), v.dot(b));
        n = n.scale(1 / nl);
        double vn = v.dot(n);
        Geometry.V3 p = v.sub(n.scale(vn));
        if (p.len() < 1e-12) return 0;
        p = p.norm();
        boolean inside = cross(a, p).dot(n) >= 0 && cross(p, b).dot(n) >= 0;
        return inside ? v.dot(p) : Math.max(v.dot(a), v.dot(b));
    }

    private static Geometry.V3 cross(Geometry.V3 a, Geometry.V3 b) {
        return new Geometry.V3(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }

    private static PositionEvent lastPos(List<PositionEvent> pos, long t) {
        int i = lastPosIndex(pos, t);
        return i < 0 ? null : pos.get(i);
    }

    private static int lastPosIndex(List<PositionEvent> pos, long t) {
        int lo = 0, hi = pos.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (pos.get(mid).t() <= t) {
                best = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        return best;
    }

    private static double[] arr(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    /** One flagged check is "odd", two or a crit count twice the threshold in one fight is "many". */
    static Level level(List<Result> rs, Params p) {
        int flagged = 0;
        boolean strong = false;
        for (Result r : rs) {
            if (!r.flagged() || r.check() == Check.JUMP_RESET) continue;
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
        long prev = prevSwing(h, swings);
        return prev < 0 || (h.t() - prev) >= Cooldown.CHARGED * cd * Cooldown.TICK_MS;
    }

    /** The attacker's swing before this hit (every swing resets the charge), -1 if none. */
    static long prevSwing(IncomingHitEvent h, List<Long> swings) {
        int i = firstAtOrAfter(swings, h.t() - SWING_SELF_MS);
        return i > 0 ? swings.get(i - 1) : -1;
    }

    /** Index of the first time not before t in a list sorted by time, size() if none. */
    static int firstAtOrAfter(List<Long> times, long t) {
        int lo = 0, hi = times.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (times.get(mid) < t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** F: hits this many ticks around the full charge were held back by the cooldown, not by range. */
    static final double TIM_WINDOW_TICKS = 3;

    /**
     * F, too even timing. The damage event and the attacker's swings reach us the same way, so the time between the
     * previous swing and the hit is exact to the server tick. The server handles attacks once per tick, so clicking
     * fast after the charge already lands most hits on one or two ticks (honest players: up to about 55% on one
     * tick within 20 hits); a triggerbot that hits at the full charge lands nearly all on one.
     * values: lateness of each counted hit in ticks against the full charge (minus is early).
     */
    static Result timing(List<IncomingHitEvent> hits, List<Long> swings, Params p) {
        List<Double> values = new ArrayList<>();
        int skipped = 0;
        for (IncomingHitEvent h : hits) {
            double cd = cooldownTicks(h.weaponOr(), h.attackerItem());
            if (Double.isNaN(cd)) continue;
            long prev = prevSwing(h, swings);
            if (prev < 0) {
                skipped++;
                continue;
            }
            double late = Math.round((h.t() - prev) / (double) Cooldown.TICK_MS) - cd;
            if (Math.abs(late) <= TIM_WINDOW_TICKS) values.add(late);
        }
        double[] v = arr(values);
        boolean flagged = v.length >= p.timMin() && topTickShare(v) * 100 >= p.timTopPct() && median(v) <= p.timLateTicks();
        Missing m = v.length == 0 && skipped > 0 ? Missing.NO_DATA : v.length < p.timMin() ? Missing.TOO_FEW : Missing.NONE;
        return new Result(Check.TIMING, List.of(), v.length, skipped, flagged, 0, m, v);
    }

    /** Share of values on the most common tick. */
    public static double topTickShare(double[] v) {
        if (v.length == 0) return 0;
        Map<Long, Integer> count = new HashMap<>();
        int best = 0;
        for (double x : v) best = Math.max(best, count.merge(Math.round(x * 2), 1, Integer::sum));
        return best / (double) v.length;
    }

    public static double median(double[] v) {
        if (v.length == 0) return Double.NaN;
        double[] s = v.clone();
        Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }

    /** G: vertical speed after a jump and after a knockback lift on ground (normal hit, sprint hit), vanilla. */
    static final double JUMP_VY = 0.42, KB_VY_NORMAL = 0.361, KB_VY_SPRINT = 0.4;
    static final double RISE_TOL = 0.006;
    /** G: the take-off is looked for this long after their ping. */
    static final long JR_LOOK_MS = 400;

    /** Height after n ticks starting with vertical speed vy: move, then gravity 0.08 and drag 0.98. */
    static double rise(double vy, int ticks) {
        double y = 0;
        for (int i = 0; i < ticks; i++) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
        }
        return y;
    }

    static double peak(double vy) {
        double y = 0, best = 0;
        for (int i = 0; i < 40 && vy > 0; i++) {
            y += vy;
            best = Math.max(best, y);
            vy = (vy - 0.08) * 0.98;
        }
        return best;
    }

    /**
     * 1 a jump, 0 a knockback lift, -1 neither (effects, another server knockback, odd terrain). The tick count comes
     * from our arrival ticks, so one tick either way is allowed.
     */
    static int takeOff(double rise, int ticks) {
        boolean jump = false, kb = false;
        for (int n = Math.max(1, ticks - 1); n <= Math.max(1, Math.min(4, ticks + 1)); n++) {
            if (Math.abs(rise - rise(JUMP_VY, n)) <= RISE_TOL) jump = true;
            if (Math.abs(rise - rise(KB_VY_NORMAL, n)) <= RISE_TOL || Math.abs(rise - rise(KB_VY_SPRINT, n)) <= RISE_TOL) kb = true;
        }
        return jump == kb ? -1 : jump ? 1 : 0;
    }

    /** The server profile measured our knockback lifting about as high as a jump. */
    static boolean kbLikeJump(Rules rules, long t) {
        ServerProfile prof = rules.at(t).profile();
        if (prof == null) return false;
        ServerProfile.Knockback k = prof.kb;
        double top = peak(JUMP_VY) - 0.03;
        return k.normalN() >= ServerCalibration.MIN_KB && k.normalV() >= top
                || k.sprintN() >= ServerCalibration.MIN_KB && k.sprintV() >= top;
    }

    private record TakeOff(AttackEvent hit, long ref, long delay, int ourPing, int oppPing) {
    }

    /**
     * G, jump reset of the opponent, information only. Knockback lifts a player on ground by 0.361 (0.400 after a
     * sprint hit) in the first tick, a jump by 0.42 (LivingEntity.jumpFromGround takes the larger speed), so the first
     * airborne packet after our hit tells which one it was. The take-off reaches us their ping after the damage event
     * (HURT, or our hit plus our ping in older recordings); a reset on the knockback tick arrives when their lift
     * would have, so its time is compared with their nearest lifts. Strong players reset a lot; even a bot cannot
     * look tighter than one tick here.
     * Cases: every reset, value = ms against their usual lift (NaN without lifts to compare with).
     */
    static Result jumpReset(List<AttackEvent> mine, List<OtherStateEvent> st, List<Long> hurts, Rules rules, Params p) {
        List<TakeOff> jumps = new ArrayList<>(), lifts = new ArrayList<>();
        int skipped = 0, likeJump = 0;
        for (int n = 0; n < mine.size(); n++) {
            AttackEvent a = mine.get(n);
            if (kbLikeJump(rules, a.t())) {
                likeJump++;
                continue;
            }
            int ourPing = Math.max(0, a.pingMs()), oppPing = a.targetPingMs() >= 0 ? a.targetPingMs() : UNKNOWN_PING_MS;
            long ref = firstIn(hurts, a.t(), a.t() + Math.max(400, 2L * ourPing + 200));
            if (ref < 0) ref = a.t() + ourPing;
            int i0 = lastAtOrBefore(st, ref);
            if (i0 < 0 || ref - st.get(i0).t() > STALE_MS) {
                skipped++;
                continue;
            }
            if (!st.get(i0).onGround()) continue;
            long end = ref + oppPing + 100 + JR_LOOK_MS;
            int k = firstAirborne(st, i0, end);
            if (k < 0) {
                skipped++;
                continue;
            }
            OtherStateEvent off = st.get(k), before = st.get(k - 1);
            // Our next hit could already have reached them.
            if (n + 1 < mine.size() && mine.get(n + 1).t() + ourPing <= off.t()) continue;
            int kind = takeOff(off.y() - before.y(), off.tick() - before.tick());
            if (kind < 0) {
                skipped++;
                continue;
            }
            (kind == 1 ? jumps : lifts).add(new TakeOff(a, ref, off.t() - ref, ourPing, a.targetPingMs()));
        }
        List<Case> cases = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        int onTick = 0;
        for (TakeOff j : jumps) {
            double usual = usualLift(lifts, j.ref());
            double off = Double.isNaN(usual) ? Double.NaN : j.delay() - usual;
            if (onKbTick(off)) onTick++;
            if (!Double.isNaN(off)) values.add(off);
            cases.add(new Case(j.hit().t(), off, j.hit().reach(), j.ourPing(), j.oppPing(), -1, 0));
        }
        int checked = jumps.size() + lifts.size();
        boolean flagged = checked >= p.jrMin() && onTick * 100 >= p.jrPct() * checked;
        Missing m = checked == 0 && likeJump > 0 ? Missing.KB_LIKE_JUMP : checked == 0 && skipped > 0 ? Missing.NO_DATA
                : checked < p.jrMin() ? Missing.TOO_FEW : Missing.NONE;
        return new Result(Check.JUMP_RESET, List.copyOf(cases), checked, skipped, flagged, onTick, m, arr(values));
    }

    /** G: a reset no earlier than half a tick before their usual lift was on the knockback tick; unknown counts too. */
    public static boolean onKbTick(double offMs) {
        return !(offMs < -Cooldown.TICK_MS / 2.0);
    }

    /** Median delay of the up to 5 lifts nearest in time: their ping drifts during a recording. */
    static double usualLift(List<TakeOff> lifts, long t) {
        if (lifts.isEmpty()) return Double.NaN;
        List<TakeOff> near = new ArrayList<>(lifts);
        near.sort(Comparator.comparingLong(l -> Math.abs(l.ref() - t)));
        double[] d = new double[Math.min(5, near.size())];
        for (int i = 0; i < d.length; i++) d[i] = near.get(i).delay();
        return median(d);
    }

    /** First sample after the on-ground sample i0, up to time end, that is in the air; -1 if none. */
    static int firstAirborne(List<OtherStateEvent> st, int i0, long end) {
        for (int k = i0 + 1; k < st.size() && st.get(k).t() <= end; k++) if (!st.get(k).onGround()) return k;
        return -1;
    }

    private static long firstIn(List<Long> times, long from, long to) {
        int i = firstAtOrAfter(times, from);
        return i < times.size() && times.get(i) <= to ? times.get(i) : -1;
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
        int i = firstAtOrAfter(times, t - ms);
        return i < times.size() && times.get(i) <= t + ms;
    }

    private static int fightIndex(List<Stats.Fight> fights, long t) {
        for (int i = 0; i < fights.size(); i++) if (t >= fights.get(i).startMs() && t <= fights.get(i).endMs()) return i;
        return 0;
    }
}
