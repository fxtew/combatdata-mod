package dev.combatdata.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * .cdrec: gzipped JSON Lines. Line 1 is a header with a summary (the recordings list reads only that),
 * then one event per line; "k" is the event type.
 */
public final class Cdrec {
    public static final String EXT = ".cdrec";

    private Cdrec() {
    }

    public record Header(int schema, String player, UUID playerUuid, String mcVersion, long wallStartMs,
                         long durationMs, int attacks, int hitsConfirmed, int incoming, int jumps, List<String> servers,
                         List<String> opponents, int notRecorded) {
        public Header(int schema, String player, UUID playerUuid, String mcVersion, long wallStartMs, long durationMs,
                      int attacks, int hitsConfirmed, int incoming, int jumps, List<String> servers, List<String> opponents) {
            this(schema, player, playerUuid, mcVersion, wallStartMs, durationMs, attacks, hitsConfirmed, incoming, jumps,
                    servers, opponents, 0);
        }
    }

    public static void write(Path file, Recording r, String modVersion) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer out = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(tmp)), StandardCharsets.UTF_8))) {
            out.write(Json.write(header(r, modVersion)));
            out.write('\n');
            for (Event e : r.events()) {
                out.write(Json.write(encode(e)));
                out.write('\n');
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public static Recording read(Path file) throws IOException {
        try (BufferedReader in = reader(file)) {
            String first = in.readLine();
            if (first == null) throw new IOException("Пустой файл записи");
            Map<String, Object> h = obj(Json.parse(first));
            List<Event> ev = new ArrayList<>();
            // A damaged line is skipped and a truncated archive keeps the events read so far,
            // so one bad spot does not make the whole recording unreadable.
            while (true) {
                String line;
                try {
                    line = in.readLine();
                } catch (IOException ex) {
                    if (ev.isEmpty()) throw ex;
                    break;
                }
                if (line == null) break;
                if (line.isBlank()) continue;
                try {
                    Event e = decode(obj(Json.parse(line)));
                    if (e != null) ev.add(e);
                } catch (RuntimeException ignored) {
                }
            }
            return new Recording(i(h, "schema"), s(h, "player"), uuid(h, "uuid"), s(h, "mc"), l(h, "wallStart"),
                    l(h, "duration"), ev, Features.parse(h.get("notRecorded")));
        }
    }

    public static Header readHeader(Path file) throws IOException {
        try (BufferedReader in = reader(file)) {
            String first = in.readLine();
            if (first == null) throw new IOException("Пустой файл записи");
            Map<String, Object> h = obj(Json.parse(first));
            Map<String, Object> sum = obj(h.getOrDefault("summary", new LinkedHashMap<>()));
            return new Header(i(h, "schema"), s(h, "player"), uuid(h, "uuid"), s(h, "mc"), l(h, "wallStart"),
                    l(h, "duration"), i(sum, "attacks"), i(sum, "hits"), i(sum, "incoming"), i(sum, "jumps"),
                    strList(sum.get("servers")), strList(sum.get("opponents")), Features.parse(h.get("notRecorded")));
        }
    }

    private static BufferedReader reader(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8));
    }

    private static Map<String, Object> header(Recording r, String modVersion) {
        int attacks = 0, hits = 0, incoming = 0, jumps = 0;
        Set<String> servers = new LinkedHashSet<>(), opp = new LinkedHashSet<>();
        for (Event e : r.events()) {
            if (e instanceof AttackEvent a) {
                attacks++;
                if (a.outcome() == Outcome.HIT_CONFIRMED) hits++;
                if (a.targetName() != null) opp.add(a.targetName());
            } else if (e instanceof IncomingHitEvent h) {
                incoming++;
                if (h.attackerName() != null) opp.add(h.attackerName());
            } else if (e instanceof JumpEvent) jumps++;
            else if (e instanceof SegmentEvent s && s.server() != null) servers.add(s.server());
        }
        Map<String, Object> sum = new LinkedHashMap<>();
        sum.put("attacks", attacks);
        sum.put("hits", hits);
        sum.put("incoming", incoming);
        sum.put("jumps", jumps);
        sum.put("servers", new ArrayList<>(servers));
        sum.put("opponents", new ArrayList<>(opp));
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("format", "combatdata");
        h.put("schema", r.schema());
        h.put("mod", modVersion);
        h.put("mc", r.mcVersion());
        h.put("player", r.player());
        h.put("uuid", r.playerUuid() == null ? null : r.playerUuid().toString());
        h.put("wallStart", r.wallStartMs());
        h.put("duration", r.durationMs());
        h.put("summary", sum);
        if (r.notRecorded() != 0) h.put("notRecorded", Features.names(r.notRecorded()));
        return h;
    }

    static Map<String, Object> encode(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (e) {
            case AttackEvent a -> {
                m.put("k", "A"); m.put("t", a.t()); m.put("tick", a.tick());
                m.put("target", str(a.target())); m.put("name", a.targetName());
                m.put("out", a.outcome().name()); m.put("type", a.type().name());
                AttackFlags f = a.flags();
                m.put("spr", f.sprinting()); m.put("gnd", f.onGround()); m.put("fall", f.fallDistance());
                m.put("climb", f.onClimbable()); m.put("water", f.inWater()); m.put("restr", f.restricted());
                m.put("ride", f.passenger()); m.put("sword", f.sword()); m.put("slow", f.slowHorizontal());
                m.put("str", a.strength()); m.put("reach", a.reach()); m.put("aim", a.aimTotalDeg());
                m.put("aimY", a.aimYawDeg()); m.put("aimP", a.aimPitchDeg()); m.put("u", a.hitU()); m.put("v", a.hitV());
                m.put("ping", a.pingMs()); m.put("click", a.clickT()); m.put("ray", a.rayDist());
                m.put("range", a.range()); m.put("selfV", a.selfTowardBps()); m.put("tgtV", a.targetAwayBps());
                m.put("tgtPing", a.targetPingMs()); m.put("cd", a.cooldownTicks());
                if (a.item() != null) m.put("item", a.item());
                if (a.weapon() != null) m.put("weapon", a.weapon().name());
                if (a.team() != Relation.TEAM_UNKNOWN) m.put("team", a.team());
            }
            case IncomingHitEvent h -> {
                m.put("k", "I"); m.put("t", h.t()); m.put("tick", h.tick());
                m.put("from", str(h.attacker())); m.put("name", h.attackerName());
                m.put("gnd", h.groundedAtHit()); m.put("held", h.jumpHeld()); m.put("kb", h.knockbackApplied());
                m.put("ping", h.pingMs()); m.put("kbAway", h.kbAway());
                if (h.kind() != null) m.put("hitKind", h.kind().name());
                if (h.projectile() != null) m.put("proj", h.projectile());
                if (h.attackerWeapon() != null) m.put("weapon", h.attackerWeapon().name());
                if (h.attackerItem() != null) m.put("item", h.attackerItem());
                if (h.team() != Relation.TEAM_UNKNOWN) m.put("team", h.team());
                if (Double.isFinite(h.reach())) {
                    m.put("reach", h.reach()); m.put("range", h.range()); m.put("attV", h.attackerTowardBps());
                    m.put("selfV", h.selfAwayBps()); m.put("attPing", h.attackerPingMs());
                }
                if (h.attackerCrit() >= 0) {
                    m.put("crit", h.attackerCrit());
                }
            }
            case JumpEvent j -> {
                m.put("k", "J"); m.put("t", j.t()); m.put("tick", j.tick()); m.put("gnd", j.onGround());
                m.put("press", j.pressT()); m.put("keys", j.keys());
            }
            case PingEvent p -> { m.put("k", "P"); m.put("t", p.t()); m.put("ping", p.pingMs()); }
            case InputEvent in -> {
                m.put("k", "N"); m.put("t", in.t()); m.put("tick", in.tick()); m.put("in", in.what().name()); m.put("on", in.on());
            }
            case PositionEvent p -> {
                // Compact: "p" [x, y, z, yaw], "o" [[uuid, x, y, z, yaw], ...]; 0.01 block and 0.1° precision is plenty.
                m.put("k", "X"); m.put("t", p.t()); m.put("tick", p.tick());
                m.put("p", List.of(r2(p.x()), r2(p.y()), r2(p.z()), r1(p.yaw())));
                List<Object> o = new ArrayList<>(p.others().size());
                for (PositionEvent.Other x : p.others()) o.add(List.of(x.id().toString(), r2(x.x()), r2(x.y()), r2(x.z()), r1(x.yaw())));
                m.put("o", o);
            }
            case MarkerEvent mk -> { m.put("k", "M"); m.put("t", mk.t()); m.put("label", mk.label()); }
            case SegmentEvent s -> {
                m.put("k", "S"); m.put("t", s.t()); m.put("server", s.server()); m.put("dim", s.dimension());
                m.put("wall", s.wallStartMs());
            }
        }
        return m;
    }

    /** Unknown event types are skipped (null) so older versions can open newer recordings. */
    static Event decode(Map<String, Object> m) {
        String k = s(m, "k");
        if (k == null) return null;
        return switch (k) {
            case "A" -> new AttackEvent(l(m, "t"), i(m, "tick"), uuid(m, "target"), s(m, "name"),
                    Outcome.valueOf(s(m, "out")), HitType.valueOf(s(m, "type")),
                    new AttackFlags(b(m, "spr"), b(m, "gnd"), d(m, "fall"), b(m, "climb"), b(m, "water"),
                            b(m, "restr"), b(m, "ride"), b(m, "sword"), b(m, "slow")),
                    (float) d(m, "str"), d(m, "reach"), d(m, "aim"), d(m, "aimY"), d(m, "aimP"), d(m, "u"), d(m, "v"),
                    i(m, "ping"), l(m, "click"), d(m, "ray"), d(m, "range"), d(m, "selfV"), d(m, "tgtV"),
                    m.get("tgtPing") instanceof Number n ? n.intValue() : -1, (float) d(m, "cd"), s(m, "item"),
                    Weapon.parse(s(m, "weapon")), team(m));
            case "I" -> new IncomingHitEvent(l(m, "t"), i(m, "tick"), uuid(m, "from"), s(m, "name"), b(m, "gnd"),
                    b(m, "held"), b(m, "kb"), i(m, "ping"), d(m, "kbAway"), IncomingHitEvent.Kind.parse(s(m, "hitKind")),
                    s(m, "proj"), Weapon.parse(s(m, "weapon")), s(m, "item"), team(m), d(m, "reach"), d(m, "range"),
                    d(m, "attV"), d(m, "selfV"), m.get("attPing") instanceof Number n ? n.intValue() : -1,
                    m.get("crit") instanceof Number c ? c.intValue() : -1);
            case "J" -> new JumpEvent(l(m, "t"), i(m, "tick"), b(m, "gnd"), l(m, "press"),
                    m.get("keys") instanceof Number n ? n.intValue() : JumpEvent.KEYS_UNKNOWN);
            case "P" -> new PingEvent(l(m, "t"), i(m, "ping"));
            case "N" -> {
                InputEvent.Input what = InputEvent.Input.parse(s(m, "in"));
                yield what == null ? null : new InputEvent(l(m, "t"), i(m, "tick"), what, b(m, "on"));
            }
            case "X" -> position(m);
            case "M" -> new MarkerEvent(l(m, "t"), s(m, "label"));
            case "S" -> new SegmentEvent(l(m, "t"), s(m, "server"), s(m, "dim"), l(m, "wall"));
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("Ожидался JSON-объект");
    }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x != null) out.add(x.toString());
        return out;
    }

    private static String str(UUID u) { return u == null ? null : u.toString(); }

    private static String s(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : v.toString(); }

    private static double d(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof Number n ? n.doubleValue() : Double.NaN; }

    private static long l(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof Number n ? n.longValue() : 0L; }

    private static int i(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof Number n ? n.intValue() : 0; }

    private static boolean b(Map<String, Object> m, String k) { return Boolean.TRUE.equals(m.get(k)); }

    private static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double num(List<?> l, int i) {
        return i < l.size() && l.get(i) instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static Event position(Map<String, Object> m) {
        if (!(m.get("p") instanceof List<?> p) || p.size() < 4) return null;
        List<PositionEvent.Other> others = new ArrayList<>();
        if (m.get("o") instanceof List<?> ol) {
            for (Object x : ol) {
                if (!(x instanceof List<?> o) || o.size() < 5) continue;
                try {
                    others.add(new PositionEvent.Other(UUID.fromString(String.valueOf(o.get(0))), num(o, 1), num(o, 2), num(o, 3),
                            (float) num(o, 4)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return new PositionEvent(l(m, "t"), i(m, "tick"), num(p, 0), num(p, 1), num(p, 2), (float) num(p, 3), List.copyOf(others));
    }

    private static int team(Map<String, Object> m) {
        return m.get("team") instanceof Number n ? n.intValue() : Relation.TEAM_UNKNOWN;
    }

    private static UUID uuid(Map<String, Object> m, String k) {
        String v = s(m, k);
        try { return v == null ? null : UUID.fromString(v); } catch (IllegalArgumentException e) { return null; }
    }
}
