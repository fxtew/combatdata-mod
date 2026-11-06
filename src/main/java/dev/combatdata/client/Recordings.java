package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.Cdrec;
import dev.combatdata.core.Favorites;
import dev.combatdata.core.Recording;
import dev.combatdata.core.Retention;
import dev.combatdata.core.Settings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import static dev.combatdata.core.Lang.t;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class Recordings {
    private static final Pattern DEFAULT_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_.*");
    private static Favorites favorites;

    /** header: null if the file is unreadable (corrupt or made by another program). sharedBy: the sender folder for a
     * recording someone shared, null for an own one. */
    public record Entry(Path file, String stem, Cdrec.Header header, long modified, long size, String sharedBy) {
        public Entry(Path file, String stem, Cdrec.Header header, long modified, long size) {
            this(file, stem, header, modified, size, null);
        }

        public boolean shared() {
            return sharedBy != null;
        }

        public boolean defaultName() {
            return DEFAULT_NAME.matcher(stem).matches();
        }

        public long time() {
            return header != null ? header.wallStartMs() : modified;
        }
    }

    public record Cleanup(int deleted, long bytes) {
    }

    private Recordings() {
    }

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("combatdata").resolve("recordings");
    }

    private static Path root() {
        return FabricLoader.getInstance().getGameDir().resolve("combatdata");
    }

    /** Recordings others shared, one folder per sender. They never count towards the own progress. */
    public static Path sharedDir() {
        return root().resolve("shared");
    }

    /** Everything put here is imported when the menu opens. */
    public static Path inboxDir() {
        return root().resolve("inbox");
    }

    public static Path exportsDir() {
        return root().resolve("exports");
    }

    private static Path favoritesFile() {
        return FabricLoader.getInstance().getGameDir().resolve("combatdata").resolve("favorites.json");
    }

    private static synchronized Favorites favorites() {
        if (favorites == null) favorites = Favorites.load(favoritesFile());
        return favorites;
    }

    public static synchronized boolean favorite(Entry e) {
        return favorites().has(favKey(e.file()));
    }

    public static synchronized void setFavorite(Entry e, boolean fav) {
        favorites().set(favKey(e.file()), fav);
        saveFavorites();
    }

    private static void saveFavorites() {
        try {
            favorites().save(favoritesFile());
        } catch (IOException ex) {
            CombatDataClient.LOG.error("[CD] не удалось сохранить избранное", ex);
        }
    }

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "combatdata-loader");
        t.setDaemon(true);
        return t;
    });

    /** Lists own and shared recordings in the background; the callback runs on the game thread. */
    public static void listAsync(Consumer<List<Entry>> done) {
        LOADER.execute(() -> {
            List<Entry> l = listAll();
            Minecraft.getInstance().execute(() -> done.accept(l));
        });
    }

    /** Own recordings only, in the background (storage limits count only these). */
    public static void listOwnAsync(Consumer<List<Entry>> done) {
        LOADER.execute(() -> {
            List<Entry> l = list();
            Minecraft.getInstance().execute(() -> done.accept(l));
        });
    }

    /** Reads a whole recording in the background; callbacks run on the game thread. */
    public static void readAsync(Path file, Consumer<Recording> done, Consumer<Exception> failed) {
        LOADER.execute(() -> {
            try {
                Recording r = Cdrec.read(file);
                Minecraft.getInstance().execute(() -> done.accept(r));
            } catch (Exception ex) {
                Minecraft.getInstance().execute(() -> failed.accept(ex));
            }
        });
    }

    /** Own recordings only: progress, cleanup and comparisons use this. */
    public static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        scan(dir(), null, out);
        out.sort(Comparator.comparingLong(Entry::time).reversed());
        return out;
    }

    /** Own and shared recordings, for the Recordings tab. */
    public static List<Entry> listAll() {
        List<Entry> out = new ArrayList<>();
        scan(dir(), null, out);
        Path shared = sharedDir();
        if (Files.isDirectory(shared)) {
            try (Stream<Path> dirs = Files.list(shared)) {
                for (Path d : dirs.filter(Files::isDirectory).toList()) {
                    String who = d.getFileName().toString();
                    if (!who.startsWith(".")) scan(d, who, out);
                }
            } catch (IOException e) {
                CombatDataClient.LOG.error("[CD] не удалось прочитать папку чужих записей", e);
            }
        }
        out.sort(Comparator.comparingLong(Entry::time).reversed());
        return out;
    }

    private static void scan(Path dir, String sharedBy, List<Entry> out) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(Cdrec.EXT)).toList()) {
                String name = f.getFileName().toString();
                Cdrec.Header h = null;
                try {
                    h = Cdrec.readHeader(f);
                } catch (Exception e) {
                    CombatDataClient.LOG.warn("[CD] не читается запись {}: {}", name, e.toString());
                }
                long mod = 0, size = 0;
                try {
                    mod = Files.getLastModifiedTime(f).toMillis();
                    size = Files.size(f);
                } catch (IOException ignored) {
                }
                out.add(new Entry(f, name.substring(0, name.length() - Cdrec.EXT.length()), h, mod, size, sharedBy));
            }
        } catch (IOException e) {
            CombatDataClient.LOG.error("[CD] не удалось прочитать папку записей", e);
        }
    }

    public static Path rename(Path file, String newName) throws IOException {
        String clean = newName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.length() > 64) clean = clean.substring(0, 64).trim();
        if (clean.isEmpty() || clean.startsWith(".")) throw new IOException(t("Недопустимое имя", "Invalid name"));
        Path target = file.resolveSibling(clean + Cdrec.EXT);
        if (target.equals(file)) return file;
        if (Files.exists(target)) throw new IOException(t("Запись с таким именем уже есть", "A recording with this name already exists"));
        Path moved = Files.move(file, target);
        ProgressData.invalidate();
        synchronized (Recordings.class) {
            String old = favKey(file);
            if (favorites().has(old)) {
                favorites().renamed(old, favKey(moved));
                saveFavorites();
            }
        }
        return moved;
    }

    public static void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
        ProgressData.invalidate();
        synchronized (Recordings.class) {
            if (favorites().has(favKey(file))) {
                favorites().set(favKey(file), false);
                saveFavorites();
            }
        }
    }

    /** Own recordings keep the plain name as before; shared ones are prefixed with their sender folder. */
    static String favKey(Path file) {
        Path parent = file.getParent();
        if (parent == null || parent.equals(dir()) || parent.getParent() == null || !parent.getParent().equals(sharedDir())) return stem(file);
        return "@" + parent.getFileName() + "/" + stem(file);
    }

    private static String stem(Path file) {
        String n = file.getFileName().toString();
        return n.endsWith(Cdrec.EXT) ? n.substring(0, n.length() - Cdrec.EXT.length()) : n;
    }

    public static long totalBytes(List<Entry> entries) {
        long t = 0;
        for (Entry e : entries) t += e.size();
        return t;
    }

    /** Favorites and the newest recording are never removed. */
    public static Cleanup cleanup(Settings s) {
        if (s.storageLimitMb <= 0 && s.autoDeleteDays <= 0) return new Cleanup(0, 0);
        List<Entry> all = list();
        Map<String, Entry> byId = new HashMap<>();
        List<Retention.FileInfo> infos = new ArrayList<>();
        for (Entry e : all) {
            byId.put(e.stem(), e);
            infos.add(new Retention.FileInfo(e.stem(), e.size(), e.time(), favorite(e)));
        }
        int n = 0;
        long bytes = 0;
        for (String id : Retention.toDelete(infos, s.storageLimitMb * 1024L * 1024L, s.autoDeleteDays, System.currentTimeMillis())) {
            Entry e = byId.get(id);
            try {
                Files.deleteIfExists(e.file());
                ProgressData.invalidate();
                n++;
                bytes += e.size();
                CombatDataClient.LOG.info("[CD] автоудаление: {}", e.file().getFileName());
            } catch (IOException ex) {
                CombatDataClient.LOG.warn("[CD] автоудаление: не удалось удалить {}: {}", e.file().getFileName(), ex.toString());
            }
        }
        return new Cleanup(n, bytes);
    }
}
