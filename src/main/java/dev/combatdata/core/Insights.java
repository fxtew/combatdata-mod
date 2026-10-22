package dev.combatdata.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Rules compare a recording's stats against thresholds and produce ranked hints for the overview. */
public final class Insights {
    private Insights() {
    }

    public static final int MAX_SHOWN = 3;

    public enum Page { ATTACK_HITS, ATTACK_AIM, ATTACK_STRIKES, ATTACK_INTERVALS, DEFENSE_JUMP, DEFENSE_KNOCKBACK, DEFENSE_TAKEN, FIGHTS_TRADES }

    public enum Tone { BAD, TIP, GOOD }

    public record Insight(String id, double importance, Tone tone, String ru, String en, Page page) {
        public String text() {
            return Lang.t(ru, en);
        }
    }

    public record Input(Stats.Result stats, JumpResetEvaluator.Params jump) {
    }

    /** Importance = weight * how far the metric exceeds the threshold (at least 1). */
    public interface Rule {
        String id();

        Insight apply(Input in);
    }


    public record JumpOffsetRule(double thresholdMs, int minAttempts, double weight) implements Rule {
        public String id() { return "jr-offset"; }

        public Insight apply(Input in) {
            Stats.JumpStats j = in.stats().jump();
            if (j.attempts() < minAttempts || !Double.isFinite(j.biasMs())) return null;
            double mean = j.biasMs(), lo = in.jump().lowerMs(), hi = in.jump().upperMs();
            double off = mean < lo ? mean - lo : mean > hi ? mean - hi : 0;
            if (Math.abs(off) <= thresholdMs) return null;
            long ms = Math.round(Math.abs(off));
            double imp = weight * Math.max(1, Math.abs(off) / thresholdMs);
            if (off < 0) {
                return new Insight(id(), imp, Tone.BAD,
                        "Jump reset в среднем раньше окна на " + ms + " мс: прыгайте чуть позже",
                        "Jump reset is on average " + ms + " ms before the window: jump a bit later", Page.DEFENSE_JUMP);
            }
            return new Insight(id(), imp, Tone.BAD,
                    "Jump reset в среднем позже окна на " + ms + " мс: прыгайте чуть раньше",
                    "Jump reset is on average " + ms + " ms after the window: jump a bit earlier", Page.DEFENSE_JUMP);
        }
    }

    public record LowChargeRule(double threshold, int minHits, double weight) implements Rule {
        public String id() { return "low-charge"; }

        public Insight apply(Input in) {
            Stats.HitStats h = in.stats().hits();
            if (h.confirmed() < minHits || !Double.isFinite(h.strengthMean()) || h.strengthMean() >= threshold) return null;
            long pct = Math.round(h.strengthMean() * 100);
            double imp = weight * Math.max(1, (threshold - h.strengthMean()) / 0.05);
            return new Insight(id(), imp, Tone.BAD,
                    "Средний заряд атаки " + pct + "%: бьёте раньше перезарядки",
                    "Mean attack charge " + pct + "%: you hit before the cooldown ends", Page.ATTACK_INTERVALS);
        }
    }

    public record OutOfRangeRule(double thresholdPct, int minMisses, double weight) implements Rule {
        public String id() { return "out-of-range"; }

        public Insight apply(Input in) {
            Stats.HitStats h = in.stats().hits();
            int total = h.confirmed() + h.unconfirmed() + h.misses(), n = h.missesOutOfRange();
            if (n < minMisses || total == 0) return null;
            double pct = 100.0 * n / total;
            if (pct < thresholdPct) return null;
            double imp = weight * Math.max(1, pct / thresholdPct);
            return new Insight(id(), imp, Tone.TIP,
                    n + " промахов «не дотянулся» (" + Math.round(pct) + "% атак): бейте ближе",
                    n + " out-of-range misses (" + Math.round(pct) + "% of attacks): swing closer", Page.ATTACK_HITS);
        }
    }

    public record AimMissRule(double thresholdPct, int minMisses, double weight) implements Rule {
        public String id() { return "aim-miss"; }

        public Insight apply(Input in) {
            Stats.HitStats h = in.stats().hits();
            int total = h.confirmed() + h.unconfirmed() + h.misses(), n = h.misses() - h.missesOutOfRange();
            if (n < minMisses || total == 0) return null;
            double pct = 100.0 * n / total;
            if (pct < thresholdPct) return null;
            double imp = weight * Math.max(1, pct / thresholdPct);
            return new Insight(id(), imp, Tone.TIP,
                    n + " промахов прицелом (" + Math.round(pct) + "% атак): смотрите на прицел",
                    n + " aim misses (" + Math.round(pct) + "% of attacks): check your aim", Page.ATTACK_AIM);
        }
    }

    public record FirstHitRule(double thresholdPct, int minTrades, double weight) implements Rule {
        public String id() { return "first-hit"; }

        public Insight apply(Input in) {
            Trades.Summary t = in.stats().trades();
            if (t.total() < minTrades || t.firstPct() >= thresholdPct) return null;
            long pct = Math.round(t.firstPct());
            double imp = weight * Math.max(1, (thresholdPct - t.firstPct()) / 10);
            return new Insight(id(), imp, Tone.TIP,
                    "Первый удар только в " + pct + "% разменов: начинайте размен раньше противника",
                    "First hit in only " + pct + "% of trades: start trades before your opponent", Page.FIGHTS_TRADES);
        }
    }

    public record SecondLossRule(double thresholdPct, int minTrades, double weight) implements Rule {
        public String id() { return "second-loss"; }

        public Insight apply(Input in) {
            Trades.Summary t = in.stats().trades();
            if (t.countSecond() < minTrades || t.winSecondPct() >= thresholdPct) return null;
            long pct = Math.round(t.winSecondPct());
            double imp = weight * Math.max(1, (thresholdPct - t.winSecondPct()) / 10);
            return new Insight(id(), imp, Tone.BAD,
                    "Когда противник бьёт первым, вы выигрываете " + pct + "% разменов",
                    "When the opponent hits first, you win " + pct + "% of trades", Page.FIGHTS_TRADES);
        }
    }

    public record LossCauseRule(double thresholdPct, int minLosses, double weight) implements Rule {
        public String id() { return "loss-cause"; }

        public Insight apply(Input in) {
            Trades.Summary t = in.stats().trades();
            if (t.losses() < minLosses) return null;
            Trades.Cause top = null;
            int n = 0;
            for (Map.Entry<Trades.Cause, Integer> e : t.lossCauses().entrySet()) {
                if (e.getKey() != Trades.Cause.NONE && e.getValue() > n) {
                    top = e.getKey();
                    n = e.getValue();
                }
            }
            if (top == null) return null;
            double pct = 100.0 * n / t.losses();
            if (pct < thresholdPct) return null;
            long p = Math.round(pct);
            double imp = weight * Math.max(1, pct / thresholdPct);
            return switch (top) {
                case MISS -> new Insight(id(), imp, Tone.BAD,
                        p + "% проигранных разменов начались после вашего промаха",
                        p + "% of lost trades started after your miss", Page.ATTACK_HITS);
                case WEAK -> new Insight(id(), imp, Tone.BAD,
                        p + "% проигранных разменов начались после удара без заряда",
                        p + "% of lost trades started after an uncharged hit", Page.ATTACK_INTERVALS);
                case CAUGHT -> new Insight(id(), imp, Tone.BAD,
                        p + "% проигранных разменов начались с 3 block hit против вас",
                        p + "% of lost trades started with a 3 block hit against you", Page.DEFENSE_TAKEN);
                default -> new Insight(id(), imp, Tone.BAD,
                        p + "% проигранных разменов: неудачный jump reset",
                        p + "% of lost trades: a failed jump reset", Page.DEFENSE_JUMP);
            };
        }
    }

    public record CaughtRule(double thresholdPct, int minHits, double weight) implements Rule {
        public String id() { return "caught"; }

        public Insight apply(Input in) {
            Stats.TakenStats t = in.stats().taken();
            if (t.rated() < minHits || !(t.caughtPct() >= thresholdPct)) return null;
            long pct = Math.round(t.caughtPct());
            double imp = weight * Math.max(1, t.caughtPct() / thresholdPct);
            return new Insight(id(), imp, Tone.BAD,
                    pct + "% ударов противника были 3 block hit против вас: он бил, пока был дальше вашей дальности",
                    pct + "% of opponent hits were 3 block hits against you: they hit from beyond your reach", Page.DEFENSE_TAKEN);
        }
    }

    /** Thresholds are conservative to avoid noise on short recordings. */
    public static List<Rule> defaults() {
        return List.of(
                new JumpOffsetRule(15, 5, 1.0),
                new LowChargeRule(0.85, 10, 1.0),
                new OutOfRangeRule(15, 5, 0.9),
                new AimMissRule(15, 5, 0.8),
                new FirstHitRule(40, 6, 0.9),
                new SecondLossRule(30, 4, 0.9),
                new LossCauseRule(50, 4, 0.85),
                new CaughtRule(25, 10, 0.9));
    }

    public static List<Insight> evaluate(List<Rule> rules, Input in, int limit) {
        List<Insight> out = new ArrayList<>();
        for (Rule r : rules) {
            Insight i = r.apply(in);
            if (i != null) out.add(i);
        }
        out.sort(Comparator.comparingDouble(Insight::importance).reversed().thenComparing(Insight::id));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    public static List<Insight> evaluate(Input in) {
        return evaluate(defaults(), in, MAX_SHOWN);
    }
}
