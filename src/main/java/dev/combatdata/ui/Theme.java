package dev.combatdata.ui;

import dev.combatdata.core.ThemeSpec;
import dev.combatdata.core.Themes;

/** Colors come from the current ThemeSpec, set at the start of every menu frame so draft changes show immediately. */
public final class Theme {
    public static int WINDOW, BORDER, PANEL, INSET;
    public static int RAISED, RAISED_HOVER, ROW_HOVER;
    public static int TEXT, TEXT_STRONG, MUTED, DIM;
    public static int ACCENT, ACCENT_HOVER, ON_ACCENT;
    public static int GREEN, RED, AMBER, BLUE;
    public static int SWITCH_OFF, KNOB;
    public static final int SCRIM = 0xA0000000;
    public static int CTRL_HOVER;
    public static int FILTERED;
    public static int GRID, GRID_5;
    public static boolean dark;
    public static final int R_WINDOW = 6, R_PANEL = 4, R_CTRL = 3;

    private static ThemeSpec current;

    static {
        apply(Themes.defaultTheme());
    }

    private Theme() {
    }

    public static void apply(ThemeSpec s) {
        if (s == null || s.equals(current)) return;
        current = s;
        WINDOW = s.color(ThemeSpec.WINDOW);
        PANEL = s.color(ThemeSpec.PANEL);
        INSET = s.color(ThemeSpec.INSET);
        BORDER = s.color(ThemeSpec.BORDER);
        RAISED = s.color(ThemeSpec.RAISED);
        TEXT = s.color(ThemeSpec.TEXT);
        MUTED = s.color(ThemeSpec.MUTED);
        ACCENT = s.color(ThemeSpec.ACCENT);
        GREEN = s.color(ThemeSpec.GREEN);
        RED = s.color(ThemeSpec.RED);
        AMBER = s.color(ThemeSpec.AMBER);
        BLUE = s.color(ThemeSpec.BLUE);
        dark = Themes.luma(WINDOW) < Themes.luma(TEXT);
        // Ratios chosen so the "Terracotta" theme matches the original palette.
        RAISED_HOVER = Themes.mix(RAISED, TEXT, 0.07);
        ROW_HOVER = Themes.mix(PANEL, TEXT, 0.03);
        TEXT_STRONG = Themes.mix(TEXT, dark ? 0xFFFFFFFF : 0xFF000000, 0.5);
        DIM = Themes.mix(MUTED, WINDOW, 0.48);
        ACCENT_HOVER = Themes.mix(ACCENT, 0xFFFFFFFF, 0.15);
        ON_ACCENT = Themes.luma(ACCENT) > 0.5 ? 0xFF1F1E1D : 0xFFFFFFFF;
        SWITCH_OFF = Themes.mix(BORDER, TEXT, 0.09);
        KNOB = dark ? TEXT_STRONG : 0xFFFFFFFF;
        CTRL_HOVER = Themes.mix(BORDER, TEXT, 0.16);
        FILTERED = Themes.mix(DIM, PANEL, 0.3);
        GRID = Themes.mix(INSET, TEXT, 0.05);
        GRID_5 = Themes.mix(INSET, TEXT, 0.11);
    }

    public static ThemeSpec current() {
        return current;
    }

    public static int alpha(int c, int alpha) {
        return Themes.alpha(c, alpha);
    }

    public static int faded(int c, double f) {
        return Themes.mix(c, PANEL, f);
    }
}
