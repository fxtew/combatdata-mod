package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.Settings;
import static dev.combatdata.core.Lang.t;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Position is saved immediately, bypassing the Apply/Cancel settings draft. */
public final class HudPositionScreen extends Screen {
    private final Screen parent;
    private final boolean fight;
    private final Settings draft;
    private boolean dragging;
    private double grabX, grabY;

    public HudPositionScreen(Screen parent) {
        this(parent, false, null);
    }

    public HudPositionScreen(Screen parent, boolean fight, Settings draft) {
        super(Component.literal(fight ? t("Положение итога боя", "Fight summary position") : t("Положение надписи", "Label position")));
        this.parent = parent;
        this.fight = fight;
        this.draft = draft;
    }

    private Settings style() {
        Settings s = draft != null ? draft : CombatDataClient.settings();
        Settings l = CombatDataClient.settings();
        s.fsX = l.fsX;
        s.fsY = l.fsY;
        s.hudX = l.hudX;
        s.hudY = l.hudY;
        return s;
    }

    @Override
    protected void init() {
        Settings s = CombatDataClient.settings();
        addRenderableWidget(Button.builder(Component.literal(t("Готово", "Done")), b -> onClose())
                .bounds(width / 2 - 104, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(t("По умолчанию", "Default")), b -> {
            if (fight) {
                s.fsX = new Settings().fsX;
                s.fsY = new Settings().fsY;
            } else {
                s.hudX = Settings.DEFAULT_HUD_X;
                s.hudY = Settings.DEFAULT_HUD_Y;
            }
        }).bounds(width / 2 + 4, height - 28, 100, 20).build());
    }

    private int[] box() {
        Settings s = style();
        if (fight) {
            FightHud.Box b = FightHud.sampleBox(font, width, height, s);
            return new int[]{b.x(), b.y(), b.w(), b.h()};
        }
        RecHud.Box b = RecHud.box(font, width, height, s, RecHud.label(s));
        return new int[]{b.x(), b.y(), b.w(), b.h()};
    }

    private static boolean inside(int[] b, double mx, double my) {
        return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        String hint = fight ? t("Перетащите итог боя мышью", "Drag the fight summary with the mouse")
                : t("Перетащите надпись мышью", "Drag the label with the mouse");
        g.drawString(font, hint, (width - font.width(hint)) / 2, 12, 0xFFFFFFFF, true);
        Settings s = style();
        int[] b = box();
        boolean hl = dragging || inside(b, mouseX, mouseY);
        if (fight) {
            FightHud.drawSample(g, font, FightHud.sampleBox(font, width, height, s), s, hl);
        } else {
            RecHud.Label l = RecHud.label(s);
            RecHud.draw(g, font, RecHud.box(font, width, height, s, l), l, hl, s);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int[] b = box();
        if (event.button() == 0 && inside(b, event.x(), event.y())) {
            dragging = true;
            grabX = event.x() - b[0];
            grabY = event.y() - b[1];
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            Settings s = CombatDataClient.settings();
            int[] b = box();
            double freeW = Math.max(1, width - b[2]), freeH = Math.max(1, height - b[3]);
            double fx = Math.max(0, Math.min(1, (event.x() - grabX) / freeW)), fy = Math.max(0, Math.min(1, (event.y() - grabY) / freeH));
            if (fight) {
                s.fsX = fx;
                s.fsY = fy;
            } else {
                s.hudX = fx;
                s.hudY = fy;
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging && event.button() == 0) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
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
