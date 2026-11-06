package dev.combatdata.ui;

import dev.combatdata.client.Exchange;
import dev.combatdata.client.Recordings;
import dev.combatdata.core.Share;
import dev.combatdata.core.Stats;
import static dev.combatdata.core.Lang.t;

import java.util.List;
import java.util.function.BiConsumer;

/** The "Share" window: whole recording or one fight, what to hide, whether opponent data goes along. */
final class ShareDialog implements Modal.Body {
    private static final int ROW = 16;

    private final List<Recordings.Entry> entries;
    private boolean anonOpponents = true, anonServer, hidePlayer, behavior;
    private boolean oneFight;
    private int fight;
    private List<Stats.Fight> fights;
    private String loadError;

    private ShareDialog(List<Recordings.Entry> entries) {
        this.entries = entries;
        if (entries.size() == 1) {
            Recordings.readAsync(entries.get(0).file(), r -> fights = Stats.fights(r.events(), Stats.FIGHT_GAP_MS),
                    e -> loadError = t("бои не прочитались", "fights could not be read"));
        }
    }

    /** status gets the result line and its colour. */
    static void open(MenuScreen menu, List<Recordings.Entry> entries, BiConsumer<String, Integer> status) {
        ShareDialog d = new ShareDialog(entries);
        String what = entries.size() == 1 ? t("Запись: ", "Recording: ") + Fmt.title(entries.get(0))
                : t("Записей: ", "Recordings: ") + entries.size() + t(" (одним .zip)", " (as one .zip)");
        Modal m = new Modal(t("Поделиться", "Share"), List.of(what,
                t("Файл появится в combatdata/exports, папка откроется сама.", "The file goes to combatdata/exports, the folder opens by itself.")),
                null, List.of(
                new Modal.Btn(t("Отмена", "Cancel"), Ui.Kind.SECONDARY, s -> menu.closeModal()),
                new Modal.Btn(t("Сохранить файл", "Save file"), Ui.Kind.PRIMARY, s -> {
                    menu.closeModal();
                    status.accept(t("Сохраняю…", "Saving…"), Theme.MUTED);
                    Exchange.export(d.request(), msg -> status.accept(msg, Theme.GREEN), msg -> status.accept(msg, Theme.RED));
                })));
        m.body = d;
        m.width = 300;
        menu.openModal(m);
    }

    Exchange.Request request() {
        boolean cut = oneFight && fights != null && !fights.isEmpty();
        return new Exchange.Request(entries, cut ? fight : -1,
                new Share.Options(cut, 0, 0, anonOpponents, anonServer, hidePlayer, behavior));
    }

    private boolean single() {
        return entries.size() == 1;
    }

    private boolean big() {
        return single() && entries.get(0).size() > Share.BIG_FILE;
    }

    @Override
    public int height(int w) {
        int h = 4 * ROW;
        if (single()) h += (ROW + 2) * (oneFight ? 2 : 1) + (big() && !oneFight ? 11 : 0);
        return h;
    }

    @Override
    public void render(Ui ui, int x, int y, int w) {
        if (single()) {
            ui.text(t("Что:", "What:"), x, Ui.textY(y, 18), Theme.TEXT);
            String[] what = {t("Вся запись", "Whole recording"), t("Один бой", "One fight")};
            ui.segmented(x + ui.font.width(t("Что:", "What:")) + 8, y - 1, what, oneFight ? 1 : 0, i -> oneFight = i == 1);
            y += ROW + 2;
            if (oneFight) {
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
        }
        y = toggle(ui, x, y, w, t("Ники противников → «Противник 1…N»", "Opponent names → \"Opponent 1…N\""), anonOpponents, v -> anonOpponents = v);
        y = toggle(ui, x, y, w, t("Скрыть адрес сервера", "Hide the server address"), anonServer, v -> anonServer = v);
        y = toggle(ui, x, y, w, t("Скрыть свой ник (отправитель: аноним)", "Hide your name (sender: anonymous)"), hidePlayer, v -> hidePlayer = v);
        toggle(ui, x, y, w, t("Данные для разбора противников", "Data for opponent checks"), behavior, v -> behavior = v);
    }

    private static int toggle(Ui ui, int x, int y, int w, String label, boolean on, java.util.function.Consumer<Boolean> set) {
        ui.toggle(x, y + 2, on, set);
        ui.text(Draw.ellipsize(ui.font, label, w - 28), x + 28, y + 3, Theme.TEXT);
        ui.click(x + 26, y, w - 26, ROW - 2, () -> set.accept(!on));
        return y + ROW;
    }
}
