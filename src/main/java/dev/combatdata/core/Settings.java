package dev.combatdata.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Unknown or invalid values fall back to defaults, so the file can be edited by hand. */
public final class Settings {
    public static final int MIN_BUFFER_MINUTES = 1, MAX_BUFFER_MINUTES = 120;
    public static final double DEFAULT_HUD_X = 0.01, DEFAULT_HUD_Y = 0.02;
    public static final double MIN_HUD_SCALE = 0.5, MAX_HUD_SCALE = 2.0;

    public int bufferMinutes = 10;
    public boolean hudVisible = true;
    /** Position as a fraction of free screen space: 0 = left/top, 1 = right/bottom. */
    public double hudX = DEFAULT_HUD_X, hudY = DEFAULT_HUD_Y;
    public double hudScale = 1.0;
    public static final int HUD_FULL = 0, HUD_TIME = 1, HUD_DOT = 2;
    public static final int HUD_COLOR_STATE = 0, HUD_COLOR_WHITE = 1, HUD_COLOR_GREY = 2;
    public int hudBgOpacity = 60;
    public int hudFormat = HUD_FULL, hudTextColor = HUD_COLOR_STATE;
    public boolean hudShowDot = true, hudBlink = true, hudShadow = false, hudHideWhenPaused = false;
    public boolean debugLog = false;
    public boolean autoSave = false;
    public boolean clearOnServerChange = false;
    public int jrLowerMs = 0, jrUpperMs = 80, jrPreMs = 200, jrPostMs = 200;
    public boolean jrExcludeHeld = false;
    public boolean jrSeparateFlyOut = true;
    /** Position interpolation delay on the opponent's client, ms (3 block hit). */
    public int interpMs = ThreeBlockHit.DEFAULT_INTERP_MS;
    public int storageLimitMb = 0, autoDeleteDays = 0;
    public boolean soundOnSave = true;
    public int soundVolume = 50;
    public int language = Lang.AUTO;
    public static final int MIN_PING_LIMIT = 50, MAX_PING_LIMIT = 1000, MAX_FIGHT_HITS = 50;
    public boolean statSwordOnly = true, statHandNoTrade = true, statNoTeammates = true;
    public boolean statNoNpc = false, statHighPing = false;
    public int statPingLimitMs = 150, statMinFightHits = 3;
    public int tradeGapMs = (int) Trades.DEFAULT_GAP_MS;
    public boolean tradeByWeight = false;
    public boolean recordPositions = true;
    public boolean recordInput = true, recordKnockback = true, recordTaken = true;
    public static final int REC_FULL = 0, REC_LITE = 1, REC_CUSTOM = 2;
    public boolean fightSummary = true;
    public boolean fsTitle = true, fsHits = true, fsJr = true, fsCombo = true, fsTrades = true, fsReach = false,
            fsThreeBlock = false, fsTaken = false, fsColors = true;
    public int fsLayout = FightSummary.LAYOUT_TWO_LINES, fsBgOpacity = 63, fsDelaySec = 10, fsShowSec = 5;
    public double fsScale = 1.0;
    public boolean fsNearRec = true;
    public double fsX = DEFAULT_HUD_X, fsY = 0.08;
    public String theme = Themes.DEFAULT_ID;
    public List<ThemeSpec> customThemes = new ArrayList<>();
    /** Changed from its own menu tab right away, so it is not part of the Apply/Cancel draft. */
    public IndicatorConfig indicators = new IndicatorConfig();

    public int notRecordedMask() {
        int m = 0;
        if (!recordPositions) m |= Features.POSITIONS;
        if (!recordInput) m |= Features.INPUT;
        if (!recordKnockback) m |= Features.KNOCKBACK;
        if (!recordTaken) m |= Features.TAKEN;
        return m;
    }

    public int recordMode() {
        int m = notRecordedMask();
        return m == 0 ? REC_FULL : m == Features.LITE ? REC_LITE : REC_CUSTOM;
    }

    public void setRecordMode(int mode) {
        if (mode == REC_CUSTOM) return;
        int m = mode == REC_LITE ? Features.LITE : 0;
        recordPositions = (m & Features.POSITIONS) == 0;
        recordInput = (m & Features.INPUT) == 0;
        recordKnockback = (m & Features.KNOCKBACK) == 0;
        recordTaken = (m & Features.TAKEN) == 0;
    }

    public FightSummary.Style fightStyle() {
        return new FightSummary.Style(fsTitle, fsHits, fsJr, fsCombo, fsTrades, fsReach, fsThreeBlock, fsTaken, fsLayout, fsColors);
    }

    public ThemeSpec themeSpec() {
        return Themes.find(theme, customThemes);
    }

    public Trades.Params tradeParams() {
        return new Trades.Params(tradeGapMs, tradeByWeight);
    }

    public long capacityMs() {
        return bufferMinutes * 60_000L;
    }

    public Settings clamp() {
        bufferMinutes = Math.max(MIN_BUFFER_MINUTES, Math.min(MAX_BUFFER_MINUTES, bufferMinutes));
        hudX = unit(hudX, DEFAULT_HUD_X);
        hudY = unit(hudY, DEFAULT_HUD_Y);
        hudScale = Double.isFinite(hudScale) ? Math.max(MIN_HUD_SCALE, Math.min(MAX_HUD_SCALE, hudScale)) : 1.0;
        jrLowerMs = Math.max(-100, Math.min(200, jrLowerMs));
        jrUpperMs = Math.max(jrLowerMs, Math.min(300, jrUpperMs));
        jrPreMs = Math.max(50, Math.min(500, jrPreMs));
        jrPostMs = Math.max(50, Math.min(500, jrPostMs));
        interpMs = Math.max(0, Math.min(300, interpMs));
        hudBgOpacity = Math.max(0, Math.min(100, hudBgOpacity));
        hudFormat = hudFormat >= HUD_FULL && hudFormat <= HUD_DOT ? hudFormat : HUD_FULL;
        hudTextColor = hudTextColor >= HUD_COLOR_STATE && hudTextColor <= HUD_COLOR_GREY ? hudTextColor : HUD_COLOR_STATE;
        storageLimitMb = Math.max(0, Math.min(100_000, storageLimitMb));
        autoDeleteDays = Math.max(0, Math.min(3650, autoDeleteDays));
        soundVolume = Math.max(0, Math.min(100, soundVolume));
        language = language >= Lang.AUTO && language <= Lang.EN ? language : Lang.AUTO;
        statPingLimitMs = Math.max(MIN_PING_LIMIT, Math.min(MAX_PING_LIMIT, statPingLimitMs));
        statMinFightHits = Math.max(1, Math.min(MAX_FIGHT_HITS, statMinFightHits));
        tradeGapMs = Math.max(500, Math.min(5000, tradeGapMs));
        fsLayout = fsLayout >= FightSummary.LAYOUT_TWO_LINES && fsLayout <= FightSummary.LAYOUT_COLUMN ? fsLayout : FightSummary.LAYOUT_TWO_LINES;
        fsBgOpacity = Math.max(0, Math.min(100, fsBgOpacity));
        fsDelaySec = Math.max(3, Math.min(30, fsDelaySec));
        fsShowSec = Math.max(2, Math.min(15, fsShowSec));
        fsScale = Double.isFinite(fsScale) ? Math.max(MIN_HUD_SCALE, Math.min(MAX_HUD_SCALE, fsScale)) : 1.0;
        fsX = unit(fsX, DEFAULT_HUD_X);
        fsY = unit(fsY, 0.08);
        // Custom themes: unique ids, at most MAX_CUSTOM, no ';' in names (code separator).
        List<ThemeSpec> themes = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ThemeSpec t : customThemes) {
            if (t == null || Themes.isBuiltin(t.id()) || !ids.add(t.id()) || themes.size() >= Themes.MAX_CUSTOM) continue;
            String n = Themes.cleanName(t.name());
            themes.add(n.equals(t.name()) && !n.isEmpty() ? t : t.withName(n.isEmpty() ? t.id() : n));
        }
        customThemes = themes;
        if (theme == null || !Themes.find(theme, customThemes).id().equals(theme)) theme = Themes.DEFAULT_ID;
        if (indicators == null) indicators = new IndicatorConfig();
        indicators.clamp();
        return this;
    }

    public StatFilter statFilter() {
        return new StatFilter(statSwordOnly ? StatFilter.WeaponView.SWORD : StatFilter.WeaponView.ALL,
                statNoTeammates, statNoNpc, statHighPing, statPingLimitMs, statMinFightHits, statHandNoTrade);
    }

    public JumpResetEvaluator.Params jumpParams() {
        return new JumpResetEvaluator.Params(jrLowerMs, jrUpperMs, jrPreMs, jrPostMs, jrExcludeHeld, jrSeparateFlyOut);
    }

    public Settings copy() {
        return fromMap(toMap());
    }

    /** Indicators are not copied: the draft holds a stale copy of them. */
    public void copyFrom(Settings o) {
        Settings c = o.copy();
        bufferMinutes = c.bufferMinutes;
        hudVisible = c.hudVisible;
        hudX = c.hudX;
        hudY = c.hudY;
        hudScale = c.hudScale;
        debugLog = c.debugLog;
        autoSave = c.autoSave;
        clearOnServerChange = c.clearOnServerChange;
        jrLowerMs = c.jrLowerMs;
        jrUpperMs = c.jrUpperMs;
        jrPreMs = c.jrPreMs;
        jrPostMs = c.jrPostMs;
        jrExcludeHeld = c.jrExcludeHeld;
        jrSeparateFlyOut = c.jrSeparateFlyOut;
        interpMs = c.interpMs;
        hudBgOpacity = c.hudBgOpacity;
        hudFormat = c.hudFormat;
        hudTextColor = c.hudTextColor;
        hudShowDot = c.hudShowDot;
        hudBlink = c.hudBlink;
        hudShadow = c.hudShadow;
        hudHideWhenPaused = c.hudHideWhenPaused;
        storageLimitMb = c.storageLimitMb;
        autoDeleteDays = c.autoDeleteDays;
        soundOnSave = c.soundOnSave;
        soundVolume = c.soundVolume;
        language = c.language;
        statSwordOnly = c.statSwordOnly;
        statHandNoTrade = c.statHandNoTrade;
        statNoTeammates = c.statNoTeammates;
        statNoNpc = c.statNoNpc;
        statHighPing = c.statHighPing;
        statPingLimitMs = c.statPingLimitMs;
        statMinFightHits = c.statMinFightHits;
        tradeGapMs = c.tradeGapMs;
        tradeByWeight = c.tradeByWeight;
        recordPositions = c.recordPositions;
        recordInput = c.recordInput;
        recordKnockback = c.recordKnockback;
        recordTaken = c.recordTaken;
        fightSummary = c.fightSummary;
        fsTitle = c.fsTitle;
        fsHits = c.fsHits;
        fsJr = c.fsJr;
        fsCombo = c.fsCombo;
        fsTrades = c.fsTrades;
        fsReach = c.fsReach;
        fsThreeBlock = c.fsThreeBlock;
        fsTaken = c.fsTaken;
        fsColors = c.fsColors;
        fsLayout = c.fsLayout;
        fsBgOpacity = c.fsBgOpacity;
        fsDelaySec = c.fsDelaySec;
        fsShowSec = c.fsShowSec;
        fsScale = c.fsScale;
        fsNearRec = c.fsNearRec;
        fsX = c.fsX;
        fsY = c.fsY;
        theme = c.theme;
        customThemes = c.customThemes;
    }

    /** Positions and indicators are excluded: they apply immediately. */
    public int diffCount(Settings o) {
        Map<String, Object> a = toMap(), b = o.toMap();
        a.remove("hudX");
        a.remove("hudY");
        a.remove("fsX");
        a.remove("fsY");
        a.remove("indicators");
        int n = 0;
        for (Map.Entry<String, Object> e : a.entrySet()) if (!e.getValue().equals(b.get(e.getKey()))) n++;
        return n;
    }

    private static double unit(double v, double def) {
        return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : def;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bufferMinutes", bufferMinutes);
        m.put("hudVisible", hudVisible);
        m.put("hudX", hudX);
        m.put("hudY", hudY);
        m.put("hudScale", hudScale);
        m.put("debugLog", debugLog);
        m.put("autoSave", autoSave);
        m.put("clearOnServerChange", clearOnServerChange);
        m.put("jrLowerMs", jrLowerMs);
        m.put("jrUpperMs", jrUpperMs);
        m.put("jrPreMs", jrPreMs);
        m.put("jrPostMs", jrPostMs);
        m.put("jrExcludeHeld", jrExcludeHeld);
        m.put("jrSeparateFlyOut", jrSeparateFlyOut);
        m.put("interpMs", interpMs);
        m.put("hudBgOpacity", hudBgOpacity);
        m.put("hudFormat", hudFormat);
        m.put("hudTextColor", hudTextColor);
        m.put("hudShowDot", hudShowDot);
        m.put("hudBlink", hudBlink);
        m.put("hudShadow", hudShadow);
        m.put("hudHideWhenPaused", hudHideWhenPaused);
        m.put("storageLimitMb", storageLimitMb);
        m.put("autoDeleteDays", autoDeleteDays);
        m.put("soundOnSave", soundOnSave);
        m.put("soundVolume", soundVolume);
        m.put("language", language);
        m.put("statSwordOnly", statSwordOnly);
        m.put("statHandNoTrade", statHandNoTrade);
        m.put("statNoTeammates", statNoTeammates);
        m.put("statNoNpc", statNoNpc);
        m.put("statHighPing", statHighPing);
        m.put("statPingLimitMs", statPingLimitMs);
        m.put("statMinFightHits", statMinFightHits);
        m.put("tradeGapMs", tradeGapMs);
        m.put("tradeByWeight", tradeByWeight);
        m.put("recordPositions", recordPositions);
        m.put("recordInput", recordInput);
        m.put("recordKnockback", recordKnockback);
        m.put("recordTaken", recordTaken);
        m.put("fightSummary", fightSummary);
        m.put("fsTitle", fsTitle);
        m.put("fsHits", fsHits);
        m.put("fsJr", fsJr);
        m.put("fsCombo", fsCombo);
        m.put("fsTrades", fsTrades);
        m.put("fsReach", fsReach);
        m.put("fsThreeBlock", fsThreeBlock);
        m.put("fsTaken", fsTaken);
        m.put("fsColors", fsColors);
        m.put("fsLayout", fsLayout);
        m.put("fsBgOpacity", fsBgOpacity);
        m.put("fsDelaySec", fsDelaySec);
        m.put("fsShowSec", fsShowSec);
        m.put("fsScale", fsScale);
        m.put("fsNearRec", fsNearRec);
        m.put("fsX", fsX);
        m.put("fsY", fsY);
        m.put("theme", theme);
        List<Object> themes = new ArrayList<>();
        for (ThemeSpec t : customThemes) themes.add(t.toMap());
        m.put("customThemes", themes);
        m.put("indicators", indicators.toMap());
        return m;
    }

    public static Settings fromMap(Map<?, ?> m) {
        Settings s = new Settings();
        if (m.get("bufferMinutes") instanceof Number n) s.bufferMinutes = n.intValue();
        if (m.get("hudVisible") instanceof Boolean b) s.hudVisible = b;
        if (m.get("hudX") instanceof Number n) s.hudX = n.doubleValue();
        if (m.get("hudY") instanceof Number n) s.hudY = n.doubleValue();
        if (m.get("hudScale") instanceof Number n) s.hudScale = n.doubleValue();
        if (m.get("debugLog") instanceof Boolean b) s.debugLog = b;
        if (m.get("autoSave") instanceof Boolean b) s.autoSave = b;
        if (m.get("clearOnServerChange") instanceof Boolean b) s.clearOnServerChange = b;
        if (m.get("jrLowerMs") instanceof Number n) s.jrLowerMs = n.intValue();
        if (m.get("jrUpperMs") instanceof Number n) s.jrUpperMs = n.intValue();
        if (m.get("jrPreMs") instanceof Number n) s.jrPreMs = n.intValue();
        if (m.get("jrPostMs") instanceof Number n) s.jrPostMs = n.intValue();
        if (m.get("jrExcludeHeld") instanceof Boolean b) s.jrExcludeHeld = b;
        if (m.get("jrSeparateFlyOut") instanceof Boolean b) s.jrSeparateFlyOut = b;
        if (m.get("interpMs") instanceof Number n) s.interpMs = n.intValue();
        if (m.get("hudBgOpacity") instanceof Number n) s.hudBgOpacity = n.intValue();
        if (m.get("hudFormat") instanceof Number n) s.hudFormat = n.intValue();
        if (m.get("hudTextColor") instanceof Number n) s.hudTextColor = n.intValue();
        if (m.get("hudShowDot") instanceof Boolean b) s.hudShowDot = b;
        if (m.get("hudBlink") instanceof Boolean b) s.hudBlink = b;
        if (m.get("hudShadow") instanceof Boolean b) s.hudShadow = b;
        if (m.get("hudHideWhenPaused") instanceof Boolean b) s.hudHideWhenPaused = b;
        if (m.get("storageLimitMb") instanceof Number n) s.storageLimitMb = n.intValue();
        if (m.get("autoDeleteDays") instanceof Number n) s.autoDeleteDays = n.intValue();
        if (m.get("soundOnSave") instanceof Boolean b) s.soundOnSave = b;
        if (m.get("soundVolume") instanceof Number n) s.soundVolume = n.intValue();
        if (m.get("language") instanceof Number n) s.language = n.intValue();
        if (m.get("statSwordOnly") instanceof Boolean b) s.statSwordOnly = b;
        if (m.get("statHandNoTrade") instanceof Boolean b) s.statHandNoTrade = b;
        if (m.get("statNoTeammates") instanceof Boolean b) s.statNoTeammates = b;
        if (m.get("statNoNpc") instanceof Boolean b) s.statNoNpc = b;
        if (m.get("statHighPing") instanceof Boolean b) s.statHighPing = b;
        if (m.get("statPingLimitMs") instanceof Number n) s.statPingLimitMs = n.intValue();
        if (m.get("statMinFightHits") instanceof Number n) s.statMinFightHits = n.intValue();
        if (m.get("tradeGapMs") instanceof Number n) s.tradeGapMs = n.intValue();
        if (m.get("tradeByWeight") instanceof Boolean b) s.tradeByWeight = b;
        if (m.get("recordPositions") instanceof Boolean b) s.recordPositions = b;
        if (m.get("recordInput") instanceof Boolean b) s.recordInput = b;
        if (m.get("recordKnockback") instanceof Boolean b) s.recordKnockback = b;
        if (m.get("recordTaken") instanceof Boolean b) s.recordTaken = b;
        if (m.get("fightSummary") instanceof Boolean b) s.fightSummary = b;
        if (m.get("fsTitle") instanceof Boolean b) s.fsTitle = b;
        if (m.get("fsHits") instanceof Boolean b) s.fsHits = b;
        if (m.get("fsJr") instanceof Boolean b) s.fsJr = b;
        if (m.get("fsCombo") instanceof Boolean b) s.fsCombo = b;
        if (m.get("fsTrades") instanceof Boolean b) s.fsTrades = b;
        if (m.get("fsReach") instanceof Boolean b) s.fsReach = b;
        if (m.get("fsThreeBlock") instanceof Boolean b) s.fsThreeBlock = b;
        if (m.get("fsTaken") instanceof Boolean b) s.fsTaken = b;
        if (m.get("fsColors") instanceof Boolean b) s.fsColors = b;
        if (m.get("fsLayout") instanceof Number n) s.fsLayout = n.intValue();
        if (m.get("fsBgOpacity") instanceof Number n) s.fsBgOpacity = n.intValue();
        if (m.get("fsDelaySec") instanceof Number n) s.fsDelaySec = n.intValue();
        if (m.get("fsShowSec") instanceof Number n) s.fsShowSec = n.intValue();
        if (m.get("fsScale") instanceof Number n) s.fsScale = n.doubleValue();
        if (m.get("fsNearRec") instanceof Boolean b) s.fsNearRec = b;
        if (m.get("fsX") instanceof Number n) s.fsX = n.doubleValue();
        if (m.get("fsY") instanceof Number n) s.fsY = n.doubleValue();
        if (m.get("theme") instanceof String th) s.theme = th;
        if (m.get("customThemes") instanceof List<?> l) {
            for (Object o : l) {
                ThemeSpec t = ThemeSpec.fromMap(o, Themes.defaultTheme());
                if (t != null) s.customThemes.add(t);
            }
        }
        s.indicators = IndicatorConfig.fromMap(m.get("indicators"));
        return s.clamp();
    }

    public static Settings load(Path file) {
        try {
            if (!Files.exists(file)) return new Settings();
            Object o = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            return o instanceof Map<?, ?> m ? fromMap(m) : new Settings();
        } catch (IOException | RuntimeException e) {
            return new Settings();
        }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(toMap()), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
