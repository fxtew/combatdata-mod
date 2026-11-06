package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.OtherAnimEvent;
import dev.combatdata.core.OtherStateEvent;
import dev.combatdata.core.PositionEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records only during combat (a hit in the last 5 s or a player within 6 blocks) to keep files small.
 * Besides the smoothed positions for the replay, nearby players get an OtherStateEvent with the packet data
 * whenever it changes.
 */
public final class PositionCapture {
    private static final long FIGHT_MS = 5000;
    private static final double NEAR = 6.0, RADIUS = 8.0;
    /** Capture time is logged with debugLog on, averaged over this many ticks of combat. */
    private static final int TIMING_TICKS = 1200;

    private static final Map<UUID, OtherStateEvent> LAST = new HashMap<>();
    private static final Map<UUID, String> LAST_ITEM = new HashMap<>();
    private static boolean fight;
    private static long timingNs, stateNs;
    private static int timingTicks, timingOthers;

    private PositionCapture() {
    }

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!Recorder.active() || p == null || mc.level == null || !CombatDataClient.settings().recordPositions) {
            reset();
            return;
        }
        long start = System.nanoTime();
        long now = Recorder.now(), last = Recorder.lastCombatMs();
        boolean inFight = last <= now && now - last <= FIGHT_MS;
        List<Player> near = new ArrayList<>();
        List<PositionEvent.Other> others = new ArrayList<>();
        for (Player o : mc.level.players()) {
            if (o == p || !o.isAlive() || o.isSpectator()) continue;
            double d = o.distanceTo(p);
            if (d > RADIUS) continue;
            if (d <= NEAR) inFight = true;
            near.add(o);
            others.add(new PositionEvent.Other(o.getUUID(), o.getX(), o.getY(), o.getZ(), o.getYRot()));
        }
        fight = inFight;
        if (!inFight) {
            reset();
            return;
        }
        int tick = Recorder.tick();
        Recorder.add(new PositionEvent(now, tick, p.getX(), p.getY(), p.getZ(), p.getYRot(), List.copyOf(others)));
        long stateStart = System.nanoTime();
        states(near, now, tick);
        long end = System.nanoTime();
        timing(end - start, end - stateStart, near.size());
    }

    private static void states(List<Player> near, long now, int tick) {
        // Someone who left starts from scratch when back, item included.
        if (!LAST_ITEM.isEmpty()) {
            List<UUID> ids = new ArrayList<>(near.size());
            for (Player o : near) ids.add(o.getUUID());
            LAST.keySet().retainAll(ids);
            LAST_ITEM.keySet().retainAll(ids);
        }
        for (Player o : near) {
            UUID id = o.getUUID();
            // The interpolation target is where the last packet put the player; position() falls back to the
            // entity position once interpolation is done.
            InterpolationHandler ih = o.getInterpolation();
            Vec3 pos = ih != null ? ih.position() : o.position();
            float yaw = ih != null ? ih.yRot() : o.getYRot(), pitch = ih != null ? ih.xRot() : o.getXRot();
            String item = Weapons.id(o.getMainHandItem());
            if (item == null) item = OtherStateEvent.EMPTY_HAND;
            String prevItem = LAST_ITEM.get(id);
            boolean itemChanged = !LAST_ITEM.containsKey(id) || !java.util.Objects.equals(prevItem, item);
            OtherStateEvent e = new OtherStateEvent(now, tick, id, pos.x, pos.y, pos.z, yaw, pitch, o.onGround(),
                    o.isSprinting(), itemChanged ? item : null);
            if (!itemChanged && e.sameState(LAST.get(id))) continue;
            LAST.put(id, e);
            LAST_ITEM.put(id, item);
            Recorder.add(e);
        }
    }

    /** ClientboundAnimatePacket SWING_MAIN_HAND about another player. */
    public static void onSwing(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!fight || !Recorder.active() || p == null || !(entity instanceof Player o) || o == p
                || !CombatDataClient.settings().recordPositions || o.distanceTo(p) > RADIUS) return;
        Recorder.add(new OtherAnimEvent(Recorder.now(), Recorder.tick(), o.getUUID(), OtherAnimEvent.Anim.SWING));
    }

    /** Damage event of our own hit on another player: when the server sent them the knockback too. */
    public static void onHurt(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!fight || !Recorder.active() || p == null || !(entity instanceof Player o) || o == p
                || !CombatDataClient.settings().recordPositions) return;
        Recorder.add(new OtherAnimEvent(Recorder.now(), Recorder.tick(), o.getUUID(), OtherAnimEvent.Anim.HURT));
    }

    private static void reset() {
        fight = false;
        LAST.clear();
        LAST_ITEM.clear();
    }

    private static void timing(long totalNs, long otherNs, int others) {
        if (!CombatDataClient.settings().debugLog) return;
        timingNs += totalNs;
        stateNs += otherNs;
        timingOthers += others;
        if (++timingTicks < TIMING_TICKS) return;
        CombatDataClient.LOG.info(String.format(java.util.Locale.ROOT,
                "[CD] захват позиций: %.1f мкс/тик, из них данные соседей %.1f мкс, соседей в среднем %.1f",
                timingNs / 1000.0 / timingTicks, stateNs / 1000.0 / timingTicks, timingOthers / (double) timingTicks));
        timingNs = stateNs = 0;
        timingTicks = timingOthers = 0;
    }
}
