package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.PositionEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/** Records only during combat (a hit in the last 5 s or a player within 6 blocks) to keep files small. */
public final class PositionCapture {
    private static final long FIGHT_MS = 5000;
    private static final double NEAR = 6.0, RADIUS = 8.0;

    private PositionCapture() {
    }

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!Recorder.active() || p == null || mc.level == null || !CombatDataClient.settings().recordPositions) return;
        long now = Recorder.now(), last = Recorder.lastCombatMs();
        // The buffer clock restarts at 0 after save/reset, but the last-hit time is from the old buffer.
        boolean fight = last <= now && now - last <= FIGHT_MS;
        List<PositionEvent.Other> others = new ArrayList<>();
        for (Player o : mc.level.players()) {
            if (o == p || !o.isAlive() || o.isSpectator()) continue;
            double d = o.distanceTo(p);
            if (d > RADIUS) continue;
            if (d <= NEAR) fight = true;
            others.add(new PositionEvent.Other(o.getUUID(), o.getX(), o.getY(), o.getZ(), o.getYRot()));
        }
        if (!fight) return;
        Recorder.add(new PositionEvent(now, Recorder.tick(), p.getX(), p.getY(), p.getZ(), p.getYRot(), List.copyOf(others)));
    }
}
