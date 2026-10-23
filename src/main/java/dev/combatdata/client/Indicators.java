package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.AttackEvent;
import dev.combatdata.core.IndicatorConfig;
import dev.combatdata.core.LiveCombat;
import dev.combatdata.core.Notes;
import dev.combatdata.core.Settings;
import dev.combatdata.core.ThreeBlockHit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import static dev.combatdata.core.Lang.t;

/** Feeds live hits into LiveCombat for the indicators and plays their sounds. */
public final class Indicators {
    private static final LiveCombat LIVE = new LiveCombat();

    private Indicators() {
    }

    /** Monotonic, unlike the buffer clock, which restarts after every save. */
    public static long clock() {
        return System.nanoTime() / 1_000_000L;
    }

    public static LiveCombat live() {
        return LIVE;
    }

    private static IndicatorConfig cfg() {
        return CombatDataClient.settings().indicators;
    }

    static void onHit(AttackEvent a) {
        Settings s = CombatDataClient.settings();
        if (s.statFilter().attack(a) != null) return;
        boolean tb = ThreeBlockHit.is(a, s.interpMs);
        double opp = ThreeBlockHit.opponentDistance(a, s.interpMs);
        if (LIVE.hit(clock(), a.target(), a.reach(), tb, opp)) play(IndicatorConfig.Kind.THREE_BLOCK, cfg().tbSound);
    }

    static void onMiss(AttackEvent a) {
        if (CombatDataClient.settings().statFilter().attack(a) != null) return;
        LIVE.miss(clock());
    }

    static void onTaken(boolean held) {
        LIVE.setParams(CombatDataClient.settings().jumpParams());
        verdict(LIVE.taken(clock(), held));
    }

    static void onJump(boolean flyOut) {
        verdict(LIVE.jump(clock(), flyOut));
    }

    static void tick() {
        LIVE.tick(clock());
    }

    public static void newSession() {
        LIVE.resetSession();
    }

    private static void verdict(LiveCombat.JrVerdict v) {
        if (v == null) return;
        IndicatorConfig c = cfg();
        play(IndicatorConfig.Kind.JUMP_RESET, v.result() == LiveCombat.Jr.SUCCESS ? c.jrSoundOk : c.jrSoundBad);
    }

    /** A sound belongs to its indicator: it plays only while that indicator is on. */
    private static void play(IndicatorConfig.Kind k, IndicatorConfig.Sound s) {
        IndicatorConfig c = cfg();
        if (c.enabled && c.on(k)) playNow(Minecraft.getInstance(), s, c.soundVolume, false);
    }

    /** test plays even when the sound is switched off, for the Test button. */
    public static void playNow(Minecraft mc, IndicatorConfig.Sound s, int volume, boolean test) {
        if ((!s.on() && !test) || volume <= 0 || mc.getSoundManager() == null) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound(s.instrument()).value(), Notes.pitch(s.note()), volume / 100f));
    }

    private static Holder<SoundEvent> sound(String id) {
        return switch (id) {
            case "bell" -> SoundEvents.NOTE_BLOCK_BELL;
            case "chime" -> SoundEvents.NOTE_BLOCK_CHIME;
            case "xylophone" -> SoundEvents.NOTE_BLOCK_XYLOPHONE;
            case "iron_xylophone" -> SoundEvents.NOTE_BLOCK_IRON_XYLOPHONE;
            case "harp" -> SoundEvents.NOTE_BLOCK_HARP;
            case "bit" -> SoundEvents.NOTE_BLOCK_BIT;
            case "flute" -> SoundEvents.NOTE_BLOCK_FLUTE;
            case "guitar" -> SoundEvents.NOTE_BLOCK_GUITAR;
            case "banjo" -> SoundEvents.NOTE_BLOCK_BANJO;
            case "cow_bell" -> SoundEvents.NOTE_BLOCK_COW_BELL;
            case "didgeridoo" -> SoundEvents.NOTE_BLOCK_DIDGERIDOO;
            case "bass" -> SoundEvents.NOTE_BLOCK_BASS;
            case "basedrum" -> SoundEvents.NOTE_BLOCK_BASEDRUM;
            case "snare" -> SoundEvents.NOTE_BLOCK_SNARE;
            case "hat" -> SoundEvents.NOTE_BLOCK_HAT;
            default -> SoundEvents.NOTE_BLOCK_PLING;
        };
    }

    public static String instrumentName(String id) {
        return switch (id) {
            case "bell" -> t("Колокол", "Bell");
            case "chime" -> t("Колокольчик", "Chime");
            case "xylophone" -> t("Ксилофон", "Xylophone");
            case "iron_xylophone" -> t("Вибрафон", "Vibraphone");
            case "harp" -> t("Арфа", "Harp");
            case "bit" -> t("8-бит", "Bit");
            case "flute" -> t("Флейта", "Flute");
            case "guitar" -> t("Гитара", "Guitar");
            case "banjo" -> t("Банджо", "Banjo");
            case "cow_bell" -> t("Бубенец", "Cow bell");
            case "didgeridoo" -> t("Диджериду", "Didgeridoo");
            case "bass" -> t("Бас", "Bass");
            case "basedrum" -> t("Бочка", "Bass drum");
            case "snare" -> t("Малый барабан", "Snare");
            case "hat" -> t("Хай-хэт", "Hi-hat");
            default -> t("Пианино", "Pling");
        };
    }

    static void toggle(Minecraft mc) {
        IndicatorConfig c = cfg();
        c.enabled = !c.enabled;
        CombatDataClient.saveSettings();
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(c.enabled
                ? t("Индикаторы включены", "Indicators on") : t("Индикаторы скрыты", "Indicators hidden")));
    }
}
