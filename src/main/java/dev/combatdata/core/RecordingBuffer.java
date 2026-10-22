package dev.combatdata.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Ring buffer ordered by time; events older than the capacity are evicted. Thread-safe. */
public final class RecordingBuffer {
    private final ArrayList<Event> events = new ArrayList<>();
    private SegmentEvent carriedSegment;
    private long version;
    private int combat;

    private static boolean combat(Event e) {
        return e instanceof AttackEvent || e instanceof IncomingHitEvent || e instanceof JumpEvent;
    }

    /** Keeps t order: a hit confirmation arrives after the hit itself. */
    public synchronized void add(Event e) {
        int i = events.size();
        while (i > 0 && events.get(i - 1).t() > e.t()) i--;
        events.add(i, e);
        // Ping and key events barely affect stats, so they don't trigger a recompute.
        if (!(e instanceof PingEvent || e instanceof InputEvent || e instanceof PositionEvent)) version++;
        if (combat(e)) combat++;
    }

    public synchronized void trim(long nowActiveMs, long capacityMs) {
        long cutoff = nowActiveMs - capacityMs;
        int n = 0;
        while (n < events.size() && events.get(n).t() < cutoff) {
            if (events.get(n) instanceof SegmentEvent s) carriedSegment = s;
            if (combat(events.get(n))) combat--;
            n++;
        }
        if (n > 0) {
            events.subList(0, n).clear();
            version++;
        }
    }

    public synchronized boolean hasCombatEvents() {
        return combat > 0;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized Recording snapshot(long nowActiveMs, long capacityMs, String player, UUID playerUuid,
                                           String mcVersion, long wallNowMs) {
        trim(nowActiveMs, capacityMs);
        return copy(nowActiveMs, capacityMs, player, playerUuid, mcVersion, wallNowMs);
    }

    /** Like snapshot() but without trimming the buffer. */
    public synchronized Recording copy(long nowActiveMs, long capacityMs, String player, UUID playerUuid,
                                       String mcVersion, long wallNowMs) {
        long cutoff = nowActiveMs - capacityMs;
        long base = Math.max(0L, cutoff);
        long duration = Math.min(nowActiveMs, capacityMs);
        SegmentEvent carried = carriedSegment;
        int start = 0;
        while (start < events.size() && events.get(start).t() < cutoff) {
            if (events.get(start) instanceof SegmentEvent s) carried = s;
            start++;
        }
        List<Event> out = new ArrayList<>(events.size() - start + 1);
        boolean hasSegmentAtStart = false;
        for (int i = start; i < events.size(); i++) {
            if (events.get(i) instanceof SegmentEvent && events.get(i).t() - base <= 0) hasSegmentAtStart = true;
        }
        if (!hasSegmentAtStart && carried != null) out.add(carried.withT(0));
        for (int i = start; i < events.size(); i++) out.add(events.get(i).withT(Math.max(0L, events.get(i).t() - base)));
        return new Recording(Recording.SCHEMA, player, playerUuid, mcVersion, wallNowMs - duration, duration, out);
    }

    public synchronized void clear() {
        events.clear();
        carriedSegment = null;
        combat = 0;
        version++;
    }
}
