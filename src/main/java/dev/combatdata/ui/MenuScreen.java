package dev.combatdata.ui;

import dev.combatdata.CombatDataClient;
import dev.combatdata.client.Recordings;
import dev.combatdata.core.Recording;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import static dev.combatdata.core.Lang.t;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

public final class MenuScreen extends Screen {
    enum TabId {
        RECORDINGS("Записи", "Recordings"), VIEW("Просмотр", "View"), PROGRESS("Прогресс", "Progress"),
        INDICATORS("Индикаторы", "Indicators"), SETTINGS("Настройки", "Settings");
        private final String ru, en;

        TabId(String ru, String en) {
            this.ru = ru;
            this.en = en;
        }

        String label() {
            return t(ru, en);
        }
    }

    static String tabLabel(TabId t) {
        return t.label();
    }

    private static TabId lastTab = TabId.RECORDINGS;

    final RecordingsTab recordings = new RecordingsTab(this);
    final ViewTab view = new ViewTab(this);
    final ProgressTab progress = new ProgressTab(this);
    final IndicatorsTab indicators = new IndicatorsTab(this);
    final SettingsTab settingsTab = new SettingsTab(this);
    private TabId tab = lastTab;
    private final List<Ui.Target> targets = new ArrayList<>();
    private Modal modal;
    private EditBox editBox;
    private EditBox searchBox;
    private EditBox oppSearch;
    private int frameMouseX, frameMouseY;
    private float frameDelta;
    private GuiGraphicsExtractor currentG;
    private boolean lastDouble;
    private boolean mouseCaptured;
    private final Find find = new Find();
    private EditBox findBox;
    private boolean findOpen;
    private int findSel;
    private List<Find.Result> findResults = List.of();
    private int findX, findY, findW, findH;

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
        findBox = new EditBox(font, 0, 0, 140, 14, findBox, Component.literal(t("Поиск по меню", "Search the menu")));
        findBox.setMaxLength(48);
        findBox.setHint(Component.literal(t("Поиск по меню (Ctrl+F)", "Search (Ctrl+F)")).withStyle(EditBox.SEARCH_HINT_STYLE));
        findBox.setResponder(q -> {
            // A new search rebuilds the index: rows can appear, e.g. a custom place or a new theme.
            if (!findOpen) find.invalidate();
            findOpen = !q.isBlank();
            findSel = 0;
        });
        findBox.visible = false;
        addWidget(findBox);
        current().onShow();
        if (editBox != null) addWidget(editBox);
    }

    private Tab current() {
        return switch (tab) {
            case RECORDINGS -> recordings;
            case VIEW -> view;
            case PROGRESS -> progress;
            case INDICATORS -> indicators;
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
        searchBox.extractRenderState(currentG, frameMouseX, frameMouseY, frameDelta);
    }

    void placeOppSearch(int x, int y, int w, int h) {
        oppSearch.visible = true;
        oppSearch.active = modal == null;
        oppSearch.setX(x);
        oppSearch.setY(y);
        oppSearch.setWidth(w);
        oppSearch.setHeight(h);
        oppSearch.extractRenderState(currentG, frameMouseX, frameMouseY, frameDelta);
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
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Theme.apply(settingsTab.previewSpec());
        super.extractRenderState(g, mouseX, mouseY, delta);
        targets.clear();
        frameMouseX = mouseX;
        frameMouseY = mouseY;
        frameDelta = delta;
        currentG = g;
        searchBox.visible = false;
        oppSearch.visible = false;
        if (findOpen && find.needsIndex()) buildIndex(g);
        Ui ui = new Ui(g, font, mouseX, mouseY, targets, modal != null ? 1 : 0);
        ui.find = find;
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
        // Narrow windows (large GUI scale) get tighter tabs so all of them fit next to the close button.
        int tabsW = 0;
        for (TabId t : TabId.values()) tabsW += font.width(t.label()) + 18;
        int tabPad = tabsW <= ww - 40 ? 16 : 8;
        int tabsEnd = wx + 8;
        for (TabId t : TabId.values()) tabsEnd += font.width(t.label()) + tabPad + 2;
        placeFind(compact ? tabsEnd : wx + 10 + font.width("Combat Data"), cbx, cby, mouseX, mouseY, delta);
        for (TabId t : TabId.values()) {
            int tw = font.width(t.label()) + tabPad;
            boolean sel = t == tab, hov = ui.hover(tx, ty, tw, 16);
            if (sel) Draw.round(g, tx, ty, tw, 16, Theme.R_CTRL, Theme.RAISED);
            else if (hov) Draw.round(g, tx, ty, tw, 16, Theme.R_CTRL, Theme.ROW_HOVER);
            ui.textCenter(t.label(), tx + tw / 2, Ui.textY(ty, 16), sel ? Theme.TEXT_STRONG : (hov ? Theme.TEXT : Theme.MUTED));
            ui.click(tx, ty, tw, 16, () -> switchTab(t));
            tx += tw + 2;
        }
        g.fill(wx + 8, line, wx + ww - 8, line + 1, Theme.BORDER);
        find.at(tab, 0);
        find.beginFrame();
        dev.combatdata.client.Safe.run("вкладка меню", () -> current().render(ui, wx + 10, top, ww - 20, wy + wh - 10 - top));
        if (modal == null && findOpen && findBox.visible) renderFind(ui);
        else findH = 0;
        if (modal != null) renderModal(ui, mouseX, mouseY, delta);
        else ui.drawTip(width, height);
    }

    /** The search field sits in the header, left of the close button; hidden if the header has no room. */
    private void placeFind(int leftLimit, int closeX, int y, int mouseX, int mouseY, float delta) {
        int right = closeX - 6, fw = Math.min(150, right - leftLimit - 10);
        findBox.visible = fw >= 70;
        if (!findBox.visible) {
            findOpen = false;
            return;
        }
        findBox.active = modal == null;
        findBox.setX(right - fw);
        findBox.setY(y);
        findBox.setWidth(fw);
        findBox.setHeight(14);
        findBox.extractRenderState(currentG, mouseX, mouseY, delta);
    }

    private static final int FIND_ROW = 22, FIND_LIMIT = 8;

    private void renderFind(Ui ui) {
        GuiGraphicsExtractor g = ui.g;
        String q = findBox.getValue();
        findResults = find.search(q, FIND_LIMIT);
        int total = find.count(q), n = findResults.size();
        findSel = Math.max(0, Math.min(findSel, n - 1));
        int w = Math.min(width - 8, Math.max(findBox.getWidth(), 250));
        int x = Math.max(4, findBox.getX() + findBox.getWidth() - w), y = findBox.getY() + findBox.getHeight() + 3;
        int more = total > n ? 12 : 0;
        int h = (n == 0 ? 18 : n * FIND_ROW) + 6 + more;
        findX = x;
        findY = y;
        findW = w;
        findH = h;
        g.nextStratum();
        Draw.roundBordered(g, x, y, w, h, Theme.R_PANEL, Theme.ACCENT, Theme.WINDOW);
        if (n == 0) {
            ui.text(t("Ничего не найдено", "Nothing found"), x + 8, y + 8, Theme.MUTED);
            return;
        }
        for (int i = 0; i < n; i++) {
            Find.Entry e = findResults.get(i).entry();
            int ry = y + 3 + i * FIND_ROW;
            boolean hov = ui.hover(x + 3, ry, w - 6, FIND_ROW);
            if (hov) findSel = i;
            if (i == findSel) Draw.round(g, x + 3, ry, w - 6, FIND_ROW, Theme.R_CTRL, Theme.RAISED);
            findLabel(ui, q, e.label(), x + 9, ry + 3, w - 18);
            ui.text(Draw.ellipsize(font, findPath(e), w - 18), x + 9, ry + 12, Theme.MUTED);
            ui.click(x + 3, ry, w - 6, FIND_ROW, () -> go(e));
        }
        if (more > 0) ui.text(Draw.ellipsize(font, t("Ещё ", "") + (total - n) + t(": уточните запрос", " more: refine the search"), w - 18),
                x + 9, y + h - 12, Theme.DIM);
    }

    /** The label with the matched part in the accent color. */
    private void findLabel(Ui ui, String q, String label, int x, int y, int maxW) {
        int[] sp = dev.combatdata.core.SearchText.span(q, label);
        if (sp == null || font.width(label) > maxW) {
            ui.text(Draw.ellipsize(font, label, maxW), x, y, Theme.TEXT_STRONG);
            return;
        }
        String a = label.substring(0, sp[0]), b = label.substring(sp[0], sp[1]), c = label.substring(sp[1]);
        ui.text(a, x, y, Theme.TEXT_STRONG);
        ui.text(b, x + font.width(a), y, Theme.ACCENT);
        ui.text(c, x + font.width(a + b), y, Theme.TEXT_STRONG);
    }

    private static String findPath(Find.Entry e) {
        String p = e.tab().label();
        if (e.tab() == TabId.SETTINGS) p += " → " + SettingsTab.sectionName(e.section());
        if (e.group() != null && !e.group().equals(e.label())) p += " → " + e.group();
        return p;
    }

    private void go(Find.Entry e) {
        findOpen = false;
        setFocused(null);
        switch (e.tab()) {
            case SETTINGS -> {
                settingsTab.reveal(e.section());
                switchTab(TabId.SETTINGS);
                find.show(e);
            }
            case VIEW -> {
                view.openPage(e.section() / 10, e.section() % 10);
                switchTab(TabId.VIEW);
            }
            default -> {
                switchTab(e.tab());
                find.show(e);
            }
        }
    }

    /**
     * Lays out every searchable part once, off screen, in both languages. The language switch lasts only for this
     * call on the render thread.
     */
    private void buildIndex(GuiGraphicsExtractor g) {
        boolean en = dev.combatdata.core.Lang.english();
        try {
            List<Find.Entry> cur = collect(g, en), other = collect(g, !en);
            dev.combatdata.core.Lang.setEnglish(en);
            find.setIndex(cur, other);
        } finally {
            dev.combatdata.core.Lang.setEnglish(en);
        }
    }

    private List<Find.Entry> collect(GuiGraphicsExtractor g, boolean english) {
        dev.combatdata.core.Lang.setEnglish(english);
        find.startCollect();
        Ui ui = new Ui(g, font, Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, new ArrayList<>(), 0);
        ui.find = find;
        g.pose().pushMatrix();
        g.pose().translate(-20000f, -20000f);
        g.enableScissor(0, 0, 1, 1);
        try {
            settingsTab.index(ui, 520);
            indicators.index(ui, 520);
        } finally {
            g.disableScissor();
            g.pose().popMatrix();
        }
        String[] subs = StatsView.subs();
        find.add(TabId.VIEW, 0, null, subs[StatsView.OVERVIEW]);
        for (int sub = StatsView.ATTACK; sub <= StatsView.FIGHTS; sub++) {
            String[] pages = StatsView.pages(sub);
            for (int m = 0; m < pages.length; m++) find.add(TabId.VIEW, sub * 10 + m, subs[sub], pages[m]);
        }
        return find.stopCollect();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (modal == null && findBox.visible && k == GLFW.GLFW_KEY_F && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
            setFocused(findBox);
            findOpen = !findBox.getValue().isBlank();
            return true;
        }
        if (modal == null && getFocused() == findBox) {
            int n = findResults.size();
            if (findOpen && k == GLFW.GLFW_KEY_DOWN) {
                findSel = Math.min(findSel + 1, Math.max(0, n - 1));
                return true;
            }
            if (findOpen && k == GLFW.GLFW_KEY_UP) {
                findSel = Math.max(0, findSel - 1);
                return true;
            }
            if (findOpen && (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER)) {
                if (n > 0) go(findResults.get(Math.min(findSel, n - 1)).entry());
                return true;
            }
            if (k == GLFW.GLFW_KEY_ESCAPE) {
                // First Esc closes the results, the second clears the field, the third closes the menu.
                if (findOpen) findOpen = false;
                else if (!findBox.getValue().isEmpty()) findBox.setValue("");
                else setFocused(null);
                findOpen = false;
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private void renderModal(Ui ui, int mouseX, int mouseY, float delta) {
        GuiGraphicsExtractor g = ui.g;
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
            editBox.extractRenderState(g, mouseX, mouseY, delta);
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
        if (modal == null && findBox.visible && findBox.isMouseOver(event.x(), event.y())) {
            findOpen = !findBox.getValue().isBlank();
            return super.mouseClicked(event, doubleClick);
        }
        boolean inFind = findH > 0 && event.x() >= findX && event.x() < findX + findW && event.y() >= findY && event.y() < findY + findH;
        if (modal == null && findOpen && !inFind) {
            // The first click outside only closes the results, it does not press what is under them.
            findOpen = false;
            setFocused(null);
            return true;
        }
        if (modal == null && searchBox.isMouseOver(event.x(), event.y())) return super.mouseClicked(event, doubleClick);
        if (modal == null && oppSearch.isMouseOver(event.x(), event.y())) return super.mouseClicked(event, doubleClick);
        // Clicking outside the search box drops focus so typing doesn't go to a hidden field.
        if (modal == null && (getFocused() == searchBox || getFocused() == oppSearch || getFocused() == findBox)) setFocused(null);
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
