package dev.combatdata.ui;

import dev.combatdata.CombatDataClient;
import dev.combatdata.client.ProgressData;
import dev.combatdata.core.*;
import static dev.combatdata.core.Lang.t;

import java.util.*;

final class StatsView {
    static final int OVERVIEW = 0, ATTACK = 1, DEFENSE = 2, FIGHTS = 3;
    static final int A_HITS = 0, A_AIM = 1, A_STRIKES = 2, A_COMBO = 3, A_WTAP = 4;
    static final int D_JUMP = 0, D_KNOCKBACK = 1, D_TAKEN = 2;
    static final int F_OPPONENTS = 0, F_FIGHTS = 1, F_TRADES = 2, F_DISTANCE = 3, F_TIMELINE = 4;

    static String[] subs() {
        return Lang.english() ? new String[]{"Overview", "Attack", "Defense", "Fights"}
                : new String[]{"Обзор", "Атака", "Защита", "Бои"};
    }

    private static String[] modes(int sub, boolean narrow) {
        boolean en = Lang.english();
        return switch (sub) {
            case ATTACK -> en ? (narrow ? new String[]{"Hits", "Aim", "Types", "Combo", "W-tap"}
                    : new String[]{"Hits", "Aim", "Strikes", "Combos", "W-tap"})
                    : narrow ? new String[]{"Попад.", "Прицел", "Удары", "Комбо", "W-tap"}
                    : new String[]{"Попадания", "Прицел", "Удары", "Комбо", "W-tap"};
            case DEFENSE -> en ? new String[]{"Jump reset", "Knockback", narrow ? "On me" : "Hits on me"}
                    : new String[]{"Jump reset", "Отлёт", narrow ? "По мне" : "Удары по мне"};
            default -> en ? new String[]{narrow ? "Opp." : "Opponents", "Fights", "Trades", narrow ? "Dist." : "Distance", narrow ? "Time" : "Timeline"}
                    : new String[]{narrow ? "Против." : "Противники", "Бои", narrow ? "Разм." : "Размены", narrow ? "Дист." : "Дистанция", narrow ? "Лента" : "Таймлайн"};
        };
    }

    interface Header {
        void draw(Ui ui, int x, int y, int w);
    }

    private static final int LINE = 11, ROW = 12, PAD = 8;

    private record Kv(String label, String value, int color) {
        Kv(String label, String value) {
            this(label, value, Theme.TEXT);
        }
    }

    private record Card(String title, List<Kv> rows, int main, String info) {
        Card(String title, List<Kv> rows, int main) {
            this(title, rows, main, null);
        }

        Card with(String text) {
            return new Card(title, rows, main, text);
        }

        int shown(boolean open) {
            return open ? rows.size() : Math.min(main, rows.size());
        }

        int height(boolean open) {
            return 14 + shown(open) * LINE + 6;
        }
    }

    private record Line(long t, String time, String text, int color) {
    }

    private final MenuScreen menu;
    private Recording rec;
    private Calibration.Result calib;
    private Stats.Result all, res;
    private List<Event> events = List.of();
    private List<JumpResetEvaluator.Attempt> attempts = List.of();
    private List<Line> timeline = List.of();
    private List<Trades.Trade> trades = List.of();
    private WTap.Result wtap = WTap.analyze(List.of());
    private List<Insights.Insight> insights = List.of();
    private String key;
    private UUID opp;
    private String oppName;
    private long timeFrom = -1, timeTo = -1;
    private int sub;
    private int attackMode, defenseMode, fightsMode;
    private boolean hitList, jrList, takenList;
    private boolean showProjectiles = true;
    private final Set<String> expanded = new HashSet<>();
    private String note;
    private double scroll;
    private int maxScroll;
    private int px, py, pw, ph;
    private boolean showFails = true, showNoJump = true, showMisses = true, showUnconfirmed = true, showFlyOut = true;
    private StatFilter.WeaponView weaponOverride;
    private boolean filtersOff, showFiltered;
    private StatFilter sf = StatFilter.NONE;
    private List<Event> rawEvents = List.of();
    private Chart jrChart = new Chart(), hitChart = new Chart(), takenChart = new Chart();
    private List<Chart.Point> jrPoints = List.of(), hitPoints = List.of(), takenPoints = List.of();
    private double takenRange = AttackEvent.DEFAULT_RANGE;
    private Chart distChart = new Chart();
    private UUID distOpp;
    private String distName;
    private List<Distance.Sample> distSeries = List.of();
    private Chart.Line distLine;
    private List<Chart.Point> distPoints = List.of();
    private double distMean = Double.NaN;
    private boolean replayOn;
    private final ReplayView replayView = new ReplayView();
    private Cooldown.Summary cdSum = Cooldown.summary(List.of());
    private double rangeLine = AttackEvent.DEFAULT_RANGE;

    StatsView(MenuScreen menu) {
        this.menu = menu;
    }

    void set(Recording r, boolean keepFilter) {
        rec = r;
        key = null;
        if (!keepFilter) {
            opp = null;
            oppName = null;
            timeFrom = timeTo = -1;
            weaponOverride = null;
            filtersOff = false;
            scroll = 0;
            jrChart = new Chart();
            hitChart = new Chart();
            takenChart = new Chart();
            distChart = new Chart();
        }
    }

    void setNote(String n) {
        note = n;
    }

    boolean hasData() {
        return rec != null;
    }

    private StatFilter effectiveFilter(Settings s) {
        StatFilter base = filtersOff ? StatFilter.NONE : s.statFilter();
        return weaponOverride != null ? base.withWeapon(weaponOverride) : base;
    }

    private StatFilter.WeaponView weaponView() {
        return sf.weapon();
    }

    private void ensure() {
        Settings s = CombatDataClient.settings();
        StatFilter cur = effectiveFilter(s);
        String k = s.jumpParams() + "/" + s.interpMs + "/" + opp + "/" + timeFrom + "/" + timeTo + "/" + showFails
                + showNoJump + showMisses + showUnconfirmed + showFlyOut + Lang.english() + "/" + cur
                + showFiltered + "/" + s.tradeParams() + "/" + showProjectiles;
        if (k.equals(key)) return;
        key = k;
        sf = cur;
        all = Stats.compute(rec, s.jumpParams(), Stats.Filter.ALL, AttackEvent.DEFAULT_RANGE, s.interpMs, sf, s.tradeParams());
        boolean filtered = opp != null || timeFrom >= 0;
        Stats.Filter f = !filtered ? Stats.Filter.ALL
                : new Stats.Filter(timeFrom >= 0 ? timeFrom : 0, timeFrom >= 0 ? timeTo : Long.MAX_VALUE, opp);
        res = !filtered ? all : Stats.compute(rec, s.jumpParams(), f, AttackEvent.DEFAULT_RANGE, s.interpMs, sf, s.tradeParams());
        rawEvents = Stats.events(rec, f);
        StatFilter.Applied ap = sf.apply(rawEvents);
        events = ap.kept();
        attempts = JumpResetEvaluator.evaluate(events, s.jumpParams());
        timeline = buildTimeline();
        trades = Trades.split(events, s.tradeParams(), sf, s.jumpParams());
        jrPoints = buildJumpPoints(s);
        hitPoints = buildHitPoints(s);
        takenPoints = buildTakenPoints();
        buildDistance();
        // Intervals use all attacks (a filtered hand hit would otherwise lengthen the interval); only filtered ones go into stats.
        List<Cooldown.Interval> kept = new ArrayList<>();
        for (Cooldown.Interval i : Cooldown.intervals(rawEvents)) if (sf.attack(i.attack()) == null) kept.add(i);
        cdSum = Cooldown.summary(kept);
        wtap = WTap.analyze(events);
        calib = Calibration.analyze(attempts, s.jumpParams());
        insights = Insights.evaluate(new Insights.Input(res, s.jumpParams()));
    }

    private void filter(UUID id, String name) {
        if (id != null && id.equals(opp)) id = null;
        opp = id;
        oppName = id == null ? null : name;
        scroll = 0;
    }

    private int graphKind() {
        if (res == null) return 0;
        if (sub == DEFENSE && defenseMode == D_JUMP && !jrList) return 1;
        if (sub == ATTACK && attackMode == A_HITS && !hitList) return 2;
        if (sub == DEFENSE && defenseMode == D_TAKEN && !takenList && res.taken().rated() > 0) return 4;
        if (sub == FIGHTS && fightsMode == F_DISTANCE && !distSeries.isEmpty()) return 5;
        return 0;
    }

    private boolean chartShown() {
        return graphKind() != 0;
    }

    private Chart chart() {
        return switch (graphKind()) {
            case 1 -> jrChart;
            case 4 -> takenChart;
            case 5 -> distChart;
            default -> hitChart;
        };
    }

    private void go(Insights.Page page) {
        switch (page) {
            case ATTACK_HITS -> { sub = ATTACK; attackMode = A_HITS; hitList = false; }
            case ATTACK_AIM -> { sub = ATTACK; attackMode = A_AIM; }
            case ATTACK_STRIKES -> { sub = ATTACK; attackMode = A_STRIKES; }
            case DEFENSE_JUMP -> { sub = DEFENSE; defenseMode = D_JUMP; jrList = false; }
            case DEFENSE_KNOCKBACK -> { sub = DEFENSE; defenseMode = D_KNOCKBACK; }
            case DEFENSE_TAKEN -> { sub = DEFENSE; defenseMode = D_TAKEN; }
            case FIGHTS_TRADES -> { sub = FIGHTS; fightsMode = F_TRADES; }
        }
        scroll = 0;
    }

    void render(Ui ui, int x, int y, int w, int h, Header header) {
        ensure();
        int sw = ui.segmented(x, y, subs(), sub, i -> {
            sub = i;
            scroll = 0;
        });
        if (header != null && w - sw - 8 > 20) header.draw(ui, x + sw + 8, y, w - sw - 8);
        int top = controls(ui, x, y + 22, w) + 2;
        if (chartShown()) {
            px = pw = 0;
            graph(ui, x, top, w, y + h - top);
            return;
        }
        px = x;
        py = top;
        pw = w;
        ph = y + h - top;
        Draw.roundBordered(ui.g, px, py, pw, ph, Theme.R_PANEL, Theme.BORDER, Theme.PANEL);
        int ix = px + 1, iy = py + 1, iw = pw - 2, ih = ph - 2;
        ui.g.enableScissor(ix, iy, ix + iw, iy + ih);
        ui.clip(ix, iy, iw, ih);
        int ctop = iy + PAD - (int) scroll, cx = ix + PAD, cw = iw - PAD * 2;
        int bottom = switch (sub) {
            case OVERVIEW -> overview(ui, cx, ctop, cw);
            case ATTACK -> switch (attackMode) {
                case A_HITS -> hitsTab(ui, cx, ctop, cw);
                case A_AIM -> aimTab(ui, cx, ctop, cw);
                case A_STRIKES -> strikesTab(ui, cx, ctop, cw);
                case A_WTAP -> wtapTab(ui, cx, ctop, cw);
                default -> comboTab(ui, cx, ctop, cw);
            };
            case DEFENSE -> defenseMode == D_KNOCKBACK ? kbTab(ui, cx, ctop, cw)
                    : defenseMode == D_TAKEN ? takenTab(ui, cx, ctop, cw) : jumpTab(ui, cx, ctop, cw);
            default -> switch (fightsMode) {
                case F_OPPONENTS -> opponentsTab(ui, cx, ctop, cw);
                case F_FIGHTS -> fightsTab(ui, cx, ctop, cw);
                case F_TRADES -> tradesTab(ui, cx, ctop, cw);
                case F_DISTANCE -> distanceTab(ui, cx, ctop, cw);
                default -> timelineTab(ui, cx, ctop, cw);
            };
        };
        int content = bottom - ctop + PAD * 2;
        maxScroll = Math.max(0, content - ih);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        ui.unclip();
        ui.g.disableScissor();
        ui.scrollbar(px + pw - 5, iy + 2, ih - 4, scroll, ih, content);
    }

    boolean scrolled(double mx, double my, double dy) {
        if (graphKind() == 5 && replayOn && replayView.scrolled(mx, my, dy)) return true;
        if (chartShown()) return chart().scrolled(mx, my, dy);
        if (mx < px || mx >= px + pw || my < py || my >= py + ph) return false;
        // Clamp now, otherwise one frame is drawn past the edge and the panel flickers.
        scroll = Math.max(0, Math.min(scroll - dy * 24, maxScroll));
        return true;
    }

    boolean pressed(double mx, double my) {
        if (graphKind() == 5 && replayOn && replayView.pressed(mx, my)) return true;
        return chartShown() && chart().pressed(mx, my);
    }

    void dragged(double mx, double my, double dx) {
        replayView.dragged(mx);
        if (chartShown()) chart().dragged(mx, dx);
    }

    void released(double mx, double my) {
        if (replayView.released()) return;
        if (chartShown()) chart().released(mx, my);
    }


    private int controls(Ui ui, int x, int y, int w) {
        Flow f = new Flow(x, y, w);
        boolean any = false;
        if (sub != OVERVIEW) {
            String[] m = modes(sub, false);
            if (ui.segmentedWidth(m) > w) m = modes(sub, true);
            int[] p = f.place(ui.segmentedWidth(m), false);
            int cur = sub == ATTACK ? attackMode : sub == DEFENSE ? defenseMode : fightsMode;
            ui.segmented(p[0], p[1], m, cur, i -> {
                if (sub == ATTACK) attackMode = i;
                else if (sub == DEFENSE) defenseMode = i;
                else fightsMode = i;
                scroll = 0;
            });
            any = true;
            boolean hits = sub == ATTACK && attackMode == A_HITS, jr = sub == DEFENSE && defenseMode == D_JUMP,
                    tk = sub == DEFENSE && defenseMode == D_TAKEN;
            if (hits || jr || tk) {
                String[] gl = {t("График", "Chart"), t("Список", "List")};
                p = f.place(ui.segmentedWidth(gl), false);
                ui.segmented(p[0], p[1], gl, (hits ? hitList : jr ? jrList : takenList) ? 1 : 0, i -> {
                    if (hits) hitList = i == 1;
                    else if (jr) jrList = i == 1;
                    else takenList = i == 1;
                    scroll = 0;
                });
            }
        }
        if (rec != null) {
            StatFilter.WeaponView wv = weaponView();
            String label = t("Оружие: ", "Weapon: ") + weaponViewName(wv);
            int cw = ui.font.width(label) + 12;
            int[] p = f.place(cw, false);
            ui.chip(p[0], p[1] + 3, cw, label, wv != StatFilter.WeaponView.ALL, () -> {
                StatFilter.WeaponView[] vs = StatFilter.WeaponView.values();
                weaponOverride = vs[(wv.ordinal() + 1) % vs.length];
            });
            any = true;
        }
        if (graphKind() != 0) {
            toggleChip(ui, f, w < 360 ? t("Отфильтр.", "Filtered") : t("Показывать отфильтрованное", "Show filtered"),
                    showFiltered, () -> showFiltered = !showFiltered);
        }
        if (opp != null) {
            String label = t("Противник: ", "Opponent: ") + Draw.ellipsize(ui.font, oppName, w / 3) + "  ×";
            int cw = ui.font.width(label) + 12;
            int[] p = f.place(cw, false);
            ui.chip(p[0], p[1] + 3, cw, label, true, () -> filter(null, null));
            any = true;
        }
        if (timeFrom >= 0) {
            String label = t("Участок ", "Range ") + Fmt.clock(timeFrom) + "–" + Fmt.clock(timeTo) + "  ×";
            int cw = ui.font.width(label) + 12;
            int[] p = f.place(cw, false);
            ui.chip(p[0], p[1] + 3, cw, label, true, () -> timeFrom = timeTo = -1);
            any = true;
        }
        int g = graphKind();
        if (g == 1) {
            toggleChip(ui, f, t("Неудачные", "Failed"), showFails, () -> showFails = !showFails);
            toggleChip(ui, f, t("Без прыжка", "No jump"), showNoJump, () -> showNoJump = !showNoJump);
            toggleChip(ui, f, "Fly out", showFlyOut, () -> showFlyOut = !showFlyOut);
        } else if (g == 5) {
            toggleChip(ui, f, t("Повтор", "Replay"), replayOn, () -> replayOn = !replayOn);
        } else if (g == 4) {
            toggleChip(ui, f, t("Снаряды", "Projectiles"), showProjectiles, () -> showProjectiles = !showProjectiles);
        } else if (g == 2) {
            toggleChip(ui, f, t("Промахи", "Misses"), showMisses, () -> showMisses = !showMisses);
            toggleChip(ui, f, t("Без подтв.", "Unconfirmed"), showUnconfirmed, () -> showUnconfirmed = !showUnconfirmed);
        }
        if (g != 0) {
            Chart c = chart();
            if (c.zoomed()) {
                String l1 = t("По участку", "This range"), l2 = t("Весь масштаб", "Full view");
                int w2 = ui.buttonWidth(l2), w1 = ui.buttonWidth(l1);
                int[] p = f.place(w2, false);
                ui.button(p[0], p[1] + 1, w2, 16, l2, Ui.Kind.GHOST, true, c::resetView);
                p = f.place(w1, false);
                ui.button(p[0], p[1] + 1, w1, 16, l1, Ui.Kind.SECONDARY, true, () -> {
                    timeFrom = c.view().from();
                    timeTo = c.view().to();
                });
            } else {
                String hint = t("Колесо: масштаб", "Wheel: zoom");
                int[] p = f.place(ui.font.width(hint), true);
                if (p != null) ui.text(hint, p[0], Ui.textY(p[1] + 1, 16), Theme.DIM);
            }
        }
        int[] ip = f.place(Ui.INFO, false);
        ui.info(ip[0], ip[1] + 4, modeHelp());
        return any || g != 0 ? f.bottom() + 2 : y - 2;
    }

    private String modeHelp() {
        return switch (sub) {
            case OVERVIEW -> Help.overview();
            case ATTACK -> switch (attackMode) {
                case A_HITS -> hitList ? Help.hits() : Help.hitsChart();
                case A_AIM -> Help.aimTab();
                case A_STRIKES -> Help.types() + "\n" + Help.charge();
                case A_WTAP -> Help.wtap();
                default -> Help.combo();
            };
            case DEFENSE -> switch (defenseMode) {
                case D_KNOCKBACK -> Help.knockback();
                case D_TAKEN -> Help.taken();
                default -> jrList ? Help.jump() : Help.jumpChart() + "\n" + Help.jump();
            };
            default -> switch (fightsMode) {
                case F_OPPONENTS -> Help.opponents();
                case F_FIGHTS -> Help.fights();
                case F_TRADES -> Help.trades();
                case F_DISTANCE -> replayOn ? Help.replay() : Help.distance();
                default -> Help.timeline();
            };
        };
    }

    private static String weaponViewName(StatFilter.WeaponView v) {
        return switch (v) {
            case ALL -> t("Все", "All");
            case SWORD -> t("Меч", "Sword");
            case HAND -> t("Рука", "Hand");
            case OTHER -> t("Другое", "Other");
        };
    }

    static String weaponName(Weapon w) {
        return switch (w) {
            case SWORD -> t("Меч", "Sword");
            case AXE -> t("Топор", "Axe");
            case MACE -> t("Булава", "Mace");
            case SPEAR -> t("Копьё", "Spear");
            case HAND -> t("Рука", "Hand");
            case OTHER -> t("Другое", "Other");
            case UNKNOWN -> t("Не меч (старая запись)", "Not a sword (old recording)");
        };
    }

    static String hiddenName(StatFilter.Hidden h) {
        return switch (h) {
            case SWORD -> t("меч", "sword");
            case AXE -> t("топор", "axe");
            case MACE -> t("булава", "mace");
            case SPEAR -> t("копьё", "spear");
            case HAND -> t("рука", "hand");
            case OTHER_WEAPON -> t("другое оружие", "other weapon");
            case UNKNOWN_WEAPON -> t("не меч", "not a sword");
            case TEAMMATE -> t("тиммейты", "teammates");
            case NPC -> "NPC";
            case HIGH_PING -> t("высокий пинг", "high ping");
        };
    }

    private static String projectileName(String typeId) {
        String id = shortId(typeId);
        if (id == null) return t("неизвестно", "unknown");
        return switch (id) {
            case "fishing_bobber" -> t("удочка", "rod");
            case "arrow", "spectral_arrow" -> t("стрела", "arrow");
            case "trident" -> t("трезубец", "trident");
            case "snowball" -> t("снежок", "snowball");
            case "egg" -> t("яйцо", "egg");
            default -> id;
        };
    }

    private static String shortId(String id) {
        return id == null ? null : id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    private static String weaponText(Weapon w, String item) {
        String s = weaponName(w);
        return item == null || w == Weapon.HAND ? s : s + " (" + shortId(item) + ")";
    }

    private static String incomingKindText(IncomingHitEvent h) {
        if (h.kind() == null) return t("ближний (старая запись)", "melee (old recording)");
        if (h.kindOr() == IncomingHitEvent.Kind.PROJECTILE) {
            return t("снаряд · ", "projectile · ") + projectileName(h.projectile());
        }
        return t("ближний · ", "melee · ") + weaponText(h.weaponOr(), h.attackerItem());
    }

    private static void toggleChip(Ui ui, Flow f, String label, boolean on, Runnable toggle) {
        int w = ui.font.width(label) + 12;
        int[] p = f.place(w, false);
        ui.chip(p[0], p[1] + 3, w, label, on, toggle);
    }

    private void graph(Ui ui, int x, int y, int w, int h) {
        if (graphKind() == 5) {
            distChart.cursor = replayOn ? replayView.time() : -1;
            distChart.onTimeClick = replayOn ? replayView::seek : null;
            if (replayOn) {
                int rh = h < 170 ? h : (int) (h * 0.6);
                replayView.render(ui, x, y, w, rh, distPoints, rangeLine, distName);
                if (rh == h) return;
                y += rh + 4;
                h -= rh + 4;
            }
        }
        Settings s = CombatDataClient.settings();
        String summary;
        int[] colors;
        String[] names;
        if (graphKind() == 1) {
            Stats.JumpStats j = res.jump();
            summary = t("Попыток ", "Attempts ") + j.attempts() + t(" · успех ", " · success ") + Fmt.pct(j.successRatePct()) + " · σ "
                    + (j.attempts() == 0 ? "—" : Fmt.num(j.sigmaMs(), 0) + t(" мс", " ms"));
            colors = new int[]{Theme.GREEN, Theme.RED, Theme.MUTED, Theme.BLUE};
            names = new String[]{t("успех", "success"), t("рано/поздно", "early/late"), t("без прыжка", "no jump"), "fly out"};
        } else if (graphKind() == 5) {
            summary = t("Противник ", "Opponent ") + nz(distName) + t(" · средняя дистанция ", " · mean distance ") + Fmt.num(distMean, 2)
                    + t(" бл.", " bl.") + (opp == null ? t(" · другой: фильтр в Противниках", " · other: filter in Opponents") : "");
            colors = new int[]{Theme.GREEN, Theme.RED, Theme.AMBER};
            names = new String[]{t("ваш удар", "your hit"), t("его удар", "their hit"), "3 block hit"};
        } else if (graphKind() == 4) {
            Stats.TakenStats tk = res.taken();
            summary = t("Ударов по вам ", "Hits on you ") + tk.rated() + t(" · ср. дистанция ", " · avg. distance ") + Fmt.num(tk.reachMean(), 2)
                    + t(" · 3 block hit против вас ", " · 3 block hit against you ") + tk.caught() + " (" + Fmt.pct(tk.caughtPct()) + ")";
            colors = new int[]{Theme.RED, Theme.AMBER, Theme.DIM};
            names = new String[]{t("удар", "hit"), "3 block hit", t("снаряд", "projectile")};
        } else {
            Stats.HitStats hs = res.hits();
            summary = t("Точность ", "Accuracy ") + Fmt.pct(hs.hitRatePct()) + t(" · ср. дистанция ", " · avg. distance ")
                    + (hs.confirmed() == 0 ? "—" : Fmt.num(hs.reachMean(), 2)) + " · 3 block hit " + res.threeBlock().count();
            colors = new int[]{Theme.GREEN, Theme.AMBER, Theme.RED, Theme.DIM};
            names = new String[]{t("попал", "hit"), "3 block hit", t("промах", "miss"), t("без подтв.", "unconfirmed")};
        }
        if (showFiltered) {
            names = Arrays.copyOf(names, names.length + 1);
            colors = Arrays.copyOf(colors, colors.length + 1);
            names[names.length - 1] = t("отфильтр.", "filtered");
            colors[colors.length - 1] = Theme.FILTERED;
        }
        int legendW = 0;
        for (String n : names) legendW += ui.font.width(n) + 16;
        boolean twoLines = w - legendW < Math.min(ui.font.width(summary), 160) + 8;
        if (twoLines) {
            line(ui, w, summary, x, y, Theme.TEXT);
            y += LINE + 1;
            h -= LINE + 1;
        }
        int lx = x + w;
        for (int i = names.length - 1; i >= 0; i--) {
            lx -= ui.font.width(names[i]);
            ui.text(names[i], lx, y, Theme.MUTED);
            lx -= 8;
            Draw.circle(ui.g, lx, y + 1, 5, colors[i]);
            lx -= 8;
        }
        if (!twoLines) line(ui, lx - x - 8, summary, x, y, Theme.TEXT);
        Chart c = chart();
        c.total(rec.durationMs());
        if (graphKind() == 1) {
            c.render(ui, x, y + 12, w, h - 12, jrPoints, new Chart.Axis(-s.jrPreMs, s.jrPostMs, 50,
                    v -> String.valueOf((long) v), s.jrLowerMs, s.jrUpperMs, Theme.alpha(Theme.GREEN, 0x40),
                    new double[]{0}, new int[]{Theme.MUTED}));
        } else if (graphKind() == 4) {
            c.render(ui, x, y + 12, w, h - 12, takenPoints, new Chart.Axis(0, 6, 1, v -> String.valueOf((long) v),
                    0, 0, 0, new double[]{takenRange}, new int[]{Theme.ACCENT}));
        } else if (graphKind() == 5) {
            c.render(ui, x, y + 12, w, h - 12, distPoints, new Chart.Axis(0, 8, 1, v -> String.valueOf((long) v),
                    0, 0, 0, new double[]{rangeLine}, new int[]{Theme.ACCENT}), distLine);
        } else {
            c.render(ui, x, y + 12, w, h - 12, hitPoints, new Chart.Axis(0, 6, 1, v -> String.valueOf((long) v),
                    0, 0, 0, new double[]{rangeLine}, new int[]{Theme.ACCENT}));
        }
    }

    private List<Chart.Point> buildTakenPoints() {
        List<Chart.Point> out = new ArrayList<>();
        takenRange = AttackEvent.DEFAULT_RANGE;
        boolean rangeSet = false;
        for (Event e : rawEvents) {
            if (!(e instanceof IncomingHitEvent h) || !Double.isFinite(h.reach())) continue;
            if (!rangeSet && Double.isFinite(h.range())) {
                takenRange = h.range();
                rangeSet = true;
            }
            StatFilter.Hidden why = sf.incoming(h);
            int color;
            String what;
            if (why != null) {
                if (!showFiltered) continue;
                color = Theme.FILTERED;
                what = t("отфильтровано: ", "filtered: ") + hiddenName(why);
            } else if (!h.rated()) {
                if (!showProjectiles) continue;
                color = Theme.DIM;
                what = t("снаряд", "projectile");
            } else if (h.caught()) {
                color = Theme.AMBER;
                what = t("3 block hit против вас", "3 block hit against you");
            } else {
                color = Theme.RED;
                what = weaponName(h.weaponOr());
            }
            out.add(new Chart.Point(h.t(), h.reach(), color, false, Fmt.clockMs(h.t()) + " · " + nz(h.attackerName()) + " · "
                    + Fmt.num(h.reach(), 2) + t(" бл. · ", " bl. · ") + what, () -> takenDetails(h), h));
        }
        return out;
    }

    private void buildDistance() {
        distOpp = opp != null ? opp : Distance.mainOpponent(events);
        distName = opp != null ? oppName : null;
        distSeries = Distance.series(rawEvents, distOpp);
        distPoints = List.of();
        distLine = null;
        distMean = Double.NaN;
        if (distSeries.isEmpty()) {
            replayView.set(List.of(), null, List.of());
            return;
        }
        long[] ts = new long[distSeries.size()];
        double[] vs = new double[distSeries.size()];
        double sum = 0;
        for (int i = 0; i < ts.length; i++) {
            ts[i] = distSeries.get(i).t();
            vs[i] = distSeries.get(i).d();
            sum += vs[i];
        }
        distMean = sum / ts.length;
        distLine = new Chart.Line(ts, vs, Theme.MUTED, Distance.MAX_GAP_MS);
        int interp = CombatDataClient.settings().interpMs;
        List<Chart.Point> out = new ArrayList<>();
        for (Event e : rawEvents) {
            if (e instanceof AttackEvent a && a.outcome() == Outcome.HIT_CONFIRMED && distOpp.equals(a.target())) {
                if (distName == null) distName = a.targetName();
                double d = Distance.at(distSeries, a.t());
                StatFilter.Hidden why = sf.attack(a);
                if (Double.isNaN(d) || (why != null && !showFiltered)) continue;
                int color = why != null ? Theme.FILTERED : ThreeBlockHit.is(a, interp) ? Theme.AMBER : Theme.GREEN;
                out.add(new Chart.Point(a.t(), d, color, false, Fmt.clockMs(a.t()) + t(" · ваш удар · ", " · your hit · ")
                        + Fmt.num(d, 2) + t(" бл.", " bl.") + (why != null ? t(" · отфильтровано", " · filtered") : ""),
                        () -> hitDetails(a, CombatDataClient.settings()), a));
            } else if (e instanceof IncomingHitEvent h && distOpp.equals(h.attacker())) {
                if (distName == null) distName = h.attackerName();
                double d = Distance.at(distSeries, h.t());
                StatFilter.Hidden why = sf.incoming(h);
                if (Double.isNaN(d) || (why != null && !showFiltered)) continue;
                int color = why != null ? Theme.FILTERED : h.caught() ? Theme.AMBER : Theme.RED;
                out.add(new Chart.Point(h.t(), d, color, false, Fmt.clockMs(h.t()) + t(" · его удар · ", " · their hit · ")
                        + Fmt.num(d, 2) + t(" бл.", " bl.") + (why != null ? t(" · отфильтровано", " · filtered") : ""),
                        () -> takenDetails(h), h));
            }
        }
        distPoints = out;
        replayView.set(Replay.frames(rawEvents, distOpp, timeFrom >= 0 ? timeFrom : 0, timeFrom >= 0 ? timeTo : Long.MAX_VALUE), distOpp, attempts);
    }

    private int distanceTab(Ui ui, int x, int y, int w) {
        if (distOpp != null && rec.skipped(Features.POSITIONS)) return skipped(ui, w, t("Позиции во время боя", "Positions during fights"), x, y);
        String msg = distOpp == null ? t("Ударов нет: не с кем считать дистанцию.", "No hits: no opponent to measure distance to.")
                : t("Нет позиций: они пишутся во время боя в новых записях (Настройки → Основные → Что записывать → «Позиции во время боя»).",
                "No positions: they are recorded during fights in newer recordings (Settings → General → What to record → Positions during fights).");
        line(ui, w, msg, x, y, Theme.DIM);
        return y + LINE;
    }

    private List<String[]> takenDetails(IncomingHitEvent h) {
        List<String[]> rows = new ArrayList<>();
        StatFilter.Hidden why = sf.incoming(h);
        rows.add(new String[]{(h.caught() ? t("3 block hit против вас", "3 block hit against you") : t("Удар по вам", "Hit on you"))
                + (why != null ? t(" · не в статистике: ", " · not counted: ") + hiddenName(why) : ""), ""});
        rows.add(new String[]{t("Время от начала", "Time from start"), Fmt.clockMs(h.t())});
        rows.add(new String[]{t("Кто ударил", "Hit by"), nz(h.attackerName())});
        rows.add(new String[]{t("Удар", "Hit"), incomingKindText(h)});
        rows.add(new String[]{t("Он на вашем экране", "Them on your screen"), Fmt.num(h.reach(), 2) + t(" бл.", " bl.")});
        rows.add(new String[]{t("Ваша дальность", "Your range"), Fmt.num(h.rangeOr(AttackEvent.DEFAULT_RANGE), 2) + t(" бл.", " bl.")});
        rows.add(new String[]{t("Его скорость к вам", "Their speed to you"), Fmt.signed(h.attackerTowardBps(), 1) + t(" бл/с", " bl/s")});
        rows.add(new String[]{t("Ваша скорость от него", "Your speed away"), Fmt.signed(h.selfAwayBps(), 1) + t(" бл/с", " bl/s")});
        rows.add(new String[]{t("Пинг его / ваш", "Ping theirs / yours"), (h.attackerPingMs() >= 0 ? h.attackerPingMs() + t(" мс", " ms") : "—")
                + " / " + (h.pingMs() > 0 ? h.pingMs() + t(" мс", " ms") : "—")});
        rows.add(new String[]{t("Отлёт за 0,5 с", "Knockback in 0.5 s"), kbText(h.kbAway())});
        rows.add(new String[]{t("Сервер", "Server"), serverAt(h.t())});
        return rows;
    }

    private static String resultName(JumpResetEvaluator.Result r) {
        return switch (r) {
            case SUCCESS -> t("Успех", "Success");
            case TOO_EARLY -> t("Рано", "Early");
            case TOO_LATE -> t("Поздно", "Late");
            case NO_ATTEMPT -> t("Без прыжка", "No jump");
            case NOT_APPLICABLE -> t("Не оценивается", "Not rated");
            case FLY_OUT -> "Fly out";
        };
    }

    private List<Chart.Point> buildJumpPoints(Settings s) {
        List<Chart.Point> out = new ArrayList<>();
        for (JumpResetEvaluator.Attempt a : attempts) {
            IncomingHitEvent h = a.hit();
            switch (a.result()) {
                case NOT_APPLICABLE -> { }
                case NO_ATTEMPT -> {
                    if (showNoJump) out.add(new Chart.Point(h.t(), 0, Theme.MUTED, true,
                            Fmt.clockMs(h.t()) + t(" · без прыжка · ", " · no jump · ") + nz(h.attackerName()), () -> jumpDetails(a, s), h));
                }
                case FLY_OUT -> {
                    if (showFlyOut) out.add(new Chart.Point(h.t(), a.deltaMs(), Theme.BLUE, false, Fmt.clockMs(h.t()) + " · "
                            + Fmt.signed(a.deltaMs(), 0) + t(" мс · ", " ms · ") + "fly out · " + kbText(h.kbAway()),
                            () -> jumpDetails(a, s), h));
                }
                default -> {
                    boolean ok = a.result() == JumpResetEvaluator.Result.SUCCESS;
                    if (!ok && !showFails) continue;
                    int color = a.held() && s.jrExcludeHeld ? Theme.DIM : ok ? Theme.GREEN : Theme.RED;
                    out.add(new Chart.Point(h.t(), a.deltaMs(), color, false, Fmt.clockMs(h.t()) + " · "
                            + Fmt.signed(a.deltaMs(), 0) + t(" мс · ", " ms · ") + resultName(a.result()), () -> jumpDetails(a, s), h));
                }
            }
        }
        if (showFiltered) {
            for (JumpResetEvaluator.Attempt a : JumpResetEvaluator.evaluate(rawEvents, s.jumpParams())) {
                IncomingHitEvent h = a.hit();
                StatFilter.Hidden why = sf.incoming(h);
                if (why == null || a.result() == JumpResetEvaluator.Result.NOT_APPLICABLE) continue;
                out.add(new Chart.Point(h.t(), a.hasJump() ? a.deltaMs() : 0, Theme.FILTERED, !a.hasJump(), Fmt.clockMs(h.t())
                        + t(" · отфильтровано: ", " · filtered: ") + hiddenName(why) + " · " + nz(h.attackerName()),
                        () -> jumpDetails(a, s), h));
            }
        }
        return out;
    }

    private List<String[]> jumpDetails(JumpResetEvaluator.Attempt a, Settings s) {
        IncomingHitEvent h = a.hit();
        long d = a.deltaMs();
        String rel = !a.hasJump() ? "—" : d < s.jrLowerMs ? t("раньше на ", "early by ") + (s.jrLowerMs - d) + t(" мс", " ms")
                : d > s.jrUpperMs ? t("позже на ", "late by ") + (d - s.jrUpperMs) + t(" мс", " ms") : t("в окне ", "in window ") + s.jrLowerMs + "…" + s.jrUpperMs + t(" мс", " ms");
        List<String[]> rows = new ArrayList<>();
        StatFilter.Hidden why = sf.incoming(h);
        rows.add(new String[]{why != null ? t("Не в статистике: ", "Not counted: ") + hiddenName(why)
                : a.result() == JumpResetEvaluator.Result.FLY_OUT ? t("Fly out (не jump reset)", "Fly out (not a jump reset)")
                : "Jump reset: " + resultName(a.result()), ""});
        rows.add(new String[]{t("Время от начала", "Time from start"), Fmt.clockMs(h.t())});
        rows.add(new String[]{t("Удар", "Hit"), incomingKindText(h)});
        if (Double.isFinite(h.reach())) rows.add(new String[]{t("Он на вашем экране", "Them on your screen"), Fmt.num(h.reach(), 2) + t(" бл.", " bl.")
                + (h.caught() ? t(" · 3 block hit против вас", " · 3 block hit against you") : "")});
        rows.add(new String[]{t("Прыжок", "Jump"), a.hasJump() ? Fmt.signed(d, 0) + t(" мс после удара", " ms after the hit") : t("не было", "none")});
        rows.add(new String[]{t("Относительно окна", "Relative to window"), rel});
        JumpEvent je = a.hasJump() ? jumpAt(a.jumpT()) : null;
        if (je != null) rows.add(new String[]{t("Клавиши при прыжке", "Keys at jump"), keysText(je)});
        rows.add(new String[]{t("Кто ударил", "Hit by"), nz(h.attackerName())});
        rows.add(new String[]{t("В момент удара", "At the hit"), h.groundedAtHit() ? t("на земле", "on ground") : t("в воздухе", "in the air")});
        rows.add(new String[]{t("Прыжок зажат", "Jump held"), h.jumpHeld() ? (s.jrExcludeHeld ? t("да, исключён", "yes, excluded") : t("да", "yes")) : t("нет", "no")});
        rows.add(new String[]{t("Нокбэк", "Knockback"), h.knockbackApplied() ? t("да", "yes") : t("нет", "no")});
        rows.add(new String[]{t("Отлёт за 0,5 с", "Knockback in 0.5 s"), kbText(h.kbAway())});
        rows.add(new String[]{t("Пинг", "Ping"), h.pingMs() > 0 ? h.pingMs() + t(" мс", " ms") : "—"});
        rows.add(new String[]{t("T удара / прыжка", "T of hit / jump"), Fmt.clockMs(h.t()) + " / " + (a.hasJump() ? Fmt.clockMs(a.jumpT()) : "—")});
        rows.add(new String[]{t("Сервер", "Server"), serverAt(h.t())});
        return rows;
    }

    private JumpEvent jumpAt(long t) {
        for (Event e : events) if (e instanceof JumpEvent j && j.t() == t) return j;
        return null;
    }

    static String keysText(JumpEvent j) {
        if (!j.keysKnown()) return t("неизвестно (старая запись)", "unknown (old recording)");
        List<String> k = new ArrayList<>();
        if (j.has(JumpEvent.KEY_FORWARD)) k.add("W");
        if (j.has(JumpEvent.KEY_LEFT)) k.add("A");
        if (j.has(JumpEvent.KEY_BACK)) k.add("S");
        if (j.has(JumpEvent.KEY_RIGHT)) k.add("D");
        String s = k.isEmpty() ? t("нет", "none") : String.join("+", k);
        return j.has(JumpEvent.KEY_SPRINT) ? s + t(", спринт", ", sprint") : s;
    }

    private List<Chart.Point> buildHitPoints(Settings s) {
        List<Chart.Point> out = new ArrayList<>();
        rangeLine = AttackEvent.DEFAULT_RANGE;
        boolean rangeSet = false;
        for (Event e : rawEvents) {
            if (!(e instanceof AttackEvent a)) continue;
            if (!rangeSet && Double.isFinite(a.range())) {
                rangeLine = a.range();
                rangeSet = true;
            }
            StatFilter.Hidden why = sf.attack(a);
            if (why != null) {
                if (showFiltered) out.add(new Chart.Point(a.t(), a.reach(), Theme.FILTERED, false, Fmt.clockMs(a.t()) + " · "
                        + nz(a.targetName()) + " · " + Fmt.num(a.reach(), 2) + t(" бл. · отфильтровано: ", " bl. · filtered: ")
                        + hiddenName(why), () -> hitDetails(a, s), a));
                continue;
            }
            int color;
            switch (a.outcome()) {
                case HIT_CONFIRMED -> color = ThreeBlockHit.is(a, s.interpMs) ? Theme.AMBER : Theme.GREEN;
                case HIT_UNCONFIRMED -> {
                    if (!showUnconfirmed) continue;
                    color = Theme.DIM;
                }
                default -> {
                    if (!showMisses) continue;
                    color = Theme.RED;
                }
            }
            out.add(new Chart.Point(a.t(), a.reach(), color, false, Fmt.clockMs(a.t()) + " · " + nz(a.targetName()) + " · "
                    + Fmt.num(a.reach(), 2) + t(" бл. · ", " bl. · ") + outcomeName(a, s.interpMs), () -> hitDetails(a, s), a));
        }
        return out;
    }

    private Cooldown.Summary cdSummary() {
        return cdSum;
    }

    private static String outcomeName(AttackEvent a, int interp) {
        return switch (a.outcome()) {
            case HIT_CONFIRMED -> ThreeBlockHit.is(a, interp) ? "3 block hit" : t("Попал", "Hit");
            case HIT_UNCONFIRMED -> t("Без подтверждения", "Unconfirmed");
            case MISS -> a.outOfRangeMiss(a.rangeOr(AttackEvent.DEFAULT_RANGE)) ? t("Не дотянулся", "Out of range") : t("Мимо", "Missed");
        };
    }

    private List<String[]> hitDetails(AttackEvent a, Settings s) {
        List<String[]> rows = new ArrayList<>();
        StatFilter.Hidden why = sf.attack(a);
        rows.add(new String[]{outcomeName(a, s.interpMs) + (why != null ? t(" · не в статистике: ", " · not counted: ") + hiddenName(why) : ""), ""});
        rows.add(new String[]{t("Время от начала", "Time from start"), Fmt.clockMs(a.t())});
        rows.add(new String[]{t("Цель", "Target"), nz(a.targetName())});
        rows.add(new String[]{t("Оружие", "Weapon"), weaponText(a.weaponOr(), a.item())});
        rows.add(new String[]{a.outcome() == Outcome.MISS ? t("До ближайшей точки", "To the nearest point") : t("Дистанция", "Distance"), Fmt.num(a.reach(), 2) + t(" бл.", " bl.")});
        if (a.outcome() == Outcome.MISS) {
            rows.add(new String[]{t("Луч до хитбокса", "Ray to hitbox"), Double.isFinite(a.rayDist()) ? Fmt.num(a.rayDist(), 2) + t(" бл.", " bl.") : t("мимо", "missed")});
        }
        rows.add(new String[]{t("Дальность атаки", "Attack range"), Fmt.num(a.rangeOr(AttackEvent.DEFAULT_RANGE), 2) + t(" бл.", " bl.")});
        rows.add(new String[]{t("Тип удара", "Hit type"), typeName(a.type())});
        rows.add(new String[]{t("Заряд", "Charge"), Fmt.pct(a.strength() * 100)});
        rows.add(new String[]{t("Прицел yaw / pitch", "Aim yaw / pitch"), Fmt.signed(a.aimYawDeg(), 1) + "° / " + Fmt.signed(a.aimPitchDeg(), 1) + "°"});
        rows.add(new String[]{t("Высота попадания", "Hit height"), Double.isFinite(a.hitV()) ? Fmt.pct(a.hitV() * 100) + t(" роста", " of height") : "—"});
        if (a.outcome() == Outcome.HIT_CONFIRMED) {
            double od = ThreeBlockHit.opponentDistance(a, s.interpMs);
            rows.add(new String[]{t("У противника на экране", "On opponent's screen"), Double.isNaN(od) ? t("нет данных", "no data") : "≈ " + Fmt.num(od, 1) + t(" бл.", " bl.")});
        }
        rows.add(new String[]{t("Пинг ваш / цели", "Ping yours / target"), (a.pingMs() > 0 ? a.pingMs() + t(" мс", " ms") : "—") + " / "
                + (a.targetPingMs() >= 0 ? a.targetPingMs() + t(" мс", " ms") : "—")});
        rows.add(new String[]{t("Сервер", "Server"), serverAt(a.t())});
        return rows;
    }

    private String serverAt(long t) {
        String s = null;
        for (Event e : rec.events()) {
            if (e.t() > t) break;
            if (e instanceof SegmentEvent seg) s = seg.server();
        }
        return s == null ? "—" : s;
    }

    private boolean visible(int y, int h) {
        return y + h >= py && y <= py + ph;
    }


    private int overview(Ui ui, int x, int y, int w) {
        if (note != null) {
            line(ui, w, note, x, y, Theme.MUTED);
            y += LINE + 4;
        }
        y = hiddenLine(ui, x, y, w);
        y = compareLine(ui, x, y, w);
        y = insightsBlock(ui, x, y, w);
        List<Card> cards = new ArrayList<>();
        Stats.HitStats hs = res.hits();
        int attacksTotal = hs.confirmed() + hs.unconfirmed() + hs.misses();
        if (attacksTotal == 0 && res.combo().taken() == 0) {
            cards.add(new Card(t("Бой", "Fight"), List.of(new Kv(t("Боевых событий нет", "No combat events"), "")), 1));
        } else {
            cards.add(hitsCard(hs).with(Help.hits()));
            cards.add(jumpCard(res.jump()).with(Help.jump()));
            if (res.taken().rated() > 0) cards.add(takenCard(res.taken(), res.threeBlock()).with(Help.taken()));
            cards.add(reachCard(hs).with(Help.reach()));
            cards.add(aimCard(hs).with(Help.aim()));
            cards.add(threeBlockCard(hs).with(Help.threeBlock()));
            cards.add(comboCard(res.combo()).with(Help.combo()));
            cards.add(tradesCard(res.trades()).with(Help.trades()));
            cards.add(chargeCard(hs).with(Help.charge()));
            cards.add(typesCard(hs).with(Help.types()));
            if (wtap.hasInput()) cards.add(wtapCard(wtap).with(Help.wtap()));
            cards.add(new Card(t("Пинг", "Ping"), List.of(
                    new Kv(t("Средний", "Mean"), res.pingMean() > 0 ? Fmt.num(res.pingMean(), 0) + t(" мс", " ms") : "—"),
                    new Kv(t("Разброс σ", "Spread σ"), res.pingMean() > 0 ? Fmt.num(res.pingSigma(), 0) + t(" мс", " ms") : "—")), 2).with(Help.ping()));
        }
        cards.add(recordCard());
        int cols = w >= 330 ? 2 : 1, gap = 8, cw = (w - gap * (cols - 1)) / cols;
        int[] colY = new int[cols];
        Arrays.fill(colY, y);
        for (Card c : cards) {
            int col = 0;
            for (int i = 1; i < cols; i++) if (colY[i] < colY[col]) col = i;
            int cx = x + col * (cw + gap);
            boolean open = expanded.contains(c.title());
            if (visible(colY[col], c.height(open))) card(ui, cx, colY[col], cw, c, open);
            colY[col] += c.height(open) + gap;
        }
        int bottom = y;
        for (int v : colY) bottom = Math.max(bottom, v);
        return bottom;
    }

    private int hiddenLine(Ui ui, int x, int y, int w) {
        String text, bl;
        Runnable action;
        if (filtersOff) {
            text = t("Фильтры статистики выключены для этой записи", "Stat filters are off for this recording");
            bl = t("Вернуть", "Restore");
            action = () -> {
                filtersOff = false;
                weaponOverride = null;
            };
        } else {
            int n = res.hiddenTotal();
            if (n == 0) return y;
            List<String> parts = new ArrayList<>();
            for (Map.Entry<StatFilter.Hidden, Integer> e : res.hidden().entrySet()) parts.add(hiddenName(e.getKey()) + " " + e.getValue());
            text = t("Скрыто фильтрами: ", "Hidden by filters: ") + n + " " + (Lang.english() ? (n == 1 ? "hit" : "hits")
                    : Lang.plural(n, "удар", "удара", "ударов")) + " (" + String.join(", ", parts) + ")";
            bl = t("Показать", "Show");
            action = () -> {
                filtersOff = true;
                weaponOverride = StatFilter.WeaponView.ALL;
            };
        }
        int bw = ui.buttonWidth(bl);
        line(ui, w - bw - Ui.INFO - 12, text, x, Ui.textY(y, 14), Theme.DIM);
        ui.info(x + w - bw - Ui.INFO - 5, y + 2, Help.hidden());
        ui.button(x + w - bw, y, bw, 14, bl, Ui.Kind.GHOST, true, action);
        return y + 18;
    }

    private static final int COMPARE_WITH = 5;
    private String compareKey;
    private List<Progress.Delta> deltas = List.of();
    private int compareN;

    private int compareLine(Ui ui, int x, int y, int w) {
        Settings s = CombatDataClient.settings();
        ProgressData.request(s, false);
        ProgressData.Snapshot snap = ProgressData.snapshot();
        String want = Progress.cacheKey(s.statFilter(), s.jumpParams(), s.interpMs, s.tradeGapMs);
        // Not keyed on the recording identity: the live buffer is a new object every second, but recompute is only needed on new events.
        long lastT = rec.events().isEmpty() ? 0 : rec.events().get(rec.events().size() - 1).t();
        String k = ProgressData.version() + "/" + want + "/" + rec.wallStartMs() / 60_000 + "/" + rec.events().size() + "/" + lastT;
        if (!k.equals(compareKey)) {
            compareKey = k;
            deltas = List.of();
            compareN = 0;
            if (snap != null && snap.key().equals(want)) {
                List<Progress.Summary> prev = Progress.previous(snap.summaries(), rec.wallStartMs(), COMPARE_WITH);
                compareN = prev.size();
                if (!prev.isEmpty()) deltas = Progress.compare(
                        Progress.summarize(null, rec, s.jumpParams(), s.interpMs, s.statFilter(), s.tradeGapMs, false).all(), prev);
            }
        }
        if (deltas.isEmpty()) return y;
        List<String> parts = new ArrayList<>();
        for (Progress.Delta d : deltas) {
            if (parts.size() == 3) break;
            String v = d.metric().percent ? Fmt.signed(d.diff(), 0) + "%" : Fmt.signed(d.diff(), 2) + t(" бл.", " bl.");
            parts.add(ProgressTab.metricName(d.metric(), false).toLowerCase(Locale.ROOT) + " " + v);
        }
        String head = Lang.english() ? "Compared with the last " + compareN + (compareN == 1 ? " recording: " : " recordings: ")
                : "По сравнению с " + (compareN == 1 ? "прошлой записью: " : "прошлыми " + compareN + " записями: ");
        line(ui, w, head + String.join(", ", parts), x, y, Theme.MUTED);
        return y + LINE + 4;
    }

    private int insightsBlock(Ui ui, int x, int y, int w) {
        if (insights.isEmpty()) {
            if (res.hits().confirmed() + res.hits().misses() + res.combo().taken() == 0) return y;
            line(ui, w, t("Выводы: явных проблем не найдено", "Insights: no obvious problems found"), x, y, Theme.DIM);
            return y + LINE + 6;
        }
        int rh = 16;
        for (Insights.Insight in : insights) {
            int color = switch (in.tone()) {
                case BAD -> Theme.RED;
                case TIP -> Theme.AMBER;
                case GOOD -> Theme.GREEN;
            };
            boolean hov = ui.hover(x, y, w, rh - 2);
            Draw.round(ui.g, x, y, w, rh - 2, Theme.R_CTRL, hov ? Theme.ROW_HOVER : Theme.WINDOW);
            ui.g.fill(x, y + 2, x + 3, y + rh - 4, color);
            ui.text(Draw.ellipsize(ui.font, in.text(), w - 22), x + 8, Ui.textY(y, rh - 2), hov ? Theme.TEXT_STRONG : Theme.TEXT);
            ui.textRight("›", x + w - 5, Ui.textY(y, rh - 2), hov ? Theme.TEXT_STRONG : Theme.MUTED);
            Insights.Page page = in.page();
            ui.click(x, y, w, rh - 2, () -> go(page));
            y += rh;
        }
        return y + 4;
    }

    private void card(Ui ui, int x, int y, int w, Card c, boolean open) {
        Draw.round(ui.g, x, y, w, c.height(open), Theme.R_CTRL, Theme.WINDOW);
        boolean more = c.rows().size() > c.main();
        int titleW = w - 12;
        int right = x + w - 6;
        if (c.info() != null) {
            ui.info(right - Ui.INFO, y + 3, c.info());
            right -= Ui.INFO + 5;
            titleW -= Ui.INFO + 5;
        }
        if (more) {
            String full = open ? t("Свернуть ▴", "Less ▴") : t("Подробнее ▾", "More ▾"), arrow = open ? "▴" : "▾";
            String label = ui.font.width(c.title()) + ui.font.width(full) + 20 + (c.info() != null ? Ui.INFO + 5 : 0) <= w ? full : arrow;
            boolean hov = ui.hover(x, y, right - x, 14);
            ui.textRight(label, right, y + 4, hov ? Theme.TEXT_STRONG : Theme.DIM);
            titleW -= ui.font.width(label) + 6;
            String key = c.title();
            ui.click(x, y, right - x, 14, () -> {
                if (!expanded.remove(key)) expanded.add(key);
            });
        }
        ui.text(Draw.ellipsize(ui.font, c.title(), titleW), x + 6, y + 4, Theme.TEXT_STRONG);
        int ry = y + 16;
        for (int i = 0; i < c.shown(open); i++) {
            Kv kv = c.rows().get(i);
            int vw = ui.font.width(kv.value());
            ui.text(Draw.ellipsize(ui.font, kv.label(), w - 16 - vw), x + 6, ry, Theme.MUTED);
            ui.textRight(Draw.ellipsize(ui.font, kv.value(), w - 12), x + w - 6, ry, kv.color());
            ry += LINE;
        }
    }

    private Card recordCard() {
        int attacks = 0, incoming = 0, jumps = 0, markers = 0;
        Set<String> servers = new LinkedHashSet<>();
        for (Event e : rec.events()) {
            if (e instanceof AttackEvent) attacks++;
            else if (e instanceof IncomingHitEvent) incoming++;
            else if (e instanceof JumpEvent) jumps++;
            else if (e instanceof MarkerEvent) markers++;
            else if (e instanceof SegmentEvent s && s.server() != null) servers.add(s.server());
        }
        List<Kv> rows = new ArrayList<>();
        rows.add(new Kv(t("Длительность", "Duration"), Fmt.clock(rec.durationMs())));
        rows.add(new Kv(t("Атак / получено / прыжков", "Attacks / taken / jumps"), attacks + " / " + incoming + " / " + jumps));
        rows.add(new Kv(t("Игрок", "Player"), rec.player() == null ? "?" : rec.player()));
        if (markers > 0) rows.add(new Kv(t("Меток", "Markers"), String.valueOf(markers)));
        rows.add(new Kv(t("Серверы", "Servers"), servers.isEmpty() ? "—" : String.join(", ", servers)));
        return new Card(t("Запись", "Recording"), rows, 2);
    }

    private static Card hitsCard(Stats.HitStats h) {
        return new Card(t("Попадания", "Hits"), List.of(
                new Kv(t("Точность", "Accuracy"), Fmt.pct(h.hitRatePct()), Theme.TEXT_STRONG),
                new Kv(t("Подтверждено уроном", "Confirmed by damage"), String.valueOf(h.confirmed()), Theme.GREEN),
                new Kv(t("Промахов", "Misses"), String.valueOf(h.misses()), h.misses() > 0 ? Theme.RED : Theme.TEXT),
                new Kv(t("  из них не дотянулся", "  of them out of range"), String.valueOf(h.missesOutOfRange())),
                new Kv(t("Без подтверждения", "Unconfirmed"), String.valueOf(h.unconfirmed()))), 3);
    }

    private static Card reachCard(Stats.HitStats h) {
        boolean none = h.confirmed() == 0;
        return new Card(t("Дистанция ударов, блоков", "Hit distance, blocks"), List.of(
                new Kv(t("Средняя", "Mean"), none ? "—" : Fmt.num(h.reachMean(), 2), Theme.TEXT_STRONG),
                new Kv(t("Дальше дальности", "Past range"), none ? "—" : Fmt.pct(h.sharePastRangePct())),
                new Kv(t("Медиана", "Median"), none ? "—" : Fmt.num(h.reachMedian(), 2)),
                new Kv(t("Максимум", "Max"), none ? "—" : Fmt.num(h.reachMax(), 2)),
                new Kv(t("Разброс σ", "Spread σ"), none ? "—" : Fmt.num(h.reachSigma(), 2))), 2);
    }

    private static Card aimCard(Stats.HitStats h) {
        boolean none = h.aimSamples() == 0;
        double bias = h.aimYawBias();
        String side = none || Math.abs(bias) < 0.05 ? "" : bias > 0 ? t(" (прицел левее)", " (aim to the left)") : t(" (прицел правее)", " (aim to the right)");
        return new Card(t("Прицел", "Aim"), List.of(
                new Kv(t("По горизонтали (yaw)", "Horizontal (yaw)"), none ? "—" : Fmt.num(h.aimYawAbsMean(), 1) + "°", Theme.TEXT_STRONG),
                new Kv(t("Средний сдвиг yaw", "Mean yaw offset"), none ? "—" : Fmt.signed(bias, 1) + "°" + side),
                new Kv(t("По вертикали (pitch)", "Vertical (pitch)"), none ? "—" : Fmt.num(h.aimPitchAbsMean(), 1) + "°"),
                new Kv(t("Высота попадания", "Hit height"), Double.isNaN(h.hitVMean()) ? "—" : Fmt.pct(h.hitVMean() * 100) + t(" роста", " of height")),
                new Kv(t("В расчёте (от 1 блока)", "Counted (from 1 block)"), String.valueOf(h.aimSamples()))), 2);
    }

    private Card threeBlockCard(Stats.HitStats h) {
        Stats.ThreeBlockStats t = res.threeBlock();
        if (t.classified() == 0) {
            return new Card("3 block hit", List.of(new Kv(t("Нет данных", "No data"), t("старая запись или пинг скрыт", "old recording or ping hidden"))), 1);
        }
        return new Card("3 block hit", List.of(
                new Kv(t("Количество", "Count"), t.count() + " (" + Fmt.pct(t.sharePct()) + ")", Theme.AMBER),
                new Kv(t("У противника в среднем", "On opponent's screen, mean"), "≈ " + Fmt.num(t.oppDistMean(), 2) + t(" бл.", " bl.")),
                new Kv(t("Оценено ударов", "Hits rated"), String.valueOf(t.classified())),
                new Kv(t("Без данных", "No data"), String.valueOf(Math.max(0, h.confirmed() - t.classified()))),
                new Kv(t("Интерполяция", "Interpolation"), CombatDataClient.settings().interpMs + t(" мс", " ms"))), 2);
    }

    private static Card jumpCard(Stats.JumpStats j) {
        boolean none = j.attempts() == 0, noSucc = j.success() == 0;
        List<Kv> rows = new ArrayList<>(List.of(
                new Kv(t("Успешных", "Successful"), j.success() + (none ? "" : " (" + Fmt.pct(j.successRatePct()) + ")"), Theme.GREEN),
                new Kv(t("Попыток", "Attempts"), String.valueOf(j.attempts())),
                new Kv(t("Рано / поздно", "Early / late"), j.early() + " / " + j.late(), j.early() + j.late() > 0 ? Theme.RED : Theme.TEXT),
                new Kv(t("Без прыжка", "No jump"), String.valueOf(j.noAttempt())),
                new Kv(t("Среднее успешных", "Mean of successful"), noSucc ? "—" : Fmt.num(j.meanSuccessMs(), 0) + t(" мс", " ms")),
                new Kv(t("Медиана успешных", "Median of successful"), noSucc ? "—" : Fmt.num(j.medianSuccessMs(), 0) + t(" мс", " ms")),
                new Kv(t("Разброс σ", "Spread σ"), none ? "—" : Fmt.num(j.sigmaMs(), 0) + t(" мс", " ms")),
                new Kv(t("Системный сдвиг", "Systematic offset"), none ? "—" : Fmt.signed(j.biasMs(), 0) + t(" мс", " ms"))));
        if (j.held() > 0) rows.add(new Kv(t("Зажатый прыжок (исключено)", "Held jump (excluded)"), String.valueOf(j.held())));
        if (j.flyOut() > 0) {
            rows.add(new Kv("Fly out", String.valueOf(j.flyOut()), Theme.BLUE));
            rows.add(new Kv(t("  отлёт: fly out / без прыжка", "  knockback: fly out / no jump"),
                    kbText(j.flyOutKbMean()) + " / " + kbText(j.noJumpKbMean())));
        }
        return new Card("Jump reset", rows, 3);
    }

    private static Card comboCard(Stats.ComboStats c) {
        return new Card(t("Комбо и размен", "Combos and trades"), List.of(
                new Kv(t("Нанесено / получено", "Dealt / taken"), c.dealt() + " / " + c.taken(), Theme.TEXT_STRONG),
                new Kv(t("Размен", "Trade"), Double.isNaN(c.trade()) ? (c.dealt() > 0 ? "∞" : "—") : Fmt.num(c.trade(), 2)),
                new Kv(t("Макс. комбо", "Max combo"), String.valueOf(c.maxCombo())),
                new Kv(t("Нанесено ударов", "Hits dealt"), String.valueOf(c.dealt()), Theme.GREEN),
                new Kv(t("Получено ударов", "Hits taken"), String.valueOf(c.taken()), Theme.RED),
                new Kv(t("Среднее комбо (от 2)", "Mean combo (from 2)"), c.avgCombo() > 0 ? Fmt.num(c.avgCombo(), 1) : "—")), 3);
    }

    private Card chargeCard(Stats.HitStats hs) {
        return new Card(t("Заряд атаки", "Attack charge"), List.of(
                new Kv(t("Средний заряд", "Mean charge"), hs.confirmed() == 0 ? "—" : Fmt.pct(hs.strengthMean() * 100), Theme.TEXT_STRONG),
                new Kv(t("Без заряда (по интервалам)", "Uncharged (by intervals)"), cdSummary().n() == 0 ? "—"
                        : cdSummary().rushed() + " (" + Fmt.pct(cdSummary().rushedPct()) + ")"),
                new Kv(t("Полный (выше 0.9)", "Full (above 0.9)"), hs.confirmed() == 0 ? "—" : Fmt.pct(hs.fullChargePct())),
                new Kv(t("Лишнее ожидание после порога", "Extra wait after threshold"), Double.isNaN(cdSummary().waitMs()) ? "—"
                        : Fmt.num(cdSummary().waitMs(), 0) + t(" мс", " ms"))), 2);
    }

    private static final HitType[] TYPE_ORDER = {HitType.SPRINT, HitType.CRIT, HitType.WEAK, HitType.NORMAL, HitType.SWEEP};

    private static Card typesCard(Stats.HitStats h) {
        List<Kv> rows = new ArrayList<>();
        for (HitType t : TYPE_ORDER) {
            int n = h.types().getOrDefault(t, 0);
            rows.add(new Kv(typeName(t), n + (h.confirmed() == 0 ? "" : " (" + Fmt.pct(100.0 * n / h.confirmed()) + ")")));
        }
        return new Card(t("Типы ударов", "Hit types"), rows, 3);
    }

    static String typeName(HitType t) {
        return switch (t) {
            case WEAK -> t("Слабый", "Weak");
            case NORMAL -> t("Обычный", "Normal");
            case SWEEP -> t("Разящий", "Sweep");
            case CRIT -> t("Крит", "Crit");
            case SPRINT -> t("С разбега", "Sprint");
        };
    }


    private int kbTab(Ui ui, int x, int y, int w) {
        Settings s = CombatDataClient.settings();
        Calibration.Result c = calib;
        line(ui, w, t("Отлёт: насколько вас отбросило за 0,5 с после удара (по линии от атакующего). Меньше лучше.", "Knockback: how far you were pushed in 0.5 s after the hit (along the line from the attacker). Less is better."),
                x, y, Theme.MUTED);
        y += LINE + 4;
        if (c.samples() == 0 && rec.skipped(Features.KNOCKBACK)) return skipped(ui, w, t("Отлёт после удара", "Knockback after a hit"), x, y);
        if (c.samples() == 0) {
            line(ui, w, t("Нет данных об отлёте: нужны полученные удары в записи новой версии мода.", "No knockback data: needs hits taken in a recording from a newer mod version."), x, y, Theme.DIM);
            return y + LINE;
        }
        line(ui, w, t("Без прыжка: ", "No jump: ") + kbText(c.baselineKb()) + " (" + c.baselineN() + t(") · в окне ", ") · in window ") + s.jrLowerMs + "…"
                + s.jrUpperMs + t(" мс: ", " ms: ") + kbText(c.curKb()) + " (" + c.curN() + t(") · попыток с прыжком ", ") · jump attempts ") + c.samples(),
                x, y, Theme.TEXT);
        if (res.jump().flyOut() > 0) {
            y += LINE;
            line(ui, w, "Fly out: " + kbText(res.jump().flyOutKbMean()) + " (" + res.jump().flyOut() + ")"
                    + t(" · не входит в калибровку", " · not used for calibration"), x, y, Theme.BLUE);
        }
        y += LINE + 6;
        double max = Double.isFinite(c.baselineKb()) ? c.baselineKb() : 0;
        for (Calibration.Bin b : c.bins()) if (b.n() > 0) max = Math.max(max, b.meanKb());
        max = Math.max(0.5, max * 1.1);
        int n = c.bins().size(), bw = Math.max(2, w / n), h = 70, by = y + 12;
        long lo = c.bins().get(0).fromMs(), span = c.bins().get(n - 1).toMs() - lo;
        if (c.hasRecommendation()) {
            int rx1 = x + (int) ((c.recLower() - lo) * (bw * n) / span), rx2 = x + (int) ((c.recUpper() - lo) * (bw * n) / span);
            ui.g.fill(rx1, by, Math.max(rx1 + 1, rx2), by + h, Theme.alpha(Theme.AMBER, 0x30));
        }
        int hover = -1;
        for (int i = 0; i < n; i++) {
            Calibration.Bin b = c.bins().get(i);
            int bx = x + i * bw;
            if (ui.hover(bx, by, bw, h)) {
                hover = i;
                Draw.round(ui.g, bx, by, bw, h, 2, Theme.ROW_HOVER);
            }
            if (b.n() == 0) continue;
            int bh = Math.max(1, (int) (h * b.meanKb() / max));
            boolean inWindow = b.fromMs() >= s.jrLowerMs && b.toMs() - 1 <= s.jrUpperMs;
            int color = hover == i ? Theme.TEXT_STRONG : b.n() < 3 ? Theme.DIM : inWindow ? Theme.GREEN : Theme.MUTED;
            ui.g.fill(bx + 1, by + h - bh, bx + bw - 1, by + h, color);
        }
        ui.g.fill(x, by + h, x + bw * n, by + h + 1, Theme.BORDER);
        if (Double.isFinite(c.baselineKb())) {
            int ly = by + h - (int) (h * c.baselineKb() / max);
            Draw.dashH(ui.g, x, x + bw * n, ly, 3, 2, Theme.RED);
        }
        int zx = x + (int) ((0 - lo) * (bw * n) / span);
        ui.g.fill(zx, by, zx + 1, by + h, Theme.BORDER);
        String title = t("Средний отлёт по дельте прыжка (корзины по ", "Mean knockback by jump delta (bins of ") + Calibration.BIN_MS + t(" мс), пунктир: без прыжка", " ms), dashed: no jump");
        if (hover >= 0) {
            Calibration.Bin b = c.bins().get(hover);
            title = b.fromMs() + "…" + b.toMs() + t(" мс: ", " ms: ") + (b.n() == 0 ? t("нет попыток", "no attempts") : kbText(b.meanKb()) + " (" + b.n() + ")");
        }
        line(ui, w, title, x, y, hover >= 0 ? Theme.TEXT_STRONG : Theme.MUTED);
        ui.text(lo + t(" мс", " ms"), x, by + h + 3, Theme.DIM);
        ui.textCenter("0", zx, by + h + 3, Theme.DIM);
        ui.textRight("+" + (lo + span) + t(" мс", " ms"), x + bw * n, by + h + 3, Theme.DIM);
        y = by + h + 16;
        if (c.hasRecommendation()) {
            String rec = t("Рекомендуемое окно: ", "Recommended window: ") + c.recLower() + "…" + c.recUpper() + t(" мс, отлёт ", " ms, knockback ") + kbText(c.recKb())
                    + " (" + c.recN() + t(" попыток)", " attempts)");
            boolean same = c.recLower() == s.jrLowerMs && c.recUpper() == s.jrUpperMs;
            String bl = t("Применить это окно", "Apply this window");
            int bwid = ui.buttonWidth(bl);
            line(ui, same ? w : w - bwid - 8, rec, x, Ui.textY(y, 16), Theme.AMBER);
            if (!same) ui.button(x + w - bwid, y, bwid, 16, bl, Ui.Kind.PRIMARY, true,
                    () -> menu.settingsTab.adoptJumpWindow((int) c.recLower(), (int) c.recUpper()));
            else ui.textRight(t("уже применено", "already applied"), x + w, Ui.textY(y, 16), Theme.GREEN);
        } else {
            line(ui, w, t("Для рекомендации нужно не меньше ", "A recommendation needs at least ") + Calibration.MIN_SAMPLES + t(" попыток с прыжком, сейчас ", " jump attempts, now ")
                    + c.samples(), x, Ui.textY(y, 16), Theme.DIM);
        }
        y += 20;
        line(ui, w, t("Ширина окна берётся из настроек, ищется положение. По всем записям: Настройки → Дополнительно.", "Window width comes from settings, only its position is fitted. For all recordings: Settings → Advanced."),
                x, y, Theme.DIM);
        return y + LINE;
    }

    private static String kbText(double v) {
        return Double.isFinite(v) ? Fmt.num(v, 2) + t(" бл.", " bl.") : "—";
    }


    /** Head/leg boundaries: the player model is 32 units tall, the head is the top 8, legs the bottom 12. */
    private static final double HEAD_V = 24.0 / 32, LEGS_V = 12.0 / 32;

    private int aimTab(Ui ui, int x, int y, int w) {
        int interp = CombatDataClient.settings().interpMs;
        List<AttackEvent> hits = new ArrayList<>();
        for (Event e : events) {
            if (e instanceof AttackEvent a && a.outcome() == Outcome.HIT_CONFIRMED && a.reach() >= Stats.MIN_AIM_REACH
                    && Double.isFinite(a.hitU()) && Double.isFinite(a.hitV())) hits.add(a);
        }
        if (hits.isEmpty()) {
            ui.text(t("Нет попаданий для расчёта (удары ближе 1 блока не учитываются)", "No hits to analyze (hits closer than 1 block are not counted)"), x, y, Theme.DIM);
            return y + LINE;
        }
        int sh = 150, cardW = 96, cardH = sh + 26;
        Draw.round(ui.g, x, y, cardW, cardH, Theme.R_CTRL, Theme.WINDOW);
        ui.textCenter(t("Вид спереди", "Front view"), x + cardW / 2, y + 4, Theme.MUTED);
        double unit = sh / 32.0, hw = sh * 0.3 / 1.8;
        int ax = x + cardW / 2, top = y + 18;
        var g = ui.g;
        int body = Theme.RAISED, limbs = Theme.faded(Theme.RAISED_HOVER, 0.3);
        g.fill(ax - (int) (4 * unit), top, ax + (int) (4 * unit), top + (int) (8 * unit), body);
        g.fill(ax - (int) (4 * unit), top + (int) (8 * unit), ax + (int) (4 * unit), top + (int) (20 * unit), body);
        g.fill(ax - (int) (8 * unit), top + (int) (8 * unit), ax - (int) (4 * unit), top + (int) (20 * unit), limbs);
        g.fill(ax + (int) (4 * unit), top + (int) (8 * unit), ax + (int) (8 * unit), top + (int) (20 * unit), limbs);
        g.fill(ax - (int) (4 * unit), top + (int) (20 * unit), ax - 1, top + sh, limbs);
        g.fill(ax, top + (int) (20 * unit), ax + (int) (4 * unit), top + sh, limbs);
        g.renderOutline(ax - (int) hw, top, (int) (hw * 2), sh, Theme.CTRL_HOVER);
        double su = 0, sv = 0;
        int head = 0, legs = 0;
        for (AttackEvent a : hits) {
            int cx = ax + (int) Math.round(a.hitU() * hw), cy = top + (int) Math.round((1 - a.hitV()) * sh);
            Draw.circle(g, cx - 1, cy - 1, 3, ThreeBlockHit.is(a, interp) ? Theme.alpha(Theme.AMBER, 0xC0) : Theme.alpha(Theme.GREEN, 0xA0));
            su += a.hitU();
            sv += a.hitV();
            if (a.hitV() >= HEAD_V) head++;
            else if (a.hitV() < LEGS_V) legs++;
        }
        double mu = su / hits.size(), mv = sv / hits.size();
        int mx = ax + (int) Math.round(mu * hw), my = top + (int) Math.round((1 - mv) * sh);
        g.fill(mx - 4, my, mx + 5, my + 1, Theme.ACCENT);
        g.fill(mx, my - 4, mx + 1, my + 5, Theme.ACCENT);

        int rx = x + cardW + 10, rw = w - cardW - 10, ry = y;
        int n = hits.size();
        String side = Math.abs(mu) < 0.05 ? t("по центру", "centered") : mu > 0 ? t("правее центра", "right of center") : t("левее центра", "left of center");
        line(ui, rw, t("Попаданий в расчёте: ", "Hits counted: ") + n + t(" · крестик: средняя точка", " · cross: mean point"), rx, ry, Theme.TEXT);
        ry += LINE;
        line(ui, rw, t("Средняя точка: ", "Mean point: ") + Fmt.signed(mu, 2) + t(" по ширине (", " across the width (") + side + t("), высота ", "), height ") + Fmt.pct(mv * 100),
                rx, ry, Theme.MUTED);
        ry += LINE;
        line(ui, rw, t("Голова ", "Head ") + Fmt.pct(100.0 * head / n) + t(" · тело ", " · body ") + Fmt.pct(100.0 * (n - head - legs) / n)
                + t(" · ноги ", " · legs ") + Fmt.pct(100.0 * legs / n), rx, ry, Theme.MUTED);
        ry += LINE + 6;
        double[] yaw = hits.stream().mapToDouble(AttackEvent::aimYawDeg).toArray();
        double[] pitch = hits.stream().mapToDouble(AttackEvent::aimPitchDeg).toArray();
        ry = hist(ui, rx, ry, rw, 34, Stats.histogram(yaw, -15, 15, 15), -15, 2, "°",
                t("Yaw, ср. |", "Yaw, avg. |") + Fmt.num(res.hits().aimYawAbsMean(), 1) + t("°| (плюс: цель правее прицела)", "°| (plus: target right of the crosshair)"), Theme.GREEN);
        ry = hist(ui, rx, ry + 4, rw, 34, Stats.histogram(pitch, -15, 15, 15), -15, 2, "°",
                t("Pitch, ср. |", "Pitch, avg. |") + Fmt.num(res.hits().aimPitchAbsMean(), 1) + t("°| (плюс: цель ниже прицела)", "°| (plus: target below the crosshair)"), Theme.MUTED);
        return Math.max(y + cardH, ry);
    }

    private int strikesTab(Ui ui, int x, int y, int w) {
        Stats.HitStats hs = res.hits();
        if (hs.confirmed() == 0) {
            ui.text(t("Подтверждённых ударов нет", "No confirmed hits"), x, y, Theme.DIM);
            return y + LINE;
        }
        ui.text(t("Типы ударов", "Hit types"), x, y, Theme.TEXT_STRONG);
        y += LINE + 2;
        int maxT = 1;
        for (HitType t : HitType.values()) maxT = Math.max(maxT, hs.types().getOrDefault(t, 0));
        int lw = 64, vw = 70, bw = w - lw - vw - 8;
        for (HitType t : HitType.values()) {
            int c = hs.types().getOrDefault(t, 0);
            ui.text(typeName(t), x, y, Theme.MUTED);
            Draw.round(ui.g, x + lw, y, bw, 8, 2, Theme.INSET);
            if (c > 0) Draw.round(ui.g, x + lw, y, Math.max(2, bw * c / maxT), 8, 2,
                    t == HitType.WEAK ? Theme.RED : t == HitType.CRIT ? Theme.AMBER : Theme.GREEN);
            ui.textRight(c + " (" + Fmt.pct(100.0 * c / hs.confirmed()) + ")", x + w, y, Theme.TEXT);
            y += LINE + 1;
        }
        y += 6;
        List<Double> str = new ArrayList<>();
        for (Event e : events) if (e instanceof AttackEvent a && a.outcome() == Outcome.HIT_CONFIRMED) str.add((double) a.strength());
        double[] sa = str.stream().mapToDouble(Double::doubleValue).toArray();
        y = hist(ui, x, y, w, 40, Stats.histogram(sa, 0, 1.0000001, 10), 0, 10, "%",
                t("Заряд атаки: средний ", "Attack charge: mean ") + Fmt.pct(hs.strengthMean() * 100) + t(", полный (выше 90%) ", ", full (above 90%) ") + Fmt.pct(hs.fullChargePct()),
                Theme.ACCENT);
        return y;
    }

    private static String methodName(WTap.Method m) {
        return switch (m) {
            case W_TAP -> "W-tap";
            case S_TAP -> "S-tap";
            case SPRINT_KEY -> t("клавиша спринта", "sprint key");
            case AUTO -> t("сам (спринт зажат)", "auto (sprint held)");
            case NONE -> t("без сброса", "no reset");
        };
    }

    private static String ms(double v) {
        return Double.isFinite(v) ? Fmt.num(v, 0) + t(" мс", " ms") : "—";
    }

    private int wtapTab(Ui ui, int x, int y, int w) {
        WTap.Result r = wtap;
        if (!r.hasInput() && rec.skipped(Features.INPUT)) return skipped(ui, w, t("Нажатия W, S и спринта", "W, S and sprint presses"), x, y);
        if (!r.hasInput()) {
            line(ui, w, t("Нет данных: нажатия W и спринта пишутся в записях новой версии мода.",
                    "No data: W and sprint presses are recorded from a newer mod version."), x, y, Theme.DIM);
            return y + LINE;
        }
        line(ui, w, t("SPRINT-ударов в сериях ", "Sprint hits in series ") + r.sprintHits() + t(" · со сбросом спринта перед ударом ", " · with a sprint reset before ")
                + Fmt.pct(r.afterResetPct()) + t(" · ударов без спринта ", " · hits without sprint ") + r.noSprintHits(), x, y, Theme.TEXT);
        y += LINE;
        List<String> parts = new ArrayList<>();
        for (WTap.Method m : WTap.Method.values()) {
            int n = r.methods().getOrDefault(m, 0);
            if (n > 0) parts.add(methodName(m) + " " + n);
        }
        line(ui, w, t("Как возвращали спринт: ", "How sprint came back: ") + (parts.isEmpty() ? "—" : String.join(", ", parts)), x, y, Theme.MUTED);
        y += LINE;
        line(ui, w, t("W отпущена в среднем через ", "W released on average ") + ms(r.releaseMeanMs()) + t(" после удара, сброс длится ", " after the hit, reset lasts ")
                + ms(r.resetMeanMs()), x, y, Theme.MUTED);
        y += LINE;
        line(ui, w, t("Серий, прерванных после удара без спринта: ", "Combos broken after a hit without sprint: ") + r.brokenNoSprint() + t(" из ", " of ") + r.combos(),
                x, y, r.combos() > 0 && r.brokenNoSprint() * 2 >= r.combos() ? Theme.RED : Theme.MUTED);
        y += LINE + 6;
        if (r.resetDurations().length > 0) {
            y = hist(ui, x, y, w, 40, Stats.histogram(r.resetDurations(), 0, 300, 15), 0, 20, t(" мс", " ms"),
                    t("Длительность сброса (W отпущена или S нажата), 20 мс на столбец", "Reset duration (W up or S down), 20 ms per bar"), Theme.ACCENT);
            y += 4;
        }
        line(ui, w, t("После удара с нокбэком спринт сбрасывается. С зажатыми W и спринтом он вернётся через тик сам,",
                "A knockback hit resets sprint. With W and sprint held it comes back a tick later by itself,"), x, y, Theme.DIM);
        y += LINE;
        line(ui, w, t("без клавиши спринта нужен W-tap или S-tap. Сброс в любом случае замедляет вас (скорость × 0,6).",
                "without the sprint key you need a W-tap or S-tap. Either way the hit slows you down (speed × 0.6)."), x, y, Theme.DIM);
        return y + LINE;
    }

    private static Card wtapCard(WTap.Result r) {
        List<Kv> rows = new ArrayList<>(List.of(
                new Kv(t("SPRINT-ударов со сбросом", "Sprint hits after reset"), Fmt.pct(r.afterResetPct()) + " (" + r.afterReset() + "/" + r.sprintHits() + ")", Theme.TEXT_STRONG),
                new Kv(t("Сброс длится", "Reset lasts"), ms(r.resetMeanMs())),
                new Kv(t("W отпущена через", "W released after"), ms(r.releaseMeanMs())),
                new Kv(t("Ударов без спринта", "Hits without sprint"), String.valueOf(r.noSprintHits())),
                new Kv(t("Комбо прервано без спринта", "Combos broken without sprint"), r.brokenNoSprint() + " / " + r.combos())));
        for (WTap.Method m : WTap.Method.values()) {
            int n = r.methods().getOrDefault(m, 0);
            if (n > 0) rows.add(new Kv("  " + methodName(m), String.valueOf(n)));
        }
        return new Card("W-tap", rows, 2);
    }

    private int comboTab(Ui ui, int x, int y, int w) {
        Stats.ComboStats c = res.combo();
        line(ui, w, t("Нанесено ", "Dealt ") + c.dealt() + t(" · получено ", " · taken ") + c.taken() + t(" · размен ", " · trade ")
                + (Double.isNaN(c.trade()) ? (c.dealt() > 0 ? "∞" : "—") : Fmt.num(c.trade(), 2)), x, y, Theme.TEXT);
        y += LINE;
        line(ui, w, t("Макс. комбо ", "Max combo ") + c.maxCombo() + t(" · среднее (от 2) ", " · mean (from 2) ")
                + (c.avgCombo() > 0 ? Fmt.num(c.avgCombo(), 1) : "—"), x, y, Theme.MUTED);
        y += LINE + 6;
        if (c.dealt() == 0) {
            ui.text(t("Подтверждённых ударов нет", "No confirmed hits"), x, y, Theme.DIM);
            return y + LINE;
        }
        double[] combos = Stats.comboLengths(events).stream().mapToDouble(Integer::doubleValue).toArray();
        int[] cc = Stats.histogram(combos, 0.5, 10.5, 10);
        return hist(ui, x, y, w, 40, cc, 1, 1, "",
                t("Длина серий ударов (до ответного удара): макс. ", "Combo length (until hit back): max ") + c.maxCombo() + t(", последний столбец 10+", ", last column 10+"),
                Theme.GREEN);
    }

    private int hist(Ui ui, int x, int y, int w, int h, int[] counts, double min, double step, String unit,
                     String title, int color) {
        int n = counts.length, max = 1, total = 0;
        for (int c : counts) {
            max = Math.max(max, c);
            total += c;
        }
        int by = y + 12, bw = Math.max(1, w / n), hover = -1;
        for (int i = 0; i < n; i++) {
            int bx = x + i * bw;
            if (ui.hover(bx, by, bw, h)) {
                hover = i;
                Draw.round(ui.g, bx, by, bw, h, 2, Theme.ROW_HOVER);
            }
            int bh = counts[i] == 0 ? 0 : Math.max(1, h * counts[i] / max);
            ui.g.fill(bx + 1, by + h - bh, bx + bw - 1, by + h, hover == i ? Theme.TEXT_STRONG : color);
        }
        ui.g.fill(x, by + h, x + bw * n, by + h + 1, Theme.BORDER);
        String t = title;
        if (hover >= 0) {
            String range = unit.isEmpty() ? binInt(min, step, hover, n)
                    : Fmt.num(min + hover * step, 0) + "…" + Fmt.num(min + (hover + 1) * step, 0) + unit;
            t = range + ": " + counts[hover] + (total > 0 ? " (" + Fmt.pct(100.0 * counts[hover] / total) + ")" : "");
        }
        line(ui, w, t, x, y, hover >= 0 ? Theme.TEXT_STRONG : Theme.MUTED);
        String lo = Fmt.num(min, 0) + unit, hi = unit.isEmpty() ? Fmt.num(min + (n - 1) * step, 0) + "+" : Fmt.num(min + n * step, 0) + unit;
        ui.text(lo, x, by + h + 3, Theme.DIM);
        ui.textRight(hi, x + bw * n, by + h + 3, Theme.DIM);
        if (min < 0 && min + n * step > 0) ui.textCenter("0", x + (int) ((-min) / step * bw), by + h + 3, Theme.DIM);
        return by + h + 14;
    }

    private static String binInt(double min, double step, int i, int n) {
        long v = Math.round(min + i * step);
        return t("Серия ", "Combo ") + v + (i == n - 1 ? "+" : "");
    }


    private static void cols(Ui ui, int x, int y, int w, double[] at, String[] text, int[] color) {
        for (int i = 0; i < text.length; i++) {
            int cx = x + (int) (at[i] * w), next = i + 1 < at.length ? x + (int) (at[i + 1] * w) : x + w;
            ui.text(Draw.ellipsize(ui.font, text[i], next - cx - 4), cx, y, color[i]);
        }
    }

    private static int[] fill(int n, int c) {
        int[] a = new int[n];
        Arrays.fill(a, c);
        return a;
    }

    private int header(Ui ui, int x, int y, int w, double[] at, String... names) {
        cols(ui, x, y, w, at, names, fill(names.length, Theme.DIM));
        ui.g.fill(x, y + 10, x + w, y + 11, Theme.BORDER);
        return y + 14;
    }

    private int jumpTab(Ui ui, int x, int y, int w) {
        Stats.JumpStats j = res.jump();
        Settings s = CombatDataClient.settings();
        line(ui, w, t("Попыток ", "Attempts ") + j.attempts() + t(" · успех ", " · success ") + Fmt.pct(j.successRatePct()) + t(" · среднее успешных ", " · mean of successful ")
                + (j.success() == 0 ? "—" : Fmt.num(j.meanSuccessMs(), 0) + t(" мс", " ms")) + " · σ "
                + (j.attempts() == 0 ? "—" : Fmt.num(j.sigmaMs(), 0) + t(" мс", " ms")), x, y, Theme.TEXT);
        y += LINE;
        line(ui, w, t("Окно успеха ", "Success window ") + s.jrLowerMs + "…" + s.jrUpperMs + t(" мс после удара · рано ", " ms after the hit · early ") + j.early() + t(" · поздно ", " · late ")
                + j.late() + t(" · без прыжка ", " · no jump ") + j.noAttempt() + (j.flyOut() > 0 ? " · fly out " + j.flyOut() : ""), x, y, Theme.MUTED);
        y += LINE + 6;
        if (attempts.isEmpty()) {
            ui.text(t("Полученных ударов нет", "No hits taken"), x, y, Theme.DIM);
            return y + LINE;
        }
        double[] at = {0, .16, .36, .50, .74};
        y = header(ui, x, y, w, at, t("Время", "Time"), t("Результат", "Result"), t("Дельта", "Delta"), t("Кто ударил", "Hit by"), t("Состояние", "State"));
        for (JumpResetEvaluator.Attempt a : attempts) {
            if (visible(y, ROW)) {
                String result;
                int rc;
                switch (a.result()) {
                    case SUCCESS -> { result = t("Успех", "Success"); rc = Theme.GREEN; }
                    case TOO_EARLY -> { result = t("Рано", "Early"); rc = Theme.RED; }
                    case TOO_LATE -> { result = t("Поздно", "Late"); rc = Theme.RED; }
                    case NO_ATTEMPT -> { result = t("Без прыжка", "No jump"); rc = Theme.MUTED; }
                    case FLY_OUT -> { result = "Fly out"; rc = Theme.BLUE; }
                    default -> { result = t("Не оценивается", "Not rated"); rc = Theme.DIM; }
                }
                if (a.held()) result += s.jrExcludeHeld ? t(" (зажат, искл.)", " (held, excl.)") : t(" (зажат)", " (held)");
                IncomingHitEvent h = a.hit();
                String state = (h.groundedAtHit() ? t("на земле", "on ground") : t("в воздухе", "in the air")) + (h.knockbackApplied() ? "" : t(", без нокбэка", ", no knockback"));
                cols(ui, x, y, w, at, new String[]{Fmt.clockMs(h.t()), result,
                                a.hasJump() ? Fmt.signed(a.deltaMs(), 0) + t(" мс", " ms") : "—", nz(h.attackerName()), state},
                        new int[]{Theme.MUTED, rc, Theme.TEXT, Theme.TEXT, Theme.MUTED});
            }
            y += ROW;
        }
        return y;
    }

    private int hitsTab(Ui ui, int x, int y, int w) {
        Stats.HitStats h = res.hits();
        Stats.ThreeBlockStats t = res.threeBlock();
        int interp = CombatDataClient.settings().interpMs;
        line(ui, w, t("Точность ", "Accuracy ") + Fmt.pct(h.hitRatePct()) + t(" · попаданий ", " · hits ") + h.confirmed() + t(" · промахов ", " · misses ") + h.misses()
                + t(" · средняя дистанция ", " · mean distance ") + (h.confirmed() == 0 ? "—" : Fmt.num(h.reachMean(), 2)), x, y, Theme.TEXT);
        y += LINE;
        line(ui, w, "3 block hit: " + (t.classified() == 0 ? t("нет данных", "no data") : t.count() + t(" из ", " of ") + t.classified())
                + t(" · последний столбец: дистанция на экране противника", " · last column: distance on the opponent's screen"), x, y, Theme.MUTED);
        y += LINE + 6;
        List<AttackEvent> list = new ArrayList<>();
        for (Event e : events) if (e instanceof AttackEvent a) list.add(a);
        if (list.isEmpty()) {
            ui.text(t("Атак нет", "No attacks"), x, y, Theme.DIM);
            return y + LINE;
        }
        boolean narrow = w < 360;
        double[] at = narrow ? new double[]{0, .22, .48, .78} : new double[]{0, .14, .32, .50, .60, .73, .82, .91};
        if (narrow) y = header(ui, x, y, w, at, t("Время", "Time"), t("Цель", "Target"), t("Исход", "Result"), t("Дист.", "Dist."));
        else y = header(ui, x, y, w, at, t("Время", "Time"), t("Цель", "Target"), t("Исход", "Result"), t("Дист.", "Dist."), t("Тип", "Type"), t("Заряд", "Charge"), "Yaw", t("У цели", "At target"));
        for (AttackEvent a : list) {
            if (visible(y, ROW)) {
                boolean tb = ThreeBlockHit.is(a, interp);
                String out;
                int oc;
                switch (a.outcome()) {
                    case HIT_CONFIRMED -> { out = tb ? "3 block hit" : t("Попал", "Hit"); oc = tb ? Theme.AMBER : Theme.GREEN; }
                    case HIT_UNCONFIRMED -> { out = t("Без подтв.", "Unconfirmed"); oc = Theme.MUTED; }
                    default -> {
                        out = a.outOfRangeMiss(a.rangeOr(AttackEvent.DEFAULT_RANGE)) ? t("Не дотянулся", "Out of range") : t("Мимо", "Missed");
                        oc = Theme.RED;
                    }
                }
                double od = ThreeBlockHit.classifiable(a, interp) ? ThreeBlockHit.opponentDistance(a, interp) : Double.NaN;
                if (narrow) cols(ui, x, y, w, at, new String[]{Fmt.clockMs(a.t()), nz(a.targetName()), out, Fmt.num(a.reach(), 2)},
                        new int[]{Theme.MUTED, Theme.TEXT, oc, Theme.TEXT});
                else cols(ui, x, y, w, at, new String[]{Fmt.clockMs(a.t()), nz(a.targetName()), out, Fmt.num(a.reach(), 2),
                                typeName(a.type()), Fmt.pct(a.strength() * 100), Fmt.signed(a.aimYawDeg(), 1) + "°",
                                Double.isNaN(od) ? "—" : "≈ " + Fmt.num(od, 1)},
                        new int[]{Theme.MUTED, Theme.TEXT, oc, Theme.TEXT, Theme.MUTED, Theme.MUTED, Theme.MUTED,
                                tb ? Theme.AMBER : Theme.MUTED});
            }
            y += ROW;
        }
        return y;
    }

    private int opponentsTab(Ui ui, int x, int y, int w) {
        List<Stats.OpponentStats> list = all.opponents();
        line(ui, w, t("Клик по строке: вся статистика только по этому противнику. Повторный клик снимает фильтр.", "Click a row: all stats for this opponent only. Click again to clear the filter."), x, y, Theme.MUTED);
        y += LINE + 6;
        if (list.isEmpty()) {
            ui.text(t("Противников нет", "No opponents"), x, y, Theme.DIM);
            return y + LINE;
        }
        double[] at = {0, .34, .48, .61, .74, .87};
        y = header(ui, x, y, w, at, t("Игрок", "Player"), t("Нанесено", "Dealt"), t("Промахов", "Misses"), t("Получено", "Taken"), t("Точность", "Accuracy"), t("Дист.", "Dist."));
        int rh = 14;
        for (Stats.OpponentStats o : list) {
            if (visible(y, rh)) {
                boolean sel = o.uuid().equals(opp), hov = ui.hover(x - 4, y - 2, w + 8, rh);
                if (sel) Draw.round(ui.g, x - 4, y - 3, w + 8, rh, Theme.R_CTRL, Theme.RAISED_HOVER);
                else if (hov) Draw.round(ui.g, x - 4, y - 3, w + 8, rh, Theme.R_CTRL, Theme.ROW_HOVER);
                cols(ui, x, y, w, at, new String[]{nz(o.name()), String.valueOf(o.dealt()), String.valueOf(o.missed()),
                                String.valueOf(o.taken()), Fmt.pct(o.hitRatePct()), o.dealt() == 0 ? "—" : Fmt.num(o.reachMean(), 2)},
                        new int[]{sel ? Theme.ACCENT : Theme.TEXT_STRONG, Theme.GREEN, Theme.TEXT, Theme.RED, Theme.TEXT, Theme.TEXT});
                UUID id = o.uuid();
                String nm = o.name();
                ui.click(x - 4, y - 3, w + 8, rh, () -> filter(id, nm));
            }
            y += rh;
        }
        return y;
    }

    private List<Line> buildTimeline() {
        List<Line> out = new ArrayList<>();
        for (Event e : events) {
            if (e instanceof SegmentEvent s) {
                out.add(new Line(s.t(), Fmt.clockMs(s.t()), t("Сервер ", "Server ") + nz(s.server()) + ", " + nz(s.dimension()), Theme.MUTED));
            } else if (e instanceof MarkerEvent m) {
                out.add(new Line(m.t(), Fmt.clockMs(m.t()), t("Метка: ", "Marker: ") + nz(m.label()), Theme.ACCENT));
            }
        }
        int n = 0;
        for (Stats.Fight f : Stats.fights(events, Stats.FIGHT_GAP_MS, sf)) {
            n++;
            out.add(new Line(f.startMs(), Fmt.clock(f.startMs()) + "–" + Fmt.clock(f.endMs()),
                    t("Бой ", "Fight ") + n + ": " + String.join(", ", f.opponents()) + t(" · атак ", " · attacks ") + f.attacks() + t(" · нанесено ", " · dealt ")
                            + f.dealt() + t(" · получено ", " · taken ") + f.taken(), Theme.TEXT));
        }
        out.sort(Comparator.comparingLong(Line::t));
        return out;
    }

    private int fightsTab(Ui ui, int x, int y, int w) {
        List<Stats.Fight> fights = Stats.fights(events, Stats.FIGHT_GAP_MS, sf);
        line(ui, w, t("Боёв ", "Fights ") + fights.size() + t(" · новый бой после паузы больше ", " · a new fight after a pause over ")
                + Stats.FIGHT_GAP_MS / 1000 + t(" с без ударов · клик: размены боя", " s without hits · click: trades of the fight"), x, y, Theme.MUTED);
        y += LINE;
        int small = Stats.fights(events, Stats.FIGHT_GAP_MS).size() - fights.size();
        if (sf.minFightHits() > 1) {
            line(ui, w, t("Бои меньше ", "Fights under ") + sf.minFightHits() + t(" подтверждённых ударов не показаны", " confirmed hits are hidden")
                    + (small > 0 ? " (" + small + ")" : ""), x, y, Theme.DIM);
            y += LINE;
        }
        y += 6;
        if (fights.isEmpty()) {
            ui.text(t("Боёв нет", "No fights"), x, y, Theme.DIM);
            return y + LINE;
        }
        double[] at = {0, .26, .64, .76, .88};
        y = header(ui, x, y, w, at, t("Время", "Time"), t("Противники", "Opponents"), t("Атак", "Atk."), t("Нанес.", "Dealt"), t("Получ.", "Taken"));
        for (Stats.Fight f : fights) {
            if (visible(y, ROW)) {
                if (ui.hover(x - 4, y - 2, w + 8, ROW)) Draw.round(ui.g, x - 4, y - 2, w + 8, ROW, Theme.R_CTRL, Theme.ROW_HOVER);
                cols(ui, x, y, w, at, new String[]{Fmt.clock(f.startMs()) + "–" + Fmt.clock(f.endMs()),
                                String.join(", ", f.opponents()), String.valueOf(f.attacks()), String.valueOf(f.dealt()), String.valueOf(f.taken())},
                        new int[]{Theme.MUTED, Theme.TEXT, Theme.TEXT, Theme.GREEN, Theme.RED});
                long from = f.startMs(), to = f.endMs();
                ui.click(x - 4, y - 2, w + 8, ROW, () -> {
                    timeFrom = from;
                    timeTo = to;
                    fightsMode = F_TRADES;
                    scroll = 0;
                });
            }
            y += ROW;
        }
        return y;
    }

    private int takenTab(Ui ui, int x, int y, int w) {
        Stats.TakenStats tk = res.taken();
        if (tk.rated() == 0 && rec.skipped(Features.TAKEN)) return skipped(ui, w, t("Дистанция ударов по вам", "Distance of hits on you"), x, y);
        if (tk.rated() == 0) {
            line(ui, w, t("Нет данных: дистанция ударов по вам пишется в записях новой версии мода, только ближние удары.",
                    "No data: distance of hits on you is recorded from a newer mod version, melee hits only."), x, y, Theme.DIM);
            return y + LINE;
        }
        Stats.HitStats hs = res.hits();
        Stats.ThreeBlockStats mine = res.threeBlock();
        line(ui, w, t("Ударов по вам ", "Hits on you ") + tk.rated() + t(" · средняя дистанция ", " · mean distance ") + Fmt.num(tk.reachMean(), 2)
                + t(" бл. (ваша ", " bl. (yours ") + (hs.confirmed() == 0 ? "—" : Fmt.num(hs.reachMean(), 2)) + t(") · максимум ", ") · max ")
                + Fmt.num(tk.reachMax(), 2), x, y, Theme.TEXT);
        y += LINE;
        line(ui, w, t("3 block hit против вас: ", "3 block hit against you: ") + tk.caught() + " (" + Fmt.pct(tk.caughtPct()) + ")"
                + t(" · ваших: ", " · yours: ") + (mine.classified() == 0 ? "—" : mine.count() + " (" + Fmt.pct(mine.sharePct()) + ")"),
                x, y, tk.caughtPct() >= 25 ? Theme.RED : Theme.MUTED);
        y += LINE;
        line(ui, w, t("3 block hit против вас: на вашем экране противник был дальше вашей дальности атаки, ответить вы не могли.",
                "3 block hit against you: on your screen the opponent was beyond your attack range, you could not hit back."), x, y, Theme.DIM);
        y += LINE + 6;
        double[] at = {0, .40, .60, .80};
        y = header(ui, x, y, w, at, t("Оружие противника", "Opponent weapon"), t("Ударов", "Hits"), t("Дист.", "Dist."), "3BH");
        for (Weapon wp : Weapon.values()) {
            Stats.WeaponTaken wt = tk.byWeapon().get(wp);
            if (wt == null) continue;
            cols(ui, x, y, w, at, new String[]{weaponName(wp), String.valueOf(wt.n()), Fmt.num(wt.reachMean(), 2),
                    wt.caught() + " (" + Fmt.pct(100.0 * wt.caught() / wt.n()) + ")"}, new int[]{Theme.TEXT, Theme.TEXT, Theme.TEXT,
                    wt.caught() > 0 ? Theme.AMBER : Theme.MUTED});
            y += ROW;
        }
        y += 8;
        boolean narrow = w < 360;
        at = narrow ? new double[]{0, .24, .56, .78} : new double[]{0, .14, .36, .54, .66, .78, .88};
        if (narrow) y = header(ui, x, y, w, at, t("Время", "Time"), t("Кто", "Who"), t("Дист.", "Dist."), "3BH");
        else y = header(ui, x, y, w, at, t("Время", "Time"), t("Кто ударил", "Hit by"), t("Оружие", "Weapon"), t("Дист.", "Dist."),
                t("Его ск.", "Their v"), t("Ваша ск.", "Your v"), t("Пинг", "Ping"));
        for (Event e : events) {
            if (!(e instanceof IncomingHitEvent h) || !h.rated()) continue;
            if (visible(y, ROW)) {
                int dc = h.caught() ? Theme.AMBER : Theme.TEXT;
                String d = Fmt.num(h.reach(), 2) + (h.caught() ? " !" : "");
                if (narrow) cols(ui, x, y, w, at, new String[]{Fmt.clockMs(h.t()), nz(h.attackerName()), d, h.caught() ? t("да", "yes") : ""},
                        new int[]{Theme.MUTED, Theme.TEXT, dc, Theme.AMBER});
                else cols(ui, x, y, w, at, new String[]{Fmt.clockMs(h.t()), nz(h.attackerName()), weaponName(h.weaponOr()), d,
                                Fmt.signed(h.attackerTowardBps(), 1), Fmt.signed(h.selfAwayBps(), 1),
                                h.attackerPingMs() >= 0 ? h.attackerPingMs() + t(" мс", " ms") : "—"},
                        new int[]{Theme.MUTED, Theme.TEXT, Theme.MUTED, dc, Theme.MUTED, Theme.MUTED, Theme.MUTED});
            }
            y += ROW;
        }
        return y;
    }

    private static Card takenCard(Stats.TakenStats tk, Stats.ThreeBlockStats mine) {
        return new Card(t("Удары по вам", "Hits on you"), List.of(
                new Kv(t("3 block hit против вас", "3 block hit against you"), tk.caught() + " (" + Fmt.pct(tk.caughtPct()) + ")",
                        tk.caughtPct() >= 25 ? Theme.RED : Theme.TEXT_STRONG),
                new Kv(t("Средняя дистанция", "Mean distance"), Fmt.num(tk.reachMean(), 2) + t(" бл.", " bl.")),
                new Kv(t("Ваши 3 block hit", "Your 3 block hits"), mine.classified() == 0 ? "—" : Fmt.pct(mine.sharePct())),
                new Kv(t("Максимум", "Max"), Fmt.num(tk.reachMax(), 2) + t(" бл.", " bl.")),
                new Kv(t("Ударов с замером", "Hits measured"), String.valueOf(tk.rated()))), 2);
    }

    static String causeName(Trades.Cause c) {
        if (c == null) return "";
        return switch (c) {
            case MISS -> t("промах", "miss");
            case WEAK -> t("удар без заряда", "uncharged hit");
            case CAUGHT -> t("3 block hit против вас", "3 block hit against you");
            case FAILED_JR -> t("неудачный jump reset", "failed jump reset");
            case NONE -> t("без явной причины", "no clear cause");
        };
    }

    private int tradesTab(Ui ui, int x, int y, int w) {
        Trades.Summary s = res.trades();
        long gap = CombatDataClient.settings().tradeGapMs;
        line(ui, w, t("Разменов ", "Trades ") + s.total() + t(" · первый удар ", " · first hit ") + Fmt.pct(s.firstPct())
                + t(" · побед / поражений / ничьих ", " · won / lost / drawn ") + s.wins() + " / " + s.losses() + " / " + s.draws(), x, y, Theme.TEXT);
        y += LINE;
        line(ui, w, t("Счёт: удары (вес). Вес: крит 1,5, ваш слабый удар по заряду; победитель ", "Score: hits (weight). Weight: crit 1.5, your weak hit by charge; winner ")
                + (CombatDataClient.settings().tradeByWeight ? t("по весу", "by weight") : t("по числу ударов", "by hit count"))
                + t(" (Настройки → Дополнительно)", " (Settings → Advanced)"), x, y, Theme.DIM);
        y += LINE;
        line(ui, w, t("Побед, когда били первым: ", "Won when hitting first: ") + Fmt.pct(s.winFirstPct()) + " (" + s.winsFirst() + "/" + s.countFirst() + ")"
                + t(" · вторым: ", " · second: ") + Fmt.pct(s.winSecondPct()) + " (" + s.winsSecond() + "/" + s.countSecond() + ")", x, y, Theme.MUTED);
        y += LINE;
        if (s.losses() > 0) {
            List<String> parts = new ArrayList<>();
            for (Trades.Cause c : Trades.Cause.values()) {
                int n = s.lossCauses().getOrDefault(c, 0);
                if (n > 0) parts.add(causeName(c) + " " + n);
            }
            line(ui, w, t("Проигрыши начинались с: ", "Losses started with: ") + String.join(", ", parts), x, y, Theme.MUTED);
            y += LINE;
        }
        line(ui, w, t("Новый размен после паузы больше ", "A new trade after a pause over ") + Fmt.num(gap / 1000.0, 1)
                + t(" с (Настройки → Дополнительно)", " s (Settings → Advanced)"), x, y, Theme.DIM);
        y += LINE + 6;
        if (trades.isEmpty()) {
            ui.text(t("Разменов нет", "No trades"), x, y, Theme.DIM);
            return y + LINE;
        }
        boolean narrow = w < 360;
        double[] at = narrow ? new double[]{0, .20, .46, .76} : new double[]{0, .13, .33, .42, .60, .72};
        if (narrow) y = header(ui, x, y, w, at, t("Время", "Time"), t("Противник", "Opponent"), t("Счёт", "Score"), t("Итог", "Result"));
        else y = header(ui, x, y, w, at, t("Время", "Time"), t("Противник", "Opponent"), t("Первый", "First"), t("Счёт", "Score"),
                t("Итог", "Result"), t("Причина", "Cause"));
        for (Trades.Trade tr : trades) {
            if (visible(y, ROW)) {
                String res;
                int rc;
                switch (tr.result()) {
                    case WIN -> { res = t("победа", "won"); rc = Theme.GREEN; }
                    case LOSS -> { res = t("поражение", "lost"); rc = Theme.RED; }
                    default -> { res = t("ничья", "draw"); rc = Theme.MUTED; }
                }
                String first = tr.first() == Trades.Side.ME ? t("вы", "you") : t("он", "they");
                String score = tr.mine() + ":" + tr.theirs() + " (" + Fmt.num(tr.mineWeight(), 1) + ":" + Fmt.num(tr.theirsWeight(), 1) + ")";
                if (narrow) cols(ui, x, y, w, at, new String[]{Fmt.clock(tr.startMs()), nz(tr.name()), score + " · " + first, res},
                        new int[]{Theme.MUTED, Theme.TEXT, Theme.TEXT, rc});
                else cols(ui, x, y, w, at, new String[]{Fmt.clockMs(tr.startMs()), nz(tr.name()), first, score, res, causeName(tr.cause())},
                        new int[]{Theme.MUTED, Theme.TEXT, tr.first() == Trades.Side.ME ? Theme.GREEN : Theme.MUTED, Theme.TEXT, rc, Theme.MUTED});
            }
            y += ROW;
        }
        return y;
    }

    private static Card tradesCard(Trades.Summary s) {
        if (s.total() == 0) return new Card(t("Размены", "Trades"), List.of(new Kv(t("Разменов нет", "No trades"), "")), 1);
        List<Kv> rows = new ArrayList<>(List.of(
                new Kv(t("Первый удар", "First hit"), Fmt.pct(s.firstPct()) + " (" + s.first() + "/" + s.total() + ")", Theme.TEXT_STRONG),
                new Kv(t("Победы / поражения / ничьи", "Won / lost / drawn"), s.wins() + " / " + s.losses() + " / " + s.draws()),
                new Kv(t("Вес ударов: ваших / его", "Hit weight: yours / theirs"), Fmt.num(s.weightMine(), 1) + " / " + Fmt.num(s.weightTheirs(), 1)),
                new Kv(t("Побед, когда вторым", "Won when second"), Fmt.pct(s.winSecondPct()), s.countSecond() > 0 && s.winSecondPct() < 30 ? Theme.RED : Theme.TEXT),
                new Kv(t("Побед, когда первым", "Won when first"), Fmt.pct(s.winFirstPct()))));
        for (Trades.Cause c : Trades.Cause.values()) {
            int n = s.lossCauses().getOrDefault(c, 0);
            if (n > 0) rows.add(new Kv(t("  проигрыш: ", "  lost: ") + causeName(c), String.valueOf(n)));
        }
        return new Card(t("Размены", "Trades"), rows, 3);
    }

    private int timelineTab(Ui ui, int x, int y, int w) {
        line(ui, w, t("Бои (пауза больше ", "Fights (a pause over ") + Stats.FIGHT_GAP_MS / 1000 + t(" с начинает новый), метки и смены сервера.", " s starts a new one), markers and server changes."),
                x, y, Theme.MUTED);
        y += LINE + 6;
        if (timeline.isEmpty()) {
            ui.text(t("Событий нет", "No events"), x, y, Theme.DIM);
            return y + LINE;
        }
        double[] at = {0, .2};
        for (Line l : timeline) {
            if (visible(y, ROW)) cols(ui, x, y, w, at, new String[]{l.time(), l.text()}, new int[]{Theme.MUTED, l.color()});
            y += ROW;
        }
        return y;
    }

    private static int skipped(Ui ui, int w, String what, int x, int y) {
        String s = t("Не записывалось: «" + what + "» было выключено для этой записи (лёгкая запись). Включить для новых записей: Настройки → Основные → Что записывать.",
                "Not recorded: \"" + what + "\" was off for this recording (lite recording). Turn it on for new recordings: Settings → General → What to record.");
        for (String l : ui.wrap(s, w)) {
            ui.text(l, x, y, Theme.MUTED);
            y += LINE;
        }
        return y;
    }

    private static void line(Ui ui, int w, String s, int x, int y, int color) {
        ui.text(Draw.ellipsize(ui.font, s, w), x, y, color);
    }

    private static String nz(String s) {
        return s == null ? "?" : s;
    }
}
