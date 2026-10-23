package dev.combatdata.ui;

import dev.combatdata.client.Recorder;
import dev.combatdata.client.Recordings;
import dev.combatdata.core.Recording;
import static dev.combatdata.core.Lang.t;

import java.nio.file.Path;

final class ViewTab implements Tab {
    private static final long REFRESH_MS = 1000, IDLE_REFRESH_MS = 5000;
    private final MenuScreen menu;
    private final StatsView stats;
    private Recordings.Entry entry;
    private Recording rec;
    private boolean live;
    private long lastRefresh, lastVersion = -1;
    private boolean combat;

    ViewTab(MenuScreen menu) {
        this.menu = menu;
        this.stats = new StatsView(menu);
    }

    void set(Recordings.Entry e, Recording r) {
        live = false;
        entry = e;
        rec = r;
        stats.setNote(null);
        stats.set(r, false);
    }

    void setLive() {
        live = true;
        entry = null;
        rec = null;
        combat = Recorder.hasCombatEvents();
        stats.set(Recorder.liveCopy(), false);
        lastRefresh = System.currentTimeMillis();
    }

    /** Opens a stats page from the search; without an open recording it shows the current buffer. */
    void openPage(int sub, int mode) {
        if (!live && rec == null) setLive();
        stats.open(sub, mode);
    }

    boolean isLive() {
        return live;
    }

    void forget(Path file) {
        if (entry != null && entry.file().equals(file)) {
            entry = null;
            rec = null;
        }
    }

    @Override
    public void onShow() {
        if (live) lastRefresh = 0;
    }

    @Override
    public void render(Ui ui, int x, int y, int w, int h) {
        if (live) {
            renderLive(ui, x, y, w, h);
            return;
        }
        if (rec == null) {
            ui.textCenter(t("Запись не выбрана", "No recording selected"), x + w / 2, y + h / 2 - 22, Theme.MUTED);
            ui.textCenter(t("Откройте запись во вкладке «Записи»", "Open a recording on the Recordings tab"), x + w / 2, y + h / 2 - 10, Theme.DIM);
            String back = t("К записям", "To recordings");
            int bw = ui.buttonWidth(back);
            ui.button(x + (w - bw) / 2, y + h / 2 + 6, bw, 16, back, Ui.Kind.SECONDARY, true,
                    () -> menu.switchTab(MenuScreen.TabId.RECORDINGS));
            return;
        }
        stats.render(ui, x, y, w, h, this::recordHeader);
    }

    private void recordHeader(Ui ui, int x, int y, int w) {
        String when = Fmt.when(entry) + " · " + Fmt.clock(rec.durationMs());
        int ty = Ui.textY(y, 18);
        if (ui.font.width(when) + 60 <= w) {
            ui.textRight(when, x + w, ty, Theme.MUTED);
            ui.textRight(Draw.ellipsize(ui.font, Fmt.title(entry), w - ui.font.width(when) - 8), x + w - ui.font.width(when) - 8, ty, Theme.TEXT_STRONG);
        } else {
            ui.textRight(Draw.ellipsize(ui.font, Fmt.title(entry), w), x + w, ty, Theme.TEXT_STRONG);
        }
    }

    private void renderLive(Ui ui, int x, int y, int w, int h) {
        long now = System.currentTimeMillis();
        long ver = Recorder.bufferVersion();
        // Recompute runs on the render thread: at most once a second and only if the buffer changed (every 5 s anyway, so
        // the duration keeps updating), and rarer when one recompute is slow, so it stays under about 5% of the time.
        long every = Math.max(REFRESH_MS, stats.computeMs() * 20);
        if (now - lastRefresh >= every && (ver != lastVersion || now - lastRefresh >= Math.max(IDLE_REFRESH_MS, every))) {
            lastRefresh = now;
            lastVersion = ver;
            combat = Recorder.hasCombatEvents();
            stats.set(Recorder.liveCopy(), true);
        }
        String server = Recorder.currentServer();
        stats.setNote(t("Текущий буфер ", "Live buffer ") + Fmt.clock(Recorder.shownMs()) + " / " + Fmt.clock(Recorder.capacityMs())
                + t(" · сервер: ", " · server: ") + (server.isEmpty() ? t("нет", "none") : server)
                + t(" · событий: ", " · events: ") + Recorder.bufferSize());
        if (combat) {
            stats.render(ui, x, y, w, h, this::liveHeader);
            return;
        }
        liveHeader(ui, x, y, w);
        Draw.roundBordered(ui.g, x, y + 24, w, h - 24, Theme.R_PANEL, Theme.BORDER, Theme.PANEL);
        int cy = y + 24 + (h - 24) / 2;
        ui.textCenter(t("В буфере пока нет боевых событий", "No combat events in the buffer yet"), x + w / 2, cy - 12, Theme.MUTED);
        ui.textCenter(t("Статистика появится после первого удара", "Stats will appear after the first hit"), x + w / 2, cy, Theme.DIM);
    }

    private void liveHeader(Ui ui, int x, int y, int w) {
        String full = t("Сохранить сейчас", "Save now"), shortL = t("Сохранить", "Save");
        String label = ui.buttonWidth(full) + 90 <= w ? full : shortL;
        int bw = ui.buttonWidth(label), bx = x + w - bw;
        ui.button(bx, y + 1, bw, 16, label, Ui.Kind.PRIMARY, Recorder.hasCombatEvents(), () -> {
            Recorder.save(net.minecraft.client.Minecraft.getInstance());
            lastRefresh = 0;
        });
        String rl = t("Сбросить", "Reset");
        int rw = ui.buttonWidth(rl);
        if (bx - rw - 4 - x >= 60) {
            bx -= rw + 4;
            ui.button(bx, y + 1, rw, 16, rl, Ui.Kind.GHOST, true, () -> menu.confirmReset(() -> lastRefresh = 0));
        }
        boolean on = Recorder.active();
        int color = on ? 0xFFE5584B : Theme.MUTED;
        String time = "REC " + Fmt.clock(Recorder.shownMs()) + " / " + Fmt.clock(Recorder.capacityMs());
        String state = on ? (Recorder.full() ? t("перезапись", "overwriting") : t("идёт запись", "recording")) : t("пауза", "paused");
        int room = bx - x - 8, ty = Ui.textY(y, 18);
        String text = ui.font.width(time + " · " + state) + 12 <= room ? time + " · " + state : time;
        if (ui.font.width(text) + 12 > room) return;
        int tx = bx - 8 - ui.font.width(text);
        Draw.circle(ui.g, tx - 10, ty, 7, color);
        ui.text(text, tx, ty, on ? Theme.TEXT_STRONG : Theme.MUTED);
    }

    @Override
    public boolean scrolled(double mx, double my, double dy) {
        return hasStats() && stats.scrolled(mx, my, dy);
    }

    private boolean hasStats() {
        return live ? combat : rec != null;
    }

    @Override
    public boolean pressed(double mx, double my) {
        return hasStats() && stats.pressed(mx, my);
    }

    @Override
    public void dragged(double mx, double my, double dx) {
        stats.dragged(mx, my, dx);
    }

    @Override
    public void released(double mx, double my) {
        stats.released(mx, my);
    }
}
