package dev.combatdata.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** A cached summary is valid only if both the file's modification time and the filter key match. */
public final class ProgressCache {
    private record Entry(long modified, String key, Progress.Summary summary) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean dirty;

    public Progress.Summary get(String file, long modified, String key) {
        Entry e = entries.get(file);
        return e != null && e.modified() == modified && e.key().equals(key) ? e.summary() : null;
    }

    public void put(String file, long modified, String key, Progress.Summary s) {
        entries.put(file, new Entry(modified, key, s));
        dirty = true;
    }

    public void retain(Set<String> files) {
        if (entries.keySet().retainAll(files)) dirty = true;
    }

    public int size() {
        return entries.size();
    }

    public boolean dirty() {
        return dirty;
    }

    public static ProgressCache load(Path file) {
        ProgressCache c = new ProgressCache();
        try {
            if (!Files.exists(file)) return c;
            if (!(Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root)) return c;
            if (!(root.get("entries") instanceof Map<?, ?> m)) return c;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> v) || !(v.get("key") instanceof String key)
                        || !(v.get("mtime") instanceof Number mt)) continue;
                Progress.Summary s = Progress.fromJson(v.get("summary"));
                if (s != null) c.entries.put(String.valueOf(e.getKey()), new Entry(mt.longValue(), key, s));
            }
        } catch (IOException | RuntimeException ignored) {
            c.entries.clear();
        }
        return c;
    }

    public void save(Path file) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("mtime", e.getValue().modified());
            v.put("key", e.getValue().key());
            v.put("summary", Progress.toJson(e.getValue().summary()));
            m.put(e.getKey(), v);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("entries", m);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        dirty = false;
    }
}
