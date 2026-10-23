package dev.combatdata.ui;

import dev.combatdata.CombatDataClient;
import dev.combatdata.client.Recordings;
import dev.combatdata.core.Recording;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import static dev.combatdata.core.Lang.t;

import java.util.ArrayList;
import java.util.List;

public final class MenuScreen extends Screen {
    enum TabId {
        RECORDINGS("Записи", "Recordings"), VIEW("Просмотр", "View"), PROGRESS("Прогресс", "Progress"),
        SETTINGS("Настройки", "Settings");
        private final String ru, en;

        TabId(String ru, String en) {
            this.ru = ru;
            this.en = en;
        }

        String label() {
            return t(ru, en);
        }
    }

    private static TabId lastTab = TabId.RECORDINGS;

    final RecordingsTab recordings = new RecordingsTab(this);
    final ViewTab view = new ViewTab(this);
    final ProgressTab progress = new ProgressTab(this);
    final SettingsTab settingsTab = new SettingsTab(this);
    private TabId tab = lastTab;
    private final List<Ui.Target> targets = new ArrayList<>();
    private Modal modal;
    private EditBox editBox;
    private EditBox searchBox;
    private EditBox oppSearch;
    private int frameMouseX, frameMouseY;
    private float frameDelta;
    private GuiGraphics currentG;
    private boolean lastDouble;
    private boolean mouseCaptured;

    public MenuScreen() {
        super(Component.literal("Combat Data"));
        CombatDataClient.applyLanguage(net.minecraft.client.Minecraft.getInstance());
    }

    @Override
    protected void init() {
        searchBox = new EditBox(font, 0, 0, 120, 16, searchBox, Component.literal(t("Поиск", "Search")));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal(t("Поиск…", "Search…")).withStyle(EditBox.SEARCH_HINT_STYLE));
        if (searchBox.getValue().isEmpty()) searchBox.setValue(RecordingsTab.query);
        searchBox.setResponder(recordings::setQuery);
        searchBox.visible = false;
        addWidget(searchBox);
        oppSearch = new EditBox(font, 0, 0, 120, 14, oppSearch, Component.literal(t("Противник", "Opponent")));
        oppSearch.setMaxLength(32);
        oppSearch.setHint(Component.literal(t("Ник…", "Name…")).withStyle(EditBox.SEARCH_HINT_STYLE));
        oppSearch.setResponder(progress::setOppQuery);
        oppSearch.visible = false;
        addWidget(oppSearch);
        current().onShow();
        if (editBox != null) addWidget(editBox);
    }

    private Tab current() {
        return switch (tab) {
            case RECORDINGS -> recordings;
            case VIEW -> view;
            case PROGRESS -> progress;
            case SETTINGS -> settingsTab;
        };
    }

    void switchTab(TabId t) {
        tab = t;
        lastTab = t == TabId.VIEW ? TabId.RECORDINGS : t;
        current().onShow();
    }

    void openRecording(Recordings.Entry entry, Recording rec) {
        view.set(entry, rec);
        switchTab(TabId.VIEW);
    }

    void openLive() {
        view.setLive();
        switchTab(TabId.VIEW);
    }

    void placeSearch(int x, int y, int w, int h) {
        searchBox.visible = w >= 40;
        searchBox.active = modal == null;
        if (!searchBox.visible) return;
        searchBox.setX(x);
        searchBox.setY(y);
        searchBox.setWidth(w);
        searchBox.setHeight(h);
        searchBox.render(currentG, frameMouseX, frameMouseY, frameDelta);
    }

    void placeOppSearch(int x, int y, int w, int h) {
        oppSearch.visible = true;
        oppSearch.active = modal == null;
        oppSearch.setX(x);
        oppSearch.setY(y);
        oppSearch.setWidth(w);
        oppSearch.setHeight(h);
        oppSearch.render(currentG, frameMouseX, frameMouseY, frameDelta);
    }

    void focusOppSearch() {
        oppSearch.setValue("");
        setFocused(oppSearch);
    }

    boolean lastDoubleClick() {
        return lastDouble;
    }

    void openModal(Modal m) {
        closeModal();
        modal = m;
        if (m.input != null) {
            editBox = new EditBox(font, 0, 0, 200, 18, Component.empty());
            editBox.setMaxLength(64);
            editBox.setValue(m.input);
            addWidget(editBox);
            setFocused(editBox);
        }
    }

    void confirmReset(Runnable after) {
        openModal(new Modal(t("Сбросить буфер?", "Reset the buffer?"), List.of(
                t("Всё записанное с ", "Everything recorded since ") + Fmt.clock(dev.combatdata.client.Recorder.shownMs())
                        + t(" назад будет удалено без сохранения.", " ago will be discarded without saving."),
                t("Также: Shift+F9 дважды или /combatdata reset confirm.", "Also: Shift+F9 twice or /combatdata reset confirm.")), null, List.of(
                new Modal.Btn(t("Отмена", "Cancel"), Ui.Kind.SECONDARY, s -> closeModal()),
                new Modal.Btn(t("Сбросить", "Reset"), Ui.Kind.DANGER, s -> {
                    dev.combatdata.client.Recorder.reset(minecraft);
                    closeModal();
                    recordings.refresh();
                    if (after != null) after.run();
                }))));
    }

    void closeModal() {
        modal = null;
        if (editBox != null) {
            removeWidget(editBox);
            editBox = null;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        Theme.apply(settingsTab.previewSpec());
        super.render(g, mouseX, mouseY, delta);
        targets.clear();
        frameMouseX = mouseX;
        frameMouseY = mouseY;
        frameDelta = delta;
        currentG = g;
        searchBox.visible = false;
        oppSearch.visible = false;
        Ui ui = new Ui(g, font, mouseX, mouseY, targets, modal != null ? 1 : 0);
        // The window must fit the screen: at GUI scale 4 on 720p the screen is only 320x180.
        int ww = Math.min(width - 4, Math.max(300, Math.min(width - 16, 560)));
        int wh = Math.min(height - 4, Math.max(200, Math.min(height - 16, 340)));
        int wx = (width - ww) / 2, wy = (height - wh) / 2;
        Draw.roundBordered(g, wx, wy, ww, wh, Theme.R_WINDOW, Theme.BORDER, Theme.WINDOW);
        boolean compact = wh < 240;
        if (!compact) ui.text("Combat Data", wx + 10, wy + 9, Theme.TEXT_STRONG);
        int cb = 14, cbx = wx + ww - 8 - cb, cby = wy + 6;
        boolean ch = ui.hover(cbx, cby, cb, cb);
        if (ch) Draw.round(g, cbx, cby, cb, cb, Theme.R_CTRL, Theme.RAISED_HOVER);
        ui.textCenter("×", cbx + cb / 2 + 1, Ui.textY(cby, cb), ch ? Theme.TEXT_STRONG : Theme.MUTED);
        ui.click(cbx, cby, cb, cb, this::onClose);
        int tx = wx + 8, ty = compact ? wy + 5 : wy + 24, line = ty + 18, top = line + 8;
        for (TabId t : TabId.values()) {
            int tw = font.width(t.label()) + 16;
            boolean sel = t == tab, hov = ui.hover(tx, ty, tw, 16);
            if (sel) Draw.round(g, tx, ty, tw, 16, Theme.R_CTRL, Theme.RAISED);
            else if (hov) Draw.round(g, tx, ty, tw, 16, Theme.R_CTRL, Theme.ROW_HOVER);
            ui.textCenter(t.label(), tx + tw / 2, Ui.textY(ty, 16), sel ? Theme.TEXT_STRONG : (hov ? Theme.TEXT : Theme.MUTED));
            ui.click(tx, ty, tw, 16, () -> switchTab(t));
            tx += tw + 2;
        }
        g.fill(wx + 8, line, wx + ww - 8, line + 1, Theme.BORDER);
        dev.combatdata.client.Safe.run("вкладка меню", () -> current().render(ui, wx + 10, top, ww - 20, wy + wh - 10 - top));
        if (modal != null) renderModal(ui, mouseX, mouseY, delta);
        else ui.drawTip(width, height);
    }

    private void renderModal(Ui ui, int mouseX, int mouseY, float delta) {
        GuiGraphics g = ui.g;
        g.nextStratum();
        ui.layer = 1;
        g.fill(0, 0, width, height, Theme.SCRIM);
        int mw = Math.min(width - 24, 260);
        int mh = 30 + modal.lines.size() * 11 + (editBox != null ? 26 : 0) + (modal.error != null ? 12 : 0) + 24;
        int mx = (width - mw) / 2, my = (height - mh) / 2;
        Draw.roundBordered(g, mx, my, mw, mh, Theme.R_WINDOW, Theme.BORDER, Theme.WINDOW);
        ui.text(Draw.ellipsize(font, modal.title, mw - 20), mx + 10, my + 10, Theme.TEXT_STRONG);
        int y = my + 26;
        for (String line : modal.lines) {
            ui.text(Draw.ellipsize(font, line, mw - 20), mx + 10, y, Theme.MUTED);
            y += 11;
        }
        if (editBox != null) {
            editBox.setX(mx + 10);
            editBox.setY(y + 2);
            editBox.setWidth(mw - 20);
            editBox.render(g, mouseX, mouseY, delta);
            y += 26;
        }
        if (modal.error != null) {
            ui.text(Draw.ellipsize(font, modal.error, mw - 20), mx + 10, y, Theme.RED);
            y += 12;
        }
        int bx = mx + mw - 10, by = my + mh - 22;
        for (int i = modal.buttons.size() - 1; i >= 0; i--) {
            Modal.Btn b = modal.buttons.get(i);
            int bw = ui.buttonWidth(b.label());
            bx -= bw;
            Modal m = modal;
            ui.button(bx, by, bw, 16, b.label(), b.kind(), true,
                    () -> b.action().accept(editBox != null && m == modal ? editBox.getValue() : null));
            bx -= 4;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (editBox != null && editBox.isMouseOver(event.x(), event.y())) return super.mouseClicked(event, doubleClick);
        if (modal == null && searchBox.isMouseOver(event.x(), event.y())) return super.mouseClicked(event, doubleClick);
        if (modal == null && oppSearch.isMouseOver(event.x(), event.y())) return super.mouseClicked(event, doubleClick);
        // Clicking outside the search box drops focus so typing doesn't go to a hidden field.
        if (modal == null && (getFocused() == searchBox || getFocused() == oppSearch)) setFocused(null);
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        lastDouble = doubleClick;
        int active = modal != null ? 1 : 0;
        for (int i = targets.size() - 1; i >= 0; i--) {
            Ui.Target t = targets.get(i);
            if (t.layer() >= active && t.contains(event.x(), event.y())) {
                t.action().run();
                return true;
            }
        }
        if (modal == null && current().pressed(event.x(), event.y())) {
            mouseCaptured = true;
            return true;
        }
        return modal != null || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (mouseCaptured) {
            current().dragged(event.x(), event.y(), dx);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (mouseCaptured && event.button() == 0) {
            mouseCaptured = false;
            current().released(event.x(), event.y());
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (modal == null && current().scrolled(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        if (modal != null) {
            closeModal();
            return;
        }
        if (tab == TabId.PROGRESS && progress.closePopup()) {
            setFocused(null);
            return;
        }
        if (settingsTab.dirty()) {
            openModal(new Modal(t("Настройки не применены", "Settings not applied"),
                    List.of(t("Есть изменения, которые не применены.", "There are changes that have not been applied.")), null, List.of(
                    new Modal.Btn(t("Назад", "Back"), Ui.Kind.SECONDARY, s -> closeModal()),
                    new Modal.Btn(t("Не сохранять", "Discard"), Ui.Kind.DANGER, s -> {
                        settingsTab.cancel();
                        closeModal();
                        minecraft.setScreen(null);
                    }),
                    new Modal.Btn(t("Применить", "Apply"), Ui.Kind.PRIMARY, s -> {
                        settingsTab.apply();
                        closeModal();
                        minecraft.setScreen(null);
                    }))));
            return;
        }
        CombatDataClient.saveSettings();
        minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
