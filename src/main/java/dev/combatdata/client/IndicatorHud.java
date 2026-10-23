package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.Cooldown;
import dev.combatdata.core.HudAnchor;
import dev.combatdata.core.IndicatorConfig;
import dev.combatdata.core.IndicatorConfig.Kind;
import dev.combatdata.core.LiveCombat;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.combatdata.core.Lang.t;

/**
 * Draws the indicators. Each one is a small pill in the style of the REC label: grey caption, colored value.
 * Values that just changed "pop" for a moment, short-lived ones fade out.
 */
public final class IndicatorHud {
    static final int H = 13, PAD = 4, ICON_GAP = 3, BAR_W = 40, BAR_H = 4;
    static final int TEXT = 0xFFF4F2EA, LABEL = 0xFFA8A69D, GOOD = 0xFF7FB285, MID = 0xFFE3B15C, BAD = 0xFFE07A6D;
    static final int HP_RED = 0xFFFF5555, HP_WHITE = 0xFFF4F2EA, HP_GOLD = 0xFFFFC94D, HP_GREEN = 0xFF7CDB72,
            HP_YELLOW = 0xFFF2C14E, ABSORB = 0xFFF2C94C;
    static final int NONE = 0, HEART = 1, CHECK = 2, CROSS = 3;
    private static final long POP_MS = 180, FADE_MS = 400, COMBO_LINGER_MS = 1500, REACH_FRESH_MS = 3000;

    private static final String[][] ICONS = {
            {},
            {".##.##.", "#######", "#######", ".#####.", "..###..", "...#..."},
            {".....##", "....##.", "##.##..", ".###...", "..#...."},
            {"##...##", ".##.##.", "..###..", ".##.##.", "##...##"},
    };

    private static long chargeFullAt = -1;

    record Seg(String text, int color) {
    }

    /** What one indicator shows this frame. bar >= 0 draws a bar instead of text; popAt is when the value changed. */
    record View(List<Seg> segs, int icon, int iconColor, double bar, int barColor, float alpha, long popAt) {
        int rawW(Font font) {
            if (bar >= 0) return BAR_W;
            int w = PAD * 2;
            if (icon != NONE) w += ICONS[icon][0].length() + (segs.isEmpty() ? 0 : ICON_GAP);
            for (Seg s : segs) w += font.width(s.text());
            return w;
        }

        int rawH() {
            return bar >= 0 ? BAR_H : H;
        }
    }

    public record Box(int x, int y, int w, int h, float scale) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private IndicatorHud() {
    }

    public static void extract(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        IndicatorConfig c = CombatDataClient.settings().indicators;
        if (!c.enabled || mc.options.hideGui || mc.player == null || mc.screen instanceof IndicatorLayoutScreen) return;
        long now = Indicators.clock();
        float partial = delta.getGameTimeDeltaPartialTick(true);
        for (Kind k : Kind.values()) {
            IndicatorConfig.Element e = c.get(k);
            if (!e.on) continue;
            View v = live(k, c, mc, partial, now);
            if (v == null || v.alpha() <= 0.02f) continue;
            draw(g, mc.font, v, box(mc.font, v, e, g.guiWidth(), g.guiHeight()), c, e, false, now);
        }
    }

    /** Draws a sample at scale 1 for the settings tab; returns its width. */
    public static int preview(GuiGraphics g, Font font, Kind k, int x, int y, IndicatorConfig c) {
        View v = sample(k, c);
        int w = v.rawW(font);
        draw(g, font, v, new Box(x, y, w, v.rawH(), 1f), c, c.get(k), false, Long.MIN_VALUE / 2);
        return w;
    }

    public static int previewWidth(Font font, Kind k, IndicatorConfig c) {
        return sample(k, c).rawW(font);
    }

    public static int previewHeight(Kind k, IndicatorConfig c) {
        return sample(k, c).rawH();
    }

    static Box sampleBox(Font font, Kind k, IndicatorConfig c, int sw, int sh) {
        return box(font, sample(k, c), c.get(k), sw, sh);
    }

    static void drawSample(GuiGraphics g, Font font, Kind k, IndicatorConfig c, Box b, boolean highlight) {
        draw(g, font, sample(k, c), b, c, c.get(k), highlight, Long.MIN_VALUE / 2);
    }

    static Box box(Font font, View v, IndicatorConfig.Element e, int sw, int sh) {
        float sc = (float) e.scale;
        int w = Math.max(1, Math.round(v.rawW(font) * sc)), h = Math.max(1, Math.round(v.rawH() * sc));
        int[] p = HudAnchor.place(e.anchor, e.dx, e.dy, w, h, sw, sh);
        return new Box(p[0], p[1], w, h, sc);
    }

    private static View live(Kind k, IndicatorConfig c, Minecraft mc, float partial, long now) {
        LocalPlayer p = mc.player;
        LiveCombat l = Indicators.live();
        return switch (k) {
            case HEALTH -> mc.gameMode != null && !mc.gameMode.canHurtPlayer() ? null
                    : health(c, p.getHealth(), p.getAbsorptionAmount(), p.getMaxHealth());
            case REACH -> reach(c, l.lastReach(), now - l.lastReachAt(), l.lastReachAt());
            case COMBO -> combo(c, l, now);
            case PING -> ping(c, Recorder.lastPing());
            case CHARGE -> charge(c, p.getAttackStrengthScale(partial), now);
            case JUMP_RESET -> jumpReset(c, l.lastJr(), now);
            case THREE_BLOCK -> threeBlock(c, l.lastThreeBlock(), now);
            case SESSION -> session(c, l.accuracyPct(), l.hits(), l.swings(), l.jrPct(), l.bestCombo(), l.threeBlocks());
        };
    }

    static View sample(Kind k, IndicatorConfig c) {
        long old = Long.MIN_VALUE / 2;
        return switch (k) {
            case HEALTH -> health(c, 17.35f, 4f, 20f);
            case REACH -> view(labeled(c, t("Дистанция", "Reach"), fmt(2.874, c.reachDecimals), c.colors ? GOOD : TEXT), 1f, old);
            case COMBO -> view(labeled(c, t("Комбо", "Combo"), "4", c.colors ? GOOD : TEXT), 1f, old);
            case PING -> ping(c, 42);
            case CHARGE -> chargeView(c, 0.72f, 1f);
            case JUMP_RESET -> jrView(c, new LiveCombat.JrVerdict(LiveCombat.Jr.SUCCESS, 45, 0, 0), 1f, old);
            case THREE_BLOCK -> tbView(c, 3.41, 1f, old);
            case SESSION -> session(c, 68, 34, 50, 72, 5, 3);
        };
    }

    private static View view(List<Seg> segs, float alpha, long popAt) {
        return new View(segs, NONE, 0, -1, 0, alpha, popAt);
    }

    private static List<Seg> labeled(IndicatorConfig c, String label, String value, int color) {
        List<Seg> s = new ArrayList<>(2);
        if (c.labels) s.add(new Seg(label + " ", LABEL));
        s.add(new Seg(value, color));
        return s;
    }

    private static String fmt(double v, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }

    /** Fully visible, then fades out over the last FADE_MS of the shown time. */
    private static float fade(long age, long shown) {
        if (age < 0 || age >= shown) return 0f;
        return Math.min(1f, (shown - age) / (float) FADE_MS);
    }

    static View health(IndicatorConfig c, float hp, float absorption, float max) {
        double div = c.hpHearts ? 2.0 : 1.0;
        int color = switch (c.hpColor) {
            case IndicatorConfig.HP_WHITE -> HP_WHITE;
            case IndicatorConfig.HP_GOLD -> HP_GOLD;
            case IndicatorConfig.HP_BY_HEALTH -> {
                double f = max > 0 ? hp / max : 1;
                yield f > 0.6 ? HP_GREEN : f > 0.3 ? HP_YELLOW : HP_RED;
            }
            default -> HP_RED;
        };
        List<Seg> s = new ArrayList<>(2);
        s.add(new Seg(IndicatorConfig.formatHealth(hp / div, c.hpDecimals), color));
        if (c.hpAbsorption && absorption > 0) s.add(new Seg(" +" + IndicatorConfig.formatHealth(absorption / div, c.hpDecimals), ABSORB));
        return new View(s, c.hpIcon ? HEART : NONE, color, -1, 0, 1f, Long.MIN_VALUE / 2);
    }

    private static View reach(IndicatorConfig c, double reach, long age, long at) {
        String label = t("Дистанция", "Reach");
        if (!Double.isFinite(reach)) return view(labeled(c, label, "—", LABEL), 1f, at);
        float alpha = c.reachHideSec > 0 ? fade(age, c.reachHideSec * 1000L) : 1f;
        if (alpha <= 0) return null;
        // An old value stays on screen but greys out, so it is not taken for the last hit.
        int color = age > REACH_FRESH_MS ? LABEL : c.colors && reach >= 2.8 ? GOOD : TEXT;
        return view(labeled(c, label, fmt(reach, c.reachDecimals), color), alpha, at);
    }

    private static View combo(IndicatorConfig c, LiveCombat l, long now) {
        String label = t("Комбо", "Combo");
        int min = c.comboHideZero ? 2 : 0, n = l.combo();
        if (n >= min && (n > 0 || !c.comboHideZero)) {
            int color = n >= 3 && c.colors ? GOOD : n == 0 ? LABEL : TEXT;
            return view(labeled(c, label, String.valueOf(n), color), 1f, n >= Math.max(2, min) ? l.comboChangedAt() : Long.MIN_VALUE / 2);
        }
        long since = now - l.comboEndedAt();
        if (l.lastCombo() >= 2 && since < COMBO_LINGER_MS) {
            return view(labeled(c, label, String.valueOf(l.lastCombo()), LABEL), fade(since, COMBO_LINGER_MS), Long.MIN_VALUE / 2);
        }
        return c.comboHideZero ? null : view(labeled(c, label, String.valueOf(n), LABEL), 1f, Long.MIN_VALUE / 2);
    }

    private static View ping(IndicatorConfig c, int ping) {
        String label = t("Пинг", "Ping");
        if (ping <= 0) return view(labeled(c, label, "—", LABEL), 1f, Long.MIN_VALUE / 2);
        int color = !c.colors ? TEXT : ping <= 80 ? GOOD : ping <= 150 ? MID : BAD;
        return view(labeled(c, label, ping + t(" мс", " ms"), color), 1f, Long.MIN_VALUE / 2);
    }

    private static View charge(IndicatorConfig c, float s, long now) {
        if (s < 1f) chargeFullAt = -1;
        else if (chargeFullAt < 0) chargeFullAt = now;
        float alpha = 1f;
        if (c.chargeHideFull && chargeFullAt >= 0) {
            // A short hold at 100% so the moment of full charge is still visible, then a quick fade.
            alpha = 1f - Math.max(0, now - chargeFullAt - 150) / 250f;
            if (alpha <= 0) return null;
        }
        return chargeView(c, s, alpha);
    }

    private static View chargeView(IndicatorConfig c, float s, float alpha) {
        boolean charged = Cooldown.charged(s);
        int color = charged && c.colors ? GOOD : TEXT;
        if (c.chargeStyle == IndicatorConfig.CHARGE_BAR) return new View(List.of(), NONE, 0, Math.min(1, s), color, alpha, Long.MIN_VALUE / 2);
        return view(labeled(c, t("Заряд", "Charge"), (int) Math.floor(Math.min(1, s) * 100) + "%", color), alpha, Long.MIN_VALUE / 2);
    }

    private static View jumpReset(IndicatorConfig c, LiveCombat.JrVerdict v, long now) {
        if (v == null) return null;
        float alpha = fade(now - v.at(), c.jrShowSec * 1000L);
        return alpha <= 0 ? null : jrView(c, v, alpha, v.at());
    }

    private static View jrView(IndicatorConfig c, LiveCombat.JrVerdict v, float alpha, long popAt) {
        String ms = t(" мс", " ms");
        int color;
        String value;
        switch (v.result()) {
            case SUCCESS -> {
                color = GOOD;
                value = v.deltaMs() + ms;
            }
            case EARLY -> {
                color = MID;
                value = t("рано на ", "early by ") + v.offMs() + ms;
            }
            default -> {
                color = BAD;
                value = t("поздно на ", "late by ") + v.offMs() + ms;
            }
        }
        if (!c.colors) color = TEXT;
        List<Seg> s = labeled(c, "JR", value, color);
        return new View(s, v.result() == LiveCombat.Jr.SUCCESS ? CHECK : CROSS, color, -1, 0, alpha, popAt);
    }

    private static View threeBlock(IndicatorConfig c, LiveCombat.ThreeBlock tb, long now) {
        if (tb == null) return null;
        float alpha = fade(now - tb.at(), c.tbShowSec * 1000L);
        return alpha <= 0 ? null : tbView(c, tb.opponentDistance(), alpha, tb.at());
    }

    private static View tbView(IndicatorConfig c, double dist, float alpha, long popAt) {
        boolean hasDist = c.tbDistance && Double.isFinite(dist);
        List<Seg> s = new ArrayList<>(2);
        // Without captions the estimated distance alone stands for the hit, in the 3 block hit color.
        if (c.labels || !hasDist) s.add(new Seg("3 block hit", c.colors ? MID : TEXT));
        if (hasDist) s.add(new Seg((c.labels ? " " : "") + fmt(dist, 2), c.labels ? TEXT : MID));
        return view(s, alpha, popAt);
    }

    static View session(IndicatorConfig c, double acc, int hits, int swings, double jr, int best, int tb) {
        List<Seg> s = new ArrayList<>();
        if (c.labels) s.add(new Seg(t("Сессия", "Session") + " ", LABEL));
        int start = s.size();
        if (c.sesAccuracy) {
            int color = !Double.isFinite(acc) ? LABEL : !c.colors ? TEXT : acc >= 60 ? GOOD : acc >= 40 ? MID : BAD;
            s.add(new Seg(Double.isFinite(acc) ? Math.round(acc) + "%" : "—", color));
        }
        if (c.sesHits) part(s, start, "", hits + "/" + swings, TEXT);
        if (c.sesJr) {
            int color = !Double.isFinite(jr) ? LABEL : !c.colors ? TEXT : jr >= 60 ? GOOD : jr >= 35 ? MID : BAD;
            part(s, start, "JR ", Double.isFinite(jr) ? Math.round(jr) + "%" : "—", color);
        }
        if (c.sesCombo) part(s, start, t("комбо ", "combo "), String.valueOf(best), TEXT);
        if (c.sesThreeBlock) part(s, start, "3BH ", String.valueOf(tb), TEXT);
        if (s.size() == start) s.add(new Seg("—", LABEL));
        return view(s, 1f, Long.MIN_VALUE / 2);
    }

    private static void part(List<Seg> s, int start, String label, String value, int color) {
        if (s.size() > start) s.add(new Seg(" · ", LABEL));
        if (!label.isEmpty()) s.add(new Seg(label, LABEL));
        s.add(new Seg(value, color));
    }

    static void draw(GuiGraphics g, Font font, View v, Box b, IndicatorConfig c, IndicatorConfig.Element e,
                     boolean highlight, long now) {
        int rw = v.rawW(font), rh = v.rawH();
        float a = Math.max(0f, Math.min(1f, v.alpha()));
        g.pose().pushMatrix();
        g.pose().translate((float) b.x(), (float) b.y());
        g.pose().scale(b.scale(), b.scale());
        long age = now - v.popAt();
        if (age >= 0 && age < POP_MS) {
            float k = 1f + 0.14f * (1f - age / (float) POP_MS);
            g.pose().translate(rw / 2f, rh / 2f);
            g.pose().scale(k, k);
            g.pose().translate(-rw / 2f, -rh / 2f);
        }
        if (v.bar() >= 0) {
            RecHud.roundRect(g, 0, 0, rw, rh, Math.round(0x90 * a) << 24);
            int fw = (int) Math.round((rw - 2) * v.bar());
            if (fw > 0) g.fill(1, 1, 1 + fw, rh - 1, alpha(v.barColor(), a));
        } else {
            int bgA = Math.round(c.bgOpacity * 255 / 100f * a);
            if (e.bg && bgA > 0) RecHud.roundRect(g, 0, 0, rw, rh, bgA << 24);
            int x = PAD;
            if (v.icon() != NONE) {
                String[] icon = ICONS[v.icon()];
                int iy = (H - icon.length) / 2;
                if (c.shadow) icon(g, icon, x + 1, iy + 1, alpha(shadowOf(v.iconColor()), a));
                icon(g, icon, x, iy, alpha(v.iconColor(), a));
                x += icon[0].length() + ICON_GAP;
            }
            for (Seg s : v.segs()) {
                g.drawString(font, s.text(), x, 3, alpha(s.color(), a), c.shadow);
                x += font.width(s.text());
            }
        }
        if (highlight) g.renderOutline(-1, -1, rw + 2, rh + 2, 0xFFFAF9F5);
        g.pose().popMatrix();
    }

    private static void icon(GuiGraphics g, String[] rows, int x, int y, int color) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            int start = -1;
            for (int i = 0; i <= row.length(); i++) {
                boolean on = i < row.length() && row.charAt(i) == '#';
                if (on && start < 0) start = i;
                if (!on && start >= 0) {
                    g.fill(x + start, y + r, x + i, y + r + 1, color);
                    start = -1;
                }
            }
        }
    }

    /** Same darkening as the game's text shadow. */
    private static int shadowOf(int c) {
        return (c & 0xFF000000) | ((c & 0xFCFCFC) >> 2);
    }

    /** Text with alpha below 4 is drawn opaque by the game, so the minimum is 4. */
    private static int alpha(int c, float a) {
        int al = Math.max(4, Math.round(((c >>> 24) & 0xFF) * a));
        return (al << 24) | (c & 0xFFFFFF);
    }
}
