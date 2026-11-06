package dev.combatdata.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ring buffer ordered by time; events older than the capacity are evicted. Thread-safe.
 * Evicted events stay in the list before head until they make up half of it, so a trim does not shift the whole
 * buffer every tick.
 */
public final class RecordingBuffer {
    private final ArrayList<Event> events = new ArrayList<>();
    private int head;
    private SegmentEvent carriedSegment;
    private long version;
    private int combat;

    private static boolean combat(Event e) {
        return e instanceof AttackEvent || e instanceof IncomingHitEvent || e instanceof JumpEvent;
    }

    /** Keeps t order: a hit confirmation arrives after the hit itself. */
    public synchronized void add(Event e) {
        int i = events.size();
        while (i > head && events.get(i - 1).t() > e.t()) i--;
        events.add(i, e);
        // Ping, key and movement events barely affect stats, so they don't trigger a recompute.
        if (!(e instanceof PingEvent || e instanceof InputEvent || e instanceof PositionEvent || e instanceof OtherStateEvent
                || e instanceof OtherAnimEvent)) version++;
        if (combat(e)) combat++;
    }

    public synchronized void trim(long nowActiveMs, long capacityMs) {
        long cutoff = nowActiveMs - capacityMs;
        int n = head;
        while (n < events.size() && events.get(n).t() < cutoff) {
            if (events.get(n) instanceof SegmentEvent s) carriedSegment = s;
            if (combat(events.get(n))) combat--;
            n++;
        }
        if (n == head) return;
        head = n;
        version++;
        if (head > events.size() / 2) {
            events.subList(0, head).clear();
            head = 0;
        }
    }

    public synchronized boolean hasCombatEvents() {
        return combat > 0;
    }

    /** Hits dealt and taken, uncopied: enough to find fights without copying the whole buffer. */
    public synchronized List<Event> combatEvents() {
        List<Event> out = new ArrayList<>(Math.max(0, combat));
        for (int i = head; i < events.size(); i++) {
            Event e = events.get(i);
            if (e instanceof AttackEvent || e instanceof IncomingHitEvent) out.add(e);
        }
        return out;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized int size() {
        return events.size() - head;
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
        int start = head;
        while (start < events.size() && events.get(start).t() < cutoff) {
            if (events.get(start) instanceof SegmentEvent s) carried = s;
            start++;
        }
        List<Event> out = new ArrayList<>(events.size() - start + 1);
        boolean hasSegmentAtStart = false;
        for (int i = start; i < events.size() && events.get(i).t() <= base; i++) {
            if (events.get(i) instanceof SegmentEvent) hasSegmentAtStart = true;
        }
        if (!hasSegmentAtStart && carried != null) out.add(carried.withT(0));
        // The buffer clock stops while disconnected, so "now minus duration" is too late after a pause.
        // A segment stores the wall time it began; the start is that plus the active time since it.
        SegmentEvent ref = carried;
        for (int i = start; i < events.size() && events.get(i).t() <= base; i++) {
            if (events.get(i) instanceof SegmentEvent s) ref = s;
        }
        long wallStart = ref != null && ref.wallStartMs() > 0 && ref.t() <= base ? ref.wallStartMs() + (base - ref.t())
                : wallNowMs - duration;
        for (int i = start; i < events.size(); i++) {
            Event e = events.get(i);
            // Most events already sit at their buffer time when the buffer has not wrapped yet.
            out.add(base == 0 ? e : e.withT(Math.max(0L, e.t() - base)));
        }
        return new Recording(Recording.SCHEMA, player, playerUuid, mcVersion, wallStart, duration, out);
    }

    public synchronized void clear() {
        events.clear();
        head = 0;
        carriedSegment = null;
        combat = 0;
        version++;
    }
}
