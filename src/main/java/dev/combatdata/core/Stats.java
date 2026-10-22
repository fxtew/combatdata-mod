package dev.combatdata.core;

import java.util.*;

/** Computed from raw events on demand, so any time range or opponent can be analysed. */
public final class Stats {
    private Stats() {
    }

    public record Filter(long fromMs, long toMs, UUID opponent) {
        public static final Filter ALL = new Filter(0, Long.MAX_VALUE, null);

        boolean time(long t) { return t >= fromMs && t <= toMs; }
    }

    public record JumpStats(int attempts, int success, int early, int late, int noAttempt, int notApplicable, int held,
                            double successRatePct, double reactionRatePct, double meanSuccessMs, double medianSuccessMs,
                            double sigmaMs, double biasMs, int flyOut, double flyOutKbMean, double noJumpKbMean) {
    }

    /** Hits closer than this are excluded from aim stats: the angle to center is meaningless point-blank. */
    public static final double MIN_AIM_REACH = 1.0;

    public record HitStats(int confirmed, int unconfirmed, int misses, int missesOutOfRange, double hitRatePct,
                           double reachMean,
                           double reachMedian, double reachMax, double sharePastRangePct, double aimMean,
                           double aimYawAbsMean, double aimPitchAbsMean, double aimYawBias, double aimPitchBias,
                           double strengthMean, double fullChargePct, Map<HitType, Integer> types, int aimSamples,
                           double reachSigma, double hitVMean) {
    }

    public record ComboStats(int dealt, int taken, int maxCombo, double avgCombo) {
        public double trade() {
            return taken == 0 ? Double.NaN : (double) dealt / taken;
        }
    }

    public record ThreeBlockStats(int classified, int count, double sharePct, double oppDistMean, double oppDistMax) {
    }

    public record OpponentStats(UUID uuid, String name, int dealt, int missed, int taken, double hitRatePct,
                                double reachMean) {
    }

    public record Result(JumpStats jump, HitStats hits, ComboStats combo, List<OpponentStats> opponents,
                         double pingMean, double pingSigma, ThreeBlockStats threeBlock,
                         Map<StatFilter.Hidden, Integer> hidden, Trades.Summary trades, TakenStats taken) {
        public Result(JumpStats jump, HitStats hits, ComboStats combo, List<OpponentStats> opponents, double pingMean,
                      double pingSigma, ThreeBlockStats threeBlock, Map<StatFilter.Hidden, Integer> hidden, Trades.Summary trades) {
            this(jump, hits, combo, opponents, pingMean, pingSigma, threeBlock, hidden, trades, TakenStats.EMPTY);
        }

        public Result(JumpStats jump, HitStats hits, ComboStats combo, List<OpponentStats> opponents, double pingMean,
                      double pingSigma, ThreeBlockStats threeBlock) {
            this(jump, hits, combo, opponents, pingMean, pingSigma, threeBlock, Map.of(), Trades.Summary.EMPTY);
        }

        public int hiddenTotal() {
            int n = 0;
            for (int v : hidden.values()) n += v;
            return n;
        }
    }

    public static Result compute(Recording r, JumpResetEvaluator.Params p, Filter f, double rangeBlocks) {
        return compute(r, p, f, rangeBlocks, ThreeBlockHit.DEFAULT_INTERP_MS);
    }

    public static Result compute(Recording r, JumpResetEvaluator.Params p, Filter f, double rangeBlocks, int interpMs) {
        return compute(r, p, f, rangeBlocks, interpMs, StatFilter.NONE);
    }

    public static Result compute(Recording r, JumpResetEvaluator.Params p, Filter f, double rangeBlocks, int interpMs,
                                 StatFilter sf) {
        return compute(r, p, f, rangeBlocks, interpMs, sf, Trades.DEFAULT_GAP_MS);
    }

    public static Result compute(Recording r, JumpResetEvaluator.Params p, Filter f, double rangeBlocks, int interpMs,
                                 StatFilter sf, long tradeGapMs) {
        return compute(r, p, f, rangeBlocks, interpMs, sf, new Trades.Params(tradeGapMs, false));
    }

    public static Result compute(Recording r, JumpResetEvaluator.Params p, Filter f, double rangeBlocks, int interpMs,
                                 StatFilter sf, Trades.Params tp) {
        StatFilter.Applied ap = sf.apply(events(r, f));
        List<Event> ev = ap.kept();
        double[] pings = ev.stream().filter(e -> e instanceof PingEvent).mapToDouble(e -> ((PingEvent) e).pingMs()).toArray();
        List<JumpResetEvaluator.Attempt> attempts = JumpResetEvaluator.evaluate(ev, p);
        return new Result(jump(attempts, p), hits(ev, rangeBlocks, MIN_AIM_REACH), combo(ev), opponents(ev), mean(pings),
                sigma(pings), threeBlock(ev, interpMs), ap.hidden(),
                Trades.summary(Trades.split(ev, tp, sf, attempts)), taken(ev));
    }

    public static List<Fight> fights(List<Event> ev, long gapMs, StatFilter sf) {
        List<Fight> out = new ArrayList<>();
        for (Fight fi : fights(ev, gapMs)) if (sf.fightCounts(fi)) out.add(fi);
        return out;
    }

    public static List<Event> events(Recording r, Filter f) {
        List<Event> ev = new ArrayList<>();
        for (Event e : r.events()) {
            if (!f.time(e.t())) continue;
            if (f.opponent() != null) {
                if (e instanceof AttackEvent a && !f.opponent().equals(a.target())) continue;
                if (e instanceof IncomingHitEvent h && !f.opponent().equals(h.attacker())) continue;
            }
            ev.add(e);
        }
        return ev;
    }

    /** Out-of-range values go to the edge bins; NaN and infinities are skipped. */
    public static int[] histogram(double[] values, double min, double max, int bins) {
        int[] out = new int[Math.max(1, bins)];
        double width = (max - min) / out.length;
        for (double v : values) {
            if (!Double.isFinite(v)) continue;
            int i = width <= 0 ? 0 : (int) Math.floor((v - min) / width);
            out[Math.max(0, Math.min(out.length - 1, i))]++;
        }
        return out;
    }

    /** Combo lengths; a combo ends on a hit taken, a pause, or a target switch. */
    public static List<Integer> comboLengths(List<Event> ev) {
        List<Integer> out = new ArrayList<>();
        int streak = 0;
        AttackEvent prev = null;
        for (Event e : ev) {
            if (e instanceof AttackEvent a && a.outcome() == Outcome.HIT_CONFIRMED) {
                if (streak > 0 && !continues(prev, a)) {
                    out.add(streak);
                    streak = 0;
                }
                streak++;
                prev = a;
            } else if (e instanceof IncomingHitEvent) {
                if (streak > 0) out.add(streak);
                streak = 0;
            }
        }
        if (streak > 0) out.add(streak);
        return out;
    }

    public static final long FIGHT_GAP_MS = 10_000;

    public record Fight(long startMs, long endMs, List<String> opponents, int attacks, int dealt, int taken) {
    }

    public static List<Fight> fights(List<Event> ev, long gapMs) {
        List<Fight> out = new ArrayList<>();
        long start = -1, end = -1;
        int attacks = 0, dealt = 0, taken = 0;
        Set<String> names = new LinkedHashSet<>();
        for (Event e : ev) {
            String nm;
            if (e instanceof AttackEvent a) nm = a.targetName();
            else if (e instanceof IncomingHitEvent h) nm = h.attackerName();
            else continue;
            if (start >= 0 && e.t() - end > gapMs) {
                out.add(new Fight(start, end, List.copyOf(names), attacks, dealt, taken));
                start = -1;
                attacks = dealt = taken = 0;
                names.clear();
            }
            if (start < 0) start = e.t();
            end = e.t();
            if (nm != null) names.add(nm);
            if (e instanceof AttackEvent a) {
                attacks++;
                if (a.outcome() == Outcome.HIT_CONFIRMED) dealt++;
            } else taken++;
        }
        if (start >= 0) out.add(new Fight(start, end, List.copyOf(names), attacks, dealt, taken));
        return out;
    }

    public record WeaponTaken(int n, double reachMean, int caught) {
    }

    public record TakenStats(int rated, double reachMean, double reachMax, int caught, double caughtPct,
                             Map<Weapon, WeaponTaken> byWeapon) {
        public static final TakenStats EMPTY = new TakenStats(0, Double.NaN, Double.NaN, 0, Double.NaN, Map.of());
    }

    static TakenStats taken(List<Event> ev) {
        int n = 0, c = 0;
        List<Double> r = new ArrayList<>();
        Map<Weapon, List<Double>> wr = new EnumMap<>(Weapon.class);
        Map<Weapon, Integer> wc = new EnumMap<>(Weapon.class);
        for (Event e : ev) {
            if (!(e instanceof IncomingHitEvent h) || !h.rated()) continue;
            n++;
            r.add(h.reach());
            wr.computeIfAbsent(h.weaponOr(), k -> new ArrayList<>()).add(h.reach());
            if (h.caught()) {
                c++;
                wc.merge(h.weaponOr(), 1, Integer::sum);
            }
        }
        if (n == 0) return TakenStats.EMPTY;
        Map<Weapon, WeaponTaken> by = new EnumMap<>(Weapon.class);
        for (Map.Entry<Weapon, List<Double>> e : wr.entrySet()) {
            by.put(e.getKey(), new WeaponTaken(e.getValue().size(), mean(arr(e.getValue())), wc.getOrDefault(e.getKey(), 0)));
        }
        double[] ra = arr(r);
        return new TakenStats(n, mean(ra), max(ra), c, 100.0 * c / n, by);
    }

    private static ThreeBlockStats threeBlock(List<Event> ev, int interpMs) {
        int n = 0, c = 0;
        List<Double> d = new ArrayList<>();
        for (Event e : ev) {
            if (!(e instanceof AttackEvent a) || !ThreeBlockHit.classifiable(a, interpMs)) continue;
            n++;
            d.add(ThreeBlockHit.opponentDistance(a, interpMs));
            if (ThreeBlockHit.is(a, interpMs)) c++;
        }
        double[] da = arr(d);
        return new ThreeBlockStats(n, c, n == 0 ? 0 : 100.0 * c / n, mean(da), max(da));
    }

    private static JumpStats jump(List<JumpResetEvaluator.Attempt> list, JumpResetEvaluator.Params p) {
        int succ = 0, early = 0, late = 0, none = 0, na = 0, held = 0, fly = 0;
        List<Double> succD = new ArrayList<>(), all = new ArrayList<>(), flyKb = new ArrayList<>(), noneKb = new ArrayList<>();
        for (JumpResetEvaluator.Attempt a : list) {
            if (p.excludeHeld() && a.held()) { held++; continue; }
            switch (a.result()) {
                case SUCCESS -> { succ++; succD.add((double) a.deltaMs()); all.add((double) a.deltaMs()); }
                case TOO_EARLY -> { early++; all.add((double) a.deltaMs()); }
                case TOO_LATE -> { late++; all.add((double) a.deltaMs()); }
                case NO_ATTEMPT -> {
                    none++;
                    if (Double.isFinite(a.hit().kbAway())) noneKb.add(a.hit().kbAway());
                }
                case NOT_APPLICABLE -> na++;
                case FLY_OUT -> {
                    fly++;
                    if (Double.isFinite(a.hit().kbAway())) flyKb.add(a.hit().kbAway());
                }
            }
        }
        int attempts = succ + early + late;
        double[] s = succD.stream().mapToDouble(Double::doubleValue).toArray();
        double[] al = all.stream().mapToDouble(Double::doubleValue).toArray();
        return new JumpStats(attempts, succ, early, late, none, na, held,
                attempts == 0 ? 0 : 100.0 * succ / attempts,
                attempts + none == 0 ? 0 : 100.0 * attempts / (attempts + none),
                mean(s), median(s), sigma(al), mean(al), fly, flyKb.isEmpty() ? Double.NaN : mean(arr(flyKb)),
                noneKb.isEmpty() ? Double.NaN : mean(arr(noneKb)));
    }

    private static HitStats hits(List<Event> ev, double range, double minAimReach) {
        int conf = 0, unconf = 0, miss = 0, missRange = 0, past = 0, full = 0;
        List<Double> reach = new ArrayList<>(), aim = new ArrayList<>(), yawAbs = new ArrayList<>(),
                pitchAbs = new ArrayList<>(), yaw = new ArrayList<>(), pitch = new ArrayList<>(), str = new ArrayList<>(),
                hv = new ArrayList<>();
        Map<HitType, Integer> types = new EnumMap<>(HitType.class);
        for (Event e : ev) {
            if (!(e instanceof AttackEvent a)) continue;
            double rg = a.rangeOr(range);
            switch (a.outcome()) {
                case MISS -> {
                    miss++;
                    if (a.outOfRangeMiss(rg)) missRange++;
                }
                case HIT_UNCONFIRMED -> unconf++;
                case HIT_CONFIRMED -> {
                    conf++;
                    reach.add(a.reach());
                    if (a.reach() > rg) past++;
                    if (!Double.isNaN(a.aimTotalDeg()) && a.reach() >= minAimReach) {
                        aim.add(a.aimTotalDeg());
                        yaw.add(a.aimYawDeg());
                        pitch.add(a.aimPitchDeg());
                        yawAbs.add(Math.abs(a.aimYawDeg()));
                        pitchAbs.add(Math.abs(a.aimPitchDeg()));
                    }
                    if (Double.isFinite(a.hitV())) hv.add(a.hitV());
                    str.add((double) a.strength());
                    if (a.strength() > HitClassifier.FULL_CHARGE) full++;
                    types.merge(a.type(), 1, Integer::sum);
                }
            }
        }
        int total = conf + unconf + miss;
        double[] r = arr(reach);
        return new HitStats(conf, unconf, miss, missRange, total == 0 ? 0 : 100.0 * conf / total, mean(r), median(r), max(r),
                conf == 0 ? 0 : 100.0 * past / conf, mean(arr(aim)), mean(arr(yawAbs)), mean(arr(pitchAbs)),
                mean(arr(yaw)), mean(arr(pitch)), mean(arr(str)), conf == 0 ? 0 : 100.0 * full / conf, types, aim.size(),
                sigma(r), hv.isEmpty() ? Double.NaN : mean(arr(hv)));
    }

    public static final long COMBO_GAP_MS = Cooldown.MAX_INTERVAL_MS;

    /** Same opponent and gap <= COMBO_GAP_MS; otherwise hits from two separate fights would merge into one combo. */
    static boolean continues(AttackEvent prev, AttackEvent a) {
        return prev != null && a.t() - prev.t() <= COMBO_GAP_MS && Objects.equals(prev.target(), a.target());
    }

    private static ComboStats combo(List<Event> ev) {
        int dealt = 0, taken = 0, streak = 0, maxC = 0;
        List<Double> done = new ArrayList<>();
        AttackEvent prev = null;
        for (Event e : ev) {
            if (e instanceof AttackEvent a && a.outcome() == Outcome.HIT_CONFIRMED) {
                dealt++;
                if (streak > 0 && !continues(prev, a)) {
                    if (streak >= 2) done.add((double) streak);
                    streak = 0;
                }
                streak++;
                prev = a;
                maxC = Math.max(maxC, streak);
            } else if (e instanceof IncomingHitEvent) {
                taken++;
                if (streak >= 2) done.add((double) streak);
                streak = 0;
            }
        }
        if (streak >= 2) done.add((double) streak);
        return new ComboStats(dealt, taken, maxC, mean(arr(done)));
    }

    private static List<OpponentStats> opponents(List<Event> ev) {
        record Acc(String[] name, int[] c, List<Double> reach) {
        }
        Map<UUID, Acc> m = new LinkedHashMap<>();
        for (Event e : ev) {
            UUID id = null;
            String nm = null;
            if (e instanceof AttackEvent a) { id = a.target(); nm = a.targetName(); }
            else if (e instanceof IncomingHitEvent h) { id = h.attacker(); nm = h.attackerName(); }
            if (id == null) continue;
            Acc acc = m.get(id);
            if (acc == null) {
                acc = new Acc(new String[1], new int[3], new ArrayList<>());
                m.put(id, acc);
            }
            acc.name()[0] = nm;
            if (e instanceof AttackEvent a) {
                if (a.outcome() == Outcome.HIT_CONFIRMED) { acc.c()[0]++; acc.reach().add(a.reach()); }
                else acc.c()[1]++;
            } else acc.c()[2]++;
        }
        List<OpponentStats> out = new ArrayList<>();
        for (var en : m.entrySet()) {
            int[] c = en.getValue().c();
            int tot = c[0] + c[1];
            out.add(new OpponentStats(en.getKey(), en.getValue().name()[0], c[0], c[1], c[2],
                    tot == 0 ? 0 : 100.0 * c[0] / tot, mean(arr(en.getValue().reach()))));
        }
        out.sort((a, b) -> Integer.compare(b.dealt() + b.taken(), a.dealt() + a.taken()));
        return out;
    }

    private static double[] arr(List<Double> l) { return l.stream().mapToDouble(Double::doubleValue).toArray(); }

    static double mean(double[] a) {
        if (a.length == 0) return 0;
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }

    static double median(double[] a) {
        if (a.length == 0) return 0;
        double[] c = a.clone();
        Arrays.sort(c);
        return c.length % 2 == 1 ? c[c.length / 2] : (c[c.length / 2 - 1] + c[c.length / 2]) / 2;
    }

    static double sigma(double[] a) {
        if (a.length == 0) return 0;
        double m = mean(a), s = 0;
        for (double v : a) s += (v - m) * (v - m);
        return Math.sqrt(s / a.length);
    }

    static double max(double[] a) {
        double m = 0;
        for (double v : a) m = Math.max(m, v);
        return m;
    }
}
