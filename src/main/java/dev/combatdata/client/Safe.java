package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static dev.combatdata.core.Lang.t;

/**
 * Every entry point (mixins, tick, HUD) goes through run(): errors are logged once per call site with a stack
 * trace, then only counted, and reported in chat once per session. VirtualMachineErrors are not caught.
 */
public final class Safe {
    private static final Map<String, Integer> ERRORS = new ConcurrentHashMap<>();
    private static volatile boolean warned;

    private Safe() {
    }

    public static void run(String where, Runnable action) {
        try {
            action.run();
        } catch (Exception | LinkageError | StackOverflowError e) {
            report(where, e);
        }
    }

    static void report(String where, Throwable e) {
        int n = ERRORS.merge(where, 1, Integer::sum);
        if (n == 1) {
            CombatDataClient.LOG.error("[CD] ошибка в {}, мод продолжает работу (дальше без стека)", where, e);
        } else if (n == 10 || n == 100 || n % 1000 == 0) {
            CombatDataClient.LOG.warn("[CD] ошибка в {} повторилась {} раз: {}", where, n, e.toString());
        }
        if (!warned) {
            warned = true;
            try {
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Combat Data] "
                            + t("Внутренняя ошибка, подробности в latest.log. Игра продолжает работать, запись может быть неполной.",
                            "Internal error, see latest.log. The game keeps running, the recording may be incomplete.")), false);
                });
            } catch (Exception | LinkageError ignored) {
            }
        }
    }

    public static int count() {
        return ERRORS.values().stream().mapToInt(Integer::intValue).sum();
    }
}
