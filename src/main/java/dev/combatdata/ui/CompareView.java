package dev.combatdata.ui;

import dev.combatdata.CombatDataClient;
import dev.combatdata.client.ProgressData;
import dev.combatdata.client.Recordings;
import dev.combatdata.client.Tasks;
import dev.combatdata.core.*;
import static dev.combatdata.core.Lang.t;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Progress → Comparison: side A against side B, one row per metric. Wide: a fixed scale per metric with A (filled
 * terracotta dot) and B (blue ring) joined by a line; narrow: "A → B (Δ)". Sides, intervals and every row's text are
 * built in the background; a click on a row opens the distributions of both sides (events loaded only then).
 */
final class CompareView {
    enum Preset { WEEK, SERVER, NORM, FILES }

    /** Texts are made with the result, so drawing a row allocates nothing. */
    record RowText(Compare.Row row, String name, String shortName, String values, String badge, String tip, String lo, String hi) {
    }

    record Result(Compare.Side a, Compare.Side b, List<Compare.Row> rows, List<RowText> texts, List<RowText> strongest,
                  String metaA, String metaB, String warning) {
    }

    /** a/b: values per fight; hist: share of each side's values per bin; aim: hit points (across, up). */
    record Detail(Compare.Metric metric, Compare.Dist da, Compare.Dist db, double[] medianCi, double[] histA, double[] histB,
                  double lo, double hi, double[][] aimA, double[][] aimB, long jrLo, long jrHi, String error) {
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM HH:mm"), WHEN_EN = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    static final int ROW = 14;
    /** A and B stay terracotta and blue in every theme: the pair is told apart with common colour blindness. */
    static final int COLOR_A = 0xFFD9774F, COLOR_B = 0xFF4F8FE0;
    static final int WIDE = 480, MID = 360;
    private static final int AIM_POINTS = 400;

    private final Tasks.Slot<Result> slot = new Tasks.Slot<>();
    private final Tasks.Slot<Detail> detailSlot = new Tasks.Slot<>();
    private Preset preset = Preset.WEEK;
    private String fileA, fileB;
    private boolean swapped;
    private final Set<Compare.Section> collapsed = EnumSet.noneOf(Compare.Section.class);
    private double scroll;
    private int maxScroll, listX, listY, listW, listH;
    private Compare.Metric jumpTo, detail;

    void files(String a, String b) {
        fileA = a;
        fileB = b;
        preset = Preset.FILES;
        swapped = false;
        scroll = 0;
        detail = null;
    }

    boolean scrolled(double mx, double my, double dy) {
        if (detail != null || mx < listX || mx >= listX + listW || my < listY || my >= listY + listH) return false;
        scroll = Math.max(0, Math.min(maxScroll, scroll - dy * ROW * 2));
        return true;
    }

    /** Esc closes the detail view first. */
    boolean back() {
        if (detail == null) return false;
        detail = null;
        return true;
    }

    /** Preset switch and swap button, placed in the tab's control row. */
    void controls(Ui ui, Flow f, int w) {
        boolean narrow = w < WIDE;
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
        if (ui.hover(p[0], p[1] + 2, bw, 16)) ui.tip(t("Поменять A и B местами", "Swap A and B"));
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
        cy = cards(ui, cx, cy, cw, r);
        if (r.warning() != null) {
            ui.text(Draw.ellipsize(ui.font, r.warning(), cw), cx, cy, Theme.AMBER);
            if (ui.hover(cx, cy - 1, cw, 10)) ui.tip(r.warning());
            cy += 11;
        }
        cy = strongest(ui, cx, cy + 1, cw, r, stale) + 2;
        ui.g.fill(cx, cy, cx + cw, cy + 1, Theme.BORDER);
        listX = x + 2;
        listY = cy + 2;
        listW = w - 4;
        listH = y + h - listY - 4;
        if (detail != null && !stale) detail(ui, snap, k, r, cx, listY + 2, cw, listH - 2);
        else rows(ui, cx, cw, r, stale);
    }

    static Result build(List<Progress.Summary> all, Preset p, String server, String fa, String fb, boolean swapped) {
        Compare.Side[] s = sides(all, p, server, fa, fb);
        Compare.Side a = swapped ? s[1] : s[0], b = swapped ? s[0] : s[1];
        List<Compare.Row> rows = Compare.compare(a, b, 1);
        List<RowText> texts = new ArrayList<>();
        for (Compare.Row row : rows) texts.add(text(row));
        List<RowText> top = new ArrayList<>();
        for (Compare.Row row : Compare.strongest(rows, 3)) top.add(texts.get(row.metric().ordinal()));
        List<Compare.Warning> ws = Compare.warnings(a, b);
        return new Result(a, b, rows, List.copyOf(texts), List.copyOf(top), meta(a), meta(b), ws.isEmpty() ? null : warningText(ws, a, b));
    }

    static RowText text(Compare.Row r) {
        Compare.Metric m = r.metric();
        return new RowText(r, name(m, false), name(m, true), value(m, r.a()) + " → " + value(m, r.b()), badge(r), tip(r),
                axis(m, m.lo), axis(m, m.hi));
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

    static String meta(Compare.Side s) {
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
        return b.toString();
    }

    /** Side by side when wide, one line each when narrow. The dot sits on the text's cap height. */
    private int cards(Ui ui, int x, int y, int w, Result r) {
        if (w >= WIDE) {
            int half = (w - 8) / 2;
            card2(ui, x, y, half, "A", r.a().label(), r.metaA(), COLOR_A, false);
            card2(ui, x + half + 8, y, half, "B", r.b().label(), r.metaB(), COLOR_B, true);
            return y + 24;
        }
        card1(ui, x, y, w, "A", r.a().label(), r.metaA(), COLOR_A, false);
        card1(ui, x, y + 11, w, "B", r.b().label(), r.metaB(), COLOR_B, true);
        return y + 22;
    }

    private static void card2(Ui ui, int x, int y, int w, String tag, String label, String meta, int color, boolean ring) {
        Draw.round(ui.g, x - 3, y - 3, w + 6, 23, Theme.R_CTRL, Theme.INSET);
        dot(ui, x + 1, y, color, ring);
        ui.text(Draw.ellipsize(ui.font, tag + "  " + label, w - 12), x + 11, y, Theme.TEXT_STRONG);
        ui.text(Draw.ellipsize(ui.font, meta, w - 12), x + 11, y + 10, Theme.MUTED);
    }

    private static void card1(Ui ui, int x, int y, int w, String tag, String label, String meta, int color, boolean ring) {
        dot(ui, x + 1, y, color, ring);
        String head = tag + "  " + label;
        int hw = Math.min(ui.font.width(head), w / 2);
        ui.text(Draw.ellipsize(ui.font, head, w / 2), x + 11, y, Theme.TEXT_STRONG);
        ui.text(Draw.ellipsize(ui.font, meta, w - hw - 20), x + hw + 19, y, Theme.MUTED);
    }

    /** A 7 px dot on a text line at y (cap height 7): filled for A, a ring for B. */
    private static void dot(Ui ui, int x, int y, int color, boolean ring) {
        Draw.circle(ui.g, x, y, 7, color);
        if (ring) Draw.circle(ui.g, x + 2, y + 2, 3, Theme.PANEL);
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

    static String warningText(List<Compare.Warning> ws, Compare.Side a, Compare.Side b) {
        List<String> parts = new ArrayList<>();
        for (Compare.Warning w : ws) parts.add(switch (w) {
            case PING -> t("пинг ", "ping ") + Math.round(a.pingMean()) + " / " + Math.round(b.pingMean()) + t(" мс", " ms");
            case SERVER -> t("разные серверы", "different servers");
            case WEAPON -> t("оружие ", "weapon ") + weaponName(a.weapon()) + " / " + weaponName(b.weapon());
        });
        return t("Условия различаются: ", "Conditions differ: ") + String.join(", ", parts)
                + t(". Разница может быть из-за них.", ". The difference may come from them.");
    }

    private int strongest(Ui ui, int x, int y, int w, Result r, boolean stale) {
        String head = w < MID ? t("Сильнее всего: ", "Most: ") : t("Сильнее всего изменилось: ", "Changed the most: ");
        ui.text(head, x, y + 2, Theme.TEXT);
        int bx = x + ui.font.width(head);
        if (r.strongest().isEmpty()) {
            ui.text(Draw.ellipsize(ui.font, t("значимых изменений нет", "no real changes"), w - (bx - x)), bx, y + 2, Theme.DIM);
            return y + 13;
        }
        for (RowText rt : r.strongest()) {
            String label = rt.shortName() + " " + rt.badge();
            int cw = ui.font.width(label) + 10;
            if (bx + cw > x + w) break;
            boolean hov = ui.hover(bx, y, cw, 12);
            Draw.round(ui.g, bx, y, cw, 12, Theme.R_CTRL, hov ? Theme.RAISED_HOVER : Theme.RAISED);
            ui.text(label, bx + 5, y + 2, stale ? Theme.DIM : badgeColor(rt.row()));
            Compare.Metric m = rt.row().metric();
            ui.click(bx, y, cw, 12, () -> {
                detail = null;
                collapsed.remove(m.section);
                jumpTo = m;
            });
            if (hov) ui.tip(rt.tip());
            bx += cw + 4;
        }
        return y + 13;
    }

    private void rows(Ui ui, int x, int w, Result r, boolean stale) {
        var g = ui.g;
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
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        ui.clip(listX, listY, listW, listH);
        int y = listY - (int) scroll;
        Layout lay = layout(ui, w);
        for (Compare.Section s : Compare.Section.values()) {
            boolean closed = collapsed.contains(s);
            if (y + ROW > listY && y < listY + listH) {
                ui.text((closed ? "▸ " : "▾ ") + sectionName(s), x, Ui.textY(y, ROW), Theme.TEXT_STRONG);
                ui.click(x - 4, y, w + 8, ROW, () -> {
                    if (!collapsed.remove(s)) collapsed.add(s);
                });
            }
            y += ROW;
            if (closed) continue;
            for (RowText rt : r.texts()) {
                if (rt.row().metric().section != s) continue;
                if (y + ROW > listY && y < listY + listH) row(ui, x, y, w, rt, lay, stale);
                y += ROW;
            }
        }
        ui.unclip();
        g.disableScissor();
        if (maxScroll > 0) ui.scrollbar(listX + listW - 5, listY, listH, scroll, listH, content);
    }

    /** Column positions for the current width; mode 0 list, 1 scale without axis labels, 2 everything. */
    record Layout(int mode, int nameW, int trackX, int trackW, int badgeW) {
    }

    private static Layout layout(Ui ui, int w) {
        if (w < MID) return new Layout(0, 0, 0, 0, 0);
        boolean wide = w >= WIDE;
        int badgeW = Math.max(ui.font.width(t("мало данных", "too little data")), ui.font.width("▼ -200 мс*")) + 6;
        int nameW = (int) (w * (wide ? 0.34 : 0.30));
        int axis = wide ? 26 : 6;
        int trackX = nameW + 8 + axis, trackW = w - trackX - axis - badgeW - 8;
        return new Layout(wide ? 2 : 1, nameW, trackX, Math.max(20, trackW), badgeW);
    }

    private void row(Ui ui, int x, int y, int w, RowText rt, Layout lay, boolean stale) {
        Compare.Row r = rt.row();
        boolean hov = ui.hover(x - 4, y, w + 8, ROW);
        if (hov) Draw.round(ui.g, x - 4, y, w + 8, ROW, Theme.R_CTRL, Theme.ROW_HOVER);
        int ty = Ui.textY(y, ROW);
        int bc = stale ? Theme.DIM : badgeColor(r);
        ui.textRight(rt.badge(), x + w - 4, ty, bc);
        if (lay.mode() == 0) {
            int bw = ui.font.width(rt.badge()), vw = ui.font.width(rt.values());
            ui.textRight(rt.values(), x + w - 4 - bw - 8, ty, stale ? Theme.DIM : Theme.TEXT);
            ui.text(Draw.ellipsize(ui.font, rt.shortName(), w - bw - vw - 26), x + 8, ty, Theme.TEXT);
        } else {
            ui.text(Draw.ellipsize(ui.font, lay.mode() == 2 ? rt.name() : rt.shortName(), lay.nameW()), x + 8, ty, Theme.TEXT);
            int tx = x + lay.trackX(), tw = lay.trackW(), cy = y + ROW / 2;
            if (lay.mode() == 2) {
                ui.textRight(rt.lo(), tx - 4, ty, Theme.DIM);
                ui.text(rt.hi(), tx + tw + 4, ty, Theme.DIM);
            }
            ui.g.fill(tx, cy, tx + tw, cy + 1, Theme.BORDER);
            int ax = pos(r.metric(), r.a(), tx, tw), bx = pos(r.metric(), r.b(), tx, tw);
            if (ax >= 0 && bx >= 0) ui.g.fill(Math.min(ax, bx), cy - 1, Math.max(ax, bx) + 1, cy + 2, stale ? Theme.DIM : Theme.faded(bc == Theme.DIM ? Theme.MUTED : bc, 0.35));
            if (ax >= 0) Draw.circle(ui.g, ax - 3, cy - 3, 7, stale ? Theme.DIM : COLOR_A);
            if (bx >= 0) {
                Draw.circle(ui.g, bx - 3, cy - 3, 7, stale ? Theme.DIM : COLOR_B);
                Draw.circle(ui.g, bx - 1, cy - 1, 3, hov ? Theme.ROW_HOVER : Theme.PANEL);
            }
        }
        if (hov) ui.tip(rt.tip() + t("\nКлик: распределения A и B", "\nClick: distributions of A and B"));
        Compare.Metric m = r.metric();
        ui.click(x - 4, y, w + 8, ROW, () -> detail = m);
    }

    /** x of a value on the track, clamped to its ends; -1 if unknown. */
    private static int pos(Compare.Metric m, double v, int tx, int tw) {
        if (!Double.isFinite(v)) return -1;
        double k = Math.max(0, Math.min(1, (v - m.lo) / (m.hi - m.lo)));
        return tx + (int) Math.round(k * (tw - 1));
    }

    // ---- Detail view ----

    private void detail(Ui ui, ProgressData.Snapshot snap, String key, Result r, int x, int y, int w, int h) {
        Compare.Metric m = detail;
        RowText rt = r.texts().get(m.ordinal());
        String backL = t("← Назад", "← Back");
        int bw = ui.buttonWidth(backL);
        ui.button(x, y, bw, 14, backL, Ui.Kind.GHOST, true, () -> detail = null);
        ui.text(Draw.ellipsize(ui.font, rt.name(), w - bw - ui.font.width(rt.badge()) - 24), x + bw + 8, Ui.textY(y, 14), Theme.TEXT_STRONG);
        ui.textRight(rt.badge(), x + w, Ui.textY(y, 14), badgeColor(rt.row()));
        y += 18;
        h -= 18;
        String dk = key + "/" + m;
        Map<String, Recordings.Entry> entries = snap.entries();
        Compare.Side a = r.a(), b = r.b();
        detailSlot.request(dk, () -> loadDetail(m, a, b, entries));
        Detail d = detailSlot.get(dk);
        if (d == null) {
            ui.textCenter(m.kind == Compare.Kind.SHARE ? t("Считаю…", "Computing…") : t("Загружаю бои из записей…", "Loading fights from recordings…"),
                    x + w / 2, y + h / 2 - 4, Theme.MUTED);
            return;
        }
        if (d.error() != null) {
            ui.textCenter(Draw.ellipsize(ui.font, d.error(), w), x + w / 2, y + h / 2 - 4, Theme.DIM);
            return;
        }
        boolean aim = m == Compare.Metric.AIM_YAW && (d.aimA().length > 0 || d.aimB().length > 0);
        int statsH = 3 * 10 + 4;
        int sideW = aim && w >= MID ? Math.min(150, w / 3) : 0;
        int gw = w - (sideW > 0 ? sideW + 8 : 0);
        int gh = Math.max(40, Math.min(150, h - statsH - 12 - (aim && sideW == 0 ? 90 : 0)));
        mirrored(ui, d, x, y, gw, gh);
        int sy = y + gh + 12;
        stats(ui, d, rt.row(), x, sy, gw);
        if (aim) {
            if (sideW > 0) silhouettes(ui, d, x + gw + 8, y, sideW, Math.min(h, gh + statsH + 12));
            else silhouettes(ui, d, x, sy + statsH + 4, w, Math.min(90, y + h - sy - statsH - 4));
        }
    }

    static Detail loadDetail(Compare.Metric m, Compare.Side a, Compare.Side b, Map<String, Recordings.Entry> entries) {
        Settings s = CombatDataClient.settings().copy();
        ServerProfiles profiles = CombatDataClient.profiles();
        List<double[]> fa, fb;
        double[][] aimA = new double[0][], aimB = new double[0][];
        if (m.kind == Compare.Kind.SHARE) {
            fa = Compare.shares(m, a.fights());
            fb = Compare.shares(m, b.fights());
        } else {
            List<Compare.FightRaw> ra = load(a, entries, s, profiles), rb = load(b, entries, s, profiles);
            if (ra == null || rb == null) return error(m, t("Не удалось прочитать записи этой стороны", "Could not read this side's recordings"));
            fa = Compare.perFight(m, ra);
            fb = Compare.perFight(m, rb);
            if (m == Compare.Metric.AIM_YAW) {
                aimA = sample(ra);
                aimB = sample(rb);
            }
        }
        if (fa.isEmpty() && fb.isEmpty()) return error(m, t("Нет данных ни у A, ни у B", "No data on either side"));
        double lo = m.lo, hi = m.hi;
        int bins = switch (m) {
            case COMBO -> 8;
            case REACH -> 16;
            default -> m.kind == Compare.Kind.SHARE ? 10 : 20;
        };
        Compare.Dist da = Compare.dist(Compare.pool(fa)), db = Compare.dist(Compare.pool(fb));
        long[] jr = {Long.MIN_VALUE, Long.MIN_VALUE};
        if (m == Compare.Metric.JR_MEAN || m == Compare.Metric.JR_SIGMA) {
            JumpResetEvaluator.Params p = s.jumpParams();
            Set<String> sv = b.servers().isEmpty() ? a.servers() : b.servers();
            ServerProfile prof = sv.isEmpty() ? null : profiles.find(sv.iterator().next());
            if (prof != null) p = prof.jumpParams(s);
            jr = new long[]{p.lowerMs(), p.upperMs()};
        }
        return new Detail(m, da, db, Compare.medianDiff(fa, fb, 11, Compare.REPS, Compare.ROW_BUDGET_MS),
                share(Stats.histogram(da.values(), lo, hi, bins), da.n()), share(Stats.histogram(db.values(), lo, hi, bins), db.n()),
                lo, hi, aimA, aimB, jr[0], jr[1], null);
    }

    private static Detail error(Compare.Metric m, String msg) {
        return new Detail(m, null, null, null, null, null, 0, 0, null, null, 0, 0, msg);
    }

    private static double[] share(int[] h, int n) {
        double[] out = new double[h.length];
        for (int i = 0; i < h.length; i++) out[i] = n == 0 ? 0 : (double) h[i] / n;
        return out;
    }

    /** Raw fights of a side: each recording read once, only the side's own fights kept. Null if a file is gone. */
    private static List<Compare.FightRaw> load(Compare.Side side, Map<String, Recordings.Entry> entries, Settings s, ServerProfiles profiles) {
        Set<Compare.FightAcc> mine = Collections.newSetFromMap(new IdentityHashMap<>());
        mine.addAll(side.fights());
        StatFilter sf = s.statFilter();
        Trades.Params tp = new Trades.Params(s.tradeGapMs, false);
        List<Compare.FightRaw> out = new ArrayList<>();
        for (Progress.Summary sum : side.sources()) {
            if (Thread.currentThread().isInterrupted()) return out;
            Set<Long> keep = new HashSet<>();
            for (Compare.FightAcc f : sum.fights()) if (mine.contains(f)) keep.add(f.startMs());
            if (keep.isEmpty()) continue;
            Recordings.Entry e = entries.get(sum.file());
            if (e == null) return null;
            try {
                Recording r = Cdrec.read(e.file());
                List<Event> kept = Progress.dropSmallFights(sf.apply(r.events()).kept(), sf);
                out.addAll(Compare.raw(kept, Rules.of(r.events(), profiles, s), sf, tp, keep));
            } catch (Exception ex) {
                return null;
            }
        }
        return out;
    }

    /** Hit points of a side, every n-th one if there are more than AIM_POINTS. */
    private static double[][] sample(List<Compare.FightRaw> raw) {
        List<double[]> all = new ArrayList<>();
        for (Compare.FightRaw f : raw) all.addAll(Arrays.asList(f.aim()));
        int step = Math.max(1, (all.size() + AIM_POINTS - 1) / AIM_POINTS);
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += step) out.add(all.get(i));
        return out.toArray(new double[0][]);
    }

    /** A's histogram above the axis, B's below, the same x; heights are each side's share per bin. */
    private static void mirrored(Ui ui, Detail d, int x, int y, int w, int h) {
        var g = ui.g;
        Draw.round(g, x, y, w, h, Theme.R_CTRL, Theme.INSET);
        int gx = x + 4, gw = w - 8, mid = y + h / 2, half = h / 2 - 3;
        int bins = d.histA().length;
        double max = 0;
        for (int i = 0; i < bins; i++) max = Math.max(max, Math.max(d.histA()[i], d.histB()[i]));
        Compare.Metric m = d.metric();
        if (d.jrLo() != Long.MIN_VALUE) {
            int x1 = gx + (int) Math.round((d.jrLo() - d.lo()) / (d.hi() - d.lo()) * gw), x2 = gx + (int) Math.round((d.jrHi() - d.lo()) / (d.hi() - d.lo()) * gw);
            g.fill(Math.max(gx, x1), y + 2, Math.min(gx + gw, x2), y + h - 2, Theme.alpha(Theme.GREEN, 0x30));
        }
        for (int i = 0; i < bins && max > 0; i++) {
            int bx = gx + i * gw / bins, bx2 = gx + (i + 1) * gw / bins - 1;
            int ha = (int) Math.round(d.histA()[i] / max * half), hb = (int) Math.round(d.histB()[i] / max * half);
            if (ha > 0) g.fill(bx, mid - ha, bx2, mid, COLOR_A);
            if (hb > 0) g.fill(bx, mid + 1, bx2, mid + 1 + hb, COLOR_B);
        }
        g.fill(gx, mid, gx + gw, mid + 1, Theme.TEXT);
        if (m == Compare.Metric.REACH) {
            int rx = gx + (int) Math.round((AttackEvent.DEFAULT_RANGE - d.lo()) / (d.hi() - d.lo()) * gw);
            for (int yy = y + 2; yy < y + h - 2; yy += 4) g.fill(rx, yy, rx + 1, Math.min(yy + 2, y + h - 2), Theme.TEXT_STRONG);
        }
        meanTick(ui, d.da().mean(), d, gx, gw, mid - half, mid);
        meanTick(ui, d.db().mean(), d, gx, gw, mid + 1, mid + 1 + half);
        ui.text("A", x + 4, y + 3, COLOR_A);
        ui.text("B", x + 4, y + h - 11, COLOR_B);
        int ly = y + h + 2;
        ui.text(axis(m, d.lo()), gx, ly, Theme.DIM);
        ui.textCenter(axis(m, (d.lo() + d.hi()) / 2), gx + gw / 2, ly, Theme.DIM);
        ui.textRight(axis(m, d.hi()), gx + gw, ly, Theme.DIM);
        if (ui.hover(x, y, w, h)) {
            int i = Math.max(0, Math.min(bins - 1, (int) ((ui.mx - gx) * (long) bins / Math.max(1, gw))));
            double b0 = d.lo() + (d.hi() - d.lo()) * i / bins, b1 = d.lo() + (d.hi() - d.lo()) * (i + 1) / bins;
            String tip = axis(m, b0) + " … " + axis(m, b1) + "\nA: " + Fmt.pct(100 * d.histA()[i]) + "   B: " + Fmt.pct(100 * d.histB()[i]);
            if (d.jrLo() != Long.MIN_VALUE) tip += t("\nЗелёная полоса: окно успеха ", "\nGreen band: success window ") + d.jrLo() + "…" + d.jrHi() + t(" мс", " ms");
            if (m == Compare.Metric.REACH) tip += t("\nПунктир: 3 блока", "\nDashed: 3 blocks");
            if (m.kind == Compare.Kind.SHARE) tip += t("\nДоля в каждом бою (от 3 значений)", "\nShare in each fight (3+ values)");
            ui.tip(tip);
        }
    }

    private static void meanTick(Ui ui, double v, Detail d, int gx, int gw, int y1, int y2) {
        if (!Double.isFinite(v)) return;
        double k = Math.max(0, Math.min(1, (v - d.lo()) / (d.hi() - d.lo())));
        int mx = gx + (int) Math.round(k * (gw - 1));
        ui.g.fill(mx, y1, mx + 1, y2, Theme.TEXT_STRONG);
    }

    private static void stats(Ui ui, Detail d, Compare.Row row, int x, int y, int w) {
        Compare.Metric m = d.metric();
        boolean perFight = m.kind == Compare.Kind.SHARE;
        String unit = perFight ? t(" (доли по боям)", " (shares by fight)") : "";
        ui.text(Draw.ellipsize(ui.font, "A: " + distLine(m, d.da()) + unit, w), x, y, COLOR_A);
        ui.text(Draw.ellipsize(ui.font, "B: " + distLine(m, d.db()) + unit, w), x, y + 10, COLOR_B);
        StringBuilder b = new StringBuilder();
        if (row.verdict() == Compare.Verdict.FEW) b.append(t("Разница: мало данных", "Difference: too little data"));
        else {
            b.append(t("Разница ", "Difference ")).append(delta(m, row.diff())).append(" [").append(delta(m, row.lo())).append(" … ")
                    .append(delta(m, row.hi())).append("]");
            if (row.fewFights()) b.append('*');
        }
        double[] mc = d.medianCi();
        if (mc != null && Double.isFinite(mc[0]) && Double.isFinite(d.da().median()) && Double.isFinite(d.db().median())) {
            b.append(t(" · медиан ", " · medians ")).append(delta(m, d.db().median() - d.da().median())).append(" [")
                    .append(delta(m, mc[0])).append(" … ").append(delta(m, mc[1])).append("]");
        }
        ui.text(Draw.ellipsize(ui.font, b.toString(), w), x, y + 20, Theme.TEXT);
        if (ui.hover(x, y + 19, w, 10)) ui.tip(b + t("\nВ скобках интервал разницы: бутстрэп по боям.", "\nIn brackets: the interval of the difference, bootstrapped by fight."));
    }

    private static String distLine(Compare.Metric m, Compare.Dist d) {
        if (d.n() == 0) return t("нет данных", "no data");
        return t("среднее ", "mean ") + value(m, d.mean()) + t(" · медиана ", " · median ") + value(m, d.median())
                + " · σ " + spread(m, d.sd()) + " · n " + d.n();
    }

    private static String spread(Compare.Metric m, double sd) {
        return m.kind == Compare.Kind.SHARE ? Fmt.num(sd, 0) + "%" : value(m == Compare.Metric.JR_SIGMA ? Compare.Metric.JR_MEAN : m, sd);
    }

    /** Two small targets with the hit points of A and B and their mean point. */
    private static void silhouettes(Ui ui, Detail d, int x, int y, int w, int h) {
        int each = (w - 6) / 2;
        target(ui, d.aimA(), "A", COLOR_A, x, y, each, h);
        target(ui, d.aimB(), "B", COLOR_B, x + each + 6, y, each, h);
    }

    private static void target(Ui ui, double[][] pts, String tag, int color, int x, int y, int w, int h) {
        var g = ui.g;
        Draw.round(g, x, y, w, h, Theme.R_CTRL, Theme.INSET);
        int bodyH = Math.max(20, h - 22), feet = y + 12 + bodyH, ax = x + w / 2;
        double unit = bodyH / 32.0;
        int head = feet - bodyH, body = Theme.RAISED, limbs = Theme.faded(Theme.RAISED_HOVER, 0.3);
        g.fill(ax - (int) (4 * unit), head, ax + (int) (4 * unit), head + (int) (8 * unit), body);
        g.fill(ax - (int) (4 * unit), head + (int) (8 * unit), ax + (int) (4 * unit), head + (int) (20 * unit), body);
        g.fill(ax - (int) (8 * unit), head + (int) (8 * unit), ax - (int) (4 * unit), head + (int) (20 * unit), limbs);
        g.fill(ax + (int) (4 * unit), head + (int) (8 * unit), ax + (int) (8 * unit), head + (int) (20 * unit), limbs);
        g.fill(ax - (int) (4 * unit), head + (int) (20 * unit), ax - 1, feet, limbs);
        g.fill(ax, head + (int) (20 * unit), ax + (int) (4 * unit), feet, limbs);
        double hwBody = bodyH * AimReport.HALF_W / AimReport.HEIGHT;
        double su = 0, sv = 0;
        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        for (double[] p : pts) {
            int px = ax + (int) Math.round(p[0] * hwBody), py = feet - (int) Math.round(p[1] * bodyH);
            g.fill(px, py, px + 1, py + 1, Theme.alpha(color, 0xA0));
            su += p[0];
            sv += p[1];
        }
        if (pts.length > 0) {
            int mx = ax + (int) Math.round(su / pts.length * hwBody), my = feet - (int) Math.round(sv / pts.length * bodyH);
            g.fill(mx - 3, my, mx + 4, my + 1, Theme.TEXT_STRONG);
            g.fill(mx, my - 3, mx + 1, my + 4, Theme.TEXT_STRONG);
        }
        g.disableScissor();
        ui.text(tag + (pts.length == 0 ? t(": нет", ": none") : ""), x + 4, y + 3, color);
        if (ui.hover(x, y, w, h)) ui.tip(t("Точки попаданий по цели, крестик: средняя точка", "Hit points on the target, the cross is the mean point")
                + (pts.length > 0 ? "\nn " + pts.length : ""));
    }

    // ---- Texts ----

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

    /** Short scale-end labels. */
    static String axis(Compare.Metric m, double v) {
        return switch (m) {
            case REACH -> Fmt.num(v, 0);
            case AIM_YAW -> Fmt.num(v, 0) + "°";
            case JR_MEAN, JR_SIGMA, PING -> String.valueOf(Math.round(v));
            case COMBO -> Fmt.num(v, 0);
            default -> Fmt.pct(v);
        };
    }

    static String delta(Compare.Metric m, double d) {
        return switch (m) {
            case REACH -> Fmt.signed(d, 2);
            case AIM_YAW -> Fmt.signed(d, 1) + "°";
            case COMBO -> Fmt.signed(d, 2);
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
