package dev.combatdata.ui;

import dev.combatdata.CombatDataClient;
import dev.combatdata.client.CodeShare;
import dev.combatdata.client.Exchange;
import dev.combatdata.client.Recordings;
import dev.combatdata.client.Tasks;
import dev.combatdata.core.Recording;
import dev.combatdata.core.Share;
import dev.combatdata.core.ShareCode;
import dev.combatdata.core.Stats;
import static dev.combatdata.core.Lang.t;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The "Share" window: a file (whole recording or one fight) or a code for chat at one of four levels, what to hide,
 * whether opponent data goes into the file. The code and its length are made in the background on every change.
 */
final class ShareDialog implements Modal.Body {
    private static final int ROW = 16;
    private static final ShareCode.Level[] LEVELS = ShareCode.Level.values();

    private final List<Recordings.Entry> entries;
    private final BiConsumer<String, Integer> status;
    private boolean anonOpponents = true, anonServer, hidePlayer, behavior;
    private boolean oneFight;
    private int fight;
    private ShareCode.Level level = ShareCode.Level.FIGHT;
    private Recording rec;
    private List<Stats.Fight> fights;
    private String loadError;
    private final Tasks.Slot<ShareCode.Encoded> code = new Tasks.Slot<>();

    private ShareDialog(List<Recordings.Entry> entries, BiConsumer<String, Integer> status) {
        this.entries = entries;
        this.status = status;
        if (entries.size() == 1) {
            Recordings.readAsync(entries.get(0).file(), r -> {
                rec = r;
                fights = Stats.fights(r.events(), Stats.FIGHT_GAP_MS);
                fight = longest(fights);
            }, e -> loadError = t("запись не прочиталась", "the recording could not be read"));
        }
    }

    private static int longest(List<Stats.Fight> fs) {
        int best = 0;
        for (int i = 1; i < fs.size(); i++) if (fs.get(i).attacks() > fs.get(best).attacks()) best = i;
        return best;
    }

    /** status gets the result line and its colour. */
    static void open(MenuScreen menu, List<Recordings.Entry> entries, BiConsumer<String, Integer> status) {
        ShareDialog d = new ShareDialog(entries, status);
        String what = entries.size() == 1 ? t("Запись: ", "Recording: ") + Fmt.title(entries.get(0))
                : t("Записей: ", "Recordings: ") + entries.size() + t(" (файлом .zip)", " (as a .zip file)");
        Modal m = new Modal(t("Поделиться", "Share"), List.of(what), null, entries.size() == 1 ? List.of(
                new Modal.Btn(t("Отмена", "Cancel"), Ui.Kind.SECONDARY, s -> menu.closeModal()),
                new Modal.Btn(t("Сохранить файл", "Save file"), Ui.Kind.SECONDARY, s -> d.saveFile(menu)),
                new Modal.Btn(t("Скопировать файл", "Copy file"), Ui.Kind.SECONDARY, s -> d.copyFile(menu)),
                new Modal.Btn(t("Скопировать код", "Copy code"), Ui.Kind.PRIMARY, s -> d.copy(menu, 0, true)))
                : List.of(new Modal.Btn(t("Отмена", "Cancel"), Ui.Kind.SECONDARY, s -> menu.closeModal()),
                new Modal.Btn(t("Сохранить файл", "Save file"), Ui.Kind.SECONDARY, s -> d.saveFile(menu)),
                new Modal.Btn(t("Скопировать файл", "Copy file"), Ui.Kind.PRIMARY, s -> d.copyFile(menu))));
        m.body = d;
        m.width = entries.size() == 1 ? 420 : 320;
        menu.openModal(m);
    }

    private void saveFile(MenuScreen menu) {
        menu.closeModal();
        status.accept(t("Сохраняю…", "Saving…"), Theme.MUTED);
        Exchange.export(request(), msg -> status.accept(msg, Theme.GREEN), msg -> status.accept(msg, Theme.RED));
    }

    /** The file goes on the clipboard: one paste into a chat, however long the recording. */
    private void copyFile(MenuScreen menu) {
        menu.closeModal();
        status.accept(t("Копирую файл…", "Copying the file…"), Theme.MUTED);
        Exchange.copyFile(request(), msg -> status.accept(msg, Theme.GREEN), msg -> status.accept(msg, Theme.RED));
    }

    private Exchange.Request request() {
        boolean cut = oneFight && fights != null && !fights.isEmpty();
        return new Exchange.Request(entries, cut ? fight : -1, new Share.Options(cut, 0, 0, anonOpponents, anonServer, hidePlayer, behavior));
    }

    private String codeKey() {
        return level + "/" + fight + "/" + oneFight + "/" + anonOpponents + "/" + anonServer + "/" + hidePlayer + "/"
                + CombatDataClient.settings().compatCode + "/" + dev.combatdata.core.Lang.english();
    }

    private ShareCode.Encoded current() {
        if (rec == null) return null;
        Recording r = rec;
        ShareCode.Level l = level;
        int f = fight;
        Share.Options o = new Share.Options(oneFight, 0, 0, anonOpponents, anonServer, hidePlayer, false);
        boolean compat = CombatDataClient.settings().compatCode;
        String k = codeKey();
        code.request(k, () -> CodeShare.encode(r, l, f, o, compat));
        return code.get(k);
    }

    /** Copies one part; the window stays open while there are more parts. */
    private void copy(MenuScreen menu, int part, boolean closeIfOne) {
        ShareCode.Encoded e = current();
        if (e == null) return;
        CodeShare.copy(e.parts().get(part));
        int n = e.parts().size();
        if (n == 1) {
            if (closeIfOne) menu.closeModal();
            status.accept(t("Код скопирован: ", "Code copied: ") + e.chars() + t(" символов", " characters"), Theme.GREEN);
        } else {
            status.accept(t("Скопирована часть ", "Copied part ") + (part + 1) + t(" из ", " of ") + n, Theme.GREEN);
            copied = part;
        }
    }

    private int copied = -1;

    private boolean single() {
        return entries.size() == 1;
    }

    private boolean big() {
        return single() && entries.get(0).size() > Share.BIG_FILE;
    }

    private boolean needsFight() {
        return oneFight || level == ShareCode.Level.FIGHT || level == ShareCode.Level.REPLAY;
    }

    @Override
    public int height(int w) {
        int h = 4 * ROW;
        if (single()) {
            h += (ROW + 2) * 2 + (needsFight() ? ROW + 2 : 0) + (big() && !oneFight ? 11 : 0) + 12 + ROW + 2;
            ShareCode.Encoded e = code.latest();
            if (e != null && e.parts().size() > 1) h += ROW + 2;
        }
        return h;
    }

    @Override
    public void render(Ui ui, int x, int y, int w) {
        if (single()) {
            String whatL = t("Файл:", "File:"), codeL = t("Код:", "Code:");
            int lw = Math.max(ui.font.width(whatL), ui.font.width(codeL)) + 8;
            ui.text(whatL, x, Ui.textY(y, 18), Theme.TEXT);
            String[] what = {t("Вся запись", "Whole recording"), t("Один бой", "One fight")};
            ui.segmented(x + lw, y - 1, what, oneFight ? 1 : 0, i -> oneFight = i == 1);
            y += ROW + 2;
            ui.text(codeL, x, Ui.textY(y, 18), Theme.TEXT);
            String[] lv = {t("Сводка", "Summary"), t("Бой", "Fight"), t("С повтором", "With replay"), t("Запись", "Recording")};
            if (ui.segmentedWidth(lv) > w - lw) lv = new String[]{t("Свод.", "Sum."), t("Бой", "Fight"), t("Повтор", "Replay"), t("Вся", "All")};
            ui.segmented(x + lw, y - 1, lv, level.ordinal(), i -> level = LEVELS[i]);
            y += ROW + 2;
            if (needsFight()) {
                String label;
                if (fights == null) label = loadError != null ? loadError : t("загружаю бои…", "loading fights…");
                else if (fights.isEmpty()) label = t("боёв нет", "no fights");
                else {
                    fight = Math.max(0, Math.min(fight, fights.size() - 1));
                    Stats.Fight f = fights.get(fight);
                    label = t("Бой ", "Fight ") + (fight + 1) + t(" из ", " of ") + fights.size() + " · " + Fmt.clock(f.endMs() - f.startMs())
                            + (f.opponents().isEmpty() ? "" : " · " + f.opponents().get(0));
                }
                ui.stepper(x, y, w, Draw.ellipsize(ui.font, label, w - 36), () -> fight = Math.max(0, fight - 1),
                        () -> fight = fights == null ? 0 : Math.min(fights.size() - 1, fight + 1));
                y += ROW + 2;
            }
            if (big() && !oneFight) {
                ui.text(Draw.ellipsize(ui.font, t("Файл ", "The file is ") + Fmt.size(entries.get(0).size())
                        + t(": можно поделиться одним боем", ": consider sharing one fight"), w), x, y, Theme.AMBER);
                y += 11;
            }
            ShareCode.Encoded fresh = current();
            // While a new code is made the last one stays on screen, dimmed, so the rows below do not jump.
            ShareCode.Encoded e = fresh != null ? fresh : code.latest();
            String info;
            int color = Theme.MUTED;
            if (rec == null) info = loadError != null ? loadError : t("готовлю код…", "making the code…");
            else if (e == null) info = t("считаю длину кода…", "measuring the code…");
            else {
                int n = e.parts().size();
                info = "≈" + e.chars() + t(" символов · ", " characters · ") + n + " " + (dev.combatdata.core.Lang.english()
                        ? (n == 1 ? "message" : "messages") : dev.combatdata.core.Lang.plural(n, "сообщение", "сообщения", "сообщений"));
                if (n > 1) info += t(" · одним вложением: «Скопировать файл»", " · as one attachment: \"Copy file\"");
                color = Theme.TEXT;
                if (fresh == null) color = Theme.MUTED;
            }
            ui.text(Draw.ellipsize(ui.font, info, w), x, y, color);
            y += 12;
            if (e != null && e.parts().size() > 1) {
                int bx = x;
                ui.text(t("Части:", "Parts:"), bx, Ui.textY(y, 16), Theme.MUTED);
                bx += ui.font.width(t("Части:", "Parts:")) + 6;
                for (int i = 0; i < e.parts().size() && bx < x + w - 20; i++) {
                    String l = String.valueOf(i + 1);
                    int bw = ui.buttonWidth(l);
                    int part = i;
                    ui.button(bx, y, bw, 16, l, i == copied ? Ui.Kind.PRIMARY : Ui.Kind.SECONDARY, true, () -> copyPart(part));
                    bx += bw + 3;
                }
                y += ROW + 2;
            }
            y = toggle(ui, x, y, w, t("Совместимый код (только латиница, длиннее)", "Compatible code (ASCII only, longer)"),
                    CombatDataClient.settings().compatCode, v -> {
                        CombatDataClient.settings().compatCode = v;
                        CombatDataClient.saveSettings();
                    });
        }
        y = toggle(ui, x, y, w, t("Ники противников → «Противник 1…N»", "Opponent names → \"Opponent 1…N\""), anonOpponents, v -> anonOpponents = v);
        y = toggle(ui, x, y, w, t("Скрыть адрес сервера", "Hide the server address"), anonServer, v -> anonServer = v);
        y = toggle(ui, x, y, w, t("Скрыть свой ник (отправитель: аноним)", "Hide your name (sender: anonymous)"), hidePlayer, v -> hidePlayer = v);
        toggle(ui, x, y, w, t("В файл: данные для разбора противников", "In the file: data for opponent checks"), behavior, v -> behavior = v);
    }

    private void copyPart(int part) {
        ShareCode.Encoded e = current();
        if (e == null || part >= e.parts().size()) return;
        CodeShare.copy(e.parts().get(part));
        copied = part;
        status.accept(t("Скопирована часть ", "Copied part ") + (part + 1) + t(" из ", " of ") + e.parts().size(), Theme.GREEN);
    }

    private static int toggle(Ui ui, int x, int y, int w, String label, boolean on, Consumer<Boolean> set) {
        ui.toggle(x, y + 2, on, set);
        ui.text(Draw.ellipsize(ui.font, label, w - 28), x + 28, y + 3, Theme.TEXT);
        ui.click(x + 26, y, w - 26, ROW - 2, () -> set.accept(!on));
        return y + ROW;
    }
}
