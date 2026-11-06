package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.*;
import net.minecraft.client.Minecraft;
import static dev.combatdata.core.Lang.t;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Codes for chat: making one for the Share window, reading one from the clipboard. */
public final class CodeShare {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
    private static final ShareCode.Assembler PARTS = new ShareCode.Assembler();
    private static String offered;

    private CodeShare() {
    }

    /**
     * fight: index into Stats.fights, used by FIGHT and REPLAY (and by SUMMARY when oneFight); RECORDING takes the
     * whole recording. Runs off the render thread.
     */
    public static ShareCode.Encoded encode(Recording rec, ShareCode.Level level, int fight, Share.Options o, boolean compat) {
        boolean cut = level == ShareCode.Level.FIGHT || level == ShareCode.Level.REPLAY || level == ShareCode.Level.SUMMARY && o.oneFight();
        Share.Options eo = o;
        List<Stats.Fight> fights = Stats.fights(rec.events(), Stats.FIGHT_GAP_MS);
        if (cut && !fights.isEmpty()) {
            Stats.Fight f = fights.get(Math.max(0, Math.min(fight, fights.size() - 1)));
            eo = new Share.Options(true, f.startMs(), f.endMs(), o.anonOpponents(), o.anonServer(), o.hidePlayer(), false);
        } else {
            eo = new Share.Options(false, 0, 0, o.anonOpponents(), o.anonServer(), o.hidePlayer(), false);
        }
        Recording r = Share.prepare(rec, eo);
        String by = o.hidePlayer() ? null : rec.player();
        if (level == ShareCode.Level.SUMMARY) {
            Rules rules = Rules.of(r.events(), CombatDataClient.profiles(), CombatDataClient.settings());
            return ShareCode.encodeSummary(ShareCode.summarize(r, rules, by, o.anonOpponents()), o.anonOpponents(), compat);
        }
        return ShareCode.encode(r, level, by, o.anonOpponents(), compat);
    }

    public static void copy(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }

    /** What a paste gave: a decoded code, parts still missing, or an error. */
    public record Pasted(ShareCode.Decoded decoded, int have, int total, String error) {
    }

    /** Reads the clipboard directly: a text field would cut long codes and drop characters. */
    public static Pasted paste() {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        List<ShareCode.Part> found = ShareCode.find(clip);
        if (found.isEmpty()) return new Pasted(null, 0, 0, t("В буфере обмена нет кода Combat Data", "No Combat Data code in the clipboard"));
        List<ShareCode.Part> done = PARTS.add(found);
        if (done == null) return new Pasted(null, PARTS.have(), PARTS.total(), null);
        try {
            return new Pasted(ShareCode.decode(done), 0, 0, null);
        } catch (ShareCode.CodeException e) {
            return new Pasted(null, 0, 0, e.getMessage());
        }
    }

    /**
     * Looked at once when the menu opens, never polled: a whole single-part code in the clipboard that was not
     * offered before. Returns it decoded, or null.
     */
    public static ShareCode.Decoded offer() {
        String clip;
        try {
            clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        } catch (RuntimeException e) {
            return null;
        }
        if (clip == null || clip.length() > 64_000 || clip.equals(offered)) return null;
        List<ShareCode.Part> found = ShareCode.find(clip);
        if (found.isEmpty()) return null;
        offered = clip;
        if (found.get(0).total() != found.size()) return null;
        try {
            return ShareCode.decode(found);
        } catch (ShareCode.CodeException e) {
            return null;
        }
    }

    public static String sender(ShareCode.Decoded d) {
        return d.by() == null ? t("аноним", "anonymous") : d.by();
    }

    public static String levelName(ShareCode.Level l) {
        return switch (l) {
            case SUMMARY -> t("сводка", "summary");
            case FIGHT -> t("бой", "fight");
            case REPLAY -> t("бой с повтором", "fight with replay");
            case RECORDING -> t("запись", "recording");
        };
    }

    private static String stem(ShareCode.Decoded d) {
        return t("код ", "code ") + STAMP.format(Instant.ofEpochMilli(d.rec().wallStartMs()).atZone(ZoneId.systemDefault()));
    }

    /** An entry to open a decoded recording without saving it; its file does not exist yet. */
    public static Recordings.Entry entry(ShareCode.Decoded d) {
        String who = Share.clean(sender(d));
        Recording r = d.rec();
        Cdrec.Header h = new Cdrec.Header(r.schema(), r.player(), r.playerUuid(), r.mcVersion(), r.wallStartMs(), r.durationMs(),
                0, 0, 0, 0, List.of(), List.of(), r.notRecorded(), share(d), null);
        String stem = stem(d);
        return new Recordings.Entry(Recordings.sharedDir().resolve(who).resolve(stem + Cdrec.EXT), stem, h, 0, 0, who);
    }

    /** Saves a decoded recording into shared/<sender>/ so it need not be pasted again. */
    public static Path save(ShareCode.Decoded d) throws IOException {
        return Share.exportOne(Recordings.sharedDir().resolve(Share.clean(sender(d))), stem(d), d.rec(), share(d), Recorder.modVersion());
    }

    private static Share share(ShareCode.Decoded d) {
        return new Share(d.by(), d.anon(), false, d.by() == null, false, d.level() != ShareCode.Level.RECORDING, 0, null);
    }
}
