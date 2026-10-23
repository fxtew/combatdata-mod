package dev.combatdata;

import dev.combatdata.client.RecHud;
import dev.combatdata.client.Recorder;
import dev.combatdata.client.Safe;
import dev.combatdata.core.Settings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import dev.combatdata.client.Commands;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public final class CombatDataClient implements ClientModInitializer {
    public static final String MOD_ID = "combatdata";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static KeyMapping MENU;
    public static KeyMapping SAVE;
    public static KeyMapping MARKER;
    public static KeyMapping RESET;
    public static KeyMapping INDICATORS;

    private static Settings settings = new Settings();

    public static Settings settings() {
        return settings;
    }

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("combatdata.json");
    }

    public static void applyLanguage(net.minecraft.client.Minecraft mc) {
        dev.combatdata.core.Lang.apply(settings.language, mc.options != null ? mc.options.languageCode : null);
    }

    public static void saveSettings() {
        try {
            settings.clamp().save(configFile());
        } catch (IOException e) {
            LOG.error("[CD] не удалось сохранить настройки", e);
        }
    }

    @Override
    public void onInitializeClient() {
        settings = Settings.load(configFile());
        saveSettings();
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        MENU = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.combatdata.open_menu", GLFW.GLFW_KEY_RIGHT_SHIFT, category));
        SAVE = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.combatdata.save_buffer", GLFW.GLFW_KEY_F9, category));
        MARKER = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.combatdata.marker", GLFW.GLFW_KEY_UNKNOWN, category));
        RESET = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.combatdata.reset_buffer", GLFW.GLFW_KEY_UNKNOWN, category));
        INDICATORS = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.combatdata.toggle_indicators", GLFW.GLFW_KEY_UNKNOWN, category));
        Commands.register();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> Safe.run("тик", () -> Recorder.onTick(mc)));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Safe.run("выход из игры", () -> Recorder.onStopping(mc)));
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(MOD_ID, "rec"),
                (g, delta) -> Safe.run("надпись REC", () -> RecHud.extract(g, delta)));
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(MOD_ID, "fight"),
                (g, delta) -> Safe.run("итог боя", () -> dev.combatdata.client.FightHud.extract(g, delta)));
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(MOD_ID, "indicators"),
                (g, delta) -> Safe.run("индикаторы", () -> dev.combatdata.client.IndicatorHud.extract(g, delta)));
        LOG.info("Combat Data загружен");
    }
}
