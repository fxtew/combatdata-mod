package dev.combatdata.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static dev.combatdata.core.Lang.t;

/**
 * Sharing recordings as files. "share" in the header says who shared it (null: anonymous), what was hidden, when and
 * with which mod version. Several recordings go into one .zip with manifest.json; its SHA-256 sums only check that
 * nothing was damaged on the way.
 */
public record Share(String by, boolean anonOpponents, boolean anonServer, boolean hidePlayer, boolean behavior,
                    boolean oneFight, long exportedMs, String mod) {

    public static final String MANIFEST = "manifest.json";
    /** Bigger single files are worth sharing as one fight. */
    public static final long BIG_FILE = 10L * 1024 * 1024;
    /** A .zip entry larger than this is not read (a broken or hostile archive). */
    static final long MAX_ENTRY = 256L * 1024 * 1024;
    /** UUIDs made for hidden players: recognisable and never a real account (version bits 0). */
    static final long FAKE_HI = 0xCD5A000000000000L;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("by", by);
        m.put("anonOpponents", anonOpponents);
        m.put("anonServer", anonServer);
        m.put("hidePlayer", hidePlayer);
        m.put("behavior", behavior);
        m.put("oneFight", oneFight);
        m.put("exported", exportedMs);
        m.put("mod", mod);
        return m;
    }

    public static Share fromMap(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        return new Share(m.get("by") instanceof String s ? s : null, bool(m, "anonOpponents"), bool(m, "anonServer"),
                bool(m, "hidePlayer"), bool(m, "behavior"), bool(m, "oneFight"),
                m.get("exported") instanceof Number n ? n.longValue() : 0, m.get("mod") instanceof String s ? s : null);
    }

    private static boolean bool(Map<?, ?> m, String k) {
        return Boolean.TRUE.equals(m.get(k));
    }

    /** Who to show as the sender: the share field, else the recording's player. */
    public static String sender(Cdrec.Header h) {
        if (h.share() != null) return h.share().by() == null ? t("аноним", "anonymous") : h.share().by();
        return h.player() == null || h.player().isBlank() ? t("неизвестно", "unknown") : h.player();
    }

    // ---- Export ----

    /** fightFrom/fightTo: the time range to keep when oneFight, buffer milliseconds. */
    public record Options(boolean oneFight, long fightFrom, long fightTo, boolean anonOpponents, boolean anonServer,
                          boolean hidePlayer, boolean behavior) {
        public static final Options DEFAULT = new Options(false, 0, 0, true, false, false, false);
    }

    /** Context kept around a single fight: jumps, positions and pings just before and after it. */
    public static final long FIGHT_MARGIN_MS = 3000;

    /** The recording as it will be shared: cut to one fight, players and server hidden, opponent data dropped. */
    public static Recording prepare(Recording r, Options o) {
        List<Event> ev = r.events();
        long wall = r.wallStartMs(), dur = r.durationMs();
        if (o.oneFight()) {
            long from = Math.max(0, o.fightFrom() - FIGHT_MARGIN_MS), to = o.fightTo() + FIGHT_MARGIN_MS;
            List<Event> cut = new ArrayList<>();
            SegmentEvent seg = null;
            for (Event e : ev) {
                if (e.t() < from) {
                    if (e instanceof SegmentEvent s) seg = s;
                    continue;
                }
                if (e.t() > to) break;
                cut.add(e.withT(e.t() - from));
            }
            if (seg != null) cut.add(0, new SegmentEvent(0, seg.server(), seg.dimension(), seg.wallStartMs() + (from - seg.t())));
            ev = cut;
            wall += from;
            dur = Math.min(dur, to) - from;
        }
        UUID self = r.playerUuid();
        Map<UUID, UUID> ids = new HashMap<>();
        List<Event> out = new ArrayList<>(ev.size());
        for (Event e : ev) {
            if (!o.behavior() && (e instanceof OtherStateEvent || e instanceof OtherAnimEvent)) continue;
            Event x = e;
            if (o.anonOpponents()) x = hidePlayers(x, self, ids);
            if (o.anonServer() && x instanceof SegmentEvent s && s.server() != null) {
                x = new SegmentEvent(s.t(), t("сервер", "server"), s.dimension(), s.wallStartMs());
            }
            out.add(x);
        }
        String player = o.hidePlayer() ? t("Игрок", "Player") : r.player();
        UUID uuid = o.hidePlayer() ? new UUID(FAKE_HI, 0) : self;
        return new Recording(r.schema(), player, uuid, r.mcVersion(), wall, Math.max(0, dur), out, r.notRecorded());
    }

    /** Every other player becomes "Opponent N" with a made-up UUID, numbered by first appearance. */
    private static Event hidePlayers(Event e, UUID self, Map<UUID, UUID> ids) {
        return switch (e) {
            case AttackEvent a -> a.withTarget(id(a.target(), self, ids), name(a.target(), a.targetName(), self, ids));
            case IncomingHitEvent h -> h.withAttacker(id(h.attacker(), self, ids), name(h.attacker(), h.attackerName(), self, ids));
            case OtherStateEvent o -> new OtherStateEvent(o.t(), o.tick(), id(o.id(), self, ids), o.x(), o.y(), o.z(), o.yaw(),
                    o.pitch(), o.onGround(), o.sprinting(), o.item());
            case OtherAnimEvent w -> new OtherAnimEvent(w.t(), w.tick(), id(w.id(), self, ids), w.anim());
            case PositionEvent p -> {
                List<PositionEvent.Other> others = new ArrayList<>(p.others().size());
                for (PositionEvent.Other x : p.others()) others.add(new PositionEvent.Other(id(x.id(), self, ids), x.x(), x.y(), x.z(), x.yaw()));
                yield new PositionEvent(p.t(), p.tick(), p.x(), p.y(), p.z(), p.yaw(), others);
            }
            default -> e;
        };
    }

    private static UUID id(UUID real, UUID self, Map<UUID, UUID> ids) {
        if (real == null || real.equals(self)) return real;
        return ids.computeIfAbsent(real, k -> new UUID(FAKE_HI, ids.size() + 1));
    }

    /** A name without a UUID gets a number from the same sequence, keyed by the name. */
    private static String name(UUID real, String name, UUID self, Map<UUID, UUID> ids) {
        UUID key = real != null ? real : name == null ? null : UUID.nameUUIDFromBytes(("name:" + name).getBytes(StandardCharsets.UTF_8));
        if (key == null || key.equals(self)) return name;
        return t("Противник ", "Opponent ") + id(key, self, ids).getLeastSignificantBits();
    }

    /** One recording as a plain .cdrec in dir, under a free name. */
    public static Path exportOne(Path dir, String stem, Recording r, Share share, String mod) throws IOException {
        Files.createDirectories(dir);
        Path f = free(dir, clean(stem), Cdrec.EXT);
        Cdrec.write(f, r, mod, share);
        return f;
    }

    public record Named(String stem, Recording recording) {
    }

    /** Several recordings in one .zip with manifest.json. */
    public static Path exportZip(Path dir, String stem, List<Named> recs, Share share, String mod) throws IOException {
        Files.createDirectories(dir);
        Path zip = free(dir, clean(stem), ".zip");
        Path tmp = Files.createTempDirectory(dir, ".share");
        try {
            List<Object> files = new ArrayList<>();
            Set<String> used = new HashSet<>();
            List<Path> written = new ArrayList<>();
            for (Named n : recs) {
                String name = clean(n.stem());
                for (int i = 2; !used.add(name.toLowerCase(Locale.ROOT)); i++) name = clean(n.stem()) + " (" + i + ")";
                Path f = tmp.resolve(name + Cdrec.EXT);
                Cdrec.write(f, n.recording(), mod, share);
                written.add(f);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.getFileName().toString());
                m.put("sha256", sha256(f));
                m.put("size", Files.size(f));
                files.add(m);
            }
            Map<String, Object> man = new LinkedHashMap<>();
            man.put("format", "combatdata-share");
            man.put("version", 1);
            man.put("by", share.by());
            man.put("mod", mod);
            man.put("exported", share.exportedMs());
            man.put("files", files);
            Path part = zip.resolveSibling(zip.getFileName() + ".tmp");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(part))) {
                out.putNextEntry(new ZipEntry(MANIFEST));
                out.write(Json.write(man).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                for (Path f : written) {
                    out.putNextEntry(new ZipEntry(f.getFileName().toString()));
                    Files.copy(f, out);
                    out.closeEntry();
                }
            }
            Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);
            return zip;
        } finally {
            deleteTree(tmp);
        }
    }

    // ---- Import ----

    public record Imported(List<Path> files, int duplicates, List<String> errors) {
        public static final Imported NONE = new Imported(List.of(), 0, List.of());

        public Imported plus(Imported o) {
            List<Path> f = new ArrayList<>(files);
            f.addAll(o.files);
            List<String> e = new ArrayList<>(errors);
            e.addAll(o.errors);
            return new Imported(List.copyOf(f), duplicates + o.duplicates, List.copyOf(e));
        }
    }

    /** Copies .cdrec and .zip files into shared/<sender>/; sources are left in place. */
    public static Imported importFiles(List<Path> files, Path sharedDir) {
        Imported total = Imported.NONE;
        for (Path f : files) {
            String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.endsWith(Cdrec.EXT)) total = total.plus(importOne(f, f.getFileName().toString(), sharedDir));
            else if (n.endsWith(".zip")) total = total.plus(importZip(f, sharedDir));
            else total = total.plus(error(f.getFileName() + t(": не запись Combat Data", ": not a Combat Data recording")));
        }
        return total;
    }

    private static Imported error(String msg) {
        return new Imported(List.of(), 0, List.of(msg));
    }

    static Imported importOne(Path src, String name, Path sharedDir) {
        Cdrec.Header h;
        try {
            h = Cdrec.readHeader(src);
        } catch (Exception e) {
            return error(name + t(": файл повреждён или не запись", ": damaged file or not a recording"));
        }
        try {
            Path dir = sharedDir.resolve(clean(sender(h)));
            Files.createDirectories(dir);
            String sha = sha256(src);
            long size = Files.size(src);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + Cdrec.EXT)) {
                for (Path p : ds) if (Files.size(p) == size && sha256(p).equals(sha)) return new Imported(List.of(), 1, List.of());
            }
            String stem = name.endsWith(Cdrec.EXT) ? name.substring(0, name.length() - Cdrec.EXT.length()) : name;
            Path target = free(dir, clean(stem), Cdrec.EXT);
            Files.copy(src, target);
            return new Imported(List.of(target), 0, List.of());
        } catch (IOException e) {
            return error(name + ": " + e.getMessage());
        }
    }

    /** Only .cdrec entries by their bare name; a damaged entry (SHA-256 differs from the manifest) is skipped. */
    static Imported importZip(Path zip, Path sharedDir) {
        Imported total = Imported.NONE;
        Map<String, String> sums = new HashMap<>();
        Path tmp = null;
        try {
            Files.createDirectories(sharedDir);
            tmp = Files.createTempDirectory(sharedDir, ".import");
            List<Path> got = new ArrayList<>();
            try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String base = baseName(e.getName());
                    if (base.equals(MANIFEST)) {
                        Object man = Json.parse(new String(readLimited(in, 1024 * 1024), StandardCharsets.UTF_8));
                        if (man instanceof Map<?, ?> m && m.get("files") instanceof List<?> l) {
                            for (Object x : l) if (x instanceof Map<?, ?> fm && fm.get("name") instanceof String nm && fm.get("sha256") instanceof String s) sums.put(nm, s);
                        }
                        continue;
                    }
                    if (!base.toLowerCase(Locale.ROOT).endsWith(Cdrec.EXT) || base.startsWith(".")) continue;
                    Path f = free(tmp, clean(base.substring(0, base.length() - Cdrec.EXT.length())), Cdrec.EXT);
                    if (!copyLimited(in, f)) {
                        total = total.plus(error(base + t(": слишком большой файл в архиве", ": file in the archive is too big")));
                        Files.deleteIfExists(f);
                        continue;
                    }
                    got.add(f);
                }
            }
            for (Path f : got) {
                String name = f.getFileName().toString();
                String want = sums.get(name);
                if (want != null && !want.equals(sha256(f))) {
                    total = total.plus(error(name + t(": повреждён при передаче", ": damaged in transfer")));
                    continue;
                }
                total = total.plus(importOne(f, name, sharedDir));
            }
        } catch (Exception e) {
            total = total.plus(error(zip.getFileName() + t(": архив не читается", ": unreadable archive")));
        } finally {
            if (tmp != null) deleteTree(tmp);
        }
        return total;
    }

    private static String baseName(String entry) {
        String s = entry.replace('\\', '/');
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > max) throw new IOException("too big");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static boolean copyLimited(InputStream in, Path f) throws IOException {
        long total = 0;
        byte[] buf = new byte[65536];
        try (OutputStream out = Files.newOutputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_ENTRY) return false;
                out.write(buf, 0, n);
            }
        }
        return true;
    }

    /** True if version a (like "0.3.0+26.1.2") is newer than b; unknown versions are never newer. */
    public static boolean newer(String a, String b) {
        int[] x = parts(a), y = parts(b);
        if (x == null || y == null) return false;
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int[] parts(String v) {
        if (v == null) return null;
        String core = v.split("[+-]", 2)[0];
        String[] s = core.split("\\.");
        int[] out = new int[s.length];
        try {
            for (int i = 0; i < s.length; i++) out[i] = Integer.parseInt(s[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    // ---- Files ----

    public static String sha256(Path f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** A safe file or folder name: no path parts, no reserved characters, not empty. */
    public static String clean(String s) {
        String c = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (c.startsWith(".")) c = c.substring(1);
        if (c.length() > 64) c = c.substring(0, 64).trim();
        return c.isEmpty() ? "_" : c;
    }

    static Path free(Path dir, String stem, String ext) {
        Path p = dir.resolve(stem + ext);
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(stem + " (" + i + ")" + ext);
        return p;
    }

    static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
