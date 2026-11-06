package dev.combatdata.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How recordings from one server are rated: jump reset window, 3 block hit delay and range, measured knockback.
 * Holds nothing about other players. Values of -1 / NaN mean "as in the settings".
 */
public final class ServerProfile {
    public enum Preset { VANILLA, KB_SYNC, CUSTOM }

    public enum KbModel { VANILLA, KB_SYNC, UNKNOWN }

    /** SETTINGS: the window from Settings → Advanced, as before profiles existed. */
    public enum JrMode { SETTINGS, CALIBRATION, MANUAL }

    public enum Origin { AUTO, BUILTIN, MANUAL }

    public static final int MAX_NOTE = 200, MAX_NAME = 48;

    public String address = "";
    /** The address also matches its subdomains: stray.gg covers eu.stray.gg. */
    public boolean subdomains;
    public String name = "";
    public Origin origin = Origin.AUTO;
    public long createdMs, lastSeenMs;
    public Preset preset = Preset.VANILLA;
    public KbModel kbModel = KbModel.VANILLA;
    public boolean useMeasuredKb = true;
    public Knockback kb = Knockback.NONE;
    public JrMode jrMode = JrMode.SETTINGS;
    public int jrLowerMs = 0, jrUpperMs = 80, jrPreMs = 200, jrPostMs = 200;
    public Calibrated cal = Calibrated.NONE;
    public int interpMs = -1;
    public double oppRange = Double.NaN;
    public boolean noRecord;
    public String note = "";

    /** Knockback we took on this server, measured over KB_MEASURE_MS without a jump. Horizontal and vertical, blocks. */
    public record Knockback(double normalH, double normalV, int normalN, double sprintH, double sprintV, int sprintN,
                            double allH, int allN, long updatedMs) {
        public static final Knockback NONE = new Knockback(Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN, 0, Double.NaN, 0, 0);
    }

    /** The last jump reset calibration on this server's recordings. */
    public record Calibrated(boolean valid, int lowerMs, int upperMs, int samples, long updatedMs) {
        public static final Calibrated NONE = new Calibrated(false, 0, 0, 0, 0);
    }

    public String shownName() {
        return name == null || name.isBlank() ? address : name;
    }

    public ServerProfile copy() {
        return fromMap(toMap());
    }

    public static ServerProfile create(String address, Preset preset, Origin origin, Settings s, long nowMs) {
        ServerProfile p = new ServerProfile();
        p.address = ServerProfiles.normalize(address);
        p.origin = origin;
        p.createdMs = nowMs;
        p.lastSeenMs = nowMs;
        p.jrLowerMs = s.jrLowerMs;
        p.jrUpperMs = s.jrUpperMs;
        p.jrPreMs = s.jrPreMs;
        p.jrPostMs = s.jrPostMs;
        p.applyPreset(preset);
        return p;
    }

    /**
     * Only starting values. How KnockbackSync shifts the jump reset window is not known in advance, so that preset
     * relies on calibration from this server's own recordings.
     */
    public void applyPreset(Preset p) {
        preset = p;
        switch (p) {
            case VANILLA -> {
                kbModel = KbModel.VANILLA;
                jrMode = JrMode.SETTINGS;
            }
            case KB_SYNC -> {
                kbModel = KbModel.KB_SYNC;
                jrMode = JrMode.CALIBRATION;
            }
            case CUSTOM -> {
            }
        }
    }

    /** Jump reset window used for this server's recordings. */
    public JumpResetEvaluator.Params jumpParams(Settings s) {
        return switch (jrMode) {
            case SETTINGS -> s.jumpParams();
            case MANUAL -> new JumpResetEvaluator.Params(jrLowerMs, jrUpperMs, jrPreMs, jrPostMs, s.jrExcludeHeld, s.jrSeparateFlyOut);
            case CALIBRATION -> cal.valid()
                    ? new JumpResetEvaluator.Params(cal.lowerMs(), cal.upperMs(), s.jrPreMs, s.jrPostMs, s.jrExcludeHeld, s.jrSeparateFlyOut)
                    : s.jumpParams();
        };
    }

    public int interpMs(Settings s) {
        return interpMs >= 0 ? interpMs : s.interpMs;
    }

    /** Jump attempts still missing for a calibration, 0 if there are enough. */
    public int calibrationMissing() {
        return Math.max(0, Calibration.MIN_SAMPLES - cal.samples());
    }

    public ServerProfile clamp() {
        address = ServerProfiles.normalize(address);
        if (name == null) name = "";
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
        if (note == null) note = "";
        if (note.length() > MAX_NOTE) note = note.substring(0, MAX_NOTE);
        name = clean(name);
        note = clean(note);
        jrLowerMs = Math.max(-100, Math.min(200, jrLowerMs));
        jrUpperMs = Math.max(jrLowerMs, Math.min(300, jrUpperMs));
        jrPreMs = Math.max(50, Math.min(500, jrPreMs));
        jrPostMs = Math.max(50, Math.min(500, jrPostMs));
        interpMs = interpMs < 0 ? -1 : Math.min(300, interpMs);
        if (!Double.isFinite(oppRange) || oppRange <= 0) oppRange = Double.NaN;
        else oppRange = Math.max(2.0, Math.min(6.0, oppRange));
        if (kb == null) kb = Knockback.NONE;
        if (cal == null) cal = Calibrated.NONE;
        return this;
    }

    /** Control characters would break the one-line rows in the menu. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        return b.toString().strip();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("address", address);
        m.put("subdomains", subdomains);
        m.put("name", name);
        m.put("origin", origin.name());
        m.put("created", createdMs);
        m.put("lastSeen", lastSeenMs);
        m.put("preset", preset.name());
        m.put("kbModel", kbModel.name());
        m.put("useMeasuredKb", useMeasuredKb);
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("normalH", num(kb.normalH()));
        k.put("normalV", num(kb.normalV()));
        k.put("normalN", kb.normalN());
        k.put("sprintH", num(kb.sprintH()));
        k.put("sprintV", num(kb.sprintV()));
        k.put("sprintN", kb.sprintN());
        k.put("allH", num(kb.allH()));
        k.put("allN", kb.allN());
        k.put("updated", kb.updatedMs());
        m.put("kb", k);
        m.put("jrMode", jrMode.name());
        m.put("jrLowerMs", jrLowerMs);
        m.put("jrUpperMs", jrUpperMs);
        m.put("jrPreMs", jrPreMs);
        m.put("jrPostMs", jrPostMs);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("valid", cal.valid());
        c.put("lower", cal.lowerMs());
        c.put("upper", cal.upperMs());
        c.put("samples", cal.samples());
        c.put("updated", cal.updatedMs());
        m.put("calibration", c);
        m.put("interpMs", interpMs);
        m.put("oppRange", num(oppRange));
        m.put("noRecord", noRecord);
        m.put("note", note);
        return m;
    }

    /** Damaged or missing fields fall back to defaults, like Settings; null if there is no address. */
    public static ServerProfile fromMap(Object o) {
        if (!(o instanceof Map<?, ?> m) || !(m.get("address") instanceof String a) || normalizeOk(a) == null) return null;
        ServerProfile p = new ServerProfile();
        p.address = a;
        if (m.get("subdomains") instanceof Boolean b) p.subdomains = b;
        if (m.get("name") instanceof String s) p.name = s;
        p.origin = parse(Origin.class, m.get("origin"), Origin.AUTO);
        p.createdMs = lng(m.get("created"));
        p.lastSeenMs = lng(m.get("lastSeen"));
        p.preset = parse(Preset.class, m.get("preset"), Preset.VANILLA);
        p.kbModel = parse(KbModel.class, m.get("kbModel"), KbModel.UNKNOWN);
        if (m.get("useMeasuredKb") instanceof Boolean b) p.useMeasuredKb = b;
        if (m.get("kb") instanceof Map<?, ?> k) {
            p.kb = new Knockback(dbl(k.get("normalH")), dbl(k.get("normalV")), (int) lng(k.get("normalN")),
                    dbl(k.get("sprintH")), dbl(k.get("sprintV")), (int) lng(k.get("sprintN")), dbl(k.get("allH")),
                    (int) lng(k.get("allN")), lng(k.get("updated")));
        }
        p.jrMode = parse(JrMode.class, m.get("jrMode"), JrMode.SETTINGS);
        if (m.get("jrLowerMs") instanceof Number n) p.jrLowerMs = n.intValue();
        if (m.get("jrUpperMs") instanceof Number n) p.jrUpperMs = n.intValue();
        if (m.get("jrPreMs") instanceof Number n) p.jrPreMs = n.intValue();
        if (m.get("jrPostMs") instanceof Number n) p.jrPostMs = n.intValue();
        if (m.get("calibration") instanceof Map<?, ?> c) {
            int lo = (int) lng(c.get("lower")), hi = (int) lng(c.get("upper"));
            p.cal = new Calibrated(Boolean.TRUE.equals(c.get("valid")) && hi >= lo, lo, hi, (int) lng(c.get("samples")),
                    lng(c.get("updated")));
        }
        if (m.get("interpMs") instanceof Number n) p.interpMs = n.intValue();
        p.oppRange = dbl(m.get("oppRange"));
        if (m.get("noRecord") instanceof Boolean b) p.noRecord = b;
        if (m.get("note") instanceof String s) p.note = s;
        return p.clamp();
    }

    private static String normalizeOk(String a) {
        String n = ServerProfiles.normalize(a);
        return n.isEmpty() ? null : n;
    }

    private static Object num(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static long lng(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, Object o, E def) {
        if (!(o instanceof String s)) return def;
        try {
            return Enum.valueOf(type, s);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
