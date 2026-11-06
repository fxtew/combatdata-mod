package dev.combatdata.core;

/**
 * Maps capture time (runs whenever the player is in a world) to buffer time, which stands still while recording is
 * switched off. Events captured while off are dropped, so the gap does not appear in the buffer at all.
 */
public final class BufferTimeline {
    private long offset, offAt;
    private boolean on;

    public BufferTimeline(boolean on) {
        this.on = on;
    }

    public synchronized boolean on() {
        return on;
    }

    public synchronized long bufferMs(long captureMs) {
        return Math.max(0L, (on ? captureMs : offAt) - offset);
    }

    /** Returns true if the state changed. */
    public synchronized boolean set(boolean value, long captureMs) {
        if (value == on) return false;
        if (value) offset += captureMs - offAt;
        else offAt = captureMs;
        on = value;
        return true;
    }

    /** Buffer time starts over at 0 now (after a save or a reset). */
    public synchronized void restart(long captureMs) {
        offset = captureMs;
        offAt = captureMs;
    }

    /** The event moved to buffer time, or null while recording is off. */
    public synchronized Event map(Event e) {
        if (!on) return null;
        return e.withT(Math.max(0L, e.t() - offset));
    }
}
