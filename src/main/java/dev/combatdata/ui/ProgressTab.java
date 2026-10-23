package dev.combatdata.ui;

import dev.combatdata.CombatDataClient;
import dev.combatdata.client.ProgressData;
import dev.combatdata.client.Recordings;
import dev.combatdata.core.*;
import static dev.combatdata.core.Lang.t;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Points are evenly spaced in order, otherwise recordings from one evening would overlap. */
final class ProgressTab implements Tab {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM"), DAY_EN = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"),
            FULL_EN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DATE_EN = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int LEFT = 34, BOTTOM = 14;

    private final MenuScreen menu;
    private Progress.Metric metric = Progress.Metric.HIT_RATE;
    private Progress.Group group = Progress.Group.RECORDING;
    private String server;
    private UUID opp;
    private String oppName;
    private String error;
    private String pointsKey;
    private List<Progress.Point> points = List.of();
    private List<Item> servers = List.of();
    private List<Item> opponents = List.of();

    ProgressTab(MenuScreen menu) {
        this.menu = menu;
    }

    @Override
    public void onShow() {
        error = null;
        ProgressData.request(CombatDataClient.settings(), true);
    }

    static String metricName(Progress.Metric m, boolean narrow) {
        return switch (m) {
            case HIT_RATE -> narrow ? t("Попад.", "Hits") : t("Попадания", "Hit rate");
            case JR_SUCCESS -> narrow ? "JR" : "Jump reset";
            case REACH -> narrow ? t("Дист.", "Dist.") : t("Дистанция", "Distance");
            case THREE_BLOCK -> narrow ? "3BH" : "3 block hit";
            case CHARGE -> t("Заряд", "Charge");
            case FIRST_HIT -> narrow ? t("1-й уд.", "1st") : t("Первый удар", "First hit");
        };
    }

    static String value(Progress.Metric m, double v) {
        return m.percent ? Fmt.pct(v) : Fmt.num(v, 2) + t(" бл.", " bl.");
    }

    private static String samplesName(Progress.Metric m, int n) {
        boolean en = Lang.english();
        return switch (m) {
            case HIT_RATE -> en ? " attacks" : " " + Lang.plural(n, "атака", "атаки", "атак");
            case JR_SUCCESS -> en ? " attempts" : " " + Lang.plural(n, "попытка", "попытки", "попыток");
            case THREE_BLOCK -> en ? " hits rated" : " " + Lang.plural(n, "удар оценён", "удара оценено", "ударов оценено");
            case FIRST_HIT -> en ? " trades" : " " + Lang.plural(n, "размен", "размена", "разменов");
            default -> en ? " hits" : " " + Lang.plural(n, "попадание", "попадания", "попаданий");
        };
    }

    private void ensure(ProgressData.Snapshot snap) {
        String k = System.identityHashCode(snap) + "/" + metric + "/" + group + "/" + server + "/" + opp + "/" + Lang.english();
        if (k.equals(pointsKey)) return;
        pointsKey = k;
        points = Progress.points(snap.summaries(), metric, group, server, opp, ZoneId.systemDefault());
        Map<String, Item> sm = new HashMap<>();
        Map<UUID, Item> om = new HashMap<>();
        // Summaries are in time order: the latest name wins, older names stay searchable.
        for (Progress.Summary s : snap.summaries()) {
            for (String sv : s.servers()) {
                Item it = sm.computeIfAbsent(sv, Item::new);
                it.recordings++;
                it.name(sv);
            }
            for (Map.Entry<UUID, Progress.Opp> e : s.opponents().entrySet()) {
                Item it = om.computeIfAbsent(e.getKey(), Item::new);
                it.recordings++;
                it.hits += e.getValue().m().confirmed();
                if (e.getValue().name() != null) it.name(e.getValue().name());
            }
        }
        servers = sorted(sm.values());
        List<Item> ol = sorted(om.values());
        // Same name on different UUIDs (name change): append the UUID prefix.
        Map<String, Integer> same = new HashMap<>();
        for (Item it : ol) same.merge(it.name.toLowerCase(Locale.ROOT), 1, Integer::sum);
        for (Item it : ol) it.label = same.get(it.name.toLowerCase(Locale.ROOT)) > 1
                ? it.name + " (" + it.key.toString().substring(0, 8) + ")" : it.name;
        opponents = ol;
        if (opp != null && om.containsKey(opp)) oppName = om.get(opp).label;
    }

    private static List<Item> sorted(Collection<Item> c) {
        List<Item> l = new ArrayList<>(c);
        l.sort((a, b) -> a.recordings != b.recordings ? Integer.compare(b.recordings, a.recordings) : Integer.compare(b.hits, a.hits));
        return l;
    }

    /** key is the opponent UUID (the name is for display and search only) or a server address. */
    private static final class Item {
        final Object key;
        final Set<String> names = new HashSet<>();
        String name = "?", label = "?";
        int recordings, hits;

        Item(Object key) {
            this.key = key;
        }

        void name(String n) {
            name = label = n;
            names.add(n.toLowerCase(Locale.ROOT));
        }

        boolean matches(String q) {
            if (q.isEmpty()) return true;
            for (String n : names) if (n.contains(q)) return true;
            return key.toString().toLowerCase(Locale.ROOT).startsWith(q);
        }
    }


    private enum Pick { SERVER, OPPONENT }

    private Pick open;
    private String query = "";
    private double popScroll;
    private int popMax;
    private final int[][] chips = new int[2][3];
    private int tabX, tabW;
    private int popX, popY, popW, popH;
    private static final int ROW = 13;

    void setOppQuery(String q) {
        query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        popScroll = 0;
    }

    boolean closePopup() {
        if (open == null) return false;
        open = null;
        return true;
    }

    private void toggle(Pick p) {
        open = open == p ? null : p;
        if (open != null) {
            popScroll = 0;
            menu.focusOppSearch();
        }
    }

    private void select(Pick p, Item it) {
        if (p == Pick.SERVER) server = it == null ? null : (String) it.key;
        else {
            opp = it == null ? null : (UUID) it.key;
            oppName = it == null ? null : it.label;
        }
        open = null;
    }

    @Override
    public boolean scrolled(double mx, double my, double dy) {
        if (open == null) return false;
        if (mx >= popX && mx < popX + popW && my >= popY && my < popY + popH) {
            popScroll = Math.max(0, Math.min(popScroll - dy * ROW * 2, popMax));
        }
        return true;
    }

    private void popup(Ui ui, int areaBottom) {
        Pick p = open;
        var g = ui.g;
        g.nextStratum();
        ui.unclip();
        ui.click(-10_000, -10_000, 20_000, 20_000, () -> open = null);
        List<Item> list = new ArrayList<>();
        for (Item it : p == Pick.SERVER ? servers : opponents) if (it.matches(query)) list.add(it);
        int[] chip = chips[p.ordinal()];
        popW = Math.min(tabW, Math.max(chip[2], 180));
        popX = Math.max(tabX, Math.min(chip[0], tabX + tabW - popW));
        popY = chip[1] + 20;
        int rows = list.size() + 1, maxH = Math.max(60, areaBottom - popY - 4);
        popH = Math.min(maxH, 26 + rows * ROW + 4);
        Draw.roundBordered(g, popX, popY, popW, popH, Theme.R_PANEL, Theme.BORDER, Theme.WINDOW);
        menu.placeOppSearch(popX + 4, popY + 4, popW - 8, 16);
        int ly = popY + 24, lh = popH - 28;
        int content = rows * ROW;
        popMax = Math.max(0, content - lh);
        popScroll = Math.max(0, Math.min(popScroll, popMax));
        g.enableScissor(popX + 2, ly, popX + popW - 2, ly + lh);
        ui.clip(popX + 2, ly, popW - 4, lh);
        int ry = ly - (int) popScroll;
        Object cur = p == Pick.SERVER ? server : opp;
        row(ui, ry, p == Pick.SERVER ? t("Все серверы", "All servers") : t("Все противники", "All opponents"), null, cur == null,
                () -> select(p, null));
        ry += ROW;
        for (Item it : list) {
            if (ry + ROW >= ly && ry <= ly + lh) {
                String meta = it.recordings + (Lang.english() ? (it.recordings == 1 ? " rec." : " recs.")
                        : " " + Lang.plural(it.recordings, "запись", "записи", "записей"));
                row(ui, ry, it.label, meta, it.key.equals(cur), () -> select(p, it));
            }
            ry += ROW;
        }
        if (list.isEmpty()) ui.text(t("Ничего не найдено", "Nothing found"), popX + 8, ry + 2, Theme.DIM);
        ui.unclip();
        g.disableScissor();
        if (popMax > 0) ui.scrollbar(popX + popW - 5, ly, lh, popScroll, lh, content);
    }

    private void row(Ui ui, int y, String label, String meta, boolean cur, Runnable action) {
        int x = popX + 2, w = popW - 4;
        if (ui.hover(x, y, w, ROW)) Draw.round(ui.g, x, y, w, ROW, Theme.R_CTRL, Theme.RAISED_HOVER);
        if (cur) ui.text("✔", x + 4, Ui.textY(y, ROW), Theme.ACCENT);
        int mw = meta == null ? 0 : ui.font.width(meta) + 10;
        ui.text(Draw.ellipsize(ui.font, label, w - 22 - mw), x + 16, Ui.textY(y, ROW), cur ? Theme.TEXT_STRONG : Theme.TEXT);
        if (meta != null) ui.textRight(meta, x + w - 6, Ui.textY(y, ROW), Theme.DIM);
        ui.click(x, y, w, ROW, action);
    }


    @Override
    public void render(Ui ui, int x, int y, int w, int h) {
        body(ui, x, y, w, h);
        tabX = x;
        tabW = w;
        if (open != null) popup(ui, y + h);
    }

    private void body(Ui ui, int x, int y, int w, int h) {
        ProgressData.request(CombatDataClient.settings(), false);
        ProgressData.Snapshot snap = ProgressData.snapshot();
        if (snap != null) ensure(snap);
        int top = controls(ui, x, y, w) + 4;
        int ph = y + h - top;
        if (snap == null) {
            Draw.roundBordered(ui.g, x, top, w, ph, Theme.R_PANEL, Theme.BORDER, Theme.PANEL);
            String st = ProgressData.status();
            ui.textCenter(t("Считаю прогресс по записям…", "Computing progress across recordings…"), x + w / 2, top + ph / 2 - 6, Theme.MUTED);
            if (st != null) ui.textCenter(st, x + w / 2, top + ph / 2 + 6, Theme.DIM);
            return;
        }
        String head = summaryLine(snap);
        String st = ProgressData.status();
        if (st != null) head = t("Пересчёт ", "Updating ") + st + " · " + head;
        if (error != null) head = error;
        ui.text(Draw.ellipsize(ui.font, head, w), x, top, error != null ? Theme.RED : Theme.MUTED);
        top += 12;
        ph = y + h - top;
        if (points.isEmpty()) {
            Draw.roundBordered(ui.g, x, top, w, ph, Theme.R_PANEL, Theme.BORDER, Theme.PANEL);
            String msg = snap.summaries().isEmpty() ? t("Сохранённых записей нет", "No saved recordings")
                    : t("Нет данных: в точке нужно не меньше ", "No data: a point needs at least ") + metric.minSamples
                    + samplesName(metric, metric.minSamples);
            ui.textCenter(Draw.ellipsize(ui.font, msg, w - 16), x + w / 2, top + ph / 2 - 4, Theme.DIM);
            return;
        }
        chart(ui, snap, x, top, w, ph);
    }

    private String summaryLine(ProgressData.Snapshot snap) {
        if (points.isEmpty()) return t("Записей ", "Recordings ") + snap.summaries().size();
        double sum = 0;
        for (Progress.Point p : points) sum += p.value();
        String s = t("Точек ", "Points ") + points.size() + t(" · среднее ", " · mean ") + value(metric, sum / points.size());
        int k = Math.min(3, points.size() / 2);
        if (k > 0) {
            double a = 0, b = 0;
            for (int i = 0; i < k; i++) {
                a += points.get(i).value();
                b += points.get(points.size() - 1 - i).value();
            }
            s += t(" · первые ", " · first ") + k + t(" → последние ", " → last ") + k + ": " + value(metric, a / k) + " → " + value(metric, b / k);
        }
        return s;
    }

    private int controls(Ui ui, int x, int y, int w) {
        Flow f = new Flow(x, y, w);
        Progress.Metric[] ms = Progress.Metric.values();
        String[] names = new String[ms.length];
        for (int i = 0; i < ms.length; i++) names[i] = metricName(ms[i], false);
        if (ui.segmentedWidth(names) > w) for (int i = 0; i < ms.length; i++) names[i] = metricName(ms[i], true);
        int[] p = f.place(ui.segmentedWidth(names), false);
        ui.segmented(p[0], p[1], names, metric.ordinal(), i -> metric = ms[i]);
        String[] gl = w < 360 ? new String[]{t("Запись", "Rec."), t("День", "Day")} : new String[]{t("По записи", "Per recording"), t("По дню", "Per day")};
        p = f.place(ui.segmentedWidth(gl), false);
        ui.segmented(p[0], p[1], gl, group.ordinal(), i -> group = Progress.Group.values()[i]);
        pickChip(ui, f, Pick.SERVER, t("Сервер: ", "Server: ") + (server == null ? t("все", "all") : Draw.ellipsize(ui.font, server, w / 3)),
                server != null);
        pickChip(ui, f, Pick.OPPONENT, t("Противник: ", "Opponent: ") + (opp == null ? t("все", "all") : Draw.ellipsize(ui.font, oppName, w / 3)),
                opp != null);
        int[] ip = f.place(Ui.INFO, false);
        ui.info(ip[0], ip[1] + 4, Help.progress());
        return f.bottom();
    }

    private void pickChip(Ui ui, Flow f, Pick pick, String label, boolean set) {
        label += " ▾";
        int cw = ui.font.width(label) + 12;
        int[] p = f.place(cw, false);
        chips[pick.ordinal()] = new int[]{p[0], p[1], cw};
        ui.chip(p[0], p[1] + 3, cw, label, set || open == pick, () -> toggle(pick));
    }

    private void chart(Ui ui, ProgressData.Snapshot snap, int x, int y, int w, int h) {
        var g = ui.g;
        Draw.roundBordered(g, x, y, w, h, Theme.R_PANEL, Theme.BORDER, Theme.INSET);
        int gx = x + LEFT, gy = y + 8, gw = Math.max(10, w - LEFT - 10), gh = Math.max(10, h - BOTTOM - 14);
        double min, max, step;
        if (metric.percent) {
            min = 0;
            max = 100;
            step = gh < 60 ? 50 : 20;
        } else {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (Progress.Point p : points) {
                lo = Math.min(lo, p.value());
                hi = Math.max(hi, p.value());
            }
            min = Math.floor(lo * 4 - 1) / 4;
            max = Math.ceil(hi * 4 + 1) / 4;
            step = max - min > 1.5 ? 0.5 : 0.25;
        }
        for (double v = Math.ceil(min / step) * step; v <= max + 1e-9; v += step) {
            int ly = (int) ChartMath.valueToPx(v, min, max, gy, gh);
            g.fill(gx, ly, gx + gw, ly + 1, Theme.RAISED);
            ui.textRight(metric.percent ? Fmt.num(v, 0) + "%" : Fmt.num(v, 2), gx - 3, ly - 4, Theme.DIM);
        }
        int n = points.size();
        int[] px = new int[n], py = new int[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            px[i] = n == 1 ? gx + gw / 2 : gx + (int) Math.round((double) i * gw / (n - 1));
            py[i] = (int) Math.round(ChartMath.valueToPx(points.get(i).value(), min, max, gy, gh));
            sum += points.get(i).value();
        }
        int my = (int) ChartMath.valueToPx(sum / n, min, max, gy, gh);
        Draw.dashH(g, gx, gx + gw, my, 3, 2, Theme.MUTED);
        int every = n <= 1 ? 1 : Math.max(1, (int) Math.ceil(44.0 * (n - 1) / gw));
        boolean en = Lang.english();
        for (int i = 0; i < n; i += every) {
            String d = (en ? DAY_EN : DAY).format(Instant.ofEpochMilli(points.get(i).t()).atZone(ZoneId.systemDefault()));
            g.fill(px[i], gy + gh, px[i] + 1, gy + gh + 2, Theme.BORDER);
            ui.textCenter(d, px[i], gy + gh + 4, Theme.DIM);
        }
        for (int i = 1; i < n; i++) Draw.line(g, px[i - 1], py[i - 1], px[i], py[i], Theme.ACCENT);
        int hover = -1;
        if (ui.hover(gx - 6, gy - 6, gw + 12, gh + 12)) {
            int best = 7;
            for (int i = 0; i < n; i++) {
                int d = Math.abs(px[i] - ui.mx);
                if (d < best) {
                    best = d;
                    hover = i;
                }
            }
        }
        for (int i = 0; i < n; i++) Draw.circle(g, px[i] - 2, py[i] - 2, 5, Theme.ACCENT);
        if (hover >= 0) {
            Progress.Point pt = points.get(hover);
            Draw.circle(g, px[hover] - 4, py[hover] - 4, 9, Theme.TEXT_STRONG);
            Draw.circle(g, px[hover] - 3, py[hover] - 3, 7, Theme.ACCENT);
            ui.click(gx - 6, gy - 6, gw + 12, gh + 12, () -> open(snap, pt));
            g.nextStratum();
            String tip = tip(pt);
            int tw = Math.min(w - 4, ui.font.width(tip) + 8), th = 12;
            int tx = Math.max(x + 2, Math.min(px[hover] - tw / 2, x + w - tw - 2)), ty = py[hover] - th - 8;
            if (ty < y + 2) ty = py[hover] + 8;
            Draw.roundBordered(g, tx, ty, tw, th, Theme.R_CTRL, Theme.BORDER, Theme.WINDOW);
            ui.text(Draw.ellipsize(ui.font, tip, tw - 8), tx + 4, ty + 2, Theme.TEXT);
        }
    }

    private String tip(Progress.Point p) {
        boolean en = Lang.english();
        var at = Instant.ofEpochMilli(p.t()).atZone(ZoneId.systemDefault());
        String when = group == Progress.Group.DAY ? (en ? DATE_EN : DATE).format(at) : (en ? FULL_EN : FULL).format(at);
        String s = when + " · " + value(metric, p.value()) + " · " + p.samples() + samplesName(metric, p.samples());
        if (group == Progress.Group.DAY) {
            int k = p.sources().size();
            s += " · " + k + (en ? (k == 1 ? " recording" : " recordings") : " " + Lang.plural(k, "запись", "записи", "записей"));
        } else if (!p.sources().get(0).servers().isEmpty()) s += " · " + String.join(", ", p.sources().get(0).servers());
        return s + t(" · клик: открыть", " · click: open");
    }

    private void open(ProgressData.Snapshot snap, Progress.Point p) {
        Progress.Summary s = p.sources().get(p.sources().size() - 1);
        Recordings.Entry e = snap.entries().get(s.file());
        if (e == null) {
            error = t("Запись не найдена: ", "Recording not found: ") + s.file();
            return;
        }
        error = null;
        Recordings.readAsync(e.file(), rec -> menu.openRecording(e, rec), ex -> {
            CombatDataClient.LOG.error("[CD] не удалось открыть запись", ex);
            error = t("Не удалось открыть: ", "Could not open: ") + ex.getMessage();
        });
    }
}
