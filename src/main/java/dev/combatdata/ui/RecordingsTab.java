package dev.combatdata.ui;

import dev.combatdata.CombatDataClient;
import dev.combatdata.client.Recorder;
import dev.combatdata.client.Recordings;
import dev.combatdata.core.Cdrec;
import dev.combatdata.core.Recording;
import dev.combatdata.core.RecordingQuery;
import net.minecraft.util.Util;
import static dev.combatdata.core.Lang.t;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class RecordingsTab implements Tab {
    private static final int ROW = 24;
    private static final RecordingQuery.Sort[] SORTS = RecordingQuery.Sort.values();
    private static String[] sortNames() {
        return dev.combatdata.core.Lang.english()
                ? new String[]{"Newest first", "Oldest first", "Longest", "Most hits", "Accuracy", "By name", "By size"}
                : new String[]{"Сначала новые", "Сначала старые", "Самые длинные", "Больше попаданий", "Точность", "По имени", "По размеру"};
    }

    private static String[] sortShort() {
        return dev.combatdata.core.Lang.english()
                ? new String[]{"Newest", "Oldest", "Longest", "Hits", "Accuracy", "Name", "Size"}
                : new String[]{"Новые", "Старые", "Длинные", "Попадания", "Точность", "Имя", "Размер"};
    }
    private static RecordingQuery.Sort sort = RecordingQuery.Sort.NEWEST;
    private static boolean favoritesOnly;
    static String query = "";

    private final MenuScreen menu;
    private List<Recordings.Entry> entries = List.of();
    private boolean loading;
    private int listToken, seenSaved = -1, openToken;
    private List<Integer> shown = List.of();
    private Path selected;
    private boolean liveSelected;
    private double scroll;
    private String status;
    private int statusColor = Theme.MUTED;
    private boolean sortOpen;
    private int listX, listY, listW, listH;

    RecordingsTab(MenuScreen menu) {
        this.menu = menu;
    }

    @Override
    public void onShow() {
        refresh();
    }

    /** Reading every file header takes a while with many recordings, so the list is loaded off the render thread. */
    void refresh() {
        seenSaved = Recorder.savedCount();
        int token = ++listToken;
        loading = true;
        Recordings.listAsync(list -> {
            if (token != listToken) return;
            loading = false;
            entries = list;
            if (selected != null && entries.stream().noneMatch(e -> e.file().equals(selected))) selected = null;
            rebuild();
        });
    }

    void setQuery(String q) {
        query = q == null ? "" : q;
        scroll = 0;
        rebuild();
    }

    private void rebuild() {

        List<RecordingQuery.Item> items = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Recordings.Entry e = entries.get(i);
            var h = e.header();
            String text = e.stem() + " " + Fmt.title(e) + " " + Fmt.when(e)
                    + (h == null ? "" : " " + String.join(" ", h.servers()) + " " + String.join(" ", h.opponents()) + " " + h.player());
            items.add(new RecordingQuery.Item(i, Fmt.title(e), text, e.time(), h == null ? 0 : h.durationMs(),
                    h == null ? 0 : h.hitsConfirmed(), h == null ? 0 : h.attacks(), e.size(), Recordings.favorite(e), h == null));
        }
        shown = RecordingQuery.apply(items, query, sort, favoritesOnly).stream().map(RecordingQuery.Item::index).toList();
    }

    private Recordings.Entry selectedEntry() {
        if (entries == null || selected == null) return null;
        for (Recordings.Entry e : entries) if (e.file().equals(selected)) return e;
        return null;
    }

    private void setStatus(String s, int color) {
        status = s;
        statusColor = color;
    }

    @Override
    public void render(Ui ui, int x, int y, int w, int h) {
        // A recording written in the background (F9 or autosave) appears without pressing Refresh.
        if (Recorder.savedCount() != seenSaved) refresh();
        boolean narrow = w < 420;
        int bx = x + w;
        String refreshL = narrow ? "↻" : t("Обновить", "Refresh");
        int bw = narrow ? 18 : ui.buttonWidth(refreshL);
        bx -= bw;
        ui.button(bx, y, bw, 16, refreshL, Ui.Kind.GHOST, true, this::refresh);
        String folderL = narrow ? t("Папка", "Folder") : t("Открыть папку", "Open folder");
        bw = ui.buttonWidth(folderL);
        bx -= bw + 4;
        ui.button(bx, y, bw, 16, folderL, Ui.Kind.GHOST, true, this::openFolder);
        String sortL = (narrow ? sortShort() : sortNames())[sort.ordinal()] + " ▾";
        bw = ui.buttonWidth(sortL);
        bx -= bw + 4;
        int sortX = bx, sortW = bw;
        ui.button(bx, y, bw, 16, sortL, Ui.Kind.SECONDARY, true, () -> sortOpen = !sortOpen);
        bw = 18;
        bx -= bw + 4;
        star(ui, bx, y, bw, 16, favoritesOnly, true, () -> {
            favoritesOnly = !favoritesOnly;
            scroll = 0;
            rebuild();
        });
        String hint = ui.hover(bx, y, bw, 16) ? (favoritesOnly ? t("Показать все записи", "Show all recordings") : t("Показать только избранные", "Show favorites only")) : null;
        menu.placeSearch(x, y, bx - 6 - x, 16);

        int ly = y + 22, lh = h - 22 - 24;
        Draw.roundBordered(ui.g, x, ly, w, lh, Theme.R_PANEL, Theme.BORDER, Theme.INSET);
        liveRow(ui, x + 1, ly + 1, w - 2);
        ui.g.fill(x + 6, ly + 1 + ROW, x + w - 6, ly + 2 + ROW, Theme.BORDER);
        listX = x + 1;
        listY = ly + 2 + ROW;
        listW = w - 2;
        listH = lh - 3 - ROW;
        if (entries.isEmpty() && loading) {
            ui.textCenter(t("Загрузка…", "Loading…"), x + w / 2, ly + lh / 2 - 4, Theme.MUTED);
        } else if (entries.isEmpty()) {
            ui.textCenter(t("Записей пока нет", "No recordings yet"), x + w / 2, ly + lh / 2 - 10, Theme.MUTED);
            ui.textCenter(t("Бои сохраняются сами, F9 сохраняет буфер вручную", "Fights are saved automatically, F9 saves the buffer manually"), x + w / 2, ly + lh / 2 + 2, Theme.DIM);
        } else if (shown.isEmpty()) {
            ui.textCenter(favoritesOnly && query.isBlank() ? t("Избранных записей нет", "No favorite recordings") : t("Ничего не найдено", "Nothing found"), x + w / 2, ly + lh / 2 - 10, Theme.MUTED);
            ui.textCenter(favoritesOnly ? t("Отметьте запись звёздочкой ☆ в строке", "Mark a recording with the ☆ star in its row")
                            : t("Поиск по имени, серверу, противнику и дате", "Search by name, server, opponent and date"),
                    x + w / 2, ly + lh / 2 + 2, Theme.DIM);
        }
        int content = shown.size() * ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, content - listH)));
        ui.g.enableScissor(listX, listY, listX + listW, listY + listH);
        ui.clip(listX, listY, listW, listH);
        for (int i = 0; i < shown.size(); i++) {
            int ry = listY + i * ROW - (int) scroll;
            if (ry + ROW < listY || ry > listY + listH) continue;
            Recordings.Entry e = entries.get(shown.get(i));
            boolean sel = e.file().equals(selected), hov = !sortOpen && ui.hover(listX, ry, listW, ROW);
            if (sel) Draw.round(ui.g, listX + 2, ry + 1, listW - 8, ROW - 2, Theme.R_CTRL, Theme.RAISED_HOVER);
            else if (hov) Draw.round(ui.g, listX + 2, ry + 1, listW - 8, ROW - 2, Theme.R_CTRL, Theme.ROW_HOVER);
            String when = Fmt.when(e), size = Fmt.size(e.size());
            int whenW = ui.font.width(when), sizeW = ui.font.width(size);
            ui.text(Draw.ellipsize(ui.font, Fmt.title(e), listW - 44 - whenW), listX + 20, ry + 4,
                    e.header() == null ? Theme.RED : Theme.TEXT_STRONG);
            ui.textRight(when, listX + listW - 12, ry + 4, Theme.MUTED);
            String lite = e.header() != null && e.header().notRecorded() != 0 ? t("лёгкая", "lite") : null;
            int liteW = lite == null ? 0 : ui.font.width(lite) + 8;
            ui.text(Draw.ellipsize(ui.font, Fmt.meta(e), listW - 40 - sizeW - liteW), listX + 20, ry + 14, Theme.MUTED);
            if (lite != null) ui.textRight(lite, listX + listW - 12 - sizeW - 8, ry + 14, Theme.ACCENT);
            ui.textRight(size, listX + listW - 12, ry + 14, Theme.DIM);
            Path file = e.file();
            ui.click(listX, ry, listW, ROW, () -> {
                boolean open = menu.lastDoubleClick() && file.equals(selected);
                selected = file;
                liveSelected = false;
                if (open) open();
            });
            boolean fav = Recordings.favorite(e);
            if (fav || sel || hov) star(ui, listX + 3, ry + 3, 14, 18, fav, false, () -> {
                Recordings.setFavorite(e, !fav);
                if (favoritesOnly) rebuild();
            });
        }
        ui.unclip();
        ui.g.disableScissor();
        ui.scrollbar(x + w - 5, listY + 2, listH - 4, scroll, listH, content);

        int fy = y + h - 16;
        Recordings.Entry sel = selectedEntry();
        bx = x + w;
        String openL = t("Открыть", "Open"), renameL = t("Переименовать", "Rename"),
                deleteL = liveSelected ? t("Сбросить", "Reset") : t("Удалить", "Delete");
        bw = ui.buttonWidth(openL);
        bx -= bw;
        ui.button(bx, fy, bw, 16, openL, Ui.Kind.PRIMARY, liveSelected || (sel != null && sel.header() != null), this::open);
        bw = ui.buttonWidth(renameL);
        bx -= bw + 4;
        ui.button(bx, fy, bw, 16, renameL, Ui.Kind.SECONDARY, sel != null, this::rename);
        bw = ui.buttonWidth(deleteL);
        bx -= bw + 4;
        if (liveSelected) ui.button(bx, fy, bw, 16, deleteL, Ui.Kind.DANGER, true, () -> menu.confirmReset(null));
        else ui.button(bx, fy, bw, 16, deleteL, Ui.Kind.DANGER, sel != null, this::delete);
        String left = hint != null ? hint : status;
        int leftColor = hint != null ? Theme.TEXT : statusColor;
        if (left == null) {
            String total = Fmt.size(Recordings.totalBytes(entries));
            left = shown.size() == entries.size() ? t("Записей: ", "Recordings: ") + entries.size() + " · " + total
                    : t("Найдено ", "Found ") + shown.size() + t(" из ", " of ") + entries.size();
            leftColor = Theme.MUTED;
        }
        ui.text(Draw.ellipsize(ui.font, left, bx - x - 8), x, Ui.textY(fy, 16), leftColor);

        if (sortOpen) sortMenu(ui, sortX, y + 18, sortW);
    }

    private static void star(Ui ui, int x, int y, int w, int h, boolean on, boolean box, Runnable action) {
        boolean hov = ui.hover(x, y, w, h);
        if (box) Draw.roundBordered(ui.g, x, y, w, h, Theme.R_CTRL, on ? Theme.AMBER : Theme.BORDER, hov ? Theme.RAISED_HOVER : Theme.RAISED);
        ui.textCenter(on ? "★" : "☆", x + w / 2 + 1, Ui.textY(y, h), on ? Theme.AMBER : (hov ? Theme.TEXT : Theme.DIM));
        ui.click(x, y, w, h, action);
    }

    private void sortMenu(Ui ui, int ax, int ay, int aw) {
        ui.g.nextStratum();
        ui.unclip();
        ui.click(-10_000, -10_000, 20_000, 20_000, () -> sortOpen = false);
        int iw = 0;
        String[] names = sortNames();
        for (String s : names) iw = Math.max(iw, ui.font.width(s));
        int mw = Math.max(aw, iw + 24), ih = 14, mh = SORTS.length * ih + 4, mx = ax + aw - mw;
        Draw.roundBordered(ui.g, mx, ay, mw, mh, Theme.R_PANEL, Theme.BORDER, Theme.WINDOW);
        for (int i = 0; i < SORTS.length; i++) {
            int iy = ay + 2 + i * ih;
            boolean cur = SORTS[i] == sort, hov = ui.hover(mx + 2, iy, mw - 4, ih);
            if (hov) Draw.round(ui.g, mx + 2, iy, mw - 4, ih, Theme.R_CTRL, Theme.RAISED_HOVER);
            if (cur) ui.text("✔", mx + 6, Ui.textY(iy, ih), Theme.ACCENT);
            ui.text(names[i], mx + 18, Ui.textY(iy, ih), cur ? Theme.TEXT_STRONG : Theme.TEXT);
            RecordingQuery.Sort s = SORTS[i];
            ui.click(mx + 2, iy, mw - 4, ih, () -> {
                sort = s;
                sortOpen = false;
                rebuild();
            });
        }
    }

    @Override
    public boolean scrolled(double mx, double my, double dy) {
        if (sortOpen || mx < listX || mx >= listX + listW || my < listY || my >= listY + listH) return false;
        scroll -= dy * ROW;
        return true;
    }

    private void liveRow(Ui ui, int x, int y, int w) {
        boolean hov = !sortOpen && ui.hover(x, y, w, ROW);
        if (liveSelected) Draw.round(ui.g, x + 2, y + 1, w - 8, ROW - 2, Theme.R_CTRL, Theme.RAISED_HOVER);
        else if (hov) Draw.round(ui.g, x + 2, y + 1, w - 8, ROW - 2, Theme.R_CTRL, Theme.ROW_HOVER);
        boolean on = Recorder.active();
        Draw.circle(ui.g, x + 6, y + 5, 7, on ? 0xFFE5584B : Theme.MUTED);
        String state = on ? (Recorder.full() ? t("Идёт запись, буфер полон", "Recording, buffer full") : t("Идёт запись", "Recording"))
                : t("Запись на паузе", "Recording paused");
        String time = Fmt.clock(Recorder.shownMs()) + " / " + Fmt.clock(Recorder.capacityMs());
        int tw = ui.font.width(time);
        ui.text(Draw.ellipsize(ui.font, t("Текущий буфер · ", "Live buffer · ") + state, w - 44 - tw), x + 20, y + 4, Theme.TEXT_STRONG);
        ui.textRight(time, x + w - 12, y + 4, on ? 0xFFE5584B : Theme.MUTED);
        String server = Recorder.currentServer();
        String meta = (server.isEmpty() ? t("вне сервера", "not on a server") : server) + t(" · событий: ", " · events: ") + Recorder.bufferSize()
                + (Recorder.hasCombatEvents() ? "" : t(" · боевых нет", " · no combat yet"));
        ui.text(Draw.ellipsize(ui.font, meta, w - 32), x + 20, y + 14, Theme.MUTED);
        ui.click(x, y, w, ROW, () -> {
            boolean open = menu.lastDoubleClick() && liveSelected;
            liveSelected = true;
            selected = null;
            if (open) open();
        });
    }

    private void open() {
        if (liveSelected) {
            setStatus(null, Theme.MUTED);
            menu.openLive();
            return;
        }
        Recordings.Entry e = selectedEntry();
        if (e == null) return;
        int token = ++openToken;
        setStatus(t("Открываю запись…", "Opening the recording…"), Theme.MUTED);
        Recordings.readAsync(e.file(), rec -> {
            if (token != openToken) return;
            setStatus(null, Theme.MUTED);
            menu.openRecording(e, rec);
        }, ex -> {
            if (token != openToken) return;
            CombatDataClient.LOG.error("[CD] не удалось открыть запись", ex);
            setStatus(t("Не удалось открыть: ", "Could not open: ") + ex.getMessage(), Theme.RED);
        });
    }

    private void rename() {
        Recordings.Entry e = selectedEntry();
        if (e == null) return;
        Modal[] self = new Modal[1];
        self[0] = new Modal(t("Переименовать запись", "Rename recording"),
                List.of(t("Новое имя файла (без расширения):", "New file name (without extension):")), e.stem(), List.of(
                new Modal.Btn(t("Отмена", "Cancel"), Ui.Kind.SECONDARY, s -> menu.closeModal()),
                new Modal.Btn(t("Переименовать", "Rename"), Ui.Kind.PRIMARY, s -> {
                    try {
                        Path np = Recordings.rename(e.file(), s == null ? "" : s);
                        menu.closeModal();
                        selected = np;
                        refresh();
                        setStatus(t("Запись переименована", "Recording renamed"), Theme.GREEN);
                    } catch (IOException ex) {
                        self[0].error = ex.getMessage();
                    }
                })));
        menu.openModal(self[0]);
    }

    private void delete() {
        Recordings.Entry e = selectedEntry();
        if (e == null) return;
        List<String> lines = new ArrayList<>(List.of(t("«", "\"") + Fmt.title(e) + t("», ", "\", ") + Fmt.when(e),
                t("Файл будет удалён без возможности восстановления.", "The file will be deleted permanently.")));
        if (Recordings.favorite(e)) lines.add(t("Запись в избранном.", "This recording is a favorite."));
        menu.openModal(new Modal(t("Удалить запись?", "Delete recording?"), lines, null, List.of(
                new Modal.Btn(t("Отмена", "Cancel"), Ui.Kind.SECONDARY, s -> menu.closeModal()),
                new Modal.Btn(t("Удалить", "Delete"), Ui.Kind.DANGER, s -> {
                    try {
                        Recordings.delete(e.file());
                        menu.view.forget(e.file());
                        setStatus(t("Запись удалена", "Recording deleted"), Theme.MUTED);
                    } catch (IOException ex) {
                        setStatus(t("Не удалось удалить: ", "Could not delete: ") + ex.getMessage(), Theme.RED);
                    }
                    menu.closeModal();
                    refresh();
                }))));
    }

    private void openFolder() {
        try {
            Path dir = Recordings.dir();
            Files.createDirectories(dir);
            Util.getPlatform().openUri(dir.toUri());
        } catch (Exception ex) {
            setStatus(t("Не удалось открыть папку: ", "Could not open the folder: ") + ex.getMessage(), Theme.RED);
        }
    }
}
