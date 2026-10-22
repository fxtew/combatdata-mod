package dev.combatdata.core;

import java.util.List;
import java.util.UUID;

/** Event times are normalized: 0 is the start of the recording. */
public record Recording(int schema, String player, UUID playerUuid, String mcVersion, long wallStartMs,
                        long durationMs, List<Event> events, int notRecorded) {
    public static final int SCHEMA = 1;

    public Recording(int schema, String player, UUID playerUuid, String mcVersion, long wallStartMs, long durationMs,
                     List<Event> events) {
        this(schema, player, playerUuid, mcVersion, wallStartMs, durationMs, events, 0);
    }

    public Recording withNotRecorded(int mask) {
        return new Recording(schema, player, playerUuid, mcVersion, wallStartMs, durationMs, events, mask);
    }

    public boolean skipped(int feature) {
        return (notRecorded & feature) != 0;
    }
}
