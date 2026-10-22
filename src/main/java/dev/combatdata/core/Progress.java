package dev.combatdata.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Per-recording summaries store sums and counts rather than percentages, so grouping by day weighs every hit
 * equally instead of averaging averages.
 */
public final class Progress {
    private Progress() {
    }

    public record Metrics(int attacks, int confirmed, int jrAttempts, int jrSuccess, double reachSum, int tbClassified,
                          int tbCount, double strengthSum, int trades, int firstHits) {
        public static final Metrics ZERO = new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        public Metrics(int attacks, int confirmed, int jrAttempts, int jrSuccess, double reachSum, int tbClassified,
                       int tbCount, double strengthSum) {
            this(attacks, confirmed, jrAttempts, jrSuccess, reachSum, tbClassified, tbCount, strengthSum, 0, 0);
        }

        public Metrics plus(Metrics o) {
            return new Metrics(attacks + o.attacks, confirmed + o.confirmed, jrAttempts + o.jrAttempts,
                    jrSuccess + o.jrSuccess, reachSum + o.reachSum, tbClassified + o.tbClassified, tbCount + o.tbCount,
                    strengthSum + o.strengthSum, trades + o.trades, firstHits + o.firstHits);
        }

        public static Metrics of(Stats.Result r) {
            Stats.HitStats h = r.hits();
            int conf = h.confirmed();
            return new Metrics(conf + h.unconfirmed() + h.misses(), conf, r.jump().attempts(), r.jump().success(),
                    conf == 0 ? 0 : h.reachMean() * conf, r.threeBlock().classified(), r.threeBlock().count(),
                    conf == 0 ? 0 : h.strengthMean() * conf, r.trades().total(), r.trades().first());
        }
    }

    public enum Metric {
        HIT_RATE(5, true), JR_SUCCESS(3, true), REACH(3, false), THREE_BLOCK(3, true), CHARGE(3, true),
        FIRST_HIT(3, true);

        public final int minSamples;
        public final boolean percent;

        Metric(int minSamples, boolean percent) {
            this.minSamples = minSamples;
            this.percent = percent;
        }

        public int samples(Metrics m) {
            return switch (this) {
                case HIT_RATE -> m.attacks();
                case JR_SUCCESS -> m.jrAttempts();
                case REACH, CHARGE -> m.confirmed();
                case THREE_BLOCK -> m.tbClassified();
                case FIRST_HIT -> m.trades();
            };
        }

        public double value(Metrics m) {
            int n = samples(m);
            if (n < minSamples) return Double.NaN;
            return switch (this) {
                case HIT_RATE -> 100.0 * m.confirmed() / n;
                case JR_SUCCESS -> 100.0 * m.jrSuccess() / n;
                case REACH -> m.reachSum() / n;
                case THREE_BLOCK -> 100.0 * m.tbCount() / n;
                case CHARGE -> 100.0 * m.strengthSum() / n;
                case FIRST_HIT -> 100.0 * m.firstHits() / n;
            };
        }
    }

    public enum Group { RECORDING, DAY }

    public record Opp(String name, Metrics m) {
    }

    public record Summary(String file, long wallStartMs, long durationMs, List<String> servers, Metrics all,
                          Map<UUID, Opp> opponents) {
        public Metrics select(UUID opp) {
            if (opp == null) return all;
            Opp o = opponents.get(opp);
            return o == null ? Metrics.ZERO : o.m();
        }
    }

    public record Point(long t, double value, int samples, List<Summary> sources) {
    }

    public record Delta(Metric metric, double current, double previous) {
        public double diff() {
            return current - previous;
        }
    }

    public static String cacheKey(StatFilter sf, JumpResetEvaluator.Params p, int interpMs) {
        return cacheKey(sf, p, interpMs, Trades.DEFAULT_GAP_MS);
    }

    /** Bump the version when the summary format changes; cached summaries with an older key are recomputed. */
    public static String cacheKey(StatFilter sf, JumpResetEvaluator.Params p, int interpMs, long tradeGapMs) {
        return "v2/" + sf + "/" + p + "/" + interpMs + "/" + tradeGapMs;
    }

    public static Summary summarize(String file, Recording r, JumpResetEvaluator.Params p, int interpMs, StatFilter sf) {
        return summarize(file, r, p, interpMs, sf, Trades.DEFAULT_GAP_MS);
    }

    public static Summary summarize(String file, Recording r, JumpResetEvaluator.Params p, int interpMs, StatFilter sf,
                                    long tradeGapMs) {
        return summarize(file, r, p, interpMs, sf, tradeGapMs, true);
    }

    public static Summary summarize(String file, Recording r, JumpResetEvaluator.Params p, int interpMs, StatFilter sf,
                                    long tradeGapMs, boolean withOpponents) {
        List<Event> kept = dropSmallFights(sf.apply(r.events()).kept(), sf);
        Recording sub = new Recording(r.schema(), r.player(), r.playerUuid(), r.mcVersion(), r.wallStartMs(),
                r.durationMs(), kept);
        // Filter again so trades respect handNoTrade (already filtered events are unaffected).
        Stats.Result all = Stats.compute(sub, p, Stats.Filter.ALL, AttackEvent.DEFAULT_RANGE, interpMs, sf, tradeGapMs);
        Map<UUID, Opp> opps = new LinkedHashMap<>();
        for (Stats.OpponentStats o : withOpponents ? all.opponents() : List.<Stats.OpponentStats>of()) {
            if (o.uuid() == null) continue;
            Stats.Result one = Stats.compute(sub, p, new Stats.Filter(0, Long.MAX_VALUE, o.uuid()),
                    AttackEvent.DEFAULT_RANGE, interpMs, sf, tradeGapMs);
            opps.put(o.uuid(), new Opp(o.name(), Metrics.of(one)));
        }
        Set<String> servers = new LinkedHashSet<>();
        for (Event e : r.events()) if (e instanceof SegmentEvent s && s.server() != null) servers.add(s.server());
        return new Summary(file, r.wallStartMs(), r.durationMs(), List.copyOf(servers), Metrics.of(all), opps);
    }

    public static List<Event> dropSmallFights(List<Event> ev, StatFilter sf) {
        List<long[]> small = new ArrayList<>();
        for (Stats.Fight f : Stats.fights(ev, Stats.FIGHT_GAP_MS)) {
            if (!sf.fightCounts(f)) small.add(new long[]{f.startMs(), f.endMs()});
        }
        if (small.isEmpty()) return ev;
        List<Event> out = new ArrayList<>(ev.size());
        for (Event e : ev) {
            if (e instanceof AttackEvent || e instanceof IncomingHitEvent) {
                boolean drop = false;
                for (long[] s : small) {
                    if (e.t() >= s[0] && e.t() <= s[1]) {
                        drop = true;
                        break;
                    }
                }
                if (drop) continue;
            }
            out.add(e);
        }
        return out;
    }

    public static List<Point> points(List<Summary> list, Metric m, Group g, String server, UUID opp, ZoneId zone) {
        List<Summary> sorted = new ArrayList<>();
        for (Summary s : list) if (server == null || s.servers().contains(server)) sorted.add(s);
        sorted.sort(Comparator.comparingLong(Summary::wallStartMs));
        List<Point> out = new ArrayList<>();
        if (g == Group.RECORDING) {
            for (Summary s : sorted) add(out, s.wallStartMs(), s.select(opp), List.of(s), m);
            return out;
        }
        LocalDate day = null;
        List<Summary> acc = new ArrayList<>();
        Metrics sum = Metrics.ZERO;
        for (Summary s : sorted) {
            LocalDate d = Instant.ofEpochMilli(s.wallStartMs()).atZone(zone).toLocalDate();
            if (day != null && !d.equals(day)) {
                add(out, day.atStartOfDay(zone).toInstant().toEpochMilli(), sum, List.copyOf(acc), m);
                acc.clear();
                sum = Metrics.ZERO;
            }
            day = d;
            acc.add(s);
            sum = sum.plus(s.select(opp));
        }
        if (day != null) add(out, day.atStartOfDay(zone).toInstant().toEpochMilli(), sum, List.copyOf(acc), m);
        return out;
    }

    private static void add(List<Point> out, long t, Metrics sum, List<Summary> src, Metric m) {
        double v = m.value(sum);
        if (!Double.isNaN(v)) out.add(new Point(t, v, m.samples(sum), src));
    }

    public static List<Summary> previous(List<Summary> all, long beforeMs, int n) {
        List<Summary> out = new ArrayList<>();
        for (Summary s : all) if (s.wallStartMs() < beforeMs) out.add(s);
        out.sort(Comparator.comparingLong(Summary::wallStartMs).reversed());
        return out.size() > n ? List.copyOf(out.subList(0, n)) : out;
    }

    public static List<Delta> compare(Metrics current, List<Summary> previous) {
        Metrics prev = Metrics.ZERO;
        for (Summary s : previous) prev = prev.plus(s.all());
        List<Delta> out = new ArrayList<>();
        for (Metric m : Metric.values()) {
            double c = m.value(current), p = m.value(prev);
            if (!Double.isNaN(c) && !Double.isNaN(p)) out.add(new Delta(m, c, p));
        }
        return out;
    }


    static Map<String, Object> metricsJson(Metrics m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("a", m.attacks());
        o.put("c", m.confirmed());
        o.put("ja", m.jrAttempts());
        o.put("js", m.jrSuccess());
        o.put("r", m.reachSum());
        o.put("tc", m.tbClassified());
        o.put("tn", m.tbCount());
        o.put("s", m.strengthSum());
        o.put("tr", m.trades());
        o.put("fh", m.firstHits());
        return o;
    }

    static Metrics metrics(Object o) {
        if (!(o instanceof Map<?, ?> m)) return Metrics.ZERO;
        return new Metrics(num(m, "a"), num(m, "c"), num(m, "ja"), num(m, "js"), dbl(m, "r"), num(m, "tc"), num(m, "tn"),
                dbl(m, "s"), num(m, "tr"), num(m, "fh"));
    }

    public static Map<String, Object> toJson(Summary s) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("file", s.file());
        o.put("start", s.wallStartMs());
        o.put("dur", s.durationMs());
        o.put("servers", s.servers());
        o.put("all", metricsJson(s.all()));
        Map<String, Object> opp = new LinkedHashMap<>();
        for (Map.Entry<UUID, Opp> e : s.opponents().entrySet()) {
            Map<String, Object> x = metricsJson(e.getValue().m());
            x.put("name", e.getValue().name());
            opp.put(e.getKey().toString(), x);
        }
        o.put("opp", opp);
        return o;
    }

    public static Summary fromJson(Object o) {
        if (!(o instanceof Map<?, ?> m) || !(m.get("file") instanceof String file)) return null;
        List<String> servers = new ArrayList<>();
        if (m.get("servers") instanceof List<?> l) for (Object x : l) if (x != null) servers.add(x.toString());
        Map<UUID, Opp> opps = new LinkedHashMap<>();
        if (m.get("opp") instanceof Map<?, ?> om) {
            for (Map.Entry<?, ?> e : om.entrySet()) {
                try {
                    UUID id = UUID.fromString(String.valueOf(e.getKey()));
                    Object name = e.getValue() instanceof Map<?, ?> x ? x.get("name") : null;
                    opps.put(id, new Opp(name == null ? null : name.toString(), metrics(e.getValue())));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return new Summary(file, lng(m, "start"), lng(m, "dur"), List.copyOf(servers), metrics(m.get("all")), opps);
    }

    private static int num(Map<?, ?> m, String k) {
        return m.get(k) instanceof Number n ? n.intValue() : 0;
    }

    private static long lng(Map<?, ?> m, String k) {
        return m.get(k) instanceof Number n ? n.longValue() : 0;
    }

    private static double dbl(Map<?, ?> m, String k) {
        return m.get(k) instanceof Number n ? n.doubleValue() : 0;
    }
}
