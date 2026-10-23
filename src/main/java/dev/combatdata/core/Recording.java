package dev.combatdata.core;

import java.util.ArrayList;
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

    /** Time of the first attack or hit taken, or -1 if there is none. */
    public long firstCombatMs() {
        for (Event e : events) if (e instanceof AttackEvent || e instanceof IncomingHitEvent) return e.t();
        return -1;
    }

    /**
     * Drops everything before fromMs, which becomes the new 0. The server segment in effect at that moment
     * is moved to 0 so the recording still knows where it was made.
     */
    public Recording trimmedFrom(long fromMs) {
        long from = Math.min(fromMs, durationMs);
        if (from <= 0) return this;
        SegmentEvent seg = null;
        boolean segAtStart = false;
        for (Event e : events) {
            if (e.t() < from && e instanceof SegmentEvent s) seg = s;
            if (e.t() == from && e instanceof SegmentEvent) segAtStart = true;
        }
        List<Event> out = new ArrayList<>();
        if (seg != null && !segAtStart) out.add(seg.withT(0));
        for (Event e : events) if (e.t() >= from) out.add(e.withT(e.t() - from));
        long wall = seg != null && seg.wallStartMs() > 0 ? seg.wallStartMs() + (from - seg.t()) : wallStartMs + from;
        return new Recording(schema, player, playerUuid, mcVersion, wall, durationMs - from, out, notRecorded);
    }
}
