package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.*;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.*;

public final class ProgressData {
    public record Snapshot(String key, List<Progress.Summary> summaries, Map<String, Recordings.Entry> entries) {
    }

    private static volatile Snapshot snapshot;
    private static volatile boolean running, stale = true;
    private static volatile String status;
    private static volatile int version;
    private static String runningKey;

    private ProgressData() {
    }

    private static Path cacheFile() {
        return FabricLoader.getInstance().getGameDir().resolve("combatdata").resolve("progress-cache.json");
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static boolean running() {
        return running;
    }

    public static String status() {
        return status;
    }

    public static int version() {
        return version;
    }

    public static void invalidate() {
        stale = true;
    }

    public static synchronized void request(Settings s, boolean force) {
        StatFilter sf = s.statFilter();
        long gap = s.tradeGapMs;
        ServerProfiles profiles = CombatDataClient.profiles();
        String key = Progress.cacheKey(sf, s, profiles, gap);
        // A copy: the settings draft may change while the thread runs.
        Settings snap = s.copy();
        Snapshot cur = snapshot;
        if (running) {
            if (key.equals(runningKey)) return;
        } else if (!force && !stale && cur != null && cur.key().equals(key)) return;
        if (running) {
            stale = true;
            return;
        }
        running = true;
        stale = false;
        runningKey = key;
        Tasks.run(() -> run(key, sf, snap, profiles, gap));
    }

    private static void run(String key, StatFilter sf, Settings s, ServerProfiles profiles, long gap) {
        try {
            ProgressCache cache = ProgressCache.load(cacheFile());
            List<Recordings.Entry> list = Recordings.list();
            List<Progress.Summary> out = new ArrayList<>();
            Map<String, Recordings.Entry> byFile = new HashMap<>();
            Set<String> names = new HashSet<>();
            int i = 0;
            for (Recordings.Entry e : list) {
                i++;
                if (e.header() == null) continue;
                String name = e.file().getFileName().toString();
                names.add(name);
                byFile.put(name, e);
                Progress.Summary sum = cache.get(name, e.modified(), key);
                if (sum == null) {
                    status = i + " / " + list.size();
                    try {
                        Recording r = Cdrec.read(e.file());
                        sum = Progress.summarize(name, r, Rules.of(r.events(), profiles, s), sf, gap, true);
                        cache.put(name, e.modified(), key, sum);
                    } catch (Exception ex) {
                        CombatDataClient.LOG.warn("[CD] прогресс: не читается {}: {}", name, ex.toString());
                        continue;
                    }
                }
                out.add(sum);
            }
            cache.retain(names);
            if (cache.dirty()) {
                try {
                    cache.save(cacheFile());
                } catch (Exception ex) {
                    CombatDataClient.LOG.warn("[CD] прогресс: не удалось сохранить кэш: {}", ex.toString());
                }
            }
            out.sort(Comparator.comparingLong(Progress.Summary::wallStartMs));
            snapshot = new Snapshot(key, List.copyOf(out), Map.copyOf(byFile));
            version++;
        } catch (Exception ex) {
            CombatDataClient.LOG.error("[CD] прогресс: ошибка расчёта", ex);
        } finally {
            status = null;
            synchronized (ProgressData.class) {
                running = false;
                runningKey = null;
            }
        }
    }
}
