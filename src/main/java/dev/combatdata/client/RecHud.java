package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.Settings;
import static dev.combatdata.core.Lang.t;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class RecHud {
    static final int H = 13, PAD = 4, DOT = 7, GAP = 5;
    static final int RED = 0xFFE5584B, GREEN = 0xFF7FB285, GREY = 0xFFA8A69D, WHITE = 0xFFF4F2EA;

    record Label(String text, int state, boolean blink, boolean paused) {
    }

    record Box(int x, int y, int w, int h, int rawW, float scale) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private RecHud() {
    }

    public static void extract(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Settings s = CombatDataClient.settings();
        if (!s.hudVisible || mc.options.hideGui || mc.player == null || mc.screen instanceof HudPositionScreen) return;
        Label l = label(s);
        if (s.hudHideWhenPaused && l.paused()) return;
        draw(g, mc.font, box(mc.font, g.guiWidth(), g.guiHeight(), s, l), l, false, s);
    }

    public static int preview(GuiGraphics g, Font font, int x, int y, Settings s) {
        Label l = label(s);
        int raw = rawWidth(font, s, l);
        draw(g, font, new Box(x, y, raw, H, raw, 1f), l, false, s);
        return raw;
    }

    static Label label(Settings s) {
        long saved = Recorder.lastSavedWall(), sinceSave = System.currentTimeMillis() - saved;
        if (saved > 0 && sinceSave < 2500) return new Label(Recorder.lastSavedAuto() ? t("Бой сохранён", "Fight saved")
                : t("Сохранено", "Saved"), GREEN, false, false);
        String now = Recorder.clock(Recorder.shownMs()), cap = Recorder.clock(Recorder.capacityMs());
        boolean time = s.hudFormat == Settings.HUD_TIME;
        if (!Recorder.active()) return new Label("REC " + now + t(" пауза", " paused"), GREY, false, true);
        if (Recorder.full()) return new Label(time ? "REC " + cap : "REC " + cap + t(" · перезапись", " · overwriting"), RED, true, false);
        return new Label(time ? "REC " + now : "REC " + now + " / " + cap, RED, true, false);
    }

    private static boolean showDot(Settings s) {
        return s.hudShowDot || s.hudFormat == Settings.HUD_DOT;
    }

    private static boolean showText(Settings s) {
        return s.hudFormat != Settings.HUD_DOT;
    }

    static int rawWidth(Font font, Settings s, Label l) {
        int w = PAD + PAD;
        if (showDot(s)) w += DOT;
        if (showText(s)) w += font.width(l.text()) + (showDot(s) ? GAP : 0);
        return w;
    }

    static Box box(Font font, int screenW, int screenH, Settings s, Label l) {
        float sc = (float) s.hudScale;
        int raw = rawWidth(font, s, l);
        int w = Math.round(raw * sc), h = Math.round(H * sc);
        int x = (int) Math.round(s.hudX * Math.max(0, screenW - w));
        int y = (int) Math.round(s.hudY * Math.max(0, screenH - h));
        return new Box(x, y, w, h, raw, sc);
    }

    static void draw(GuiGraphics g, Font font, Box b, Label l, boolean highlight, Settings s) {
        g.pose().pushMatrix();
        g.pose().translate((float) b.x(), (float) b.y());
        g.pose().scale(b.scale(), b.scale());
        int w = b.rawW();
        int alpha = Math.round(s.hudBgOpacity * 255 / 100f);
        if (alpha > 0) roundRect(g, 0, 0, w, H, alpha << 24);
        int tx = PAD;
        if (showDot(s)) {
            boolean on = !l.blink() || !s.hudBlink || (System.currentTimeMillis() / 600) % 2 == 0;
            circle(g, PAD, (H - DOT) / 2, DOT, on ? l.state() : (l.state() & 0x00FFFFFF) | 0x55000000);
            tx += DOT + GAP;
        }
        if (showText(s)) {
            int color = switch (s.hudTextColor) {
                case Settings.HUD_COLOR_WHITE -> WHITE;
                case Settings.HUD_COLOR_GREY -> GREY;
                default -> l.state();
            };
            g.drawString(font, l.text(), tx, 3, color, s.hudShadow);
        }
        if (highlight) g.renderOutline(-1, -1, w + 2, H + 2, 0xFFFAF9F5);
        g.pose().popMatrix();
    }

    static void roundRect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    static void circle(GuiGraphics g, int x, int y, int d, int color) {
        double r = d / 2.0;
        for (int row = 0; row < d; row++) {
            double cy = row + 0.5 - r;
            double hw = Math.sqrt(Math.max(0, r * r - cy * cy));
            int x1 = (int) Math.round(r - hw), x2 = (int) Math.round(r + hw);
            if (x2 > x1) g.fill(x + x1, y + row, x + x2, y + row + 1, color);
        }
    }
}
