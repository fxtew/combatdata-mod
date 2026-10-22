package dev.combatdata.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Favorites and the newest recording are never deleted. A limit of 0 means no limit. */
public final class Retention {
    public static final long DAY_MS = 86_400_000L;

    public record FileInfo(String id, long sizeBytes, long timeMs, boolean favorite) {
    }

    private Retention() {
    }

    public static List<String> toDelete(List<FileInfo> files, long maxBytes, int maxAgeDays, long nowMs) {
        List<FileInfo> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparingLong(FileInfo::timeMs));
        FileInfo newest = sorted.isEmpty() ? null : sorted.get(sorted.size() - 1);
        long total = 0;
        for (FileInfo f : sorted) total += f.sizeBytes();
        List<String> out = new ArrayList<>();
        for (FileInfo f : sorted) {
            if (f.favorite() || f == newest) continue;
            boolean old = maxAgeDays > 0 && nowMs - f.timeMs() > maxAgeDays * DAY_MS;
            boolean over = maxBytes > 0 && total > maxBytes;
            if (old || over) {
                out.add(f.id());
                total -= f.sizeBytes();
            }
        }
        return out;
    }

    public static long totalBytes(List<FileInfo> files) {
        long t = 0;
        for (FileInfo f : files) t += f.sizeBytes();
        return t;
    }
}
