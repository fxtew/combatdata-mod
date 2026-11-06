package dev.combatdata.client;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** The mod's one pool for heavy work off the render thread. Writing recordings keeps its own single thread. */
public final class Tasks {
    private static final AtomicInteger N = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "combatdata-task-" + N.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private Tasks() {
    }

    public static Future<?> run(Runnable r) {
        return POOL.submit(r);
    }

    /**
     * The latest result for a key. Requesting another key cancels the task still running for the previous one, and
     * its result is dropped. The render thread only reads.
     */
    public static final class Slot<T> {
        private String key;
        private Future<?> task;
        private volatile String valueKey;
        private volatile T value;
        private volatile boolean busy;

        public synchronized void request(String k, Callable<T> job) {
            if (k.equals(key)) return;
            if (task != null) task.cancel(true);
            key = k;
            busy = true;
            task = POOL.submit(() -> {
                try {
                    T v = job.call();
                    synchronized (this) {
                        if (k.equals(key)) {
                            value = v;
                            valueKey = k;
                        }
                    }
                } catch (Exception | LinkageError | StackOverflowError e) {
                    if (!Thread.currentThread().isInterrupted()) Safe.report("фоновая задача", e);
                } finally {
                    synchronized (this) {
                        if (k.equals(key)) busy = false;
                    }
                }
            });
        }

        /** The result for this key, or null while it is computed. */
        public T get(String k) {
            return k.equals(valueKey) ? value : null;
        }

        /** The last finished result, whatever its key: shown dimmed while the new one is computed. */
        public T latest() {
            return value;
        }

        public boolean busy() {
            return busy;
        }

        public synchronized void reset() {
            if (task != null) task.cancel(true);
            key = null;
            busy = false;
        }
    }
}
