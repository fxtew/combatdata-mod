package dev.combatdata.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static dev.combatdata.core.Lang.t;

/**
 * A recording, a fight or its summary as text for a chat message. Not encrypted, only packed.
 *
 * Binary: [version][level][flags][body][CRC32 of all before]. The body is deflated (raw, level 9) only when that is
 * shorter (flag COMPRESSED). Inside: sender, player, start, duration, a string table (names, server, items), the
 * opponent table, then events by columns: a stream of kinds (2 bits each), a stream of time steps (varint ms), and
 * every field of every kind in its own stream, so similar numbers sit together and deflate well. Signed numbers are
 * zigzag varints; NaN is stored as 0 and other values shifted by one.
 *
 * Text: "CD" + version + level letter [+ part "/" total] + ":" and Base32768, or "=" and Base64URL (the compatible
 * code), for example CD1B:… or CD1R2/3=….
 */
public final class ShareCode {
    public static final int VERSION = 1;
    /** Characters per message part, below Discord's 2000 with room for the prefix. */
    public static final int PART_CHARS = 1900;
    static final int MAX_BYTES = 4 * 1024 * 1024, MAX_EVENTS = 200_000, MAX_STRINGS = 4096, MAX_STR = 64;
    static final int FLAG_ANON = 1, FLAG_REPLAY = 2, FLAG_COMPRESSED = 4;
    /** Replay positions are kept five times a second. */
    static final long REPLAY_STEP_MS = 200;

    public enum Level {
        SUMMARY('S'), FIGHT('B'), REPLAY('P'), RECORDING('R');

        public final char letter;

        Level(char letter) {
            this.letter = letter;
        }

        static Level of(char c) {
            for (Level l : values()) if (l.letter == c) return l;
            return null;
        }
    }

    public static final class CodeException extends Exception {
        public CodeException(String msg) {
            super(msg);
        }
    }

    /** parts: the text to paste, one message each. */
    public record Encoded(Level level, List<String> parts, int bytes) {
        public int chars() {
            int n = 0;
            for (String p : parts) n += p.length();
            return n;
        }
    }

    /** The numbers of a summary code, in this order. */
    public enum SumField {
        DURATION_S, FIGHTS, ATTACKS, HITS, REACH_CM, AIM_YAW_DECI, CHARGE_PCT, SPRINT_HITS, CRITS, TB_RATED, TB_HITS,
        JR_ATTEMPTS, JR_SUCCESS, JR_MEAN_MS, JR_SIGMA_MS, COMBO_X100, MAX_COMBO, TRADES, FIRST_HITS, TRADES_WON, TAKEN, PING_MS
    }

    public record Summary(String by, String player, String opponent, String server, long wallStartMs, long[] values) {
        public long get(SumField f) {
            return f.ordinal() < values.length ? values[f.ordinal()] : 0;
        }
    }

    /** rec: the recording (null for a summary); anon: names were hidden by the sender. */
    public record Decoded(Level level, String by, boolean anon, Recording rec, Summary summary) {
    }

    // ---- Summary ----

    public static Summary summarize(Recording r, Rules rules, String by, boolean anon) {
        Stats.Result s = Stats.compute(r, rules, Stats.Filter.ALL, AttackEvent.DEFAULT_RANGE, StatFilter.NONE,
                new Trades.Params(Trades.DEFAULT_GAP_MS, false));
        Stats.HitStats h = s.hits();
        int sprint = h.types().getOrDefault(HitType.SPRINT, 0), crit = h.types().getOrDefault(HitType.CRIT, 0);
        long[] v = new long[SumField.values().length];
        v[SumField.DURATION_S.ordinal()] = r.durationMs() / 1000;
        v[SumField.FIGHTS.ordinal()] = Stats.fights(r.events(), Stats.FIGHT_GAP_MS).size();
        v[SumField.ATTACKS.ordinal()] = h.confirmed() + h.unconfirmed() + h.misses();
        v[SumField.HITS.ordinal()] = h.confirmed();
        v[SumField.REACH_CM.ordinal()] = Math.round(h.reachMean() * 100);
        v[SumField.AIM_YAW_DECI.ordinal()] = Math.round(h.aimYawAbsMean() * 10);
        v[SumField.CHARGE_PCT.ordinal()] = Math.round(h.strengthMean() * 100);
        v[SumField.SPRINT_HITS.ordinal()] = sprint;
        v[SumField.CRITS.ordinal()] = crit;
        v[SumField.TB_RATED.ordinal()] = s.threeBlock().classified();
        v[SumField.TB_HITS.ordinal()] = s.threeBlock().count();
        v[SumField.JR_ATTEMPTS.ordinal()] = s.jump().attempts();
        v[SumField.JR_SUCCESS.ordinal()] = s.jump().success();
        v[SumField.JR_MEAN_MS.ordinal()] = Math.round(s.jump().biasMs());
        v[SumField.JR_SIGMA_MS.ordinal()] = Math.round(s.jump().sigmaMs());
        v[SumField.COMBO_X100.ordinal()] = Math.round(s.combo().avgCombo() * 100);
        v[SumField.MAX_COMBO.ordinal()] = s.combo().maxCombo();
        v[SumField.TRADES.ordinal()] = s.trades().total();
        v[SumField.FIRST_HITS.ordinal()] = s.trades().first();
        v[SumField.TRADES_WON.ordinal()] = s.trades().wins();
        v[SumField.TAKEN.ordinal()] = s.combo().taken();
        v[SumField.PING_MS.ordinal()] = Math.round(s.pingMean());
        String opp = s.opponents().isEmpty() || anon ? null : s.opponents().get(0).name();
        String server = null;
        for (Event e : r.events()) if (e instanceof SegmentEvent sg && sg.server() != null) { server = sg.server(); break; }
        return new Summary(by, r.player(), opp, server, r.wallStartMs(), v);
    }

    // ---- Encode ----

    /**
     * r: already prepared (cut to the fight, names hidden: Share.prepare). Positions go in only at REPLAY; behaviour
     * data, markers, per-second pings and raw click times never do.
     */
    public static Encoded encode(Recording r, Level level, String by, boolean anon, boolean base64) {
        Out o = new Out();
        o.str(by);
        o.str(r.player());
        o.uvar(Math.max(0, r.wallStartMs() / 1000));
        o.uvar(Math.max(0, r.durationMs()));
        body(o, r, level == Level.REPLAY, anon);
        return wrap(level, o.bytes(), (anon ? FLAG_ANON : 0) | (level == Level.REPLAY ? FLAG_REPLAY : 0), base64);
    }

    public static Encoded encodeSummary(Summary s, boolean anon, boolean base64) {
        Out o = new Out();
        o.str(s.by());
        o.str(s.player());
        o.str(s.opponent());
        o.str(s.server());
        o.uvar(Math.max(0, s.wallStartMs() / 1000));
        o.uvar(s.values().length);
        for (long v : s.values()) o.svar(v);
        return wrap(Level.SUMMARY, o.bytes(), anon ? FLAG_ANON : 0, base64);
    }

    private static final int K_ATTACK = 0, K_INCOMING = 1, K_JUMP = 2, K_INPUT = 3;
    private static final int[] TIME = {1, 0, 53, 54};
    private static final int T_INCOMING = 0, T_ATTACK = 1, A_TARGET = 2, A_OT = 3, A_FLAGS = 4, A_WEAPON = 5, A_ITEM = 6, A_TEAM = 7, A_FALL = 8,
            A_STR = 9, A_REACH = 10, A_RAY = 11, A_RANGE = 12, A_PING = 13, A_TPING = 14, A_CD = 15, A_YAW = 16, A_PITCH = 17,
            A_U = 18, A_V = 19, A_TOW = 20, A_AWAY = 21, A_SIDE = 22, A_VERT = 23, A_TURN = 24, H_ATT = 25, H_BITS = 26,
            H_WEAPON = 27, H_ITEM = 28, H_PROJ = 29, H_TEAM = 30, H_PING = 31, H_APING = 32, H_KB = 33, H_REACH = 34,
            H_RANGE = 35, H_TOW = 36, H_AWAY = 37, J_BITS = 38, J_KEYS = 39, J_PRESS = 40, I_WHAT = 41, P_DT = 42, P_X = 43,
            P_Y = 44, P_Z = 45, P_YAW = 46, P_N = 47, O_ID = 48, O_X = 49, O_Y = 50, O_Z = 51, O_YAW = 52, T_JUMP = 53, T_INPUT = 54, COLS = 55;

    /**
     * Key presses matter only for W-tap, which looks between two of our confirmed hits at most
     * Cooldown.MAX_INTERVAL_MS apart (WTap.analyze). Presses elsewhere are left out; one stays so the code still says
     * that presses were recorded.
     */
    static List<Event> wtapInputs(List<Event> ev) {
        List<long[]> win = new ArrayList<>();
        AttackEvent prev = null;
        for (Event e : ev) {
            if (e instanceof AttackEvent a && a.outcome() == Outcome.HIT_CONFIRMED) {
                if (prev != null && a.t() - prev.t() <= Cooldown.MAX_INTERVAL_MS) win.add(new long[]{prev.t(), a.t()});
                prev = a;
            }
        }
        List<Event> out = new ArrayList<>(ev.size());
        boolean anyInput = false, kept = false;
        int w = 0;
        for (Event e : ev) {
            if (!(e instanceof InputEvent)) {
                out.add(e);
                continue;
            }
            anyInput = true;
            while (w < win.size() && win.get(w)[1] < e.t()) w++;
            if (w < win.size() && e.t() > win.get(w)[0]) {
                out.add(e);
                kept = true;
            }
        }
        if (anyInput && !kept) for (Event e : ev) if (e instanceof InputEvent) {
            out.add(e);
            out.sort(Comparator.comparingLong(Event::t));
            break;
        }
        return out;
    }

    private static void body(Out o, Recording r, boolean replay, boolean anon) {
        Map<String, Integer> strings = new LinkedHashMap<>();
        Map<UUID, Integer> players = new LinkedHashMap<>();
        Map<UUID, String> names = new HashMap<>();
        String server = null;
        long pingSum = 0;
        int pingN = 0;
        List<Event> ev = new ArrayList<>();
        List<PositionEvent> pos = new ArrayList<>();
        long lastPos = -REPLAY_STEP_MS;
        for (Event e : r.events()) {
            switch (e) {
                case AttackEvent a -> {
                    ev.add(a);
                    player(players, names, a.target(), a.targetName());
                }
                case IncomingHitEvent h -> {
                    ev.add(h);
                    player(players, names, h.attacker(), h.attackerName());
                }
                case JumpEvent j -> ev.add(j);
                case InputEvent in -> ev.add(in);
                case PingEvent p -> {
                    if (p.pingMs() > 0) {
                        pingSum += p.pingMs();
                        pingN++;
                    }
                }
                case SegmentEvent s -> {
                    if (server == null) server = s.server();
                }
                case PositionEvent p -> {
                    if (replay && p.t() - lastPos >= REPLAY_STEP_MS) {
                        pos.add(p);
                        lastPos = p.t();
                        for (PositionEvent.Other x : p.others()) player(players, names, x.id(), null);
                    }
                }
                default -> {
                }
            }
        }
        ev = wtapInputs(ev);
        o.svar(index(strings, server));
        o.uvar(pingN == 0 ? 0 : Math.round((double) pingSum / pingN));
        o.uvar(players.size());
        // Hidden names are not stored: the decoder numbers players itself.
        for (UUID id : players.keySet()) o.svar(anon ? -1 : index(strings, names.get(id)));
        // One stream per field: a column of similar numbers deflates far better than mixed rows.
        Out[] c = new Out[COLS];
        for (int i = 0; i < COLS; i++) c[i] = new Out();
        // Times per kind, each from the previous event of that kind: the decoder merges them back by time.
        long[] prevT = new long[4];
        int[] counts = new int[4];
        int prevPing = 0, prevTPing = 0, prevHPing = 0, prevAPing = 0;
        for (Event e : ev) {
            int kind = e instanceof AttackEvent ? K_ATTACK : e instanceof IncomingHitEvent ? K_INCOMING : e instanceof JumpEvent ? K_JUMP : K_INPUT;
            c[TIME[kind]].uvar(Math.max(0, e.t() - prevT[kind]));
            prevT[kind] = Math.max(prevT[kind], e.t());
            counts[kind]++;
            switch (e) {
                case AttackEvent a -> {
                    c[A_TARGET].uvar(a.target() == null ? 0 : players.get(a.target()) + 1);
                    c[A_OT].raw(a.outcome().ordinal() | (a.type().ordinal() << 2));
                    AttackFlags f = a.flags();
                    c[A_FLAGS].raw((f.sprinting() ? 1 : 0) | (f.onGround() ? 2 : 0) | (f.onClimbable() ? 4 : 0) | (f.inWater() ? 8 : 0)
                            | (f.restricted() ? 16 : 0) | (f.passenger() ? 32 : 0) | (f.sword() ? 64 : 0) | (f.slowHorizontal() ? 128 : 0));
                    c[A_WEAPON].uvar(a.weapon() == null ? 0 : a.weapon().ordinal() + 1);
                    c[A_ITEM].uvar(index(strings, a.item()) + 1);
                    c[A_TEAM].svar(a.team());
                    c[A_FALL].q(f.fallDistance(), 16);
                    c[A_STR].uvar(Math.round(Math.max(0, Math.min(1, a.strength())) * 32));
                    c[A_REACH].q(a.reach(), 100);
                    // The ray length is close to the reach: store the difference.
                    // Only misses need it (out of range or off target); a hit gets the reach back.
                    if (a.outcome() != Outcome.HIT_CONFIRMED) c[A_RAY].q(Double.isFinite(a.reach()) ? a.rayDist() - a.reach() : Double.NaN, 100);
                    c[A_RANGE].q(a.range(), 100);
                    c[A_PING].svar(a.pingMs() - prevPing);
                    prevPing = a.pingMs();
                    c[A_TPING].svar(a.targetPingMs() - prevTPing);
                    prevTPing = a.targetPingMs();
                    c[A_CD].q(a.cooldownTicks(), 8);
                    // The total aim angle is rebuilt from yaw and pitch: only whether it is known is kept.
                    c[A_YAW].q(Double.isNaN(a.aimTotalDeg()) ? Double.NaN : a.aimYawDeg(), 2);
                    c[A_PITCH].q(a.aimPitchDeg(), 2);
                    c[A_U].q(a.hitU(), 16);
                    c[A_V].q(a.hitV(), 16);
                    // Speeds to 0.1 block/s for 3 block hit; sideways and vertical speed and the turn only feed
                    // thresholds (strafing from 2 blocks/s, fast flicks), so 0.25 block/s and 10 degrees/s do.
                    c[A_TOW].q(a.selfTowardBps(), 10);
                    c[A_AWAY].q(a.targetAwayBps(), 10);
                    c[A_SIDE].q(a.targetSideBps(), 4);
                    c[A_VERT].q(a.targetVertBps(), 4);
                    c[A_TURN].q(a.turnDps(), 0.1);
                }
                case IncomingHitEvent h -> {
                    c[H_ATT].uvar(h.attacker() == null ? 0 : players.get(h.attacker()) + 1);
                    int crit = h.attackerCrit() < 0 ? 0 : h.attackerCrit() == 0 ? 1 : 2;
                    c[H_BITS].raw((h.groundedAtHit() ? 1 : 0) | (h.jumpHeld() ? 2 : 0) | (h.knockbackApplied() ? 4 : 0)
                            | (h.kind() == null ? 0 : h.kind() == IncomingHitEvent.Kind.MELEE ? 8 : 16) | (crit << 5));
                    c[H_WEAPON].uvar(h.attackerWeapon() == null ? 0 : h.attackerWeapon().ordinal() + 1);
                    c[H_ITEM].uvar(index(strings, h.attackerItem()) + 1);
                    c[H_PROJ].uvar(index(strings, h.projectile()) + 1);
                    c[H_TEAM].svar(h.team());
                    c[H_PING].svar(h.pingMs() - prevHPing);
                    prevHPing = h.pingMs();
                    c[H_APING].svar(h.attackerPingMs() - prevAPing);
                    prevAPing = h.attackerPingMs();
                    c[H_KB].q(h.kbAway(), 100);
                    c[H_REACH].q(h.reach(), 100);
                    c[H_RANGE].q(h.range(), 100);
                    c[H_TOW].q(h.attackerTowardBps(), 10);
                    c[H_AWAY].q(h.selfAwayBps(), 10);
                }
                case JumpEvent j -> {
                    c[J_BITS].raw((j.onGround() ? 1 : 0) | (j.pressT() < 0 ? 2 : 0));
                    c[J_KEYS].raw(j.keys() + 1);
                    if (j.pressT() >= 0) c[J_PRESS].svar(j.t() - j.pressT());
                }
                case InputEvent in -> c[I_WHAT].raw(in.what().ordinal() | (in.on() ? 4 : 0));
                default -> {
                }
            }
        }
        // Replay positions: our own as steps from the previous sample, others relative to us, in 1/16 block.
        long pt = 0;
        long px = 0, py = 0, pz = 0;
        for (PositionEvent e : pos) {
            c[P_DT].uvar(Math.max(0, e.t() - pt));
            pt = e.t();
            long x = Math.round(e.x() * 16), y = Math.round(e.y() * 16), z = Math.round(e.z() * 16);
            c[P_X].svar(x - px);
            c[P_Y].svar(y - py);
            c[P_Z].svar(z - pz);
            px = x;
            py = y;
            pz = z;
            c[P_YAW].raw(angle(e.yaw()));
            c[P_N].uvar(e.others().size());
            for (PositionEvent.Other x2 : e.others()) {
                c[O_ID].uvar(players.get(x2.id()));
                c[O_X].svar(Math.round(x2.x() * 16) - x);
                c[O_Y].svar(Math.round(x2.y() * 16) - y);
                c[O_Z].svar(Math.round(x2.z() * 16) - z);
                c[O_YAW].raw(angle(x2.yaw()));
            }
        }
        for (int n : counts) o.uvar(n);
        o.uvar(pos.size());
        o.uvar(COLS);
        for (Out s : c) o.block(s);
        o.uvar(strings.size());
        for (String s : strings.keySet()) o.str(s);
    }

    private static int angle(float deg) {
        return Math.floorMod(Math.round(deg * 256 / 360f), 256);
    }

    private static void player(Map<UUID, Integer> players, Map<UUID, String> names, UUID id, String name) {
        if (id == null) return;
        players.putIfAbsent(id, players.size());
        if (name != null) names.putIfAbsent(id, name);
    }

    private static int index(Map<String, Integer> strings, String s) {
        if (s == null) return -1;
        return strings.computeIfAbsent(s, k -> strings.size());
    }

    private static Encoded wrap(Level level, byte[] body, int flags, boolean base64) {
        byte[] data = body;
        Deflater d = new Deflater(9, true);
        d.setInput(body);
        d.finish();
        byte[] buf = new byte[body.length + 64];
        int n = d.deflate(buf);
        boolean done = d.finished();
        d.end();
        if (done && n < body.length) {
            data = Arrays.copyOf(buf, n);
            flags |= FLAG_COMPRESSED;
        }
        byte[] all = new byte[3 + data.length + 4];
        all[0] = (byte) VERSION;
        all[1] = (byte) level.letter;
        all[2] = (byte) flags;
        System.arraycopy(data, 0, all, 3, data.length);
        CRC32 crc = new CRC32();
        crc.update(all, 0, 3 + data.length);
        long c = crc.getValue();
        for (int i = 0; i < 4; i++) all[3 + data.length + i] = (byte) (c >>> (24 - 8 * i));
        String text = base64 ? Base64.getUrlEncoder().withoutPadding().encodeToString(all) : Base32768.encode(all);
        String sep = base64 ? "=" : ":";
        String head = "CD" + VERSION + level.letter;
        List<String> parts = new ArrayList<>();
        if (head.length() + 1 + text.length() <= PART_CHARS) parts.add(head + sep + text);
        else {
            int per = PART_CHARS - head.length() - 8;
            int total = (text.length() + per - 1) / per;
            for (int i = 0; i < total; i++) {
                parts.add(head + (i + 1) + "/" + total + sep + text.substring(i * per, Math.min(text.length(), (i + 1) * per)));
            }
        }
        return new Encoded(level, List.copyOf(parts), all.length);
    }

    // ---- Parse and assemble ----

    private static final Pattern PREFIX = Pattern.compile("CD(\\d+)([SBPR])(?:(\\d{1,3})/(\\d{1,3}))?([:=])");

    /** One part found in pasted text. */
    public record Part(int version, Level level, int index, int total, boolean base64, String payload) {
    }

    /** All code parts in the text, in order; parts are separated by whitespace or start a new prefix. */
    public static List<Part> find(String text) {
        List<Part> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = PREFIX.matcher(text);
        int from = 0;
        while (m.find(from)) {
            boolean b64 = m.group(5).equals("=");
            int i = m.end();
            StringBuilder p = new StringBuilder();
            while (i < text.length()) {
                char c = text.charAt(i);
                if (b64 ? !(Character.isLetterOrDigit(c) && c < 128 || c == '-' || c == '_') : !Base32768.isCodeChar(c)) break;
                p.append(c);
                i++;
            }
            int ver;
            try {
                ver = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                ver = Integer.MAX_VALUE;
            }
            int idx = m.group(3) == null ? 1 : Integer.parseInt(m.group(3)), total = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
            if (p.length() > 0 && total >= 1 && idx >= 1 && idx <= total) out.add(new Part(ver, Level.of(m.group(2).charAt(0)), idx, total, b64, p.toString()));
            from = Math.max(i, m.end());
        }
        return out;
    }

    /** Collects parts of one multi-part code until all are there. */
    public static final class Assembler {
        private Part[] parts;

        /** Adds what the text holds; returns the whole code's parts when complete, otherwise null. */
        public List<Part> add(List<Part> found) {
            for (Part p : found) {
                if (parts == null || parts.length != p.total() || !sameKind(p)) parts = new Part[p.total()];
                parts[p.index() - 1] = p;
            }
            if (parts == null) return null;
            for (Part p : parts) if (p == null) return null;
            List<Part> done = List.of(parts);
            parts = null;
            return done;
        }

        private boolean sameKind(Part p) {
            for (Part q : parts) if (q != null && (q.level() != p.level() || q.base64() != p.base64() || q.version() != p.version())) return false;
            return true;
        }

        public int have() {
            if (parts == null) return 0;
            int n = 0;
            for (Part p : parts) if (p != null) n++;
            return n;
        }

        public int total() {
            return parts == null ? 0 : parts.length;
        }

        public void clear() {
            parts = null;
        }
    }

    // ---- Decode ----

    public static Decoded decode(List<Part> parts) throws CodeException {
        if (parts.isEmpty()) throw new CodeException(t("Кода Combat Data нет", "No Combat Data code"));
        Part first = parts.get(0);
        if (first.version() > VERSION) throw new CodeException(t("Код из более новой версии мода: обновите мод", "The code is from a newer mod version: update the mod"));
        if (first.level() == null) throw new CodeException(t("Неизвестный уровень кода", "Unknown code level"));
        StringBuilder text = new StringBuilder();
        for (Part p : parts) text.append(p.payload());
        byte[] all;
        try {
            all = first.base64() ? Base64.getUrlDecoder().decode(text.toString()) : Base32768.decode(text);
        } catch (IllegalArgumentException e) {
            throw new CodeException(t("Код повреждён: лишние или чужие символы", "The code is damaged: stray characters"));
        }
        if (all.length < 7) throw new CodeException(t("Код обрезан", "The code is cut off"));
        CRC32 crc = new CRC32();
        crc.update(all, 0, all.length - 4);
        long want = 0;
        for (int i = 0; i < 4; i++) want = (want << 8) | (all[all.length - 4 + i] & 0xFF);
        if (crc.getValue() != want) throw new CodeException(t("Код повреждён или вставлен не целиком", "The code is damaged or not pasted whole"));
        if ((all[0] & 0xFF) > VERSION) throw new CodeException(t("Код из более новой версии мода: обновите мод", "The code is from a newer mod version: update the mod"));
        Level level = Level.of((char) all[1]);
        if (level == null) throw new CodeException(t("Неизвестный уровень кода", "Unknown code level"));
        int flags = all[2] & 0xFF;
        byte[] body = Arrays.copyOfRange(all, 3, all.length - 4);
        if ((flags & FLAG_COMPRESSED) != 0) body = inflate(body);
        boolean anon = (flags & FLAG_ANON) != 0;
        try {
            In in = new In(body);
            if (level == Level.SUMMARY) {
                String by = in.str(), player = in.str(), opp = in.str(), server = in.str();
                long wall = in.uvar() * 1000;
                int n = (int) Math.min(in.uvar(), 256);
                long[] v = new long[Math.max(n, SumField.values().length)];
                for (int i = 0; i < n; i++) v[i] = in.svar();
                return new Decoded(level, by, anon, null, new Summary(by, player, opp, server, wall, v));
            }
            String by = in.str(), player = in.str();
            long wall = in.uvar() * 1000, dur = in.uvar();
            Recording r = readBody(in, player, wall, dur, (flags & FLAG_REPLAY) != 0, anon);
            return new Decoded(level, by, anon, r, null);
        } catch (RuntimeException e) {
            throw new CodeException(t("Код повреждён", "The code is damaged"));
        }
    }

    private static byte[] inflate(byte[] data) throws CodeException {
        Inflater inf = new Inflater(true);
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new CodeException(t("Код слишком большой", "The code is too large"));
            }
        } catch (DataFormatException e) {
            throw new CodeException(t("Код повреждён", "The code is damaged"));
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    /** UUIDs made for players of a code: the same kind as Share uses for hidden players. */
    static UUID playerId(int i) {
        return new UUID(Share.FAKE_HI, 1000 + i);
    }

    private static Recording readBody(In o, String player, long wall, long dur, boolean replay, boolean anon) {
        int serverIdx = (int) o.svar();
        int ping = (int) o.uvar();
        int np = (int) Math.min(o.uvar(), MAX_STRINGS);
        int[] nameIdx = new int[np];
        for (int i = 0; i < np; i++) nameIdx[i] = (int) o.svar();
        long[] cnt = new long[4];
        long count = 0;
        for (int k = 0; k < 4; k++) count += cnt[k] = o.uvar();
        long npos = o.uvar();
        if (count > MAX_EVENTS || npos > MAX_EVENTS) throw new IllegalStateException("too many events");
        int cols = (int) o.uvar();
        if (cols < COLS || cols > 4 * COLS) throw new IllegalStateException("columns");
        In[] c = new In[cols];
        for (int i = 0; i < cols; i++) c[i] = o.block();
        int ns = (int) o.uvar();
        if (ns > MAX_STRINGS) throw new IllegalStateException("too many strings");
        String[] str = new String[ns];
        for (int i = 0; i < ns; i++) str[i] = o.str();
        String[] names = new String[np];
        for (int i = 0; i < np; i++) names[i] = anon && nameIdx[i] < 0 ? t("Противник ", "Opponent ") + (i + 1) : s(str, nameIdx[i]);
        List<Event> ev = new ArrayList<>();
        ev.add(new SegmentEvent(0, s(str, serverIdx), "minecraft:overworld", wall));
        if (ping > 0) ev.add(new PingEvent(0, ping));
        long maxT = 0;
        int prevPing = 0, prevTPing = 0, prevHPing = 0, prevAPing = 0;
        for (int kind = 0; kind < 4; kind++) for (long i = 0, t = 0; i < cnt[kind]; i++) {
            t += c[TIME[kind]].uvar();
            maxT = Math.max(maxT, t);
            int tick = (int) (t / 50);
            switch (kind) {
                case K_ATTACK -> {
                    int target = (int) c[A_TARGET].uvar() - 1;
                    int ot = c[A_OT].raw(), fl = c[A_FLAGS].raw();
                    int wi = (int) c[A_WEAPON].uvar();
                    String item = s(str, (int) c[A_ITEM].uvar() - 1);
                    int team = (int) c[A_TEAM].svar();
                    double fall = c[A_FALL].q(16);
                    float strength = c[A_STR].uvar() / 32f;
                    double reach = c[A_REACH].q(100), range = c[A_RANGE].q(100);
                    double ray = (ot & 3) == Outcome.HIT_CONFIRMED.ordinal() ? reach : reach + c[A_RAY].q(100);
                    prevPing += (int) c[A_PING].svar();
                    prevTPing += (int) c[A_TPING].svar();
                    float cd = (float) c[A_CD].q(8);
                    double yaw = c[A_YAW].q(2), pitch = c[A_PITCH].q(2), u = c[A_U].q(16), v = c[A_V].q(16);
                    double aim = Double.isNaN(yaw) ? Double.NaN : Math.hypot(yaw, Double.isNaN(pitch) ? 0 : pitch);
                    double tow = c[A_TOW].q(10), away = c[A_AWAY].q(10), side = c[A_SIDE].q(4), vert = c[A_VERT].q(4), turn = c[A_TURN].q(0.1);
                    AttackFlags f = new AttackFlags((fl & 1) != 0, (fl & 2) != 0, Double.isNaN(fall) ? 0 : fall, (fl & 4) != 0,
                            (fl & 8) != 0, (fl & 16) != 0, (fl & 32) != 0, (fl & 64) != 0, (fl & 128) != 0);
                    Outcome out = Outcome.values()[Math.min(Outcome.values().length - 1, ot & 3)];
                    HitType type = HitType.values()[Math.min(HitType.values().length - 1, (ot >>> 2) & 7)];
                    Weapon w = wi <= 0 || wi > Weapon.values().length ? null : Weapon.values()[wi - 1];
                    ev.add(new AttackEvent(t, tick, target < 0 ? null : playerId(target), target < 0 || target >= np ? null : names[target],
                            out, type, f, strength, reach, aim, yaw, pitch, u, v, prevPing, -1, ray, range, tow, away, prevTPing, cd,
                            item, w, team, side, vert, turn));
                }
                case K_INCOMING -> {
                    int att = (int) c[H_ATT].uvar() - 1;
                    int b = c[H_BITS].raw();
                    int wi = (int) c[H_WEAPON].uvar();
                    String item = s(str, (int) c[H_ITEM].uvar() - 1), proj = s(str, (int) c[H_PROJ].uvar() - 1);
                    int team = (int) c[H_TEAM].svar();
                    prevHPing += (int) c[H_PING].svar();
                    prevAPing += (int) c[H_APING].svar();
                    double kb = c[H_KB].q(100), reach = c[H_REACH].q(100), range = c[H_RANGE].q(100), tow = c[H_TOW].q(10), away = c[H_AWAY].q(10);
                    int crit = (b >>> 5) & 3;
                    IncomingHitEvent.Kind k = (b & 8) != 0 ? IncomingHitEvent.Kind.MELEE : (b & 16) != 0 ? IncomingHitEvent.Kind.PROJECTILE : null;
                    Weapon w = wi <= 0 || wi > Weapon.values().length ? null : Weapon.values()[wi - 1];
                    ev.add(new IncomingHitEvent(t, tick, att < 0 ? null : playerId(att), att < 0 || att >= np ? null : names[att],
                            (b & 1) != 0, (b & 2) != 0, (b & 4) != 0, prevHPing, kb, k, proj, w, item, team, reach, range, tow, away,
                            prevAPing, crit == 0 ? -1 : crit - 1));
                }
                case K_JUMP -> {
                    int b = c[J_BITS].raw();
                    int keys = c[J_KEYS].raw() - 1;
                    long press = (b & 2) != 0 ? -1 : t - c[J_PRESS].svar();
                    ev.add(new JumpEvent(t, tick, (b & 1) != 0, press, keys));
                }
                default -> {
                    int b = c[I_WHAT].raw();
                    InputEvent.Input what = InputEvent.Input.values()[Math.min(InputEvent.Input.values().length - 1, b & 3)];
                    ev.add(new InputEvent(t, tick, what, (b & 4) != 0));
                }
            }
        }
        long pt = 0, x = 0, y = 0, z = 0;
        for (int i = 0; i < npos; i++) {
            pt += c[P_DT].uvar();
            x += c[P_X].svar();
            y += c[P_Y].svar();
            z += c[P_Z].svar();
            float yaw = c[P_YAW].raw() * 360f / 256;
            int no = (int) Math.min(c[P_N].uvar(), 64);
            List<PositionEvent.Other> others = new ArrayList<>(no);
            for (int k = 0; k < no; k++) {
                int id = (int) c[O_ID].uvar();
                long ox = x + c[O_X].svar(), oy = y + c[O_Y].svar(), oz = z + c[O_Z].svar();
                others.add(new PositionEvent.Other(playerId(id), ox / 16.0, oy / 16.0, oz / 16.0, c[O_YAW].raw() * 360f / 256));
            }
            ev.add(new PositionEvent(pt, (int) (pt / 50), x / 16.0, y / 16.0, z / 16.0, yaw, others));
        }
        ev.sort(Comparator.comparingLong(Event::t));
        int missing = replay ? 0 : Features.POSITIONS;
        return new Recording(Recording.SCHEMA, player, new UUID(Share.FAKE_HI, 0), "?", wall, Math.max(dur, maxT), ev, missing);
    }

    private static String s(String[] str, int i) {
        return i < 0 || i >= str.length ? null : str[i];
    }

    // ---- Byte streams ----

    static final class Out {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream();

        void raw(int v) {
            b.write(v & 0xFF);
        }

        void uvar(long v) {
            while ((v & ~0x7FL) != 0) {
                b.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            b.write((int) v);
        }

        void svar(long v) {
            uvar((v << 1) ^ (v >> 63));
        }

        /** A real number in steps of 1/scale; NaN and infinities as 0, the rest shifted by one. */
        void q(double v, double scale) {
            if (!Double.isFinite(v)) {
                uvar(0);
                return;
            }
            long z = Math.round(v * scale);
            uvar(((z << 1) ^ (z >> 63)) + 1);
        }

        void str(String s) {
            if (s == null) {
                uvar(0);
                return;
            }
            byte[] u = s.getBytes(StandardCharsets.UTF_8);
            uvar(u.length + 1L);
            b.writeBytes(u);
        }

        void block(Out o) {
            byte[] x = o.bytes();
            uvar(x.length);
            b.writeBytes(x);
        }

        byte[] bytes() {
            return b.toByteArray();
        }
    }

    static final class In {
        private final byte[] d;
        private int p;
        private final int end;

        In(byte[] d) {
            this(d, 0, d.length);
        }

        private In(byte[] d, int from, int to) {
            this.d = d;
            this.p = from;
            this.end = to;
        }

        int raw() {
            if (p >= end) throw new IllegalStateException("eof");
            return d[p++] & 0xFF;
        }

        long uvar() {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                int c = raw();
                v |= (long) (c & 0x7F) << shift;
                if ((c & 0x80) == 0) return v;
            }
            throw new IllegalStateException("varint");
        }

        long svar() {
            long u = uvar();
            return (u >>> 1) ^ -(u & 1);
        }

        double q(double scale) {
            long u = uvar();
            if (u == 0) return Double.NaN;
            u -= 1;
            return ((u >>> 1) ^ -(u & 1)) / scale;
        }

        /** Control characters removed, cut to MAX_STR; null stays null. */
        String str() {
            long n = uvar();
            if (n == 0) return null;
            int len = (int) (n - 1);
            if (len < 0 || len > end - p || len > 1024) throw new IllegalStateException("string");
            String s = new String(d, p, len, StandardCharsets.UTF_8);
            p += len;
            s = s.replaceAll("\\p{Cntrl}", "");
            return s.length() > MAX_STR ? s.substring(0, MAX_STR) : s;
        }

        In block() {
            long n = uvar();
            if (n < 0 || n > end - p) throw new IllegalStateException("block");
            In b = new In(d, p, p + (int) n);
            p += (int) n;
            return b;
        }
    }
}
