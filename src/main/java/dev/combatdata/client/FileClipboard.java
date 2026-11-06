package dev.combatdata.client;

import net.minecraft.util.Util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Puts a file (not its text) on the system clipboard, so Ctrl+V in a chat app attaches it. The game's clipboard
 * only holds text, so this goes through the system tools. Blocking: call off the game thread.
 */
final class FileClipboard {
    private FileClipboard() {
    }

    /** Returns false when the system has no tool for it or the tool failed. */
    static boolean copy(Path file) {
        String path = file.toAbsolutePath().toString();
        try {
            return switch (Util.getPlatform()) {
                case WINDOWS -> {
                    // The path goes through the environment, not the command line, so no quoting can break it.
                    ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                            "Set-Clipboard -LiteralPath $env:CD_CLIP_FILE");
                    pb.environment().put("CD_CLIP_FILE", path);
                    yield run(pb, null);
                }
                case OSX -> run(new ProcessBuilder("osascript", "-e", "on run argv", "-e",
                        "set the clipboard to (POSIX file (item 1 of argv))", "-e", "end run", path), null);
                default -> {
                    String uri = file.toAbsolutePath().toUri().toString() + "\n";
                    boolean wayland = System.getenv("WAYLAND_DISPLAY") != null;
                    yield wayland && run(new ProcessBuilder("wl-copy", "-t", "text/uri-list"), uri)
                            || run(new ProcessBuilder("xclip", "-selection", "clipboard", "-t", "text/uri-list", "-i"), uri);
                }
            };
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean run(ProcessBuilder pb, String input) {
        try {
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            if (input != null) {
                try (OutputStream o = p.getOutputStream()) {
                    o.write(input.getBytes(StandardCharsets.UTF_8));
                }
            } else p.getOutputStream().close();
            // xclip and wl-copy keep serving the clipboard in the background; a tool still running is fine.
            if (!p.waitFor(10, TimeUnit.SECONDS)) return List.of("xclip", "wl-copy").contains(pb.command().get(0));
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
