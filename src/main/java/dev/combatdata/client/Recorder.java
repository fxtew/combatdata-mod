package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import static dev.combatdata.core.Lang.t;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Recorder {
    private static final long SAVE_COOLDOWN_MS = 5000;
    private static final long PING_INTERVAL_MS = 1000;
    /** A manual save waits this long at most for hit confirmations and knockback measurements. */
    private static final long SAVE_WAIT_MS = 1500;
    /** An autosave keeps this much time before the first attack or hit taken, the rest is idle time. */
    private static final long AUTO_LEAD_MS = 10_000;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final BufferClock CLOCK = new BufferClock();
    private static final RecordingBuffer BUFFER = new RecordingBuffer();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "combatdata-writer");
        t.setDaemon(true);
        return t;
    });

    private static int tick;
    private static boolean connected;
    private static String server = "", dimension = "", playerName;
    private static UUID playerUuid;
    private static long lastPingMs = -PING_INTERVAL_MS;
    private static int lastPing;
    private static long lastSaveWall;
    private static int markers;
    private static boolean saveQueued, menuRequested;
    private static long saveQueuedWall;
    private static long autoCheckedFor = Long.MIN_VALUE;
    private static volatile int savedCount;
    private static volatile long lastSavedWall;
    private static volatile boolean lastSavedAuto;

    private Recorder() {
    }

    public static long now() { return CLOCK.activeMs(System.nanoTime()); }

    public static int tick() { return tick; }

    public static boolean active() { return CLOCK.isRunning(); }

    public static int lastPing() { return lastPing; }

    public static long capacityMs() { return CombatDataClient.settings().capacityMs(); }

    public static long shownMs() { return Math.min(now(), capacityMs()); }

    public static boolean full() { return now() >= capacityMs(); }

    /** Wall time of the last recording written to disk, 0 if none yet. */
    public static long lastSavedWall() { return lastSavedWall; }

    public static boolean lastSavedAuto() { return lastSavedAuto; }

    /** Grows after every recording written to disk, so open lists know to reload. */
    public static int savedCount() { return savedCount; }

    private static long lastCombatMs = Long.MIN_VALUE / 2;

    public static long lastCombatMs() { return lastCombatMs; }

    private static void clearCombat() {
        lastCombatMs = Long.MIN_VALUE / 2;
        autoCheckedFor = Long.MIN_VALUE;
        FightHud.reset();
    }

    public static void add(Event e) {
        if (e instanceof AttackEvent || e instanceof IncomingHitEvent) lastCombatMs = Math.max(lastCombatMs, e.t());
        BUFFER.add(e);
        EventLog.log(e);
    }

    public static void onTick(Minecraft mc) {
        CombatDataClient.applyLanguage(mc);
        tick++;
        long nowNs = System.nanoTime();
        boolean conn = mc.player != null && mc.level != null && mc.getConnection() != null;
        if (conn) {
            if (!connected) Indicators.newSession();
            playerName = mc.player.getName().getString();
            playerUuid = mc.player.getUUID();
            if (mc.isPaused()) CLOCK.pause(nowNs);
            else CLOCK.resume(nowNs);
            String srv = serverName(mc), dim = dimensionName(mc.level);
            if (CombatDataClient.settings().clearOnServerChange && !server.isEmpty() && !srv.equals(server)) {
                if (CombatDataClient.settings().autoSave && worthAutoSave()) saveNow(mc, true, false);
                AttackCapture.flushPending();
                IncomingCapture.clearPending();
                BUFFER.clear();
                InputCapture.reset();
                CLOCK.reset(nowNs);
                lastPingMs = -PING_INTERVAL_MS;
                markers = 0;
                clearCombat();
                CombatDataClient.LOG.info("[CD] смена сервера: буфер очищен");
            }
            if (!connected || !srv.equals(server) || !dim.equals(dimension)) {
                server = srv;
                dimension = dim;
                add(new SegmentEvent(now(), srv, dim, System.currentTimeMillis()));
            }
            long n = now();
            BUFFER.trim(n, capacityMs());
            if (n - lastPingMs >= PING_INTERVAL_MS) {
                samplePing(mc, n);
                lastPingMs = n;
            }
        } else {
            CLOCK.pause(nowNs);
            if (connected) onDisconnect(mc);
        }
        connected = conn;
        AttackCapture.tick();
        IncomingCapture.tick(mc);
        InputCapture.tick(mc);
        PositionCapture.tick(mc);
        FightHud.tick();
        Indicators.tick();
        if (saveQueued && (System.currentTimeMillis() - saveQueuedWall >= SAVE_WAIT_MS
                || (!AttackCapture.hasPending() && !IncomingCapture.hasPending()))) {
            saveQueued = false;
            saveNow(mc, false, connected);
        }
        autoSaveTick(mc);
        handleKeys(mc);
    }

    /** No ticks run in a world after this, so pending saves happen right away. */
    private static void onDisconnect(Minecraft mc) {
        if (saveQueued) {
            saveQueued = false;
            saveNow(mc, false, false);
        } else if (CombatDataClient.settings().autoSave && worthAutoSave()) {
            saveNow(mc, true, false);
        }
        AttackCapture.flushPending();
        IncomingCapture.flushPending();
    }

    /** Closing the game: save what would be lost and wait for the writer, which is a daemon thread. */
    public static void onStopping(Minecraft mc) {
        if (connected) onDisconnect(mc);
        WRITER.shutdown();
        try {
            WRITER.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The fight is over when nothing happened for the fight summary delay; then the buffer is saved once. */
    private static void autoSaveTick(Minecraft mc) {
        Settings s = CombatDataClient.settings();
        if (!s.autoSave || !active() || saveQueued) return;
        long last = lastCombatMs, n = now();
        if (last < 0 || last > n || n - last < s.fsDelaySec * 1000L || last == autoCheckedFor) return;
        autoCheckedFor = last;
        if (worthAutoSave()) saveNow(mc, true, true);
    }

    /** Only fights that pass the minimum fight size are saved, so a single poke does not make a file. */
    private static boolean worthAutoSave() {
        if (!BUFFER.hasCombatEvents()) return false;
        StatFilter sf = CombatDataClient.settings().statFilter();
        return !Stats.fights(sf.apply(BUFFER.combatEvents()).kept(), Stats.FIGHT_GAP_MS, sf).isEmpty();
    }

    private static final long RESET_CONFIRM_MS = 3000;
    private static long resetAskedAt = -RESET_CONFIRM_MS;

    public static void reset(Minecraft mc) {
        AttackCapture.clearPending();
        IncomingCapture.clearPending();
        BUFFER.clear();
        InputCapture.reset();
        CLOCK.reset(System.nanoTime());
        lastPingMs = -PING_INTERVAL_MS;
        markers = 0;
        saveQueued = false;
        clearCombat();
        resetAskedAt = -RESET_CONFIRM_MS;
        if (connected) BUFFER.add(new SegmentEvent(0, server, dimension, System.currentTimeMillis()));
        CombatDataClient.LOG.info("[CD] буфер сброшен");
        chat(mc, t("Буфер сброшен, запись идёт с 0:00.", "Buffer reset, recording from 0:00."));
    }

    static void requestReset(Minecraft mc) {
        long wall = System.currentTimeMillis();
        if (wall - resetAskedAt <= RESET_CONFIRM_MS) {
            reset(mc);
            return;
        }
        resetAskedAt = wall;
        chat(mc, t("Нажмите ещё раз в течение 3 с, чтобы сбросить буфер без сохранения.",
                "Press again within 3 s to reset the buffer without saving."));
    }

    static void addMarker(Minecraft mc) {
        if (!active()) {
            chat(mc, t("Запись на паузе, метку поставить нельзя.", "Recording is paused, cannot set a marker."));
            return;
        }
        add(new MarkerEvent(now(), t("Метка ", "Marker ") + (++markers)));
        chat(mc, t("Метка ", "Marker ") + markers + t(" поставлена на ", " set at ") + EventLog.formatTime(now()));
    }

    /** Opened on the next tick: a command runs from the chat screen, which closes right after it. */
    static void requestMenu() {
        menuRequested = true;
    }

    private static void handleKeys(Minecraft mc) {
        while (CombatDataClient.SAVE.consumeClick()) {
            if (mc.hasShiftDown()) requestReset(mc);
            else save(mc);
        }
        while (CombatDataClient.RESET.consumeClick()) requestReset(mc);
        while (CombatDataClient.MARKER.consumeClick()) {
            if (active()) addMarker(mc);
        }
        while (CombatDataClient.INDICATORS.consumeClick()) Indicators.toggle(mc);
        boolean open = menuRequested;
        menuRequested = false;
        while (CombatDataClient.MENU.consumeClick()) open = true;
        if (open && mc.screen == null) mc.setScreen(new dev.combatdata.ui.MenuScreen());
    }

    public static int bufferSize() { return BUFFER.size(); }

    public static Recording liveCopy() {
        return BUFFER.copy(now(), capacityMs(), playerName, playerUuid, mcVersion(), System.currentTimeMillis())
                .withNotRecorded(CombatDataClient.settings().notRecordedMask());
    }

    public static boolean hasCombatEvents() { return BUFFER.hasCombatEvents(); }

    public static long bufferVersion() { return BUFFER.version(); }

    public static String currentServer() { return server; }

    /**
     * Manual save. It runs a moment later: a hit right before F9 is still waiting for the server's
     * confirmation, and saving at once would store it as unconfirmed.
     */
    public static void save(Minecraft mc) {
        if (saveQueued) return;
        long wall = System.currentTimeMillis();
        if (wall - lastSaveWall < SAVE_COOLDOWN_MS) {
            chat(mc, t("Подождите пару секунд перед следующим сохранением.", "Wait a couple of seconds before saving again."));
            Sounds.failed(mc);
            return;
        }
        if (!BUFFER.hasCombatEvents() && !AttackCapture.hasPending() && !IncomingCapture.hasPending()) {
            chat(mc, t("В буфере нет боевых событий, сохранять нечего.", "No combat events in the buffer, nothing to save."));
            Sounds.failed(mc);
            return;
        }
        lastSaveWall = wall;
        saveQueued = true;
        saveQueuedWall = wall;
    }

    private static void saveNow(Minecraft mc, boolean auto, boolean newSegment) {
        AttackCapture.flushPending();
        IncomingCapture.flushPending();
        long wall = System.currentTimeMillis();
        if (!BUFFER.hasCombatEvents()) {
            if (!auto) {
                chat(mc, t("В буфере нет боевых событий, сохранять нечего.", "No combat events in the buffer, nothing to save."));
                Sounds.failed(mc);
            }
            return;
        }
        Recording snap = BUFFER.snapshot(now(), capacityMs(), playerName, playerUuid, mcVersion(), wall)
                .withNotRecorded(CombatDataClient.settings().notRecordedMask());
        long first = snap.firstCombatMs();
        Recording rec = auto && first > AUTO_LEAD_MS ? snap.trimmedFrom(first - AUTO_LEAD_MS) : snap;
        BUFFER.clear();
        InputCapture.reset();
        CLOCK.reset(System.nanoTime());
        lastPingMs = -PING_INTERVAL_MS;
        markers = 0;
        if (auto) {
            // Keep the fight summary card on screen; only the buffer times start over.
            lastCombatMs = Long.MIN_VALUE / 2;
            autoCheckedFor = Long.MIN_VALUE;
            FightHud.newBuffer();
        } else {
            clearCombat();
        }
        if (newSegment && connected) BUFFER.add(new SegmentEvent(0, server, dimension, wall));
        Path dir = Recordings.dir();
        Path file = uniqueFile(dir, LocalDateTime.now().format(FILE_TIME) + "_" + sanitize(server));
        WRITER.execute(() -> {
            try {
                Files.createDirectories(dir);
                Cdrec.write(file, rec, modVersion());
                ProgressData.invalidate();
                CombatDataClient.LOG.info("[CD] {}: {} ({} событий)", auto ? "автосохранение" : "запись сохранена",
                        file, rec.events().size());
                lastSavedAuto = auto;
                lastSavedWall = System.currentTimeMillis();
                savedCount++;
                Recordings.Cleanup c = Recordings.cleanup(CombatDataClient.settings());
                mc.execute(() -> {
                    // An autosave is shown only by the HUD label, so chat is not spammed after every fight.
                    if (!auto) {
                        chat(mc, t("Запись сохранена: ", "Recording saved: ") + file.getFileName());
                        Sounds.saved(mc);
                    }
                    reportCleanup(mc, c);
                });
            } catch (Exception ex) {
                CombatDataClient.LOG.error("[CD] не удалось сохранить запись", ex);
                mc.execute(() -> {
                    chat(mc, t("Не удалось сохранить запись: ", "Could not save the recording: ") + ex.getMessage());
                    Sounds.failed(mc);
                });
            }
        });
    }

    /** An autosave and a manual save can land in the same second. */
    private static Path uniqueFile(Path dir, String stem) {
        Path f = dir.resolve(stem + Cdrec.EXT);
        for (int i = 2; Files.exists(f) && i < 100; i++) f = dir.resolve(stem + "_" + i + Cdrec.EXT);
        return f;
    }

    public static void cleanupNow(Minecraft mc) {
        WRITER.execute(() -> {
            Recordings.Cleanup c = Recordings.cleanup(CombatDataClient.settings());
            mc.execute(() -> reportCleanup(mc, c));
        });
    }

    private static void reportCleanup(Minecraft mc, Recordings.Cleanup c) {
        if (c.deleted() == 0) return;
        String text = t("Автоудаление: удалено записей ", "Auto-delete: recordings removed: ") + c.deleted() + " ("
                + String.format(java.util.Locale.ROOT, t("%.1f МБ", "%.1f MB"), c.bytes() / 1048576.0) + ")";
        CombatDataClient.LOG.info("[CD] {}", text);
        chat(mc, text);
    }

    private static void samplePing(Minecraft mc, long n) {
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        if (info != null && info.getLatency() > 0) {
            lastPing = info.getLatency();
            BUFFER.add(new PingEvent(n, lastPing));
        }
    }

    static void chat(Minecraft mc, String text) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[Combat Data] " + text));
    }

    private static String serverName(Minecraft mc) {
        ServerData sd = mc.getCurrentServer();
        if (sd != null && sd.ip != null && !sd.ip.isBlank()) return sd.ip;
        return mc.hasSingleplayerServer() ? "singleplayer" : "unknown";
    }

    private static String dimensionName(ClientLevel level) {
        return level.dimension().identifier().toString();
    }

    private static String sanitize(String s) {
        String r = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return r.isEmpty() ? "server" : r;
    }

    public static String clock(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private static String mcVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("26.1.2");
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer(CombatDataClient.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }
}
