package dev.combatdata.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Server profiles from config/combatdata-servers.json plus the built-in list shipped with the mod. Profiles are looked
 * up by address when a recording is viewed, so editing one re-rates old recordings. User profiles win over the built-in
 * list: the list is only copied into a new profile on the first visit.
 */
public final class ServerProfiles {
    public static final int SCHEMA = 1;
    private static final String DEFAULT_PORT = ":25565";

    private final List<ServerProfile> profiles = new ArrayList<>();
    private final List<ServerProfile> builtin = new ArrayList<>();
    private long version;

    public synchronized List<ServerProfile> list() {
        return List.copyOf(profiles);
    }

    /** Grows on every change; cached stats include it in their key. */
    public synchronized long version() {
        return version;
    }

    public synchronized void changed() {
        version++;
    }

    /** Fields of all profiles that change how recordings are rated. */
    public synchronized String ratingKey() {
        List<String> parts = new ArrayList<>();
        for (ServerProfile p : profiles) {
            parts.add(p.address + "," + p.subdomains + "," + p.jrMode + "," + p.jrLowerMs + "," + p.jrUpperMs + ","
                    + p.jrPreMs + "," + p.jrPostMs + "," + p.cal.valid() + ":" + p.cal.lowerMs() + ":" + p.cal.upperMs()
                    + "," + p.interpMs + "," + p.oppRange);
        }
        Collections.sort(parts);
        return Integer.toHexString(String.join(";", parts).hashCode()) + "/" + parts.size();
    }

    /** Lower case, no trailing dot, no default port. */
    public static String normalize(String address) {
        if (address == null) return "";
        String a = address.strip().toLowerCase(Locale.ROOT);
        if (a.endsWith(DEFAULT_PORT)) a = a.substring(0, a.length() - DEFAULT_PORT.length());
        while (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        return a;
    }

    /** An exact address first, then the longest subdomain pattern; null if none. */
    public synchronized ServerProfile find(String server) {
        return match(profiles, server);
    }

    static ServerProfile match(List<ServerProfile> list, String server) {
        String a = normalize(server);
        if (a.isEmpty()) return null;
        ServerProfile best = null;
        for (ServerProfile p : list) {
            if (p.address.equals(a)) return p;
            if (p.subdomains && a.endsWith("." + p.address) && (best == null || p.address.length() > best.address.length())) best = p;
        }
        return best;
    }

    /** The profile for a server joined now: created from the built-in list or as vanilla if missing. */
    public synchronized ServerProfile visit(String server, Settings s, long nowMs) {
        String a = normalize(server);
        if (a.isEmpty()) return null;
        ServerProfile p = match(profiles, a);
        if (p == null) {
            ServerProfile known = match(builtin, a);
            if (known != null) {
                p = known.copy();
                p.origin = ServerProfile.Origin.BUILTIN;
                p.createdMs = nowMs;
            } else {
                p = ServerProfile.create(a, ServerProfile.Preset.VANILLA, ServerProfile.Origin.AUTO, s, nowMs);
            }
            profiles.add(p);
        }
        p.lastSeenMs = nowMs;
        version++;
        return p;
    }

    /** Adds or replaces the profile with the same address. */
    public synchronized void put(ServerProfile p) {
        p.clamp();
        if (p.address.isEmpty()) return;
        profiles.removeIf(x -> x.address.equals(p.address));
        profiles.add(p);
        version++;
    }

    public synchronized boolean remove(String address) {
        boolean r = profiles.removeIf(x -> x.address.equals(normalize(address)));
        if (r) version++;
        return r;
    }

    /** Replaces the built-in list (from the mod's resources). */
    public synchronized void setBuiltin(String json) {
        builtin.clear();
        builtin.addAll(parseList(json, "servers"));
        version++;
    }

    public synchronized List<ServerProfile> builtin() {
        return List.copyOf(builtin);
    }

    public static ServerProfiles load(Path file) {
        ServerProfiles p = new ServerProfiles();
        try {
            if (Files.exists(file)) p.profiles.addAll(parseList(Files.readString(file, StandardCharsets.UTF_8), "profiles"));
        } catch (IOException | RuntimeException ignored) {
        }
        return p;
    }

    /** Damaged entries are skipped, damaged fields fall back to defaults; duplicate addresses keep the first. */
    static List<ServerProfile> parseList(String json, String key) {
        List<ServerProfile> out = new ArrayList<>();
        Object root;
        try {
            root = Json.parse(json);
        } catch (RuntimeException e) {
            return out;
        }
        if (!(root instanceof Map<?, ?> m) || !(m.get(key) instanceof List<?> l)) return out;
        Set<String> seen = new HashSet<>();
        for (Object o : l) {
            ServerProfile p;
            try {
                p = ServerProfile.fromMap(o);
            } catch (RuntimeException e) {
                p = null;
            }
            if (p != null && !p.address.isEmpty() && seen.add(p.address)) out.add(p);
        }
        return out;
    }

    public synchronized void save(Path file) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        List<Object> l = new ArrayList<>();
        for (ServerProfile p : profiles) l.add(p.toMap());
        m.put("profiles", l);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(m), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
