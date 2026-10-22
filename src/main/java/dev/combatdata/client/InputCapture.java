package dev.combatdata.client;

import dev.combatdata.core.InputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Polled once per tick: the game itself reads keys per tick (KeyboardInput.tick), so finer timing is pointless. */
public final class InputCapture {
    private static final InputEvent.Input[] ALL = InputEvent.Input.values();
    private static final boolean[] last = new boolean[ALL.length];
    private static boolean known;

    private InputCapture() {
    }

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!Recorder.active() || p == null || !dev.combatdata.CombatDataClient.settings().recordInput) {
            known = false;
            return;
        }
        var o = mc.options;
        boolean[] now = {o.keyUp.isDown(), o.keyDown.isDown(), o.keySprint.isDown(), p.isSprinting()};
        long t = Recorder.now();
        int tick = Recorder.tick();
        for (int i = 0; i < ALL.length; i++) {
            if (!known || now[i] != last[i]) {
                if (known || now[i]) Recorder.add(new InputEvent(t, tick, ALL[i], now[i]));
                last[i] = now[i];
            }
        }
        known = true;
    }

    public static void reset() {
        known = false;
    }
}
