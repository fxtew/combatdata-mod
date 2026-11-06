package dev.combatdata.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects one server's data from many recordings: jump attempts for the window calibration and the knockback we
 * took without jumping. Only segments rated by the given profile count.
 *
 * Knockback: horizontal is kbAway (along the line from the attacker over KB_MEASURE_MS); vertical is the highest
 * rise of our feet over the same time, from positions. A hit counts as a sprint hit when the attacker's last packet
 * state in SPRINT_LOOKBACK_MS before it showed sprinting; without opponent data it counts only in the total.
 */
public final class ServerCalibration {
    public static final long SPRINT_LOOKBACK_MS = 300;
    /** Fewer knockback samples than this are shown as "not enough data". */
    public static final int MIN_KB = 10;

    private final ServerProfile profile;
    private final Settings settings;
    private final ServerProfiles profiles;
    private final List<JumpResetEvaluator.Attempt> attempts = new ArrayList<>();
    /** Farther or higher than this within KB_MEASURE_MS is a teleport or a respawn, not knockback. */
    static final double MAX_KB = 5, MAX_RISE = 3;

    private final List<Double> nH = new ArrayList<>(), nV = new ArrayList<>(), sH = new ArrayList<>(), sV = new ArrayList<>(),
            aH = new ArrayList<>();
    private int recordings;

    public ServerCalibration(ServerProfile profile, ServerProfiles profiles, Settings settings) {
        this.profile = profile;
        this.profiles = profiles;
        this.settings = settings;
    }

    public record Result(Calibration.Result calibration, ServerProfile.Knockback kb, int recordings) {
    }

    public void add(Recording r, StatFilter sf) {
        Rules rules = Rules.of(r.events(), profiles, settings);
        List<Event> mine = new ArrayList<>();
        for (Event e : sf.apply(r.events()).kept()) {
            ServerProfile p = rules.at(e.t()).profile();
            if (p != null && p.address.equals(profile.address)) mine.add(e);
        }
        if (mine.isEmpty()) return;
        recordings++;
        List<JumpResetEvaluator.Attempt> list = JumpResetEvaluator.evaluate(mine, profile.jumpParams(settings));
        attempts.addAll(list);
        List<PositionEvent> pos = new ArrayList<>();
        List<OtherStateEvent> other = new ArrayList<>();
        for (Event e : mine) {
            if (e instanceof PositionEvent p) pos.add(p);
            else if (e instanceof OtherStateEvent o) other.add(o);
        }
        for (JumpResetEvaluator.Attempt a : list) {
            IncomingHitEvent h = a.hit();
            if (a.result() != JumpResetEvaluator.Result.NO_ATTEMPT || !h.knockbackApplied() || h.kindOr() != IncomingHitEvent.Kind.MELEE
                    || !Double.isFinite(h.kbAway()) || Math.abs(h.kbAway()) > MAX_KB) continue;
            aH.add(h.kbAway());
            Boolean sprint = sprinting(other, h);
            double rise = rise(pos, h.t());
            if (sprint == null) continue;
            (sprint ? sH : nH).add(h.kbAway());
            if (Double.isFinite(rise) && rise <= MAX_RISE) (sprint ? sV : nV).add(rise);
        }
    }

    public Result build(long nowMs) {
        Calibration.Result c = Calibration.analyze(attempts, profile.jumpParams(settings));
        ServerProfile.Knockback kb = new ServerProfile.Knockback(median(nH), median(nV), nH.size(), median(sH), median(sV),
                sH.size(), median(aH), aH.size(), nowMs);
        return new Result(c, kb, recordings);
    }

    /** Stores the results in the profile: calibration only if it found a window. */
    public static void apply(ServerProfile p, Result r, long nowMs) {
        Calibration.Result c = r.calibration();
        p.cal = c.hasRecommendation()
                ? new ServerProfile.Calibrated(true, (int) c.recLower(), (int) c.recUpper(), c.samples(), nowMs)
                : new ServerProfile.Calibrated(false, 0, 0, c.samples(), nowMs);
        p.kb = r.kb();
    }

    static Boolean sprinting(List<OtherStateEvent> other, IncomingHitEvent h) {
        if (h.attacker() == null) return null;
        for (int i = other.size() - 1; i >= 0; i--) {
            OtherStateEvent o = other.get(i);
            if (o.t() > h.t() || !o.id().equals(h.attacker())) continue;
            return h.t() - o.t() <= SPRINT_LOOKBACK_MS ? o.sprinting() : null;
        }
        return null;
    }

    static double rise(List<PositionEvent> pos, long t) {
        double base = Double.NaN, top = Double.NaN;
        for (PositionEvent p : pos) {
            if (p.t() <= t) base = p.y();
            else if (p.t() <= t + IncomingHitEvent.KB_MEASURE_MS) top = Double.isNaN(top) ? p.y() : Math.max(top, p.y());
            else break;
        }
        return Double.isNaN(base) || Double.isNaN(top) ? Double.NaN : Math.max(0, top - base);
    }

    /** Median, not mean: one teleport or respawn right after a hit would pull a mean far off. */
    static double median(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return Behavior.median(a);
    }
}
