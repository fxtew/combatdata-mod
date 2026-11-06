package dev.combatdata.core;

import java.util.*;
import java.util.function.Predicate;

/**
 * Comparison of two sides (recordings or groups). Every side is a list of fights with per-metric sums (n, sum, sum of
 * squares), so groups are compared from cached summaries without loading events.
 *
 * The interval of the difference B - A resamples fights, not hits: hits of one fight are related and a per-hit
 * interval comes out too narrow. With fewer than MIN_FIGHTS fights on a side the per-hit formulas are used instead
 * (Newcombe for shares, Welch for means) and the row is marked "few fights".
 */
public final class Compare {
    private Compare() {
    }

    public enum Section { ATTACK, DEFENSE, FIGHTS }

    public enum Better { UP, DOWN, NEUTRAL }

    /** SHARE: values are 0/1, shown as percent. SIGMA: spread of the source metric's values. */
    public enum Kind { SHARE, MEAN, SIGMA }

    public enum Metric {
        HIT_RATE(Section.ATTACK, Kind.SHARE, 0, 100, Better.UP, 30),
        REACH(Section.ATTACK, Kind.MEAN, 0, 4, Better.NEUTRAL, 30),
        AIM_YAW(Section.ATTACK, Kind.MEAN, 0, 20, Better.DOWN, 30),
        CHARGE(Section.ATTACK, Kind.MEAN, 0, 100, Better.UP, 30),
        SPRINT_HITS(Section.ATTACK, Kind.SHARE, 0, 100, Better.NEUTRAL, 30),
        CRIT_HITS(Section.ATTACK, Kind.SHARE, 0, 100, Better.NEUTRAL, 30),
        THREE_BLOCK(Section.ATTACK, Kind.SHARE, 0, 100, Better.UP, 20),
        WTAP(Section.ATTACK, Kind.SHARE, 0, 100, Better.UP, 15),
        JR_SUCCESS(Section.DEFENSE, Kind.SHARE, 0, 100, Better.UP, 10),
        JR_MEAN(Section.DEFENSE, Kind.MEAN, -200, 200, Better.NEUTRAL, 10),
        JR_SIGMA(Section.DEFENSE, Kind.SIGMA, 0, 200, Better.DOWN, 10),
        FLY_OUT(Section.DEFENSE, Kind.SHARE, 0, 100, Better.NEUTRAL, 10),
        COMBO(Section.FIGHTS, Kind.MEAN, 0, 8, Better.UP, 10),
        FIRST_HIT(Section.FIGHTS, Kind.SHARE, 0, 100, Better.UP, 10),
        TRADE_WIN(Section.FIGHTS, Kind.SHARE, 0, 100, Better.UP, 10),
        PING(Section.FIGHTS, Kind.MEAN, 0, 300, Better.NEUTRAL, 10);

        public final Section section;
        public final Kind kind;
        public final double lo, hi;
        public final Better better;
        public final int minSamples;

        Metric(Section section, Kind kind, double lo, double hi, Better better, int minSamples) {
            this.section = section;
            this.kind = kind;
            this.lo = lo;
            this.hi = hi;
            this.better = better;
            this.minSamples = minSamples;
        }

        /** The metric whose sums hold the values (JR_SIGMA is the spread of JR_MEAN's timings). */
        public Metric source() {
            return this == JR_SIGMA ? JR_MEAN : this;
        }

        public double value(Acc a) {
            if (a.n() < minSamples) return Double.NaN;
            double mean = a.sum() / a.n();
            return switch (kind) {
                case SHARE -> 100 * mean;
                case MEAN -> mean;
                case SIGMA -> Math.sqrt(Math.max(0, a.sq() / a.n() - mean * mean));
            };
        }
    }

    public static final int MIN_FIGHTS = 5;
    public static final int REPS = 1000;
    public static final long ROW_BUDGET_MS = 200;
    /** Ping means further apart than this make the conditions differ. */
    public static final double PING_WARN_MS = 50;

    public record Acc(int n, double sum, double sq) {
        public static final Acc ZERO = new Acc(0, 0, 0);

        public Acc plus(Acc o) {
            return new Acc(n + o.n, sum + o.sum, sq + o.sq);
        }

        public Acc add(double v) {
            return new Acc(n + 1, sum + v, sq + v * v);
        }
    }

    /** One fight: start time, server, main opponent and weapon (most of our attacks), sums per metric (by ordinal). */
    public record FightAcc(long startMs, String server, UUID opponent, String opponentName, Weapon weapon, Acc[] acc) {
        public Acc get(Metric m) {
            Acc a = acc[m.source().ordinal()];
            return a == null ? Acc.ZERO : a;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof FightAcc f && startMs == f.startMs && Objects.equals(server, f.server)
                    && Objects.equals(opponent, f.opponent) && Objects.equals(opponentName, f.opponentName)
                    && weapon == f.weapon && Arrays.equals(acc, f.acc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(startMs, server, opponent, opponentName, weapon, Arrays.hashCode(acc));
        }
    }

    /** Fights of an already filtered event list, with the gap and filter of the statistics. */
    public static List<FightAcc> fights(List<Event> ev, Rules rules, StatFilter sf, Trades.Params tp) {
        return fights(ev, rules, sf, tp, null);
    }

    /** Fights with their raw values (for the detail view); keep: fight start times to collect, null for all. */
    public static List<FightRaw> raw(List<Event> ev, Rules rules, StatFilter sf, Trades.Params tp, Set<Long> keep) {
        List<FightRaw> out = new ArrayList<>();
        fights(ev, rules, sf, tp, out);
        if (keep != null) out.removeIf(f -> !keep.contains(f.acc().startMs()));
        return out;
    }

    private static List<FightAcc> fights(List<Event> ev, Rules rules, StatFilter sf, Trades.Params tp, List<FightRaw> rawOut) {
        List<FightAcc> out = new ArrayList<>();
        List<Stats.Fight> fights = Stats.fights(ev, Stats.FIGHT_GAP_MS, sf);
        if (fights.isEmpty()) return out;
        // Jumps and pings around a fight belong to it; fights are at least FIGHT_GAP_MS apart.
        long margin = Math.min(2000, Stats.FIGHT_GAP_MS / 2);
        int from = 0;
        String segment = null;
        for (Stats.Fight f : fights) {
            List<Event> sub = new ArrayList<>();
            while (from < ev.size() && ev.get(from).t() < f.startMs() - margin) {
                if (ev.get(from) instanceof SegmentEvent s) segment = s.server();
                from++;
            }
            for (int i = from; i < ev.size() && ev.get(i).t() <= f.endMs() + margin; i++) {
                Event e = ev.get(i);
                sub.add(e);
                if (e instanceof SegmentEvent s && e.t() <= f.startMs()) segment = s.server();
            }
            String server = rules.at(f.startMs()).server();
            Raw raw = rawOut == null ? null : new Raw();
            FightAcc fa = fight(sub, f.startMs(), server != null ? server : segment, rules, sf, tp, raw);
            out.add(fa);
            if (raw != null) rawOut.add(raw.done(fa));
        }
        return out;
    }

    static FightAcc fight(List<Event> ev, long start, String server, Rules rules, StatFilter sf, Trades.Params tp, Raw raw) {
        Acc[] acc = new Acc[Metric.values().length];
        Arrays.fill(acc, Acc.ZERO);
        Map<UUID, Integer> opp = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        Map<Weapon, Integer> weapons = new EnumMap<>(Weapon.class);
        for (Event e : ev) {
            if (e instanceof PingEvent p && p.pingMs() > 0) add(acc, raw, Metric.PING, p.pingMs());
            if (e instanceof IncomingHitEvent h && h.attacker() != null) {
                opp.merge(h.attacker(), 1, Integer::sum);
                if (h.attackerName() != null) names.put(h.attacker(), h.attackerName());
            }
            if (!(e instanceof AttackEvent a)) continue;
            if (a.target() != null) {
                opp.merge(a.target(), 1, Integer::sum);
                if (a.targetName() != null) names.put(a.target(), a.targetName());
            }
            if (a.weapon() != null) weapons.merge(a.weapon(), 1, Integer::sum);
            boolean hit = a.outcome() == Outcome.HIT_CONFIRMED;
            add(acc, raw, Metric.HIT_RATE, hit ? 1 : 0);
            if (!hit) continue;
            if (raw != null && Double.isFinite(a.hitU()) && Double.isFinite(a.hitV())) raw.aim.add(new double[]{a.hitU(), a.hitV()});
            add(acc, raw, Metric.REACH, a.reach());
            if (!Double.isNaN(a.aimTotalDeg()) && a.reach() >= Stats.MIN_AIM_REACH) add(acc, raw, Metric.AIM_YAW, Math.abs(a.aimYawDeg()));
            add(acc, raw, Metric.CHARGE, 100.0 * a.strength());
            add(acc, raw, Metric.SPRINT_HITS, a.type() == HitType.SPRINT ? 1 : 0);
            add(acc, raw, Metric.CRIT_HITS, a.type() == HitType.CRIT ? 1 : 0);
            Rules.Part p = rules.at(a.t());
            if (ThreeBlockHit.classifiable(a, p.interpMs())) add(acc, raw, Metric.THREE_BLOCK, ThreeBlockHit.is(a, p.interpMs(), p.oppRange()) ? 1 : 0);
        }
        List<JumpResetEvaluator.Attempt> attempts = JumpResetEvaluator.evaluate(ev, rules);
        boolean excludeHeld = rules.at(start).jump().excludeHeld();
        for (JumpResetEvaluator.Attempt a : attempts) {
            if (excludeHeld && a.held()) continue;
            switch (a.result()) {
                case SUCCESS, TOO_EARLY, TOO_LATE -> {
                    add(acc, raw, Metric.JR_SUCCESS, a.result() == JumpResetEvaluator.Result.SUCCESS ? 1 : 0);
                    add(acc, raw, Metric.JR_MEAN, a.deltaMs());
                    add(acc, raw, Metric.FLY_OUT, 0);
                }
                case FLY_OUT -> add(acc, raw, Metric.FLY_OUT, 1);
                default -> {
                }
            }
        }
        WTap.Result wt = WTap.analyze(ev);
        if (wt.hasInput()) for (int i = 0; i < wt.sprintHits(); i++) add(acc, raw, Metric.WTAP, i < wt.afterReset() ? 1 : 0);
        for (int len : Stats.comboLengths(ev)) if (len >= 2) add(acc, raw, Metric.COMBO, len);
        Trades.Summary ts = Trades.summary(Trades.split(ev, tp, sf, attempts));
        for (int i = 0; i < ts.total(); i++) {
            add(acc, raw, Metric.FIRST_HIT, i < ts.first() ? 1 : 0);
            add(acc, raw, Metric.TRADE_WIN, i < ts.wins() ? 1 : 0);
        }
        UUID main = top(opp);
        Weapon w = top(weapons);
        return new FightAcc(start, server, main, main == null ? null : names.get(main), w == null ? Weapon.UNKNOWN : w, acc);
    }

    private static void add(Acc[] acc, Raw raw, Metric m, double v) {
        acc[m.ordinal()] = acc[m.ordinal()].add(v);
        if (raw != null) raw.values.get(m.ordinal()).add(v);
    }

    /** Raw values of one fight while it is computed: by metric ordinal, plus hit points (hitU, hitV). */
    static final class Raw {
        final List<List<Double>> values = new ArrayList<>();
        final List<double[]> aim = new ArrayList<>();

        Raw() {
            for (int i = 0; i < Metric.values().length; i++) values.add(new ArrayList<>());
        }

        FightRaw done(FightAcc fa) {
            double[][] v = new double[values.size()][];
            for (int i = 0; i < v.length; i++) v[i] = values.get(i).stream().mapToDouble(Double::doubleValue).toArray();
            return new FightRaw(fa, v, aim.toArray(new double[0][]));
        }
    }

    /** A fight with every value behind its sums; aim: points of hits on the target (across, up). */
    public record FightRaw(FightAcc acc, double[][] values, double[][] aim) {
        public double[] get(Metric m) {
            return values[m.source().ordinal()];
        }
    }

    private static <K> K top(Map<K, Integer> m) {
        K best = null;
        int n = 0;
        for (Map.Entry<K, Integer> e : m.entrySet()) {
            if (e.getValue() > n) {
                best = e.getKey();
                n = e.getValue();
            }
        }
        return best;
    }

    /** A side: label, the recordings it came from, the fights it compares. */
    public record Side(String label, List<Progress.Summary> sources, List<FightAcc> fights) {
        public Acc total(Metric m) {
            Acc a = Acc.ZERO;
            for (FightAcc f : fights) a = a.plus(f.get(m));
            return a;
        }

        public double value(Metric m) {
            return m.value(total(m));
        }

        public Set<String> servers() {
            Set<String> s = new LinkedHashSet<>();
            for (Progress.Summary x : sources) s.addAll(x.servers());
            return s;
        }

        /** The weapon of most of the fights; null without fights. */
        public Weapon weapon() {
            Map<Weapon, Integer> m = new EnumMap<>(Weapon.class);
            for (FightAcc f : fights) m.merge(f.weapon(), 1, Integer::sum);
            return top(m);
        }

        public double pingMean() {
            Acc a = total(Metric.PING);
            return a.n() == 0 ? Double.NaN : a.sum() / a.n();
        }
    }

    public static Side side(String label, List<Progress.Summary> sums, Predicate<FightAcc> keep) {
        List<FightAcc> f = new ArrayList<>();
        List<Progress.Summary> used = new ArrayList<>();
        for (Progress.Summary s : sums) {
            boolean any = false;
            for (FightAcc x : s.fights()) {
                if (keep == null || keep.test(x)) {
                    f.add(x);
                    any = true;
                }
            }
            if (any || keep == null) used.add(s);
        }
        return new Side(label, List.copyOf(used), List.copyOf(f));
    }

    /** FEW: not enough data; SAME: within chance; CHANGED: a real change of a neutral metric. */
    public enum Verdict { BETTER, WORSE, SAME, CHANGED, FEW }

    /** lo/hi: interval of b - a (NaN if not computed); fewFights: per-hit interval; reps: bootstrap repeats done. */
    public record Row(Metric metric, double a, double b, int nA, int nB, double lo, double hi, Verdict verdict,
                      boolean fewFights, int reps) {
        public double diff() {
            return b - a;
        }

        /** The change in parts of the metric's scale, for ranking. */
        public double size() {
            return Double.isFinite(diff()) ? Math.abs(diff()) / (metric.hi - metric.lo) : 0;
        }
    }

    public static List<Row> compare(Side a, Side b, long seed) {
        return compare(a, b, seed, REPS, ROW_BUDGET_MS);
    }

    public static List<Row> compare(Side a, Side b, long seed, int reps, long budgetMs) {
        List<Row> out = new ArrayList<>();
        for (Metric m : Metric.values()) out.add(row(m, a, b, seed + m.ordinal(), reps, budgetMs));
        return out;
    }

    public static Row row(Metric m, Side a, Side b, long seed, int reps, long budgetMs) {
        Acc ta = a.total(m), tb = b.total(m);
        double va = m.value(ta), vb = m.value(tb);
        if (Double.isNaN(va) || Double.isNaN(vb)) return new Row(m, va, vb, ta.n(), tb.n(), Double.NaN, Double.NaN, Verdict.FEW, false, 0);
        boolean few = a.fights().size() < MIN_FIGHTS || b.fights().size() < MIN_FIGHTS;
        double[] ci;
        int done = 0;
        if (few) ci = perHit(m, ta, tb);
        else {
            double[] d = bootstrap(m, a.fights(), b.fights(), seed, reps, budgetMs);
            done = d.length;
            ci = d.length < 20 ? perHit(m, ta, tb) : new double[]{quantile(d, 0.025), quantile(d, 0.975)};
        }
        return new Row(m, va, vb, ta.n(), tb.n(), ci[0], ci[1], verdict(m, vb - va, ci), few, done);
    }

    static Verdict verdict(Metric m, double diff, double[] ci) {
        if (!(ci[0] > 0) && !(ci[1] < 0)) return Verdict.SAME;
        if (m.better == Better.NEUTRAL) return Verdict.CHANGED;
        return (diff > 0) == (m.better == Better.UP) ? Verdict.BETTER : Verdict.WORSE;
    }

    /**
     * Differences of resampled sides; a resample without enough data on a side is drawn again (up to reps tries
     * more). Stops early when over budgetMs.
     */
    static double[] bootstrap(Metric m, List<FightAcc> a, List<FightAcc> b, long seed, int reps, long budgetMs) {
        Random rnd = new Random(seed);
        double[] out = new double[reps];
        int n = 0;
        long end = System.nanoTime() + budgetMs * 1_000_000L;
        for (int tries = 0; n < reps && tries < 2 * reps; tries++) {
            if ((tries & 63) == 63 && System.nanoTime() > end) break;
            double va = m.value(resample(m, a, rnd)), vb = m.value(resample(m, b, rnd));
            if (Double.isNaN(va) || Double.isNaN(vb)) continue;
            out[n++] = vb - va;
        }
        return Arrays.copyOf(out, n);
    }

    private static Acc resample(Metric m, List<FightAcc> f, Random rnd) {
        Acc s = Acc.ZERO;
        for (int i = 0; i < f.size(); i++) s = s.plus(f.get(rnd.nextInt(f.size())).get(m));
        return s;
    }

    static final double Z = 1.959964;

    /** Interval of b - a from the totals, treating hits as independent. */
    static double[] perHit(Metric m, Acc a, Acc b) {
        double ma = a.sum() / a.n(), mb = b.sum() / b.n();
        double va = Math.max(0, a.sq() / a.n() - ma * ma), vb = Math.max(0, b.sq() / b.n() - mb * mb);
        return switch (m.kind) {
            case SHARE -> {
                double[] d = newcombe(a.sum(), a.n(), b.sum(), b.n());
                yield new double[]{100 * d[0], 100 * d[1]};
            }
            case MEAN -> {
                double se = Math.sqrt(va / Math.max(1, a.n() - 1) + vb / Math.max(1, b.n() - 1));
                yield new double[]{mb - ma - Z * se, mb - ma + Z * se};
            }
            case SIGMA -> {
                double sa = Math.sqrt(va), sb = Math.sqrt(vb);
                double se = Math.sqrt(sa * sa / (2.0 * Math.max(1, a.n() - 1)) + sb * sb / (2.0 * Math.max(1, b.n() - 1)));
                yield new double[]{sb - sa - Z * se, sb - sa + Z * se};
            }
        };
    }

    /** Wilson score interval of a proportion. */
    static double[] wilson(double k, int n) {
        double p = k / n, z2 = Z * Z, den = 1 + z2 / n;
        double c = (p + z2 / (2 * n)) / den, h = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / den;
        return new double[]{c - h, c + h};
    }

    /** Newcombe's hybrid score interval of p_b - p_a (method 10). */
    static double[] newcombe(double ka, int na, double kb, int nb) {
        double pa = ka / na, pb = kb / nb;
        double[] wa = wilson(ka, na), wb = wilson(kb, nb);
        double d = pb - pa;
        double lo = d - Math.sqrt((pb - wb[0]) * (pb - wb[0]) + (wa[1] - pa) * (wa[1] - pa));
        double hi = d + Math.sqrt((wb[1] - pb) * (wb[1] - pb) + (pa - wa[0]) * (pa - wa[0]));
        return new double[]{lo, hi};
    }

    static double quantile(double[] v, double q) {
        double[] s = v.clone();
        Arrays.sort(s);
        double pos = q * (s.length - 1);
        int i = (int) Math.floor(pos);
        return i + 1 < s.length ? s[i] + (pos - i) * (s[i + 1] - s[i]) : s[i];
    }

    /** Up to n biggest real changes of rated metrics, biggest first. */
    public static List<Row> strongest(List<Row> rows, int n) {
        List<Row> l = new ArrayList<>();
        for (Row r : rows) if (r.verdict() == Verdict.BETTER || r.verdict() == Verdict.WORSE) l.add(r);
        l.sort((x, y) -> Double.compare(y.size(), x.size()));
        return l.size() > n ? List.copyOf(l.subList(0, n)) : l;
    }

    public enum Warning { PING, SERVER, WEAPON }

    /** Conditions that differ enough to explain a change by themselves. */
    public static List<Warning> warnings(Side a, Side b) {
        List<Warning> out = new ArrayList<>();
        double pa = a.pingMean(), pb = b.pingMean();
        if (Double.isFinite(pa) && Double.isFinite(pb) && Math.abs(pa - pb) > PING_WARN_MS) out.add(Warning.PING);
        Set<String> sa = a.servers(), sb = b.servers();
        if (!sa.isEmpty() && !sb.isEmpty() && Collections.disjoint(sa, sb)) out.add(Warning.SERVER);
        Weapon wa = a.weapon(), wb = b.weapon();
        if (wa != null && wb != null && wa != wb) out.add(Warning.WEAPON);
        return out;
    }

    /** The recording against the norm: the n recordings before it (all fights of each). */
    public static Side[] norm(List<Progress.Summary> all, Progress.Summary target, int n, String normLabel) {
        List<Progress.Summary> prev = Progress.previous(all, target.wallStartMs(), n);
        return new Side[]{side(normLabel, prev, null), side(target.file(), List.of(target), null)};
    }

    /** The last `days` days (B) against the `days` before them (A). */
    public static Side[] lastDays(List<Progress.Summary> all, long nowMs, int days, String labelA, String labelB) {
        long span = days * 86_400_000L;
        List<Progress.Summary> a = new ArrayList<>(), b = new ArrayList<>();
        for (Progress.Summary s : all) {
            if (s.wallStartMs() >= nowMs - span) b.add(s);
            else if (s.wallStartMs() >= nowMs - 2 * span) a.add(s);
        }
        return new Side[]{side(labelA, a, null), side(labelB, b, null)};
    }

    /** One server (B) against all the others (A). */
    public static Side[] serverVsRest(List<Progress.Summary> all, String server, String labelRest) {
        return new Side[]{side(labelRest, all, f -> !server.equals(f.server())), side(server, all, f -> server.equals(f.server()))};
    }

    /** Fights with fewer values than this are left out of a per-fight share distribution. */
    public static final int SHARE_FIGHT_MIN = 3;

    /** One side's values in the detail view. */
    public record Dist(double[] values, double mean, double median, double sd) {
        public int n() {
            return values.length;
        }
    }

    public static Dist dist(double[] v) {
        if (v.length == 0) return new Dist(v, Double.NaN, Double.NaN, Double.NaN);
        double s = 0, q = 0;
        for (double x : v) {
            s += x;
            q += x * x;
        }
        double mean = s / v.length;
        return new Dist(v, mean, Behavior.median(v), Math.sqrt(Math.max(0, q / v.length - mean * mean)));
    }

    /**
     * Values grouped by fight for the detail view: every raw value, or for a share the percent of each fight with at
     * least SHARE_FIGHT_MIN values (a histogram of zeros and ones says nothing).
     */
    public static List<double[]> perFight(Metric m, List<FightRaw> raw) {
        List<double[]> out = new ArrayList<>();
        for (FightRaw f : raw) {
            double[] v = f.get(m);
            if (m.kind != Kind.SHARE) {
                if (v.length > 0) out.add(v);
            } else if (v.length >= SHARE_FIGHT_MIN) {
                double s = 0;
                for (double x : v) s += x;
                out.add(new double[]{100 * s / v.length});
            }
        }
        return out;
    }

    /** Per-fight shares straight from the sums, without loading events. */
    public static List<double[]> shares(Metric m, List<FightAcc> fights) {
        List<double[]> out = new ArrayList<>();
        for (FightAcc f : fights) {
            Acc a = f.get(m);
            if (a.n() >= SHARE_FIGHT_MIN) out.add(new double[]{100 * a.sum() / a.n()});
        }
        return out;
    }

    public static double[] pool(List<double[]> l) {
        int n = 0;
        for (double[] v : l) n += v.length;
        double[] out = new double[n];
        int i = 0;
        for (double[] v : l) {
            System.arraycopy(v, 0, out, i, v.length);
            i += v.length;
        }
        return out;
    }

    /** Interval of median(B) - median(A), resampling fights; NaN with fewer than MIN_FIGHTS fights on a side. */
    public static double[] medianDiff(List<double[]> a, List<double[]> b, long seed, int reps, long budgetMs) {
        if (a.size() < MIN_FIGHTS || b.size() < MIN_FIGHTS) return new double[]{Double.NaN, Double.NaN};
        Random rnd = new Random(seed);
        double[] d = new double[reps];
        int n = 0;
        long end = System.nanoTime() + budgetMs * 1_000_000L;
        while (n < reps) {
            if ((n & 15) == 15 && System.nanoTime() > end) break;
            d[n++] = Behavior.median(pool(resampleRaw(b, rnd))) - Behavior.median(pool(resampleRaw(a, rnd)));
        }
        if (n < 20) return new double[]{Double.NaN, Double.NaN};
        double[] got = Arrays.copyOf(d, n);
        return new double[]{quantile(got, 0.025), quantile(got, 0.975)};
    }

    private static List<double[]> resampleRaw(List<double[]> f, Random rnd) {
        List<double[]> out = new ArrayList<>(f.size());
        for (int i = 0; i < f.size(); i++) out.add(f.get(rnd.nextInt(f.size())));
        return out;
    }
}
