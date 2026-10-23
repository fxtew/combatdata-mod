package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.FightSummary;
import dev.combatdata.core.Recording;
import dev.combatdata.core.Settings;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

public final class FightHud {
    private static final int PAD = 4, LINE = 10, FADE_MS = 500;
    private static final int TITLE = 0xFFA8A69D, TEXT = 0xFFF4F2EA;
    private static final int GOOD = 0xFF7FB285, MID = 0xFFE3B15C, BAD = 0xFFE07A6D;

    /** Last-hit time of the fight already summarized, so the card is not shown twice. */
    private static long checkedFor = Long.MIN_VALUE;
    private static FightSummary.Result shown;
    private static long shownAtWall;

    private record Seg(String text, int color) {
    }

    private record Card(int w, int h, List<List<Seg>> lines) {
    }

    public record Box(int x, int y, int w, int h, float scale) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private FightHud() {
    }

    /** The buffer clock restarted, so the remembered fight time no longer means anything. */
    static void newBuffer() {
        checkedFor = Long.MIN_VALUE;
    }

    public static void reset() {
        checkedFor = Long.MIN_VALUE;
        shown = null;
    }

    static void tick() {
        Settings s = CombatDataClient.settings();
        if (!s.fightSummary || !Recorder.active()) return;
        long last = Recorder.lastCombatMs(), now = Recorder.now(), quiet = s.fsDelaySec * 1000L;
        if (last < 0 || last > now || now - last < quiet || last == checkedFor) return;
        checkedFor = last;
        Recording rec = Recorder.liveCopy();
        FightSummary.Result r = FightSummary.last(rec, rec.durationMs(), quiet, s.jumpParams(), s.interpMs,
                s.statFilter(), s.tradeParams());
        if (r != null) {
            shown = r;
            shownAtWall = System.currentTimeMillis();
        }
    }

    public static void extract(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Settings s = CombatDataClient.settings();
        FightSummary.Result r = shown;
        if (r == null || !s.fightSummary || mc.options.hideGui || mc.player == null || mc.screen instanceof HudPositionScreen) return;
        long age = System.currentTimeMillis() - shownAtWall, show = s.fsShowSec * 1000L;
        if (age >= show) {
            shown = null;
            return;
        }
        float fade = Math.min(1f, (show - age) / (float) FADE_MS);
        Card c = card(mc.font, r, s);
        draw(g, mc.font, box(mc.font, g.guiWidth(), g.guiHeight(), s, c), c, s, fade, false);
    }

    public static void preview(GuiGraphics g, Font font, int x, int y, Settings s) {
        Card c = card(font, FightSummary.sample(), s);
        draw(g, font, new Box(x, y, c.w(), c.h(), 1f), c, s, 1f, false);
    }

    public static int previewWidth(Font font, Settings s) {
        return card(font, FightSummary.sample(), s).w();
    }

    public static int previewHeight(Font font, Settings s) {
        return card(font, FightSummary.sample(), s).h();
    }

    static Box sampleBox(Font font, int screenW, int screenH, Settings s) {
        return box(font, screenW, screenH, s, card(font, FightSummary.sample(), s));
    }

    static void drawSample(GuiGraphics g, Font font, Box b, Settings s, boolean highlight) {
        draw(g, font, b, card(font, FightSummary.sample(), s), s, 1f, highlight);
    }

    private static Box box(Font font, int screenW, int screenH, Settings s, Card c) {
        float sc = (float) s.fsScale;
        int w = Math.round(c.w() * sc), h = Math.round(c.h() * sc);
        if (!s.fsNearRec) {
            int x = (int) Math.round(s.fsX * Math.max(0, screenW - w)), y = (int) Math.round(s.fsY * Math.max(0, screenH - h));
            return new Box(x, y, w, h, sc);
        }
        RecHud.Box rec = RecHud.box(font, screenW, screenH, s, RecHud.label(s));
        int x = Math.max(0, Math.min(rec.x(), screenW - w));
        int y = rec.y() + rec.h() / 2 < screenH / 2 ? rec.y() + rec.h() + 3 : rec.y() - h - 3;
        return new Box(x, Math.max(0, Math.min(y, screenH - h)), w, h, sc);
    }

    private static Card card(Font font, FightSummary.Result r, Settings s) {
        FightSummary.Style st = s.fightStyle();
        List<FightSummary.Part> parts = FightSummary.parts(r, st);
        List<List<Seg>> lines = new ArrayList<>();
        String title = st.title() ? FightSummary.title(r) : null;
        if (st.layout() == FightSummary.LAYOUT_COLUMN) {
            if (title != null) lines.add(List.of(new Seg(title, TITLE)));
            for (FightSummary.Part p : parts) {
                List<Seg> line = new ArrayList<>();
                addPart(line, p, st.colors());
                lines.add(line);
            }
        } else {
            boolean one = st.layout() == FightSummary.LAYOUT_ONE_LINE;
            if (title != null && !one) lines.add(List.of(new Seg(title, TITLE)));
            List<Seg> line = new ArrayList<>();
            if (title != null && one) line.add(new Seg(title, TITLE));
            for (FightSummary.Part p : parts) {
                if (!line.isEmpty()) line.add(new Seg(" · ", TITLE));
                addPart(line, p, st.colors());
            }
            if (!line.isEmpty()) lines.add(line);
        }
        if (lines.isEmpty()) lines.add(List.of(new Seg(dev.combatdata.core.Lang.t("Бой окончен", "Fight over"), TEXT)));
        int w = 0;
        for (List<Seg> l : lines) {
            int lw = 0;
            for (Seg sg : l) lw += font.width(sg.text());
            w = Math.max(w, lw);
        }
        return new Card(w + PAD * 2, PAD * 2 + LINE * lines.size() - 2, lines);
    }

    private static void addPart(List<Seg> line, FightSummary.Part p, boolean colors) {
        int vc = !colors ? TEXT : switch (p.quality()) {
            case FightSummary.GOOD -> GOOD;
            case FightSummary.MID -> MID;
            case FightSummary.BAD -> BAD;
            default -> TEXT;
        };
        if (!p.before().isEmpty()) line.add(new Seg(p.before(), TEXT));
        line.add(new Seg(p.value(), vc));
        if (!p.after().isEmpty()) line.add(new Seg(p.after(), TEXT));
    }

    private static void draw(GuiGraphics g, Font font, Box b, Card c, Settings s, float fade, boolean highlight) {
        g.pose().pushMatrix();
        g.pose().translate((float) b.x(), (float) b.y());
        g.pose().scale(b.scale(), b.scale());
        int alpha = Math.round(s.fsBgOpacity * 255 / 100f * fade);
        if (alpha > 0) RecHud.roundRect(g, 0, 0, c.w(), c.h(), alpha << 24);
        // Without a background the text needs a shadow to stay readable against a bright sky.
        boolean shadow = s.fsBgOpacity < 15;
        int y = PAD;
        for (List<Seg> line : c.lines()) {
            int x = PAD;
            for (Seg sg : line) {
                g.drawString(font, sg.text(), x, y, fadeColor(sg.color(), fade), shadow);
                x += font.width(sg.text());
            }
            y += LINE;
        }
        if (highlight) g.renderOutline(-1, -1, c.w() + 2, c.h() + 2, 0xFFFAF9F5);
        g.pose().popMatrix();
    }

    private static int fadeColor(int c, float fade) {
        int a = Math.max(4, Math.round(((c >>> 24) & 0xFF) * fade));
        return (a << 24) | (c & 0xFFFFFF);
    }
}
