package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import static dev.combatdata.core.Lang.t;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Export and import of recording files, in the background; callbacks run on the game thread. */
public final class Exchange {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

    private Exchange() {
    }

    /** fight: index into Stats.fights of the single recording, -1 for the whole recording. */
    public record Request(List<Recordings.Entry> entries, int fight, Share.Options options) {
    }

    /** Writes the export to combatdata/exports and opens the folder. done gets the message to show. */
    public static void export(Request req, Consumer<String> done, Consumer<String> failed) {
        Tasks.run(() -> {
            try {
                Path out = write(req);
                Minecraft.getInstance().execute(() -> {
                    Util.getPlatform().openUri(Recordings.exportsDir().toUri());
                    done.accept(t("Сохранено: ", "Saved: ") + out.getFileName());
                });
            } catch (Exception e) {
                CombatDataClient.LOG.warn("[CD] экспорт: {}", e.toString());
                Minecraft.getInstance().execute(() -> failed.accept(t("Не удалось сохранить: ", "Could not save: ") + e.getMessage()));
            }
        });
    }

    /**
     * Writes the export like export() and puts the file itself on the clipboard, to paste into a chat as an
     * attachment. Where the system cannot do that, the folder opens instead.
     */
    public static void copyFile(Request req, Consumer<String> done, Consumer<String> failed) {
        Tasks.run(() -> {
            try {
                Path out = write(req);
                boolean ok = FileClipboard.copy(out);
                Minecraft.getInstance().execute(() -> {
                    if (ok) {
                        done.accept(t("Файл скопирован: вставьте в чат (Ctrl+V) · ", "File copied: paste it into a chat (Ctrl+V) · ")
                                + out.getFileName());
                    } else {
                        Util.getPlatform().openUri(Recordings.exportsDir().toUri());
                        failed.accept(t("Не удалось скопировать файл, открыта папка: ", "Could not copy the file, the folder is open: ")
                                + out.getFileName());
                    }
                });
            } catch (Exception e) {
                CombatDataClient.LOG.warn("[CD] экспорт: {}", e.toString());
                Minecraft.getInstance().execute(() -> failed.accept(t("Не удалось сохранить: ", "Could not save: ") + e.getMessage()));
            }
        });
    }

    static Path write(Request req) throws IOException {
        Share.Options o = req.options();
        String mod = Recorder.modVersion();
        List<Share.Named> recs = new ArrayList<>();
        String by = null;
        boolean cut = false;
        for (Recordings.Entry e : req.entries()) {
            Recording r = Cdrec.read(e.file());
            Share.Options eo = o;
            if (req.fight() >= 0 && req.entries().size() == 1) {
                List<Stats.Fight> fights = Stats.fights(r.events(), Stats.FIGHT_GAP_MS);
                if (req.fight() < fights.size()) {
                    Stats.Fight f = fights.get(req.fight());
                    eo = new Share.Options(true, f.startMs(), f.endMs(), o.anonOpponents(), o.anonServer(), o.hidePlayer(), o.behavior());
                    cut = true;
                }
            }
            if (by == null) by = r.player();
            String stem = e.stem() + (cut ? t(" бой ", " fight ") + (req.fight() + 1) : "");
            recs.add(new Share.Named(stem, Share.prepare(r, eo)));
        }
        if (by == null || by.isBlank()) by = Minecraft.getInstance().getUser().getName();
        Share share = new Share(o.hidePlayer() ? null : by, o.anonOpponents(), o.anonServer(), o.hidePlayer(), o.behavior(), cut,
                System.currentTimeMillis(), mod);
        Path dir = Recordings.exportsDir();
        if (recs.size() == 1) return Share.exportOne(dir, recs.get(0).stem(), recs.get(0).recording(), share, mod);
        return Share.exportZip(dir, "combatdata_" + LocalDateTime.now().format(STAMP), recs, share, mod);
    }

    /** Dropped files: copied into shared/<sender>/. */
    public static void importFiles(List<Path> files, Consumer<Share.Imported> done) {
        Tasks.run(() -> {
            Share.Imported r = Share.importFiles(files, Recordings.sharedDir());
            Minecraft.getInstance().execute(() -> done.accept(r));
        });
    }

    /** Imports everything in combatdata/inbox; files that were imported or already there are removed from it. */
    public static void checkInbox(Consumer<Share.Imported> done) {
        Path inbox = Recordings.inboxDir();
        if (!Files.isDirectory(inbox)) return;
        Tasks.run(() -> {
            Share.Imported total = Share.Imported.NONE;
            try (Stream<Path> s = Files.list(inbox)) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    Share.Imported one = Share.importFiles(List.of(f), Recordings.sharedDir());
                    total = total.plus(one);
                    if (one.errors().isEmpty()) Files.deleteIfExists(f);
                }
            } catch (IOException e) {
                CombatDataClient.LOG.warn("[CD] папка импорта: {}", e.toString());
            }
            Share.Imported r = total;
            if (!r.files().isEmpty() || !r.errors().isEmpty()) Minecraft.getInstance().execute(() -> done.accept(r));
        });
    }

    public static void openInbox() {
        try {
            Files.createDirectories(Recordings.inboxDir());
        } catch (IOException ignored) {
        }
        Util.getPlatform().openUri(Recordings.inboxDir().toUri());
    }

    /** One line about an import for the status bar. */
    public static String summary(Share.Imported r) {
        StringBuilder b = new StringBuilder();
        if (!r.files().isEmpty()) b.append(t("Импортировано: ", "Imported: ")).append(r.files().size());
        if (r.duplicates() > 0) b.append(b.isEmpty() ? "" : " · ").append(t("уже были: ", "already there: ")).append(r.duplicates());
        if (!r.errors().isEmpty()) b.append(b.isEmpty() ? "" : " · ").append(r.errors().get(0))
                .append(r.errors().size() > 1 ? t(" и ещё ", " and ") + (r.errors().size() - 1) : "");
        return b.isEmpty() ? t("Нечего импортировать", "Nothing to import") : b.toString();
    }
}
