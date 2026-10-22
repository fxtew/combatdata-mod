package dev.combatdata.core;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 12 base colors; hover shades, muted text and on-accent text are derived (ui/Theme.apply). */
public final class ThemeSpec {
    public static final int WINDOW = 0, PANEL = 1, INSET = 2, BORDER = 3, RAISED = 4, TEXT = 5, MUTED = 6, ACCENT = 7,
            GREEN = 8, RED = 9, AMBER = 10, BLUE = 11, COUNT = 12;
    public static final String[] KEYS = {"window", "panel", "inset", "border", "raised", "text", "muted", "accent",
            "green", "red", "amber", "blue"};

    private final String id, name;
    private final int[] colors;

    public ThemeSpec(String id, String name, int... colors) {
        if (colors.length != COUNT) throw new IllegalArgumentException("нужно " + COUNT + " цветов");
        this.id = id;
        this.name = name;
        this.colors = new int[COUNT];
        for (int i = 0; i < COUNT; i++) this.colors[i] = 0xFF000000 | colors[i];
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int color(int role) {
        return colors[role];
    }

    public int[] colors() {
        return colors.clone();
    }

    public ThemeSpec withColor(int role, int rgb) {
        int[] c = colors.clone();
        c[role] = rgb;
        return new ThemeSpec(id, name, c);
    }

    public ThemeSpec withName(String n) {
        return new ThemeSpec(id, n, colors);
    }

    public ThemeSpec withId(String i) {
        return new ThemeSpec(i, name, colors);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        Map<String, Object> c = new LinkedHashMap<>();
        for (int i = 0; i < COUNT; i++) c.put(KEYS[i], Themes.hex(colors[i]));
        m.put("colors", c);
        return m;
    }

    /** Missing or invalid colors fall back to base. */
    public static ThemeSpec fromMap(Object o, ThemeSpec base) {
        if (!(o instanceof Map<?, ?> m) || !(m.get("id") instanceof String id) || id.isBlank()) return null;
        String name = m.get("name") instanceof String s && !s.isBlank() ? s : id;
        int[] c = base.colors();
        if (m.get("colors") instanceof Map<?, ?> cm) {
            for (int i = 0; i < COUNT; i++) {
                int v = cm.get(KEYS[i]) instanceof String h ? Themes.parseHex(h) : -1;
                if (v >= 0) c[i] = v;
            }
        }
        return new ThemeSpec(id, name, c);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ThemeSpec t && t.id.equals(id) && t.name.equals(name) && Arrays.equals(t.colors, colors);
    }

    @Override
    public int hashCode() {
        return (id.hashCode() * 31 + name.hashCode()) * 31 + Arrays.hashCode(colors);
    }

    @Override
    public String toString() {
        return id;
    }
}
