package dev.combatdata.core;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** HUD indicators. Every one shows only your own data about what already happened. All are off by default. */
public final class IndicatorConfig {
    public enum Kind {
        HEALTH("health"), REACH("reach"), COMBO("combo"), PING("ping"), CHARGE("charge"), JUMP_RESET("jumpReset"),
        THREE_BLOCK("threeBlock"), SESSION("session");

        public final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    public static final double MIN_SCALE = 0.5, MAX_SCALE = 3.0;
    public static final int HP_RED = 0, HP_WHITE = 1, HP_GOLD = 2, HP_BY_HEALTH = 3;
    public static final int CHARGE_BAR = 0, CHARGE_NUMBER = 1;

    /** Placement and background of one indicator. */
    public static final class Element {
        public boolean on;
        public int anchor, dx, dy;
        public double scale = 1.0;
        public boolean bg = true;

        Element(int anchor, int dx, int dy, boolean bg) {
            this.anchor = anchor;
            this.dx = dx;
            this.dy = dy;
            this.bg = bg;
        }

        public boolean samePlace(Element o) {
            return anchor == o.anchor && dx == o.dx && dy == o.dy && scale == o.scale;
        }

        public void placeFrom(Element o) {
            anchor = o.anchor;
            dx = o.dx;
            dy = o.dy;
            scale = o.scale;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("on", on);
            m.put("anchor", anchor);
            m.put("dx", dx);
            m.put("dy", dy);
            m.put("scale", scale);
            m.put("bg", bg);
            return m;
        }

        void read(Map<?, ?> m) {
            if (m.get("on") instanceof Boolean b) on = b;
            if (m.get("anchor") instanceof Number n) anchor = n.intValue();
            if (m.get("dx") instanceof Number n) dx = n.intValue();
            if (m.get("dy") instanceof Number n) dy = n.intValue();
            if (m.get("scale") instanceof Number n) scale = n.doubleValue();
            if (m.get("bg") instanceof Boolean b) bg = b;
        }

        void clamp(Element def) {
            if (anchor < 0 || anchor > 8) {
                anchor = def.anchor;
                dx = def.dx;
                dy = def.dy;
            }
            dx = Math.max(-4000, Math.min(4000, dx));
            dy = Math.max(-4000, Math.min(4000, dy));
            scale = Double.isFinite(scale) ? Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale)) : 1.0;
        }
    }

    public record Sound(boolean on, String instrument, int note) {
        public Sound withOn(boolean v) {
            return new Sound(v, instrument, note);
        }

        public Sound withInstrument(String v) {
            return new Sound(on, v, note);
        }

        public Sound withNote(int v) {
            return new Sound(on, instrument, Notes.clampNote(v));
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("on", on);
            m.put("instrument", instrument);
            m.put("note", note);
            return m;
        }

        static Sound read(Object o, Sound def) {
            if (!(o instanceof Map<?, ?> m)) return def;
            boolean on = m.get("on") instanceof Boolean b ? b : def.on();
            String ins = m.get("instrument") instanceof String s && Notes.known(s) ? s : def.instrument();
            int note = m.get("note") instanceof Number n ? Notes.clampNote(n.intValue()) : def.note();
            return new Sound(on, ins, note);
        }
    }

    public boolean enabled = true;
    public boolean labels = true, colors = true, shadow = true;
    public int bgOpacity = 45, soundVolume = 70;
    public final Map<Kind, Element> elements = new EnumMap<>(Kind.class);
    public int hpDecimals = 2, hpColor = HP_RED;
    public boolean hpAbsorption = true, hpHearts = false, hpIcon = false;
    public int reachDecimals = 2, reachHideSec = 0;
    public boolean comboHideZero = true;
    public int chargeStyle = CHARGE_BAR;
    public boolean chargeHideFull = true;
    public int jrShowSec = 2;
    public Sound jrSoundOk = new Sound(true, "pling", 18), jrSoundBad = new Sound(false, "bass", 6);
    public int tbShowSec = 2;
    public boolean tbDistance = true;
    public Sound tbSound = new Sound(true, "chime", 15);
    public boolean sesAccuracy = true, sesHits = true, sesJr = true, sesCombo = false, sesThreeBlock = false;

    public IndicatorConfig() {
        for (Kind k : Kind.values()) elements.put(k, defaultElement(k));
    }

    /**
     * Health sits centered right above the hunger bar (x from center+10 to center+91, top at height-39).
     * The counters stack at the left edge, the short flashes go around the crosshair.
     */
    public static Element defaultElement(Kind k) {
        return switch (k) {
            case HEALTH -> new Element(HudAnchor.BOTTOM_CENTER, 50, -39, false);
            case REACH -> new Element(HudAnchor.MIDDLE_LEFT, 4, -24, true);
            case COMBO -> new Element(HudAnchor.MIDDLE_LEFT, 4, -8, true);
            case PING -> new Element(HudAnchor.MIDDLE_LEFT, 4, 8, true);
            case SESSION -> new Element(HudAnchor.MIDDLE_LEFT, 4, 24, true);
            case CHARGE -> new Element(HudAnchor.CENTER, 0, 12, false);
            case JUMP_RESET -> new Element(HudAnchor.CENTER, 0, 28, true);
            case THREE_BLOCK -> new Element(HudAnchor.CENTER, 0, -26, true);
        };
    }

    public Element get(Kind k) {
        return elements.get(k);
    }

    public boolean on(Kind k) {
        return elements.get(k).on;
    }

    public boolean anyOn() {
        for (Element e : elements.values()) if (e.on) return true;
        return false;
    }

    public IndicatorConfig clamp() {
        bgOpacity = Math.max(0, Math.min(100, bgOpacity));
        soundVolume = Math.max(0, Math.min(100, soundVolume));
        for (Kind k : Kind.values()) elements.get(k).clamp(defaultElement(k));
        hpDecimals = Math.max(0, Math.min(3, hpDecimals));
        hpColor = hpColor >= HP_RED && hpColor <= HP_BY_HEALTH ? hpColor : HP_RED;
        reachDecimals = Math.max(1, Math.min(3, reachDecimals));
        reachHideSec = Math.max(0, Math.min(60, reachHideSec));
        chargeStyle = chargeStyle == CHARGE_NUMBER ? CHARGE_NUMBER : CHARGE_BAR;
        jrShowSec = Math.max(1, Math.min(10, jrShowSec));
        tbShowSec = Math.max(1, Math.min(10, tbShowSec));
        return this;
    }

    public IndicatorConfig copy() {
        return fromMap(toMap());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("labels", labels);
        m.put("colors", colors);
        m.put("shadow", shadow);
        m.put("bgOpacity", bgOpacity);
        m.put("soundVolume", soundVolume);
        Map<String, Object> el = new LinkedHashMap<>();
        for (Kind k : Kind.values()) el.put(k.key, elements.get(k).toMap());
        m.put("elements", el);
        m.put("hpDecimals", hpDecimals);
        m.put("hpColor", hpColor);
        m.put("hpAbsorption", hpAbsorption);
        m.put("hpHearts", hpHearts);
        m.put("hpIcon", hpIcon);
        m.put("reachDecimals", reachDecimals);
        m.put("reachHideSec", reachHideSec);
        m.put("comboHideZero", comboHideZero);
        m.put("chargeStyle", chargeStyle);
        m.put("chargeHideFull", chargeHideFull);
        m.put("jrShowSec", jrShowSec);
        m.put("jrSoundOk", jrSoundOk.toMap());
        m.put("jrSoundBad", jrSoundBad.toMap());
        m.put("tbShowSec", tbShowSec);
        m.put("tbDistance", tbDistance);
        m.put("tbSound", tbSound.toMap());
        m.put("sesAccuracy", sesAccuracy);
        m.put("sesHits", sesHits);
        m.put("sesJr", sesJr);
        m.put("sesCombo", sesCombo);
        m.put("sesThreeBlock", sesThreeBlock);
        return m;
    }

    public static IndicatorConfig fromMap(Object o) {
        IndicatorConfig c = new IndicatorConfig();
        if (!(o instanceof Map<?, ?> m)) return c;
        if (m.get("enabled") instanceof Boolean b) c.enabled = b;
        if (m.get("labels") instanceof Boolean b) c.labels = b;
        if (m.get("colors") instanceof Boolean b) c.colors = b;
        if (m.get("shadow") instanceof Boolean b) c.shadow = b;
        if (m.get("bgOpacity") instanceof Number n) c.bgOpacity = n.intValue();
        if (m.get("soundVolume") instanceof Number n) c.soundVolume = n.intValue();
        if (m.get("elements") instanceof Map<?, ?> el) {
            for (Kind k : Kind.values()) if (el.get(k.key) instanceof Map<?, ?> e) c.elements.get(k).read(e);
        }
        if (m.get("hpDecimals") instanceof Number n) c.hpDecimals = n.intValue();
        if (m.get("hpColor") instanceof Number n) c.hpColor = n.intValue();
        if (m.get("hpAbsorption") instanceof Boolean b) c.hpAbsorption = b;
        if (m.get("hpHearts") instanceof Boolean b) c.hpHearts = b;
        if (m.get("hpIcon") instanceof Boolean b) c.hpIcon = b;
        if (m.get("reachDecimals") instanceof Number n) c.reachDecimals = n.intValue();
        if (m.get("reachHideSec") instanceof Number n) c.reachHideSec = n.intValue();
        if (m.get("comboHideZero") instanceof Boolean b) c.comboHideZero = b;
        if (m.get("chargeStyle") instanceof Number n) c.chargeStyle = n.intValue();
        if (m.get("chargeHideFull") instanceof Boolean b) c.chargeHideFull = b;
        if (m.get("jrShowSec") instanceof Number n) c.jrShowSec = n.intValue();
        c.jrSoundOk = Sound.read(m.get("jrSoundOk"), c.jrSoundOk);
        c.jrSoundBad = Sound.read(m.get("jrSoundBad"), c.jrSoundBad);
        if (m.get("tbShowSec") instanceof Number n) c.tbShowSec = n.intValue();
        if (m.get("tbDistance") instanceof Boolean b) c.tbDistance = b;
        c.tbSound = Sound.read(m.get("tbSound"), c.tbSound);
        if (m.get("sesAccuracy") instanceof Boolean b) c.sesAccuracy = b;
        if (m.get("sesHits") instanceof Boolean b) c.sesHits = b;
        if (m.get("sesJr") instanceof Boolean b) c.sesJr = b;
        if (m.get("sesCombo") instanceof Boolean b) c.sesCombo = b;
        if (m.get("sesThreeBlock") instanceof Boolean b) c.sesThreeBlock = b;
        return c.clamp();
    }

    /** Health with the given number of decimals; whole numbers round up so a living player never shows 0. */
    public static String formatHealth(double hp, int decimals) {
        if (!Double.isFinite(hp)) return "—";
        if (decimals <= 0) return String.valueOf((long) Math.ceil(hp - 1e-9));
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f", hp);
    }
}
