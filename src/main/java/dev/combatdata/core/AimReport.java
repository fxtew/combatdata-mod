package dev.combatdata.core;

import java.util.*;

/**
 * Where the crosshair was on every swing, hits and misses, which way the misses go, and how aim holds up against
 * strafing and jumping targets, on fast flicks, in the air and by distance. Ends with ranked tips.
 * Target motion and turn speed exist only in newer recordings; air and distance work for all of them.
 */
public final class AimReport {
    private AimReport() {
    }

    /** Player hitbox: half width and height, blocks. */
    public static final double HALF_W = 0.3, HEIGHT = 1.8;
    /** Sideways target speed, blocks/s: strafing from STRAFE_BPS, not moving sideways below STILL_BPS. */
    public static final double STRAFE_BPS = 2.0, STILL_BPS = 1.0;
    /** Vertical target speed, blocks/s, from which the target counts as jumping or falling. */
    public static final double JUMP_BPS = 1.0;
    /** Crosshair turn speed, degrees/s: a flick from FLICK_DPS, smooth below SMOOTH_DPS. */
    public static final double FLICK_DPS = 180, SMOOTH_DPS = 60;
    /** Distance bins: below 2, 2-2.5, 2.5-3, 3 and more blocks. */
    public static final double[] DIST_EDGES = {2.0, 2.5, 3.0};
    public static final int MIN_SWINGS = 8, MIN_MISSES = 4, MAX_TIPS = 4;
    /** Accuracy gap, percentage points, from which a condition is called out. */
    public static final double GAP_PCT = 15;
    /** Share of misses on one side, percent, from which the side is called out. */
    public static final double SIDE_PCT = 65;

    public enum Kind { HIT, MISS, OUT_OF_RANGE }

    /** Where the crosshair passed the target on a miss; EDGE: right at the outline. */
    public enum Side { LEFT, RIGHT, ABOVE, BELOW, EDGE }

    public enum Cond { STRAFE, STILL, JUMPING, LEVEL, FLICK, SMOOTH, SELF_AIR, SELF_GROUND }

    /** x: crosshair across the target, half widths from center, plus is right; y: height from the feet, 1 is the top. */
    public record Shot(AttackEvent a, Kind kind, double x, double y, Side side) {
    }

    /** Hits, aim misses (the ray missed) and out-of-range misses (the ray hit beyond reach). */
    public record Segment(int hits, int aimMisses, int rangeMisses) {
        static final Segment EMPTY = new Segment(0, 0, 0);

        public int swings() {
            return hits + aimMisses + rangeMisses;
        }

        public int aimSwings() {
            return hits + aimMisses;
        }

        /** Aim only: out-of-range misses are a distance problem, not an aim one. */
        public double aimPct() {
            return aimSwings() == 0 ? Double.NaN : 100.0 * hits / aimSwings();
        }

        public double accuracyPct() {
            return swings() == 0 ? Double.NaN : 100.0 * hits / swings();
        }

        Segment add(Kind k) {
            return switch (k) {
                case HIT -> new Segment(hits + 1, aimMisses, rangeMisses);
                case MISS -> new Segment(hits, aimMisses + 1, rangeMisses);
                case OUT_OF_RANGE -> new Segment(hits, aimMisses, rangeMisses + 1);
            };
        }
    }

    /** Two counts of the same misses: how many went one way out of all that went either way. */
    public record Split(int first, int second) {
        public int total() {
            return first + second;
        }

        public double firstPct() {
            return total() == 0 ? Double.NaN : 100.0 * first / total();
        }
    }

    public record Tip(String id, double importance, Insights.Tone tone, String ru, String en) {
        public String text() {
            return Lang.t(ru, en);
        }
    }

    /**
     * strafeLag: misses on strafing targets behind / ahead of their movement; flickOver: misses on flicks past / short
     * of the target; jumpVert: misses on jumping targets below / above them.
     */
    public record Result(List<Shot> shots, Segment all, Map<Side, Integer> missSides, Map<Cond, Segment> conds,
                         Segment[] distance, Split strafeLag, Split flickOver, Split jumpVert, int withMotion,
                         List<Tip> tips) {
        public Segment cond(Cond c) {
            return conds.getOrDefault(c, Segment.EMPTY);
        }

        public int aimMisses() {
            int n = 0;
            for (int v : missSides.values()) n += v;
            return n;
        }
    }

    public static Result analyze(List<Event> events) {
        List<Shot> shots = new ArrayList<>();
        Segment all = Segment.EMPTY;
        Map<Side, Integer> sides = new EnumMap<>(Side.class);
        Map<Cond, Segment> conds = new EnumMap<>(Cond.class);
        Segment[] dist = new Segment[DIST_EDGES.length + 1];
        Arrays.fill(dist, Segment.EMPTY);
        int lagBehind = 0, lagAhead = 0, over = 0, under = 0, below = 0, above = 0, withMotion = 0;
        for (Event e : events) {
            if (!(e instanceof AttackEvent a)) continue;
            Shot s = shot(a);
            if (s == null) continue;
            shots.add(s);
            Kind k = s.kind();
            all = all.add(k);
            int bin = distBin(a.reach());
            dist[bin] = dist[bin].add(k);
            count(conds, a.flags() != null && !a.flags().onGround() ? Cond.SELF_AIR : Cond.SELF_GROUND, k);
            if (k == Kind.MISS) sides.merge(s.side(), 1, Integer::sum);
            if (!a.hasMotion()) continue;
            withMotion++;
            double side = a.targetSideBps(), vert = a.targetVertBps(), turn = a.turnDps();
            if (Math.abs(side) >= STRAFE_BPS) count(conds, Cond.STRAFE, k);
            else if (Math.abs(side) < STILL_BPS) count(conds, Cond.STILL, k);
            count(conds, Math.abs(vert) >= JUMP_BPS ? Cond.JUMPING : Cond.LEVEL, k);
            if (Math.abs(turn) >= FLICK_DPS) count(conds, Cond.FLICK, k);
            else if (Math.abs(turn) < SMOOTH_DPS) count(conds, Cond.SMOOTH, k);
            if (s.kind() != Kind.MISS) continue;
            boolean sideways = s.side() == Side.LEFT || s.side() == Side.RIGHT;
            double sx = s.side() == Side.RIGHT ? 1 : -1;
            if (sideways && Math.abs(side) >= STRAFE_BPS) {
                // Crosshair on the opposite side of where the target is going: it trails behind.
                if (sx * side < 0) lagBehind++;
                else lagAhead++;
            }
            if (sideways && Math.abs(turn) >= FLICK_DPS) {
                if (sx * turn > 0) over++;
                else under++;
            }
            if (Math.abs(vert) >= JUMP_BPS && s.side() == Side.BELOW) below++;
            if (Math.abs(vert) >= JUMP_BPS && s.side() == Side.ABOVE) above++;
        }
        Result r = new Result(shots, all, sides, conds, dist, new Split(lagBehind, lagAhead), new Split(over, under),
                new Split(below, above), withMotion, List.of());
        return new Result(shots, all, sides, conds, dist, r.strafeLag(), r.flickOver(), r.jumpVert(), withMotion,
                tips(r));
    }

    private static void count(Map<Cond, Segment> conds, Cond c, Kind k) {
        conds.put(c, conds.getOrDefault(c, Segment.EMPTY).add(k));
    }

    public static int distBin(double reach) {
        int i = 0;
        while (i < DIST_EDGES.length && reach >= DIST_EDGES[i]) i++;
        return i;
    }

    /** Confirmed hits and misses from 1 block; unconfirmed hits and point-blank swings are left out. */
    static Shot shot(AttackEvent a) {
        if (a.outcome() == Outcome.HIT_UNCONFIRMED || !(a.reach() >= Stats.MIN_AIM_REACH)) return null;
        Kind kind = a.outcome() == Outcome.HIT_CONFIRMED ? Kind.HIT
                : a.outOfRangeMiss(a.rangeOr(AttackEvent.DEFAULT_RANGE)) ? Kind.OUT_OF_RANGE : Kind.MISS;
        double x, y;
        if (kind == Kind.HIT && Double.isFinite(a.hitU()) && Double.isFinite(a.hitV())) {
            x = a.hitU();
            y = a.hitV();
        } else {
            if (!Double.isFinite(a.aimYawDeg()) || !Double.isFinite(a.aimPitchDeg())) return null;
            // Reach of a miss is to the nearest face; the center is about half a width deeper.
            double d = a.reach() + HALF_W;
            double yaw = Math.max(-80, Math.min(80, a.aimYawDeg())), pitch = Math.max(-80, Math.min(80, a.aimPitchDeg()));
            // aimYaw > 0: target right of the crosshair, so the crosshair is left of it; aimPitch > 0: crosshair above.
            x = d * Math.tan(Math.toRadians(-yaw)) / HALF_W;
            y = 0.5 + d * Math.tan(Math.toRadians(pitch)) / HEIGHT;
        }
        return new Shot(a, kind, x, y, kind == Kind.HIT ? null : side(x, y));
    }

    static Side side(double x, double y) {
        double ex = (Math.abs(x) - 1) * HALF_W, ey = y > 1 ? (y - 1) * HEIGHT : y < 0 ? -y * HEIGHT : 0;
        if (ex <= 0 && ey <= 0) return Side.EDGE;
        if (ex >= ey) return x > 0 ? Side.RIGHT : Side.LEFT;
        return y > 1 ? Side.ABOVE : Side.BELOW;
    }

    private static long pct(double v) {
        return Math.round(v);
    }

    /** Condition worse than its counterpart by GAP_PCT or more, both with enough swings. */
    private static double gap(Result r, Cond worse, Cond base) {
        Segment w = r.cond(worse), b = r.cond(base);
        if (w.aimSwings() < MIN_SWINGS || b.aimSwings() < MIN_SWINGS) return Double.NaN;
        double g = b.aimPct() - w.aimPct();
        return g >= GAP_PCT ? g : Double.NaN;
    }

    static List<Tip> tips(Result r) {
        List<Tip> out = new ArrayList<>();
        Segment strafe = r.cond(Cond.STRAFE), still = r.cond(Cond.STILL), jump = r.cond(Cond.JUMPING), level = r.cond(Cond.LEVEL),
                flick = r.cond(Cond.FLICK), smooth = r.cond(Cond.SMOOTH), air = r.cond(Cond.SELF_AIR), ground = r.cond(Cond.SELF_GROUND);

        double g = gap(r, Cond.STRAFE, Cond.STILL);
        if (!Double.isNaN(g)) out.add(new Tip("aim-strafe", g / GAP_PCT, Insights.Tone.BAD,
                "Против стрейфа точность прицела " + pct(strafe.aimPct()) + "% против " + pct(still.aimPct()) + "% без него: ведите прицел за противником непрерывно, а не догоняйте рывками",
                "Against strafing your aim accuracy is " + pct(strafe.aimPct()) + "% vs " + pct(still.aimPct()) + "% without it: track the opponent continuously instead of catching up in jerks"));
        g = gap(r, Cond.JUMPING, Cond.LEVEL);
        if (!Double.isNaN(g)) out.add(new Tip("aim-jumping", g / GAP_PCT, Insights.Tone.BAD,
                "По прыгающим " + pct(jump.aimPct()) + "% против " + pct(level.aimPct()) + "% по стоящим на земле: держите прицел на груди и ведите его вместе с прыжком",
                "Against jumping opponents " + pct(jump.aimPct()) + "% vs " + pct(level.aimPct()) + "% on the ground: keep the crosshair on the chest and move it with the jump"));
        g = gap(r, Cond.FLICK, Cond.SMOOTH);
        if (!Double.isNaN(g)) out.add(new Tip("aim-flick", g / GAP_PCT, Insights.Tone.BAD,
                "При резких доводках " + pct(flick.aimPct()) + "% против " + pct(smooth.aimPct()) + "% при плавных: наводитесь заранее и бейте, когда прицел уже на цели",
                "On fast flicks " + pct(flick.aimPct()) + "% vs " + pct(smooth.aimPct()) + "% when smooth: aim ahead of time and swing once the crosshair is already on target"));
        g = gap(r, Cond.SELF_AIR, Cond.SELF_GROUND);
        if (!Double.isNaN(g)) out.add(new Tip("aim-air", g / GAP_PCT, Insights.Tone.BAD,
                "В воздухе " + pct(air.aimPct()) + "% против " + pct(ground.aimPct()) + "% на земле: в прыжке меньше дёргайте мышь, наводитесь до прыжка",
                "In the air " + pct(air.aimPct()) + "% vs " + pct(ground.aimPct()) + "% on the ground: move the mouse less mid-jump, aim before you jump"));

        Split lag = r.strafeLag();
        if (lag.total() >= MIN_MISSES && lag.firstPct() >= SIDE_PCT) out.add(new Tip("aim-lag", lag.firstPct() / SIDE_PCT, Insights.Tone.TIP,
                "Против стрейфа прицел отстаёт: " + lag.first() + " из " + lag.total() + " промахов позади цели. Ведите прицел чуть впереди, по ходу движения",
                "Your aim trails strafing opponents: " + lag.first() + " of " + lag.total() + " misses are behind them. Lead slightly in the direction they move"));
        else if (lag.total() >= MIN_MISSES && 100 - lag.firstPct() >= SIDE_PCT) out.add(new Tip("aim-lead", (100 - lag.firstPct()) / SIDE_PCT, Insights.Tone.TIP,
                "Против стрейфа прицел убегает вперёд: " + lag.second() + " из " + lag.total() + " промахов перед целью. Не забегайте, держите прицел на теле",
                "Your aim runs ahead of strafing opponents: " + lag.second() + " of " + lag.total() + " misses are in front of them. Don't lead, keep it on the body"));
        Split fo = r.flickOver();
        if (fo.total() >= MIN_MISSES && fo.firstPct() >= SIDE_PCT) out.add(new Tip("aim-overshoot", fo.firstPct() / SIDE_PCT, Insights.Tone.TIP,
                "При доводке прицел проскакивает цель: " + fo.first() + " из " + fo.total() + " промахов дальше по ходу поворота. Притормаживайте мышь у цели",
                "Your flicks overshoot: " + fo.first() + " of " + fo.total() + " misses are past the target. Slow the mouse down as you reach it"));
        else if (fo.total() >= MIN_MISSES && 100 - fo.firstPct() >= SIDE_PCT) out.add(new Tip("aim-undershoot", (100 - fo.firstPct()) / SIDE_PCT, Insights.Tone.TIP,
                "При доводке прицел не доходит до цели: " + fo.second() + " из " + fo.total() + " промахов не доведены. Доводите до конца, потом бейте",
                "Your flicks fall short: " + fo.second() + " of " + fo.total() + " misses stop before the target. Finish the flick, then swing"));
        Split jv = r.jumpVert();
        if (jv.total() >= MIN_MISSES && jv.firstPct() >= SIDE_PCT) out.add(new Tip("aim-jump-low", jv.firstPct() / SIDE_PCT, Insights.Tone.TIP,
                "По прыгающим промахи ниже цели (" + jv.first() + " из " + jv.total() + "): поднимайте прицел вместе с прыжком противника",
                "Against jumping opponents you miss below them (" + jv.first() + " of " + jv.total() + "): raise the crosshair as they jump"));
        else if (jv.total() >= MIN_MISSES && 100 - jv.firstPct() >= SIDE_PCT) out.add(new Tip("aim-jump-high", (100 - jv.firstPct()) / SIDE_PCT, Insights.Tone.TIP,
                "По прыгающим промахи выше цели (" + jv.second() + " из " + jv.total() + "): опускайте прицел, когда противник приземляется",
                "Against jumping opponents you miss above them (" + jv.second() + " of " + jv.total() + "): lower the crosshair as they land"));

        sideTip(r, out);
        distanceTip(r, out);

        if (strafe.aimSwings() >= MIN_SWINGS && strafe.aimPct() >= 70) out.add(new Tip("aim-strafe-good", strafe.aimPct() / 70, Insights.Tone.GOOD,
                "Против стрейфа точность прицела " + pct(strafe.aimPct()) + "%: ведёте цель хорошо",
                "Aim accuracy against strafing is " + pct(strafe.aimPct()) + "%: you track well"));
        out.sort(Comparator.comparing((Tip t) -> t.tone() == Insights.Tone.GOOD)
                .thenComparing(Comparator.comparingDouble(Tip::importance).reversed()).thenComparing(Tip::id));
        return out.size() > MAX_TIPS ? List.copyOf(out.subList(0, MAX_TIPS)) : List.copyOf(out);
    }

    /** Overall miss direction, only when one side clearly dominates. */
    private static void sideTip(Result r, List<Tip> out) {
        int n = 0;
        for (Map.Entry<Side, Integer> e : r.missSides().entrySet()) if (e.getKey() != Side.EDGE) n += e.getValue();
        if (n < MIN_MISSES + 1) return;
        Side top = null;
        int topN = 0;
        for (Side s : Side.values()) {
            int c = r.missSides().getOrDefault(s, 0);
            if (s != Side.EDGE && c > topN) {
                top = s;
                topN = c;
            }
        }
        double share = 100.0 * topN / n;
        if (top == null || share < 55) return;
        long p = pct(share);
        double imp = share / 55;
        out.add(switch (top) {
            case LEFT -> new Tip("aim-side", imp, Insights.Tone.TIP, "Промахи чаще слева от цели (" + p + "%): прицел запаздывает влево, доводите его правее",
                    "Most misses pass left of the target (" + p + "%): bring the crosshair further right");
            case RIGHT -> new Tip("aim-side", imp, Insights.Tone.TIP, "Промахи чаще справа от цели (" + p + "%): прицел уходит вправо, доводите его левее",
                    "Most misses pass right of the target (" + p + "%): bring the crosshair further left");
            case ABOVE -> new Tip("aim-side", imp, Insights.Tone.TIP, "Промахи чаще выше головы (" + p + "%): опустите прицел к груди",
                    "Most misses go over the head (" + p + "%): lower the crosshair to the chest");
            default -> new Tip("aim-side", imp, Insights.Tone.TIP, "Промахи чаще ниже ног (" + p + "%): поднимите прицел к груди",
                    "Most misses go under the feet (" + p + "%): raise the crosshair to the chest");
        });
    }

    /** The worst distance bin when it is clearly below the overall accuracy. */
    private static void distanceTip(Result r, List<Tip> out) {
        double base = r.all().accuracyPct();
        int worst = -1;
        double worstPct = Double.MAX_VALUE;
        for (int i = 0; i < r.distance().length; i++) {
            Segment s = r.distance()[i];
            if (s.swings() >= MIN_SWINGS && s.accuracyPct() < worstPct) {
                worst = i;
                worstPct = s.accuracyPct();
            }
        }
        if (worst < 0 || !(base - worstPct >= GAP_PCT)) return;
        Segment s = r.distance()[worst];
        double imp = (base - worstPct) / GAP_PCT;
        long p = pct(worstPct), b = pct(base);
        String ru, en;
        if (worst == DIST_EDGES.length && s.rangeMisses() >= s.aimMisses()) {
            ru = "С 3 блоков точность " + p + "% против " + b + "% в среднем, чаще не дотягиваетесь: подходите ближе перед ударом";
            en = "From 3 blocks accuracy is " + p + "% vs " + b + "% overall, mostly out of reach: step closer before swinging";
        } else if (worst == DIST_EDGES.length) {
            ru = "С 3 блоков точность " + p + "% против " + b + "% в среднем: издалека цель меньше, наводитесь точнее";
            en = "From 3 blocks accuracy is " + p + "% vs " + b + "% overall: the target is smaller far away, aim more carefully";
        } else if (worst == 0) {
            ru = "Вплотную (ближе 2 блоков) точность " + p + "% против " + b + "%: цель быстро уходит из прицела, держите дистанцию";
            en = "Up close (under 2 blocks) accuracy is " + p + "% vs " + b + "%: the target leaves the crosshair fast, keep some distance";
        } else {
            String range = edge(DIST_EDGES[worst - 1]) + "–" + edge(DIST_EDGES[worst]);
            ru = "На " + range + " блока точность " + p + "% против " + b + "% в среднем";
            en = "At " + range + " blocks accuracy is " + p + "% vs " + b + "% overall";
        }
        out.add(new Tip("aim-distance", imp, Insights.Tone.TIP, ru, en));
    }

    /** Bin edge without trailing zeros: 2, 2.5, 3. */
    public static String edge(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
