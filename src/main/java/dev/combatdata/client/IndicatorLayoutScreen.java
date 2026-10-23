package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.HudAnchor;
import dev.combatdata.core.IndicatorConfig;
import dev.combatdata.core.IndicatorConfig.Kind;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import static dev.combatdata.core.Lang.t;

/**
 * Places all enabled indicators at once over the live game HUD. Drag to move, wheel to resize, right click to put one
 * back. Box centers snap to the screen center lines. Changes are saved on close.
 */
public final class IndicatorLayoutScreen extends Screen {
    private static final int SNAP = 4;
    private static final int GUIDE = 0x99E3B15C;
    private final Screen parent;
    private Kind dragging;
    private double grabX, grabY;
    private boolean snapX, snapY;

    public IndicatorLayoutScreen(Screen parent) {
        super(Component.literal(t("Расстановка индикаторов", "Indicator layout")));
        this.parent = parent;
    }

    private static IndicatorConfig cfg() {
        return CombatDataClient.settings().indicators;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal(t("Готово", "Done")), b -> onClose())
                .bounds(width / 2 - 104, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(t("Всё по умолчанию", "Reset all")), b -> {
            for (Kind k : Kind.values()) cfg().get(k).placeFrom(IndicatorConfig.defaultElement(k));
        }).bounds(width / 2 + 4, height - 28, 100, 20).build());
    }

    /** The game HUD stays visible: the health number is placed against the hunger bar. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0x30000000);
    }

    private Kind at(double mx, double my) {
        Kind[] all = Kind.values();
        for (int i = all.length - 1; i >= 0; i--) {
            Kind k = all[i];
            if (cfg().on(k) && IndicatorHud.sampleBox(font, k, cfg(), width, height).contains(mx, my)) return k;
        }
        return null;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        if (dragging != null && snapX) g.fill(width / 2, 0, width / 2 + 1, height, GUIDE);
        if (dragging != null && snapY) g.fill(0, height / 2, width, height / 2 + 1, GUIDE);
        Kind hover = dragging != null ? dragging : at(mouseX, mouseY);
        boolean any = false;
        for (Kind k : Kind.values()) {
            if (!cfg().on(k)) continue;
            any = true;
            IndicatorHud.drawSample(g, font, k, cfg(), IndicatorHud.sampleBox(font, k, cfg(), width, height), k == hover);
        }
        String hint = any ? t("Тяните мышью · колесо: размер · ПКМ: на место", "Drag to move · wheel: size · right click: default")
                : t("Включите индикаторы во вкладке «Индикаторы»", "Turn indicators on in the Indicators tab");
        g.drawString(font, hint, (width - font.width(hint)) / 2, 12, 0xFFFFFFFF, true);
        if (hover != null) {
            String sc = String.format(java.util.Locale.ROOT, "%.2fx", cfg().get(hover).scale);
            g.drawString(font, sc, (width - font.width(sc)) / 2, 24, 0xFFA8A69D, true);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        Kind k = at(event.x(), event.y());
        if (k != null && event.button() == 0) {
            IndicatorHud.Box b = IndicatorHud.sampleBox(font, k, cfg(), width, height);
            dragging = k;
            grabX = event.x() - b.x();
            grabY = event.y() - b.y();
            return true;
        }
        if (k != null && event.button() == 1) {
            cfg().get(k).placeFrom(IndicatorConfig.defaultElement(k));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging == null) return super.mouseDragged(event, dx, dy);
        IndicatorHud.Box b = IndicatorHud.sampleBox(font, dragging, cfg(), width, height);
        int x = (int) Math.round(event.x() - grabX), y = (int) Math.round(event.y() - grabY);
        x = Math.max(0, Math.min(x, width - b.w()));
        y = Math.max(0, Math.min(y, height - b.h()));
        snapX = Math.abs(x + b.w() / 2 - width / 2) <= SNAP;
        snapY = Math.abs(y + b.h() / 2 - height / 2) <= SNAP;
        if (snapX) x = width / 2 - b.w() / 2;
        if (snapY) y = height / 2 - b.h() / 2;
        setPlace(dragging, x, y, b.w(), b.h());
        return true;
    }

    private void setPlace(Kind k, int x, int y, int w, int h) {
        int[] a = HudAnchor.fromBox(x, y, w, h, width, height);
        IndicatorConfig.Element e = cfg().get(k);
        e.anchor = a[0];
        e.dx = a[1];
        e.dy = a[2];
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null && event.button() == 0) {
            dragging = null;
            snapX = snapY = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    /** Resizes around the box center so the indicator does not jump away from the cursor. */
    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        Kind k = dragging != null ? dragging : at(mx, my);
        if (k == null || scrollY == 0) return super.mouseScrolled(mx, my, scrollX, scrollY);
        IndicatorConfig.Element e = cfg().get(k);
        IndicatorHud.Box before = IndicatorHud.sampleBox(font, k, cfg(), width, height);
        double cx = before.x() + before.w() / 2.0, cy = before.y() + before.h() / 2.0;
        e.scale = Math.max(IndicatorConfig.MIN_SCALE, Math.min(IndicatorConfig.MAX_SCALE, e.scale + (scrollY > 0 ? 0.25 : -0.25)));
        IndicatorHud.Box after = IndicatorHud.sampleBox(font, k, cfg(), width, height);
        int x = (int) Math.round(cx - after.w() / 2.0), y = (int) Math.round(cy - after.h() / 2.0);
        setPlace(k, Math.max(0, Math.min(x, width - after.w())), Math.max(0, Math.min(y, height - after.h())), after.w(), after.h());
        return true;
    }

    @Override
    public void onClose() {
        CombatDataClient.saveSettings();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
