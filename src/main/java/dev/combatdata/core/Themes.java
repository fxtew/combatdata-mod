package dev.combatdata.core;

import java.util.List;
import java.util.Locale;

import static dev.combatdata.core.Lang.t;

public final class Themes {
    public static final String DEFAULT_ID = "terracotta";
    public static final int MAX_CUSTOM = 20, MAX_NAME = 24;
    /** Theme code: "cdtheme1;Name;262624,2E2D2B,..." (12 colors in ThemeSpec.KEYS order). */
    public static final String CODE_PREFIX = "cdtheme1;";
    private static final String CUSTOM_PREFIX = "custom-";

    public static final List<ThemeSpec> BUILTIN = List.of(
            new ThemeSpec("terracotta", "Terracotta", 0x262624, 0x2E2D2B, 0x1F1E1D, 0x3A3936, 0x34332F, 0xE8E6DE,
                    0xA8A69D, 0xD97757, 0x7FB285, 0xE07A6D, 0xE3B15C, 0x6FA8DC),
            new ThemeSpec("obsidian", "Obsidian", 0x15131C, 0x1C1A26, 0x0F0E14, 0x2E2A3D, 0x262335, 0xE6E1F2,
                    0x9D96B3, 0xA78BFA, 0x6FCF97, 0xF07178, 0xF2C66D, 0x7AA2F7),
            new ThemeSpec("prismarine", "Prismarine", 0x16232A, 0x1C2C33, 0x111B20, 0x2C424B, 0x24363E, 0xDCEBEA,
                    0x8FAAAA, 0x4FC1B0, 0x7FCB8A, 0xEF8A80, 0xE9C46A, 0x6CB4EE),
            new ThemeSpec("deepslate", "Deepslate", 0x1E1F22, 0x26272B, 0x18191B, 0x36383D, 0x2E3034, 0xE3E5E8,
                    0x9EA2A8, 0x5FC4D6, 0x76C68A, 0xE5736B, 0xE0B050, 0x8C9EF5),
            new ThemeSpec("nether", "Nether", 0x241718, 0x2D1D1F, 0x1B1112, 0x452B2E, 0x382427, 0xF0E0DC,
                    0xB39A96, 0xF0703A, 0x8CC084, 0xFF6B6B, 0xF2B84B, 0x7FA7E0),
            new ThemeSpec("quartz", "Quartz", 0xF3F1EC, 0xFBFAF7, 0xE7E4DD, 0xD3CFC6, 0xE9E6DF, 0x2B2A27,
                    0x6E6B64, 0xC2552F, 0x2E8B47, 0xC8382B, 0xB07A10, 0x2F6FB5));

    private Themes() {
    }

    public static ThemeSpec builtin(String id) {
        for (ThemeSpec s : BUILTIN) if (s.id().equals(id)) return s;
        return null;
    }

    public static boolean isBuiltin(String id) {
        return builtin(id) != null;
    }

    public static ThemeSpec defaultTheme() {
        return BUILTIN.get(0);
    }

    public static ThemeSpec find(String id, List<ThemeSpec> custom) {
        if (id != null) {
            for (ThemeSpec s : custom) if (s.id().equals(id)) return s;
            ThemeSpec b = builtin(id);
            if (b != null) return b;
        }
        return defaultTheme();
    }

    public static String displayName(ThemeSpec s) {
        return switch (s.id()) {
            case "terracotta" -> t("Терракота", "Terracotta");
            case "obsidian" -> t("Обсидиан", "Obsidian");
            case "prismarine" -> t("Призмарин", "Prismarine");
            case "deepslate" -> t("Глубинный сланец", "Deepslate");
            case "nether" -> t("Незер", "Nether");
            case "quartz" -> t("Кварц (светлая)", "Quartz (light)");
            default -> s.name();
        };
    }

    public static String newId(List<ThemeSpec> custom) {
        for (int n = 1; ; n++) {
            String id = CUSTOM_PREFIX + n;
            boolean used = false;
            for (ThemeSpec s : custom) if (s.id().equals(id)) used = true;
            if (!used) return id;
        }
    }

    public static String uniqueName(String wanted, List<ThemeSpec> custom, String exceptId) {
        String base = cleanName(wanted);
        if (base.isEmpty()) base = t("Своя тема", "My theme");
        String name = base;
        for (int n = 2; taken(name, custom, exceptId); n++) {
            String suffix = " " + n;
            name = base.substring(0, Math.min(base.length(), MAX_NAME - suffix.length())) + suffix;
        }
        return name;
    }

    private static boolean taken(String name, List<ThemeSpec> custom, String exceptId) {
        for (ThemeSpec s : custom) if (!s.id().equals(exceptId) && s.name().equalsIgnoreCase(name)) return true;
        return false;
    }

    public static String cleanName(String s) {
        if (s == null) return "";
        String c = s.replace(';', ' ').replaceAll("\\p{Cntrl}", "").trim();
        return c.length() > MAX_NAME ? c.substring(0, MAX_NAME).trim() : c;
    }

    public static ThemeSpec copyOf(ThemeSpec base, List<ThemeSpec> custom) {
        return new ThemeSpec(newId(custom), uniqueName(displayName(base), custom, null), base.colors());
    }


    public static String hex(int argb) {
        return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }

    /** Accepts "#RGB", "#RRGGBB", "RRGGBB", "0xRRGGBB"; returns -1 if invalid. */
    public static int parseHex(String s) {
        if (s == null) return -1;
        String h = s.trim();
        if (h.startsWith("#")) h = h.substring(1);
        else if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        if (h.length() != 6) return -1;
        for (int i = 0; i < 6; i++) if (Character.digit(h.charAt(i), 16) < 0) return -1;
        return Integer.parseInt(h, 16);
    }

    public static String toCode(ThemeSpec s) {
        StringBuilder sb = new StringBuilder(CODE_PREFIX).append(cleanName(displayName(s))).append(';');
        for (int i = 0; i < ThemeSpec.COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(hex(s.color(i)).substring(1));
        }
        return sb.toString();
    }

    public static ThemeSpec fromCode(String code) {
        if (code == null) return null;
        String c = code.trim();
        if (!c.startsWith(CODE_PREFIX)) return null;
        String[] parts = c.substring(CODE_PREFIX.length()).split(";", -1);
        if (parts.length != 2) return null;
        String[] hexes = parts[1].split(",");
        if (hexes.length != ThemeSpec.COUNT) return null;
        int[] colors = new int[ThemeSpec.COUNT];
        for (int i = 0; i < hexes.length; i++) {
            colors[i] = parseHex(hexes[i]);
            if (colors[i] < 0) return null;
        }
        return new ThemeSpec("", cleanName(parts[0]), colors);
    }


    public static int mix(int a, int b, double f) {
        int r = ch(a, 16, b, f), g = ch(a, 8, b, f), bl = ch(a, 0, b, f);
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }

    private static int ch(int a, int shift, int b, double f) {
        int x = (a >> shift) & 0xFF, y = (b >> shift) & 0xFF;
        return (int) Math.round(x + (y - x) * f);
    }

    /** Luma 0..1 (Rec. 601 weights). */
    public static double luma(int c) {
        return (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF)) / 255.0;
    }

    public static int alpha(int c, int alpha) {
        return (alpha << 24) | (c & 0xFFFFFF);
    }

    public static double[] toHsl(int c) {
        double r = ((c >> 16) & 0xFF) / 255.0, g = ((c >> 8) & 0xFF) / 255.0, b = (c & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), l = (max + min) / 2, d = max - min;
        if (d < 1e-9) return new double[]{0, 0, l};
        double s = d / (1 - Math.abs(2 * l - 1));
        double h;
        if (max == r) h = 60 * (((g - b) / d) % 6);
        else if (max == g) h = 60 * ((b - r) / d + 2);
        else h = 60 * ((r - g) / d + 4);
        if (h < 0) h += 360;
        return new double[]{h, Math.min(1, s), l};
    }

    public static int fromHsl(double h, double s, double l) {
        h = ((h % 360) + 360) % 360;
        s = Math.max(0, Math.min(1, s));
        l = Math.max(0, Math.min(1, l));
        double c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = l - c / 2;
        double r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        int ri = (int) Math.round((r + m) * 255), gi = (int) Math.round((g + m) * 255), bi = (int) Math.round((b + m) * 255);
        return (ri << 16) | (gi << 8) | bi;
    }
}
