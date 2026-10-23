package dev.combatdata.client;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import static dev.combatdata.core.Lang.t;

public final class Commands {
    private Commands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                ClientCommandManager.literal("combatdata")
                        .executes(c -> {
                            c.getSource().sendFeedback(Component.literal(t(
                                    "Combat Data: /combatdata open меню, save сохранить, marker метка, reset сбросить буфер",
                                    "Combat Data: /combatdata open menu, save to save, marker to mark, reset to reset the buffer")));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("open").executes(c -> {
                            Recorder.requestMenu();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("marker").executes(c -> {
                            Minecraft mc = c.getSource().getClient();
                            Safe.run("команда marker", () -> Recorder.addMarker(mc));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reset")
                                .executes(c -> {
                                    c.getSource().sendFeedback(Component.literal(t(
                                            "[Combat Data] Буфер будет удалён без сохранения. Чтобы сбросить, введите /combatdata reset confirm",
                                            "[Combat Data] The buffer will be discarded without saving. To reset, type /combatdata reset confirm")));
                                    return 1;
                                })
                                .then(ClientCommandManager.literal("confirm").executes(c -> {
                                    Minecraft mc = c.getSource().getClient();
                                    Safe.run("команда reset", () -> Recorder.reset(mc));
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("save").executes(c -> {
                            Minecraft mc = c.getSource().getClient();
                            Safe.run("команда save", () -> Recorder.save(mc));
                            return 1;
                        }))));
    }
}
