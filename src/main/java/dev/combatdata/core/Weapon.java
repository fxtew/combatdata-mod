package dev.combatdata.core;

/** Detected via item tags (SWORDS, AXES, SPEARS); the mace has no tag. UNKNOWN: old recordings that only stored "not a sword". */
public enum Weapon {
    SWORD, AXE, MACE, SPEAR, HAND, OTHER, UNKNOWN;

    public static Weapon fromSwordFlag(boolean sword) {
        return sword ? SWORD : UNKNOWN;
    }

    public static Weapon parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
