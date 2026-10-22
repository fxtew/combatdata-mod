package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

public final class Sounds {
    private Sounds() {
    }

    public static void saved(Minecraft mc) {
        play(mc, true, CombatDataClient.settings());
    }

    public static void failed(Minecraft mc) {
        play(mc, false, CombatDataClient.settings());
    }

    public static void preview(Minecraft mc, Settings s) {
        play(mc, true, s);
    }

    private static void play(Minecraft mc, boolean ok, Settings s) {
        if (!s.soundOnSave || s.soundVolume <= 0) return;
        float vol = s.soundVolume / 100f;
        var sound = ok ? SoundEvents.NOTE_BLOCK_PLING.value() : SoundEvents.NOTE_BLOCK_BASS.value();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, ok ? 1.6f : 0.8f, vol));
    }
}
