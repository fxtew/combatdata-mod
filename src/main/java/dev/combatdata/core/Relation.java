package dev.combatdata.core;

import java.util.UUID;

/**
 * Many servers use scoreboard teams only for tab sorting and name colors, putting random players together.
 * So only a shared team with friendly fire disabled (SAME_TEAM) counts as teammates.
 */
public final class Relation {
    public static final int TEAM_UNKNOWN = -1;
    public static final int TEAM_NONE = 0;
    public static final int TEAM_SAME_FF = 1;
    public static final int TEAM_SAME = 2;

    private Relation() {
    }

    public static boolean teammate(int team) {
        return team == TEAM_SAME;
    }

    /** Servers often mark bots and NPCs with version 2 UUIDs (real players use 4 or 3). */
    public static boolean npc(UUID id) {
        return id != null && id.version() == 2;
    }
}
