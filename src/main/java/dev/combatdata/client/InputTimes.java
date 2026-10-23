package dev.combatdata.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

/** Raw attack click time, more precise than the tick in which the game processed the attack. */
public final class InputTimes {
    private static final long MAX_AGE_MS = 200;
    private static volatile long lastLeftClickNs;

    private InputTimes() {
    }

    public static void onMouseButton(int button, int action) {
        if (action == 1 && isAttackButton(button)) lastLeftClickNs = System.nanoTime();
    }

    /** Attack may be rebound to another mouse button; it is compared by the saved key name, e.g. "key.mouse.left". */
    private static boolean isAttackButton(int button) {
        var options = Minecraft.getInstance().options;
        if (options == null) return button == 0;
        return options.keyAttack.saveString().equals(InputConstants.Type.MOUSE.getOrCreate(button).getName());
    }

    /** Click time in buffer ms if within the last 200 ms, else -1. Consumes the click. */
    public static long clickAt(long nowMs) {
        long c = lastLeftClickNs;
        if (c == 0) return -1;
        long ageMs = (System.nanoTime() - c) / 1_000_000L;
        lastLeftClickNs = 0;
        if (ageMs > MAX_AGE_MS) return -1;
        return Math.max(0, nowMs - ageMs);
    }
}
