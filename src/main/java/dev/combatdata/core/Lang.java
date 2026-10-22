package dev.combatdata.core;

/** Strings live next to the code as t("ru", "en") pairs so translations are not lost. AUTO follows the game language. */
public final class Lang {
    public static final int AUTO = 0, RU = 1, EN = 2;
    private static volatile boolean english;

    private Lang() {
    }

    public static boolean english() {
        return english;
    }

    public static String t(String ru, String en) {
        return english ? en : ru;
    }

    public static boolean resolveEnglish(int setting, String mcCode) {
        if (setting == RU) return false;
        if (setting == EN) return true;
        return mcCode == null || !mcCode.toLowerCase(java.util.Locale.ROOT).startsWith("ru");
    }

    public static void apply(int setting, String mcCode) {
        english = resolveEnglish(setting, mcCode);
    }

    public static void setEnglish(boolean en) {
        english = en;
    }

    public static String plural(long n, String one, String few, String many) {
        long a = Math.abs(n) % 100, b = a % 10;
        if (a >= 11 && a <= 14) return many;
        if (b == 1) return one;
        if (b >= 2 && b <= 4) return few;
        return many;
    }
}
