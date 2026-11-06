package dev.combatdata.ui;

import dev.combatdata.client.ProgressData;
import dev.combatdata.client.Tasks;
import dev.combatdata.core.*;
import static dev.combatdata.core.Lang.t;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Progress → Comparison: side A against side B, one row per metric "name: A → B (Δ)". The sides and intervals are
 * computed in the background from the cached summaries; the rows are drawn from the finished result only.
 */
final class CompareView {
    enum Preset { WEEK, SERVER, NORM, FILES }

    record Result(Compare.Side a, Compare.Side b, List<Compare.Row> rows, List<Compare.Warning> warnings) {
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM HH:mm"), WHEN_EN = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    static final int ROW = 14;

    private final Tasks.Slot<Result> slot = new Tasks.Slot<>();
    private Preset preset = Preset.WEEK;
    private String fileA, fileB;
    private boolean swapped;
    private final Set<Compare.Section> collapsed = EnumSet.noneOf(Compare.Section.class);
    private double scroll;
    private int maxScroll, listX, listY, listW, listH;
    private Compare.Metric jumpTo;

    void files(String a, String b) {
        fileA = a;
        fileB = b;
        preset = Preset.FILES;
        swapped = false;
        scroll = 0;
    }

    boolean scrolled(double mx, double my, double dy) {
        if (mx < listX || mx >= listX + listW || my < listY || my >= listY + listH) return false;
        scroll = Math.max(0, Math.min(maxScroll, scroll - dy * ROW * 2));
        return true;
    }

    /** Preset switch and swap button, placed in the tab's control row. */
    void controls(Ui ui, Flow f, int w) {
        boolean narrow = w < 420;
        List<Preset> ps = new ArrayList<>(List.of(Preset.WEEK, Preset.SERVER, Preset.NORM));
        if (fileA != null) ps.add(Preset.FILES);
        String[] names = new String[ps.size()];
        for (int i = 0; i < names.length; i++) names[i] = presetName(ps.get(i), narrow);
        int[] p = f.place(ui.segmentedWidth(names), false);
        ui.segmented(p[0], p[1], names, ps.indexOf(preset), i -> {
            preset = ps.get(i);
            scroll = 0;
        });
        String sw = "⇄";
        int bw = ui.buttonWidth(sw);
        p = f.place(bw, false);
        ui.button(p[0], p[1] + 2, bw, 16, sw, Ui.Kind.GHOST, true, () -> swapped = !swapped);
    }

    static String presetName(Preset p, boolean narrow) {
        return switch (p) {
            case WEEK -> narrow ? t("7 дн.", "7 d") : t("7 дней / прошлые 7", "7 days / previous 7");
            case SERVER -> narrow ? t("Сервер", "Server") : t("Сервер / остальные", "Server / the rest");
            case NORM -> narrow ? t("Норма", "Norm") : t("Последняя / норма", "Latest / norm");
            case FILES -> narrow ? t("Две", "Two") : t("Две записи", "Two recordings");
        };
    }

    private String key(ProgressData.Snapshot snap, String server) {
        return System.identityHashCode(snap) + "/" + preset + "/" + server + "/" + fileA + "/" + fileB + "/" + swapped + "/" + Lang.english();
    }

    /** server: the tab's server choice; the SERVER preset falls back to the server of most fights. */
    void render(Ui ui, ProgressData.Snapshot snap, String server, int x, int y, int w, int h) {
        String k = key(snap, server);
        List<Progress.Summary> all = snap.summaries();
        Preset pr = preset;
        String fa = fileA, fb = fileB;
        boolean sw = swapped;
        slot.request(k, () -> build(all, pr, server, fa, fb, sw));
        Result r = slot.get(k);
        boolean stale = r == null;
        if (r == null) r = slot.latest();
        Draw.roundBordered(ui.g, x, y, w, h, Theme.R_PANEL, Theme.BORDER, Theme.PANEL);
        if (r == null) {
            ui.textCenter(t("Считаю сравнение…", "Comparing…"), x + w / 2, y + h / 2 - 4, Theme.MUTED);
            return;
        }
        int cx = x + 8, cw = w - 16, cy = y + 6;
        cy = card(ui, cx, cy, cw, "A", r.a(), Theme.ACCENT);
        cy = card(ui, cx, cy, cw, "B", r.b(), Theme.BLUE);
        if (!r.warnings().isEmpty()) {
            ui.text(Draw.ellipsize(ui.font, warningText(r.warnings()), cw), cx, cy, Theme.AMBER);
            cy += 11;
        }
        cy = strongest(ui, cx, cy + 2, cw, r.rows()) + 2;
        ui.g.fill(cx, cy, cx + cw, cy + 1, Theme.BORDER);
        listX = x + 2;
        listY = cy + 2;
        listW = w - 4;
        listH = y + h - listY - 4;
        rows(ui, cx, cw, r, stale);
    }

    static Result build(List<Progress.Summary> all, Preset p, String server, String fa, String fb, boolean swapped) {
        Compare.Side[] s = sides(all, p, server, fa, fb);
        Compare.Side a = swapped ? s[1] : s[0], b = swapped ? s[0] : s[1];
        return new Result(a, b, Compare.compare(a, b, 1), Compare.warnings(a, b));
    }

    static Compare.Side[] sides(List<Progress.Summary> all, Preset p, String server, String fa, String fb) {
        switch (p) {
            case WEEK -> {
                return Compare.lastDays(all, System.currentTimeMillis(), 7, t("Прошлые 7 дней", "Previous 7 days"),
                        t("Последние 7 дней", "Last 7 days"));
            }
            case SERVER -> {
                String sv = server != null ? server : mainServer(all);
                if (sv == null) sv = "";
                return Compare.serverVsRest(all, sv, t("Остальные серверы", "Other servers"));
            }
            case NORM -> {
                if (all.isEmpty()) return new Compare.Side[]{empty(t("Норма", "Norm")), empty("—")};
                Progress.Summary last = all.get(all.size() - 1);
                Compare.Side[] n = Compare.norm(all, last, 10, t("Норма: прошлые 10 записей", "Norm: previous 10 recordings"));
                return new Compare.Side[]{n[0], relabel(n[1], when(last.wallStartMs()))};
            }
            case FILES -> {
                Progress.Summary x = find(all, fa), y = find(all, fb);
                if (x != null && y != null && y.wallStartMs() < x.wallStartMs()) {
                    Progress.Summary tmp = x;
                    x = y;
                    y = tmp;
                }
                return new Compare.Side[]{one(x), one(y)};
            }
        }
        throw new IllegalStateException();
    }

    private static Compare.Side one(Progress.Summary s) {
        return s == null ? empty(t("запись не найдена", "recording not found")) : Compare.side(when(s.wallStartMs()), List.of(s), null);
    }

    private static Compare.Side empty(String label) {
        return new Compare.Side(label, List.of(), List.of());
    }

    private static Compare.Side relabel(Compare.Side s, String label) {
        return new Compare.Side(label, s.sources(), s.fights());
    }

    private static Progress.Summary find(List<Progress.Summary> all, String file) {
        for (Progress.Summary s : all) if (s.file().equals(file)) return s;
        return null;
    }

    private static String mainServer(List<Progress.Summary> all) {
        Map<String, Integer> n = new HashMap<>();
        for (Progress.Summary s : all) for (Compare.FightAcc f : s.fights()) if (f.server() != null) n.merge(f.server(), 1, Integer::sum);
        String best = null;
        int c = 0;
        for (Map.Entry<String, Integer> e : n.entrySet()) {
            if (e.getValue() > c) {
                best = e.getKey();
                c = e.getValue();
            }
        }
        return best;
    }

    static String when(long ms) {
        return (Lang.english() ? WHEN_EN : WHEN).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    private int card(Ui ui, int x, int y, int w, String tag, Compare.Side s, int color) {
        StringBuilder b = new StringBuilder();
        int n = s.fights().size();
        b.append(Lang.english() ? n + (n == 1 ? " fight" : " fights") : n + " " + Lang.plural(n, "бой", "боя", "боёв"));
        if (s.sources().size() > 1) b.append(t(" · записей ", " · recordings ")).append(s.sources().size());
        double ping = s.pingMean();
        if (Double.isFinite(ping)) b.append(t(" · пинг ", " · ping ")).append(Math.round(ping)).append(t(" мс", " ms"));
        Set<String> sv = s.servers();
        if (!sv.isEmpty()) b.append(" · ").append(sv.size() == 1 ? sv.iterator().next() : sv.iterator().next() + " +" + (sv.size() - 1));
        Weapon wp = s.weapon();
        if (wp != null && wp != Weapon.UNKNOWN) b.append(" · ").append(weaponName(wp));
        Draw.circle(ui.g, x + 3, y + 4, 7, color);
        String head = tag + "  " + s.label();
        ui.text(Draw.ellipsize(ui.font, head, w / 2), x + 10, y, Theme.TEXT_STRONG);
        int hw = Math.min(ui.font.width(head), w / 2) + 16;
        ui.text(Draw.ellipsize(ui.font, b.toString(), w - hw), x + hw, y, Theme.MUTED);
        return y + 11;
    }

    static String weaponName(Weapon w) {
        return switch (w) {
            case SWORD -> t("меч", "sword");
            case AXE -> t("топор", "axe");
            case MACE -> t("булава", "mace");
            case SPEAR -> t("копьё", "spear");
            case HAND -> t("рука", "hand");
            default -> t("другое", "other");
        };
    }

    static String warningText(List<Compare.Warning> ws) {
        List<String> parts = new ArrayList<>();
        for (Compare.Warning w : ws) parts.add(switch (w) {
            case PING -> t("пинг", "ping");
            case SERVER -> t("серверы", "servers");
            case WEAPON -> t("оружие", "weapon");
        });
        return t("Условия различаются: ", "Conditions differ: ") + String.join(", ", parts)
                + t(". Разница может быть из-за них.", ". The difference may come from them.");
    }

    private int strongest(Ui ui, int x, int y, int w, List<Compare.Row> rows) {
        List<Compare.Row> top = Compare.strongest(rows, 3);
        String head = t("Сильнее всего изменилось: ", "Changed the most: ");
        ui.text(head, x, y + 2, Theme.TEXT);
        int bx = x + ui.font.width(head);
        if (top.isEmpty()) {
            ui.text(Draw.ellipsize(ui.font, t("значимых изменений нет", "no real changes"), w - (bx - x)), bx, y + 2, Theme.DIM);
            return y + 13;
        }
        for (Compare.Row r : top) {
            String label = name(r.metric(), true) + " " + badge(r);
            int cw = ui.font.width(label) + 10;
            if (bx + cw > x + w) break;
            boolean hov = ui.hover(bx, y, cw, 12);
            Draw.round(ui.g, bx, y, cw, 12, Theme.R_CTRL, hov ? Theme.RAISED_HOVER : Theme.RAISED);
            ui.text(label, bx + 5, y + 2, badgeColor(r));
            Compare.Metric m = r.metric();
            ui.click(bx, y, cw, 12, () -> {
                collapsed.remove(m.section);
                jumpTo = m;
            });
            bx += cw + 4;
        }
        return y + 13;
    }

    private void rows(Ui ui, int x, int w, Result r, boolean stale) {
        var g = ui.g;
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        ui.clip(listX, listY, listW, listH);
        int content = 0;
        for (Compare.Section s : Compare.Section.values()) {
            content += ROW;
            if (!collapsed.contains(s)) for (Compare.Row row : r.rows()) if (row.metric().section == s) content += ROW;
        }
        maxScroll = Math.max(0, content - listH);
        if (jumpTo != null) {
            int off = 0;
            for (Compare.Section s : Compare.Section.values()) {
                off += ROW;
                if (collapsed.contains(s)) continue;
                for (Compare.Row row : r.rows()) {
                    if (row.metric().section != s) continue;
                    if (row.metric() == jumpTo) scroll = Math.max(0, off - ROW);
                    off += ROW;
                }
            }
            jumpTo = null;
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int y = listY - (int) scroll;
        boolean narrow = w < 360;
        for (Compare.Section s : Compare.Section.values()) {
            boolean closed = collapsed.contains(s);
            if (y + ROW > listY && y < listY + listH) {
                String head = (closed ? "▸ " : "▾ ") + sectionName(s);
                ui.text(head, x, Ui.textY(y, ROW), Theme.TEXT_STRONG);
                ui.click(x - 4, y, w + 8, ROW, () -> {
                    if (!collapsed.remove(s)) collapsed.add(s);
                });
            }
            y += ROW;
            if (closed) continue;
            for (Compare.Row row : r.rows()) {
                if (row.metric().section != s) continue;
                if (y + ROW > listY && y < listY + listH) row(ui, x, y, w, row, narrow, stale);
                y += ROW;
            }
        }
        ui.unclip();
        g.disableScissor();
        if (maxScroll > 0) ui.scrollbar(listX + listW - 5, listY, listH, scroll, listH, content);
    }

    private void row(Ui ui, int x, int y, int w, Compare.Row r, boolean narrow, boolean stale) {
        boolean hov = ui.hover(x - 4, y, w + 8, ROW);
        if (hov) Draw.round(ui.g, x - 4, y, w + 8, ROW, Theme.R_CTRL, Theme.ROW_HOVER);
        String values = value(r.metric(), r.a()) + " → " + value(r.metric(), r.b());
        String bd = badge(r);
        int bw = ui.font.width(bd), vw = ui.font.width(values);
        int ty = Ui.textY(y, ROW);
        ui.textRight(bd, x + w - 4, ty, stale ? Theme.DIM : badgeColor(r));
        ui.textRight(values, x + w - 4 - bw - 10, ty, stale ? Theme.DIM : Theme.TEXT);
        ui.text(Draw.ellipsize(ui.font, name(r.metric(), narrow), w - bw - vw - 30), x + 8, ty, Theme.TEXT);
        if (hov) ui.tip(tip(r));
    }

    static String name(Compare.Metric m, boolean shortName) {
        return switch (m) {
            case HIT_RATE -> t("Попадания", "Hit rate");
            case REACH -> shortName ? t("Дистанция", "Distance") : t("Дистанция удара", "Hit distance");
            case AIM_YAW -> shortName ? t("Прицел ↔", "Aim ↔") : t("Прицел: отклонение по горизонтали", "Aim: horizontal offset");
            case CHARGE -> shortName ? t("Заряд", "Charge") : t("Заряд удара", "Hit charge");
            case SPRINT_HITS -> shortName ? t("Спринт-удары", "Sprint hits") : t("Доля спринт-ударов", "Share of sprint hits");
            case CRIT_HITS -> shortName ? t("Криты", "Crits") : t("Доля критов", "Share of crits");
            case THREE_BLOCK -> "3 block hit";
            case WTAP -> shortName ? "W-tap" : t("W-tap после спринт-удара", "W-tap after a sprint hit");
            case JR_SUCCESS -> shortName ? "JR" : t("Jump reset: успех", "Jump reset: success");
            case JR_MEAN -> shortName ? t("JR: время", "JR: timing") : t("Jump reset: среднее время", "Jump reset: mean timing");
            case JR_SIGMA -> shortName ? t("JR: σ", "JR: σ") : t("Jump reset: разброс (σ)", "Jump reset: spread (σ)");
            case FLY_OUT -> "Fly out";
            case COMBO -> shortName ? t("Комбо", "Combo") : t("Средняя длина комбо", "Mean combo length");
            case FIRST_HIT -> shortName ? t("Первый удар", "First hit") : t("Первый удар в размене", "First hit in a trade");
            case TRADE_WIN -> shortName ? t("Размены", "Trades") : t("Выигранные размены", "Trades won");
            case PING -> shortName ? t("Пинг", "Ping") : t("Средний пинг", "Mean ping");
        };
    }

    static String sectionName(Compare.Section s) {
        return switch (s) {
            case ATTACK -> t("Атака", "Attack");
            case DEFENSE -> t("Защита", "Defence");
            case FIGHTS -> t("Бои", "Fights");
        };
    }

    static String value(Compare.Metric m, double v) {
        if (!Double.isFinite(v)) return "—";
        return switch (m) {
            case REACH -> Fmt.num(v, 2) + t(" бл.", " bl.");
            case AIM_YAW -> Fmt.num(v, 1) + "°";
            case JR_MEAN, JR_SIGMA, PING -> Math.round(v) + t(" мс", " ms");
            case COMBO -> Fmt.num(v, 2);
            default -> Fmt.pct(v);
        };
    }

    static String delta(Compare.Metric m, double d) {
        return switch (m) {
            case REACH -> Fmt.signed(d, 2);
            case AIM_YAW, COMBO -> Fmt.signed(d, m == Compare.Metric.COMBO ? 2 : 1);
            case JR_MEAN, JR_SIGMA, PING -> Fmt.signed(d, 0) + t(" мс", " ms");
            default -> Fmt.signed(d, 0) + "%";
        };
    }

    /** Arrows repeat the colour for colour-blind players. */
    static String badge(Compare.Row r) {
        return switch (r.verdict()) {
            case FEW -> t("мало данных", "too little data");
            case SAME -> "≈";
            case CHANGED -> delta(r.metric(), r.diff());
            case BETTER, WORSE -> (r.diff() > 0 ? "▲ " : "▼ ") + delta(r.metric(), r.diff());
        } + (r.fewFights() && r.verdict() != Compare.Verdict.FEW ? "*" : "");
    }

    static int badgeColor(Compare.Row r) {
        return switch (r.verdict()) {
            case BETTER -> Theme.GREEN;
            case WORSE -> Theme.RED;
            case CHANGED -> Theme.TEXT;
            default -> Theme.DIM;
        };
    }

    static String tip(Compare.Row r) {
        Compare.Metric m = r.metric();
        StringBuilder b = new StringBuilder(name(m, false)).append('\n');
        b.append("A: ").append(value(m, r.a())).append(t(" (данных ", " (samples ")).append(r.nA()).append(")\n");
        b.append("B: ").append(value(m, r.b())).append(t(" (данных ", " (samples ")).append(r.nB()).append(")\n");
        if (r.verdict() == Compare.Verdict.FEW) {
            b.append(t("Нужно не меньше ", "Needs at least ")).append(m.minSamples).append(t(" с каждой стороны", " on each side"));
            return b.toString();
        }
        b.append(t("Разница ", "Difference ")).append(delta(m, r.diff())).append(t(", интервал ", ", interval "))
                .append(delta(m, r.lo())).append(" … ").append(delta(m, r.hi()));
        if (r.verdict() == Compare.Verdict.SAME) b.append(t("\nИнтервал включает ноль: в пределах случайности", "\nThe interval includes zero: within chance"));
        if (r.fewFights()) b.append(t("\n* Мало боёв: интервал по ударам, он уже настоящего", "\n* Few fights: a per-hit interval, narrower than the real one"));
        if (m.better == Compare.Better.NEUTRAL) b.append(t("\nБез оценки: ни больше, ни меньше не лучше", "\nNot rated: neither more nor less is better"));
        return b.toString();
    }
}
