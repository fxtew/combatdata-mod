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

public final class Recorder {
    private static final long SAVE_COOLDOWN_MS = 5000;
    private static final long PING_INTERVAL_MS = 1000;
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

    private Recorder() {
    }

    public static long now() { return CLOCK.activeMs(System.nanoTime()); }

    public static int tick() { return tick; }

    public static boolean active() { return CLOCK.isRunning(); }

    public static int lastPing() { return lastPing; }

    public static long capacityMs() { return CombatDataClient.settings().capacityMs(); }

    public static long shownMs() { return Math.min(now(), capacityMs()); }

    public static boolean full() { return now() >= capacityMs(); }

    public static long lastSaveWall() { return lastSaveWall; }

    private static long lastCombatMs = Long.MIN_VALUE / 2;

    public static long lastCombatMs() { return lastCombatMs; }

    private static void clearCombat() {
        lastCombatMs = Long.MIN_VALUE / 2;
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
            playerName = mc.player.getName().getString();
            playerUuid = mc.player.getUUID();
            if (mc.isPaused()) CLOCK.pause(nowNs);
            else CLOCK.resume(nowNs);
            String srv = serverName(mc), dim = dimensionName(mc.level);
            if (CombatDataClient.settings().clearOnServerChange && !server.isEmpty() && !srv.equals(server)) {
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
            if (connected) {
                AttackCapture.flushPending();
                IncomingCapture.clearPending();
            }
        }
        connected = conn;
        AttackCapture.tick();
        IncomingCapture.tick(mc);
        InputCapture.tick(mc);
        PositionCapture.tick(mc);
        FightHud.tick();
        handleKeys(mc);
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

    private static void handleKeys(Minecraft mc) {
        while (CombatDataClient.SAVE.consumeClick()) {
            if (mc.hasShiftDown()) requestReset(mc);
            else save(mc);
        }
        while (CombatDataClient.RESET.consumeClick()) requestReset(mc);
        while (CombatDataClient.MARKER.consumeClick()) {
            if (active()) {
                add(new MarkerEvent(now(), t("Метка ", "Marker ") + (++markers)));
                chat(mc, t("Метка ", "Marker ") + markers + t(" поставлена на ", " set at ") + EventLog.formatTime(now()));
            }
        }
        while (CombatDataClient.MENU.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new dev.combatdata.ui.MenuScreen());
        }
    }

    public static int bufferSize() { return BUFFER.size(); }

    public static Recording liveCopy() {
        return BUFFER.copy(now(), capacityMs(), playerName, playerUuid, mcVersion(), System.currentTimeMillis())
                .withNotRecorded(CombatDataClient.settings().notRecordedMask());
    }

    public static boolean hasCombatEvents() { return BUFFER.hasCombatEvents(); }

    public static long bufferVersion() { return BUFFER.version(); }

    public static String currentServer() { return server; }

    public static void save(Minecraft mc) {
        long wall = System.currentTimeMillis();
        if (wall - lastSaveWall < SAVE_COOLDOWN_MS) {
            chat(mc, t("Подождите пару секунд перед следующим сохранением.", "Wait a couple of seconds before saving again."));
            Sounds.failed(mc);
            return;
        }
        AttackCapture.flushPending();
        IncomingCapture.flushPending();
        if (!BUFFER.hasCombatEvents()) {
            chat(mc, t("В буфере нет боевых событий, сохранять нечего.", "No combat events in the buffer, nothing to save."));
            Sounds.failed(mc);
            return;
        }
        Recording rec = BUFFER.snapshot(now(), capacityMs(), playerName, playerUuid, mcVersion(), wall)
                .withNotRecorded(CombatDataClient.settings().notRecordedMask());
        BUFFER.clear();
        InputCapture.reset();
        CLOCK.reset(System.nanoTime());
        lastPingMs = -PING_INTERVAL_MS;
        lastSaveWall = wall;
        markers = 0;
        clearCombat();
        if (connected) BUFFER.add(new SegmentEvent(0, server, dimension, wall));
        Path dir = Recordings.dir();
        Path file = dir.resolve(LocalDateTime.now().format(FILE_TIME) + "_" + sanitize(server) + Cdrec.EXT);
        WRITER.execute(() -> {
            try {
                Files.createDirectories(dir);
                Cdrec.write(file, rec, modVersion());
                ProgressData.invalidate();
                CombatDataClient.LOG.info("[CD] запись сохранена: {} ({} событий)", file, rec.events().size());
                Recordings.Cleanup c = Recordings.cleanup(CombatDataClient.settings());
                mc.execute(() -> {
                    chat(mc, t("Запись сохранена: ", "Recording saved: ") + file.getFileName());
                    Sounds.saved(mc);
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
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Combat Data] " + text), false);
    }

    private static String serverName(Minecraft mc) {
        ServerData sd = mc.getCurrentServer();
        if (sd != null && sd.ip != null && !sd.ip.isBlank()) return sd.ip;
        return mc.hasSingleplayerServer() ? "singleplayer" : "unknown";
    }

    /** ResourceKey prints as "ResourceKey[minecraft:dimension / minecraft:overworld]"; take the second part. */
    private static String dimensionName(ClientLevel level) {
        String s = String.valueOf(level.dimension());
        int i = s.lastIndexOf(" / ");
        return i >= 0 ? s.substring(i + 3).replace("]", "") : s;
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
