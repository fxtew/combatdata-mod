package dev.combatdata.core;

/** Counts only time spent in a world on a server (between resume and pause). */
public final class BufferClock {
    private long baseNs;
    private long runningSinceNs;
    private boolean running;

    public synchronized void resume(long nowNs) {
        if (!running) {
            running = true;
            runningSinceNs = nowNs;
        }
    }

    public synchronized void pause(long nowNs) {
        if (running) {
            baseNs += nowNs - runningSinceNs;
            running = false;
        }
    }

    public synchronized long activeMs(long nowNs) {
        long ns = baseNs + (running ? nowNs - runningSinceNs : 0L);
        return ns / 1_000_000L;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized void reset(long nowNs) {
        baseNs = 0L;
        runningSinceNs = nowNs;
    }
}
