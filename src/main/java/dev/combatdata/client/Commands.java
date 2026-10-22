package dev.combatdata.client;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import static dev.combatdata.core.Lang.t;

public final class Commands {
    private Commands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                ClientCommands.literal("combatdata")
                        .executes(c -> {
                            c.getSource().sendFeedback(Component.literal(t(
                                    "Combat Data: /combatdata reset сбросить буфер, /combatdata save сохранить",
                                    "Combat Data: /combatdata reset to reset the buffer, /combatdata save to save")));
                            return 1;
                        })
                        .then(ClientCommands.literal("reset").executes(c -> {
                            Minecraft mc = c.getSource().getClient();
                            Safe.run("команда reset", () -> Recorder.reset(mc));
                            return 1;
                        }))
                        .then(ClientCommands.literal("save").executes(c -> {
                            Minecraft mc = c.getSource().getClient();
                            Safe.run("команда save", () -> Recorder.save(mc));
                            return 1;
                        }))));
    }
}
