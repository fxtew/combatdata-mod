package dev.combatdata.ui;

import dev.combatdata.CombatDataClient;
import dev.combatdata.client.HudPositionScreen;
import dev.combatdata.client.Recordings;
import dev.combatdata.core.Calibration;
import dev.combatdata.core.Cdrec;
import dev.combatdata.core.JumpResetEvaluator;
import dev.combatdata.core.Retention;
import dev.combatdata.core.Settings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import static dev.combatdata.core.Lang.t;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Changes go to a draft and apply on "Apply"; HUD position changes apply immediately. */
final class SettingsTab implements Tab {
    private static String[] sections() {
        return dev.combatdata.core.Lang.english()
                ? new String[]{"General", "HUD", "Stat filters", "Appearance", "Advanced"}
                : new String[]{"Основные", "HUD", "Фильтры статистики", "Оформление", "Дополнительно"};
    }

    private static String[] sectionsShort() {
        return dev.combatdata.core.Lang.english()
                ? new String[]{"General", "HUD", "Filters", "Look", "Advanced"}
                : new String[]{"Основные", "HUD", "Фильтры", "Вид", "Дополн."};
    }
    private static final int[] PRESETS = {5, 10, 20, 30, 60};
    private static final Settings DEF = new Settings();
    private final MenuScreen menu;
    private Settings draft;
    private int section;
    private double scroll;
    private int maxScroll;
    private int panelX, panelY, panelW, panelH;

    SettingsTab(MenuScreen menu) {
        this.menu = menu;
    }

    private static Settings live() {
        return CombatDataClient.settings();
    }

    @Override
    public void onShow() {
        if (draft == null) draft = live().copy();
    }

    boolean dirty() {
        return draft != null && draft.diffCount(live()) > 0;
    }

    void apply() {
        if (draft == null) return;
        boolean limits = draft.storageLimitMb != live().storageLimitMb || draft.autoDeleteDays != live().autoDeleteDays;
        live().copyFrom(draft);
        CombatDataClient.saveSettings();
        CombatDataClient.applyLanguage(Minecraft.getInstance());
        if (limits && (draft.storageLimitMb > 0 || draft.autoDeleteDays > 0)) {
            dev.combatdata.client.Recorder.cleanupNow(Minecraft.getInstance());
            storageReloadAt = System.currentTimeMillis() + 1500;
        }
        draft = live().copy();
    }

    void cancel() {
        draft = live().copy();
    }

    @Override
    public void render(Ui ui, int x, int y, int w, int h) {
        if (draft == null) draft = live().copy();
        draft.hudX = live().hudX;
        draft.hudY = live().hudY;
        draft.fsX = live().fsX;
        draft.fsY = live().fsY;
        ui.segmented(x, y, ui.segmentedWidth(sections()) <= w ? sections() : sectionsShort(), section, i -> {
            section = i;
            storageList = null;
            scroll = 0;
        });
        int footerY = y + h - 16;
        panelX = x;
        panelY = y + 24;
        panelW = w;
        panelH = footerY - 8 - panelY;
        Draw.roundBordered(ui.g, panelX, panelY, panelW, panelH, Theme.R_PANEL, Theme.BORDER, Theme.PANEL);
        int ix = panelX + 1, iy = panelY + 1, iw = panelW - 2, ih = panelH - 2;
        ui.g.enableScissor(ix, iy, ix + iw, iy + ih);
        ui.clip(ix, iy, iw, ih);
        int top = iy - (int) scroll;
        if (ui.find != null) ui.find.at(MenuScreen.TabId.SETTINGS, section);
        int bottom = content(ui, ix, top, iw, section);
        int content = bottom - top;
        maxScroll = Math.max(0, content - ih);
        if (ui.find != null) scroll += ui.find.scrollDelta(iy, ih);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        ui.unclip();
        ui.g.disableScissor();
        ui.scrollbar(panelX + panelW - 5, iy + 2, ih - 4, scroll, ih, content);

        int pending = draft.diffCount(live());
        if (pending > 0) {
            Draw.circle(ui.g, x, footerY + 5, 6, Theme.AMBER);
            ui.text(pending + (dev.combatdata.core.Lang.english() ? (pending == 1 ? " change not applied" : " changes not applied")
                    : " " + dev.combatdata.core.Lang.plural(pending, "изменение", "изменения", "изменений") + " не применено"), x + 10,
                    Ui.textY(footerY, 16), Theme.MUTED);
        }
        int bx = x + w, bw = ui.buttonWidth(t("Применить", "Apply"));
        bx -= bw;
        ui.button(bx, footerY, bw, 16, t("Применить", "Apply"), Ui.Kind.PRIMARY, pending > 0, this::apply);
        bw = ui.buttonWidth(t("Отмена", "Cancel"));
        bx -= bw + 4;
        ui.button(bx, footerY, bw, 16, t("Отмена", "Cancel"), Ui.Kind.SECONDARY, pending > 0, this::cancel);
    }

    private int content(Ui ui, int x, int top, int w, int s) {
        return switch (s) {
            case 0 -> keys(ui, x, heading(ui, x, whatToRecord(ui, x, recording(ui, x, top, w), w), w, t("Язык и клавиши", "Language and keys")), w);
            case 1 -> hud(ui, x, top, w);
            case 2 -> filters(ui, x, top, w);
            case 3 -> appearance(ui, x, top, w);
            default -> advanced(ui, x, top, w);
        };
    }

    /** Lays out every section for the search index. */
    void index(Ui ui, int w) {
        if (draft == null) draft = live().copy();
        for (int s = 0; s < sections().length; s++) {
            ui.find.at(MenuScreen.TabId.SETTINGS, s);
            content(ui, 0, 0, w, s);
        }
    }

    /** Opens a section for a search result; the search then scrolls to the row. */
    void reveal(int s) {
        if (s != section) {
            section = s;
            storageList = null;
            scroll = 0;
        }
    }

    static String sectionName(int s) {
        String[] n = sections();
        return s >= 0 && s < n.length ? n[s] : "";
    }

    @Override
    public boolean scrolled(double mx, double my, double dy) {
        if (mx < panelX || mx >= panelX + panelW || my < panelY || my >= panelY + panelH) return false;
        // Clamp now, otherwise one frame is drawn past the edge and the panel flickers.
        scroll = Math.max(0, Math.min(scroll - dy * 20, maxScroll));
        return true;
    }

    private static void labelInfo(Ui ui, int x, int y, int h, String label, boolean hint, String text) {
        int ly = hint ? y + h / 2 - 9 : Ui.textY(y, h);
        ui.info(x + 10 + ui.font.width(label) + 14, ly - 1, text);
    }

    private int row(Ui ui, int x, int y, int w, int h, String label, String hint, boolean changed) {
        ui.mark(label, hint, x + 4, y, w - 8, h);
        int ly = hint == null ? Ui.textY(y, h) : y + h / 2 - 9;
        ui.text(label, x + 10, ly, Theme.TEXT);
        if (changed) Draw.circle(ui.g, x + 10 + ui.font.width(label) + 5, ly + 1, 5, Theme.AMBER);
        if (hint != null) ui.text(Draw.ellipsize(ui.font, hint, w / 2), x + 10, y + h / 2 + 1, Theme.MUTED);
        ui.g.fill(x + 8, y + h - 1, x + w - 8, y + h, Theme.BORDER);
        return y + h;
    }

    private void reset(Ui ui, int right, int y, boolean differs, String def, Runnable action) {
        if (!differs) return;
        int s = 14, bx = right - s;
        boolean hov = ui.hover(bx, y, s, s);
        if (hov) {
            Draw.round(ui.g, bx, y, s, s, Theme.R_CTRL, Theme.RAISED_HOVER);
            String tip = t("По умолчанию: ", "Default: ") + def;
            ui.textRight(tip, bx - 4, Ui.textY(y, s), Theme.MUTED);
        }
        ui.textCenter("↺", bx + s / 2 + 1, Ui.textY(y, s), hov ? Theme.TEXT_STRONG : Theme.MUTED);
        ui.click(bx, y, s, s, action);
    }

    private int recording(Ui ui, int x, int y, int w) {
        Settings l = live();
        int rh = 38, right = x + w - 10;
        row(ui, x, y, w, rh, t("Длина буфера", "Buffer length"), t("Хранятся последние N минут, от 1 до 120", "Keeps the last N minutes, 1 to 120"), draft.bufferMinutes != l.bufferMinutes);
        int sw = 72;
        ui.stepper(right - sw, y + 4, sw, draft.bufferMinutes + t(" мин", " min"), () -> stepBuffer(-1), () -> stepBuffer(1));
        reset(ui, right - sw - 4, y + 5, draft.bufferMinutes != DEF.bufferMinutes, DEF.bufferMinutes + t(" мин", " min"),
                () -> draft.bufferMinutes = DEF.bufferMinutes);
        int cw = 22, cx = right - (cw + 3) * PRESETS.length + 3;
        for (int m : PRESETS) {
            ui.chip(cx, y + 23, cw, String.valueOf(m), draft.bufferMinutes == m, () -> {
                draft.bufferMinutes = m;
                draft.clamp();
            });
            cx += cw + 3;
        }
        y += rh;
        row(ui, x, y, w, 26, t("Очищать буфер при смене сервера", "Clear buffer on server change"), null, draft.clearOnServerChange != l.clearOnServerChange);
        ui.toggle(right - 20, y + 7, draft.clearOnServerChange, v -> draft.clearOnServerChange = v);
        reset(ui, right - 26, y + 6, draft.clearOnServerChange != DEF.clearOnServerChange, onOff(DEF.clearOnServerChange),
                () -> draft.clearOnServerChange = DEF.clearOnServerChange);
        y += 26;
        y = flag(ui, x, y, w, t("Автосохранение боёв", "Autosave fights"),
                t("Через " + draft.fsDelaySec + " с после боя, от " + draft.statMinFightHits + " ударов",
                        draft.fsDelaySec + " s after a fight, from " + draft.statMinFightHits + " hits"),
                draft.autoSave, l.autoSave, DEF.autoSave, v -> draft.autoSave = v);
        y = flag(ui, x, y, w, t("Звук при сохранении", "Sound on save"), t("Высокий звук: сохранено, низкий: не удалось", "High: saved, low: failed"), draft.soundOnSave,
                l.soundOnSave, DEF.soundOnSave, v -> draft.soundOnSave = v);
        row(ui, x, y, w, 26, t("Громкость звука", "Sound volume"), null, draft.soundVolume != l.soundVolume);
        int tw = ui.buttonWidth(t("Проба", "Test"));
        ui.button(right - tw, y + 5, tw, 16, t("Проба", "Test"), Ui.Kind.GHOST, draft.soundOnSave && draft.soundVolume > 0,
                () -> dev.combatdata.client.Sounds.preview(Minecraft.getInstance(), draft));
        int vx = right - tw - 4 - sw;
        ui.stepper(vx, y + 5, sw, draft.soundVolume + "%", () -> {
            draft.soundVolume -= 10;
            draft.clamp();
        }, () -> {
            draft.soundVolume += 10;
            draft.clamp();
        });
        reset(ui, vx - 4, y + 6, draft.soundVolume != DEF.soundVolume, DEF.soundVolume + "%", () -> draft.soundVolume = DEF.soundVolume);
        return y + 26;
    }

    private static String[] recordModes() {
        return new String[]{t("Полная", "Full"), t("Лёгкая", "Lite"), t("Своя", "Custom")};
    }

    private int whatToRecord(Ui ui, int x, int y, int w) {
        Settings l = live();
        y = heading(ui, x, y, w, t("Что записывать", "What to record"));
        String label = t("Режим записи", "Recording mode");
        y = choice(ui, x, y, w, label, recordModes(), draft.recordMode(), l.recordMode(), DEF.recordMode(), draft::setRecordMode);
        labelInfo(ui, x, y - 26, 26, label, false, Help.recordMode());
        y = flag(ui, x, y, w, t("Позиции во время боя", "Positions during fights"), t("Бои → Дистанция и повтор; больше всего места", "Fights → Distance and replay; the largest part"),
                draft.recordPositions, l.recordPositions, DEF.recordPositions, v -> draft.recordPositions = v);
        y = flag(ui, x, y, w, t("Нажатия W, S и спринта", "W, S and sprint presses"), t("Атака → W-tap", "Attack → W-tap"),
                draft.recordInput, l.recordInput, DEF.recordInput, v -> draft.recordInput = v);
        y = flag(ui, x, y, w, t("Отлёт после удара", "Knockback after a hit"), t("Защита → Отлёт и подбор окна jump reset", "Defense → Knockback and jump reset window fitting"),
                draft.recordKnockback, l.recordKnockback, DEF.recordKnockback, v -> draft.recordKnockback = v);
        y = flag(ui, x, y, w, t("Дистанция ударов по вам", "Distance of hits on you"), t("Защита → Удары по мне, 3 block hit против вас", "Defense → Hits on me, 3 block hits against you"),
                draft.recordTaken, l.recordTaken, DEF.recordTaken, v -> draft.recordTaken = v);
        ui.text(Draw.ellipsize(ui.font, t("Выключенное не пишется в новые записи, в Просмотре там «не записывалось».", "What is off is not saved in new recordings; View shows \"not recorded\" there."), w - 20),
                x + 10, y + 8, Theme.DIM);
        return y + 20;
    }

    static int heading(Ui ui, int x, int y, int w, String text) {
        if (ui.find != null) ui.find.group(text);
        ui.mark(text, null, x + 4, y + 3, w - 8, 18);
        ui.text(text, x + 10, y + 10, Theme.ACCENT);
        ui.g.fill(x + 8, y + 21, x + w - 8, y + 22, Theme.BORDER);
        return y + 22;
    }

    private int filters(Ui ui, int x, int y, int w) {
        Settings l = live();
        y = heading(ui, x, y, w, t("Удары", "Hits"));
        y = flag(ui, x, y, w, t("Только удары мечом", "Sword hits only"), t("Рука и другое оружие не входят в атаку, комбо и 3 block hit", "Hand and other weapons are left out of attack, combos and 3 block hit"),
                draft.statSwordOnly, l.statSwordOnly, DEF.statSwordOnly, v -> draft.statSwordOnly = v);
        y = flag(ui, x, y, w, t("Удар рукой не начинает размен", "Hand hit does not start a trade"), t("Тычок рукой перед боем не первый удар (для разменов)", "A poke before a fight is not the first hit (for trades)"),
                draft.statHandNoTrade, l.statHandNoTrade, DEF.statHandNoTrade, v -> draft.statHandNoTrade = v);
        y = heading(ui, x, y, w, t("Игроки", "Players"));
        y = flag(ui, x, y, w, t("Не учитывать тиммейтов", "Ignore teammates"), t("Общая команда scoreboard без урона по своим", "Same scoreboard team with friendly fire off"),
                draft.statNoTeammates, l.statNoTeammates, DEF.statNoTeammates, v -> draft.statNoTeammates = v);
        y = flag(ui, x, y, w, t("Не учитывать NPC и ботов", "Ignore NPCs and bots"), t("Игроки с UUID версии 2", "Players with a version 2 UUID"),
                draft.statNoNpc, l.statNoNpc, DEF.statNoNpc, v -> draft.statNoNpc = v);
        y = heading(ui, x, y, w, t("Пинг и бои", "Ping and fights"));
        y = flag(ui, x, y, w, t("Не учитывать высокий пинг", "Ignore high ping"), t("Удары, когда ваш пинг выше порога", "Hits while your ping is above the limit"),
                draft.statHighPing, l.statHighPing, DEF.statHighPing, v -> draft.statHighPing = v);
        int right = x + w - 10, sw = 72;
        row(ui, x, y, w, 26, t("Порог пинга", "Ping limit"), null, draft.statPingLimitMs != l.statPingLimitMs);
        ui.stepper(right - sw, y + 5, sw, draft.statPingLimitMs + t(" мс", " ms"), () -> {
            draft.statPingLimitMs -= 25;
            draft.clamp();
        }, () -> {
            draft.statPingLimitMs += 25;
            draft.clamp();
        });
        reset(ui, right - sw - 4, y + 6, draft.statPingLimitMs != DEF.statPingLimitMs, DEF.statPingLimitMs + t(" мс", " ms"),
                () -> draft.statPingLimitMs = DEF.statPingLimitMs);
        y += 26;
        row(ui, x, y, w, 30, t("Минимальный размер боя", "Minimum fight size"), t("Подтверждённых ударов в сумме, иначе бой не в списке", "Confirmed hits in total, smaller fights are not listed"),
                draft.statMinFightHits != l.statMinFightHits);
        ui.stepper(right - sw, y + 7, sw, String.valueOf(draft.statMinFightHits), () -> {
            draft.statMinFightHits--;
            draft.clamp();
        }, () -> {
            draft.statMinFightHits++;
            draft.clamp();
        });
        reset(ui, right - sw - 4, y + 8, draft.statMinFightHits != DEF.statMinFightHits, String.valueOf(DEF.statMinFightHits),
                () -> draft.statMinFightHits = DEF.statMinFightHits);
        y += 30;
        ui.text(Draw.ellipsize(ui.font, t("Применяются при просмотре ко всем записям, в файле сохраняется всё.", "Applied when viewing, to all recordings; files keep everything."), w - 20), x + 10, y + 8, Theme.DIM);
        ui.text(Draw.ellipsize(ui.font, t("В Просмотре чип «Оружие» меняет выбор только для этой записи.", "In View, the Weapon chip changes it for that recording only."), w - 20), x + 10, y + 20, Theme.DIM);
        return y + 36;
    }

    private int advanced(Ui ui, int x, int y, int w) {
        Settings l = live();
        y = heading(ui, x, y, w, "Jump reset");
        y = jumpReset(ui, x, y, w);
        y = heading(ui, x, y, w, "3 block hit");
        y = statistics(ui, x, y, w);
        y = heading(ui, x, y, w, t("Размены", "Trades"));
        int right = x + w - 10, sw = 72;
        row(ui, x, y, w, 30, t("Пауза между разменами", "Pause between trades"), t("Без ударов дольше: начинается новый размен", "No hits for longer: a new trade starts"),
                draft.tradeGapMs != l.tradeGapMs);
        ui.stepper(right - sw, y + 7, sw, Fmt.num(draft.tradeGapMs / 1000.0, 2) + t(" с", " s"), () -> {
            draft.tradeGapMs -= 250;
            draft.clamp();
        }, () -> {
            draft.tradeGapMs += 250;
            draft.clamp();
        });
        reset(ui, right - sw - 4, y + 8, draft.tradeGapMs != DEF.tradeGapMs, Fmt.num(DEF.tradeGapMs / 1000.0, 2) + t(" с", " s"),
                () -> draft.tradeGapMs = DEF.tradeGapMs);
        y += 30;
        y = choice(ui, x, y, w, t("Победитель размена", "Trade winner"), new String[]{t("По ударам", "By hits"), t("По весу", "By weight")},
                draft.tradeByWeight ? 1 : 0, l.tradeByWeight ? 1 : 0, DEF.tradeByWeight ? 1 : 0, v -> draft.tradeByWeight = v == 1);
        y = heading(ui, x, y, w, t("Хранилище", "Storage"));
        y = storage(ui, x, y, w);
        y = heading(ui, x, y, w, t("Отладка", "Debugging"));
        return flag(ui, x, y, w, t("Отладочный лог", "Debug log"), t("Строки [CD] в latest.log", "[CD] lines in latest.log"),
                draft.debugLog, l.debugLog, DEF.debugLog, v -> draft.debugLog = v);
    }

    private static String onOff(boolean v) {
        return v ? t("вкл.", "on") : t("выкл.", "off");
    }

    private void stepBuffer(int dir) {
        int m = draft.bufferMinutes;
        if (dir > 0) m = m < 10 ? m + 1 : m + 5;
        else m = m <= 10 ? m - 1 : m - 5;
        draft.bufferMinutes = m;
        draft.clamp();
    }

    private static String[] formats() {
        return new String[]{t("Полный", "Full"), t("Время", "Time"), t("Кружок", "Dot")};
    }

    private static String[] textColors() {
        return new String[]{t("Состояние", "State"), t("Белый", "White"), t("Серый", "Grey")};
    }

    private int flag(Ui ui, int x, int y, int w, String label, String hint, boolean value, boolean liveV, boolean def,
                     java.util.function.Consumer<Boolean> set) {
        int h = hint == null ? 26 : 30, right = x + w - 10;
        row(ui, x, y, w, h, label, hint, value != liveV);
        ui.toggle(right - 20, y + (h - 11) / 2, value, set);
        reset(ui, right - 26, y + (h - 14) / 2, value != def, onOff(def), () -> set.accept(def));
        return y + h;
    }

    private int choice(Ui ui, int x, int y, int w, String label, String[] items, int value, int liveV, int def,
                       java.util.function.Consumer<Integer> set) {
        int right = x + w - 10, sw = ui.segmentedWidth(items);
        row(ui, x, y, w, 26, label, null, value != liveV);
        ui.segmented(right - sw, y + 4, items, value, set);
        reset(ui, right - sw - 4, y + 6, value != def, items[def], () -> set.accept(def));
        return y + 26;
    }

    private int hud(Ui ui, int x, int y, int w) {
        Settings l = live();
        int right = x + w - 10;
        row(ui, x, y, w, 30, t("Предпросмотр", "Preview"), t("Так надпись будет выглядеть после «Применить»", "How the label will look after Apply"), false);
        int pw = 150, ph = 22, pxl = right - pw, pyl = y + 4;
        ui.g.fill(pxl, pyl, pxl + pw, pyl + ph / 2, 0xFF7EA6D6);
        ui.g.fill(pxl, pyl + ph / 2, pxl + pw, pyl + ph, 0xFF5D8C3A);
        dev.combatdata.client.RecHud.preview(ui.g, ui.font, pxl + 6, pyl + (ph - 13) / 2, draft);
        y += 30;
        row(ui, x, y, w, 26, t("Показывать надпись записи", "Show recording label"), null, draft.hudVisible != l.hudVisible);
        ui.toggle(right - 20, y + 7, draft.hudVisible, v -> draft.hudVisible = v);
        reset(ui, right - 26, y + 6, draft.hudVisible != DEF.hudVisible, onOff(DEF.hudVisible),
                () -> draft.hudVisible = DEF.hudVisible);
        y += 26;
        row(ui, x, y, w, 26, t("Масштаб надписи", "Label scale"), null, draft.hudScale != l.hudScale);
        ui.stepper(right - 72, y + 5, 72, String.format(Locale.ROOT, "%.2fx", draft.hudScale), () -> {
            draft.hudScale -= 0.25;
            draft.clamp();
        }, () -> {
            draft.hudScale += 0.25;
            draft.clamp();
        });
        reset(ui, right - 76, y + 6, draft.hudScale != DEF.hudScale, String.format(Locale.ROOT, "%.2fx", DEF.hudScale),
                () -> draft.hudScale = DEF.hudScale);
        y += 26;
        row(ui, x, y, w, 26, t("Непрозрачность фона", "Background opacity"), null, draft.hudBgOpacity != l.hudBgOpacity);
        ui.stepper(right - 72, y + 5, 72, draft.hudBgOpacity + "%", () -> {
            draft.hudBgOpacity -= 10;
            draft.clamp();
        }, () -> {
            draft.hudBgOpacity += 10;
            draft.clamp();
        });
        reset(ui, right - 76, y + 6, draft.hudBgOpacity != DEF.hudBgOpacity, DEF.hudBgOpacity + "%",
                () -> draft.hudBgOpacity = DEF.hudBgOpacity);
        y += 26;
        y = choice(ui, x, y, w, t("Формат", "Format"), formats(), draft.hudFormat, l.hudFormat, DEF.hudFormat, v -> draft.hudFormat = v);
        y = choice(ui, x, y, w, t("Цвет текста", "Text color"), textColors(), draft.hudTextColor, l.hudTextColor, DEF.hudTextColor,
                v -> draft.hudTextColor = v);
        y = flag(ui, x, y, w, t("Кружок", "Dot"), null, draft.hudShowDot, l.hudShowDot, DEF.hudShowDot, v -> draft.hudShowDot = v);
        y = flag(ui, x, y, w, t("Мигание кружка", "Blinking dot"), null, draft.hudBlink, l.hudBlink, DEF.hudBlink, v -> draft.hudBlink = v);
        y = flag(ui, x, y, w, t("Тень у текста", "Text shadow"), null, draft.hudShadow, l.hudShadow, DEF.hudShadow, v -> draft.hudShadow = v);
        y = flag(ui, x, y, w, t("Скрывать на паузе", "Hide when paused"), t("Вне мира и в паузе одиночной игры", "Outside a world and in singleplayer pause"), draft.hudHideWhenPaused,
                l.hudHideWhenPaused, DEF.hudHideWhenPaused, v -> draft.hudHideWhenPaused = v);
        row(ui, x, y, w, 30, t("Положение надписи", "Label position"), t("Меняется сразу, без «Применить»", "Changes immediately, no Apply needed"), false);
        int bw = ui.buttonWidth(t("Переместить", "Move"));
        ui.button(right - bw, y + 7, bw, 16, t("Переместить", "Move"), Ui.Kind.SECONDARY, true,
                () -> Minecraft.getInstance().setScreen(new HudPositionScreen(menu)));
        // Position bypasses the draft, so resetting it saves immediately.
        reset(ui, right - bw - 4, y + 8, l.hudX != DEF.hudX || l.hudY != DEF.hudY, t("левый верхний угол", "top left corner"), () -> {
            l.hudX = DEF.hudX;
            l.hudY = DEF.hudY;
            CombatDataClient.saveSettings();
        });
        y += 30;
        return fightSummary(ui, x, y, w);
    }

    private static String[] fsLayouts() {
        return new String[]{t("Две строки", "Two lines"), t("Одна строка", "One line"), t("Столбиком", "Column")};
    }

    private int fightSummary(Ui ui, int x, int y, int w) {
        Settings l = live();
        int right = x + w - 10, sw = 72;
        y = heading(ui, x, y, w, t("Итог боя", "Fight summary"));
        String label = t("Показывать итог боя", "Show fight summary");
        y = flag(ui, x, y, w, label, t("После боя на несколько секунд", "For a few seconds after a fight"),
                draft.fightSummary, l.fightSummary, DEF.fightSummary, v -> draft.fightSummary = v);
        labelInfo(ui, x, y - 30, 30, label, true, Help.fightSummary());
        int fw = dev.combatdata.client.FightHud.previewWidth(ui.font, draft) + 12;
        int fh = dev.combatdata.client.FightHud.previewHeight(ui.font, draft) + 8;
        boolean wide = fw <= w - 80;
        int rh = wide ? Math.max(26, fh + 8) : fh + 22, fx = wide ? right - fw : x + 10, fy = wide ? y + (rh - fh) / 2 : y + 18;
        if (wide) row(ui, x, y, w, rh, t("Пример", "Example"), null, false);
        else {
            ui.text(t("Пример", "Example"), x + 10, y + 5, Theme.TEXT);
            ui.g.fill(x + 8, y + rh - 1, x + w - 8, y + rh, Theme.BORDER);
        }
        ui.g.enableScissor(Math.max(fx, x + 1), fy, Math.min(fx + fw, x + w - 1), fy + fh);
        ui.g.fill(fx, fy, fx + fw, fy + fh / 2, 0xFF7EA6D6);
        ui.g.fill(fx, fy + fh / 2, fx + fw, fy + fh, 0xFF5D8C3A);
        dev.combatdata.client.FightHud.preview(ui.g, ui.font, fx + 6, fy + 4, draft);
        ui.g.disableScissor();
        y += rh;

        boolean partsChanged = draft.fsTitle != l.fsTitle || draft.fsHits != l.fsHits || draft.fsJr != l.fsJr || draft.fsCombo != l.fsCombo
                || draft.fsTrades != l.fsTrades || draft.fsReach != l.fsReach || draft.fsThreeBlock != l.fsThreeBlock || draft.fsTaken != l.fsTaken;
        String pl = t("Что показывать", "What to show");
        ui.text(pl, x + 10, y + 8, Theme.TEXT);
        if (partsChanged) Draw.circle(ui.g, x + 10 + ui.font.width(pl) + 5, y + 9, 5, Theme.AMBER);
        Flow fl = new Flow(x + 10, y + 22, w - 20);
        fsChip(ui, fl, t("Ник и время", "Name and time"), draft.fsTitle, v -> draft.fsTitle = v);
        fsChip(ui, fl, t("Попадания", "Hits"), draft.fsHits, v -> draft.fsHits = v);
        fsChip(ui, fl, "Jump reset", draft.fsJr, v -> draft.fsJr = v);
        fsChip(ui, fl, t("Комбо", "Combo"), draft.fsCombo, v -> draft.fsCombo = v);
        fsChip(ui, fl, t("Размены", "Trades"), draft.fsTrades, v -> draft.fsTrades = v);
        fsChip(ui, fl, t("Дистанция", "Reach"), draft.fsReach, v -> draft.fsReach = v);
        fsChip(ui, fl, "3 block hit", draft.fsThreeBlock, v -> draft.fsThreeBlock = v);
        fsChip(ui, fl, t("Получено ударов", "Hits taken"), draft.fsTaken, v -> draft.fsTaken = v);
        y = fl.bottom() + 2;
        ui.g.fill(x + 8, y - 1, x + w - 8, y, Theme.BORDER);

        y = choice(ui, x, y, w, t("Вид", "Layout"), fsLayouts(), draft.fsLayout, l.fsLayout, DEF.fsLayout, v -> draft.fsLayout = v);
        y = flag(ui, x, y, w, t("Цветные цифры", "Colored numbers"), t("Зелёный хорошо, жёлтый средне, красный плохо", "Green good, yellow fair, red bad"),
                draft.fsColors, l.fsColors, DEF.fsColors, v -> draft.fsColors = v);
        row(ui, x, y, w, 26, t("Непрозрачность фона", "Background opacity"), null, draft.fsBgOpacity != l.fsBgOpacity);
        ui.stepper(right - sw, y + 5, sw, draft.fsBgOpacity + "%", () -> {
            draft.fsBgOpacity -= 10;
            draft.clamp();
        }, () -> {
            draft.fsBgOpacity += 10;
            draft.clamp();
        });
        reset(ui, right - sw - 4, y + 6, draft.fsBgOpacity != DEF.fsBgOpacity, DEF.fsBgOpacity + "%", () -> draft.fsBgOpacity = DEF.fsBgOpacity);
        y += 26;
        row(ui, x, y, w, 26, t("Масштаб", "Scale"), null, draft.fsScale != l.fsScale);
        ui.stepper(right - sw, y + 5, sw, String.format(Locale.ROOT, "%.2fx", draft.fsScale), () -> {
            draft.fsScale -= 0.25;
            draft.clamp();
        }, () -> {
            draft.fsScale += 0.25;
            draft.clamp();
        });
        reset(ui, right - sw - 4, y + 6, draft.fsScale != DEF.fsScale, String.format(Locale.ROOT, "%.2fx", DEF.fsScale), () -> draft.fsScale = DEF.fsScale);
        y += 26;
        row(ui, x, y, w, 30, t("Бой окончен через", "Fight ends after"), t("Без ударов; для итога и автосохранения", "No hits; for summary and autosave"), draft.fsDelaySec != l.fsDelaySec);
        ui.stepper(right - sw, y + 7, sw, draft.fsDelaySec + t(" с", " s"), () -> {
            draft.fsDelaySec -= draft.fsDelaySec > 10 ? 5 : 1;
            draft.clamp();
        }, () -> {
            draft.fsDelaySec += draft.fsDelaySec >= 10 ? 5 : 1;
            draft.clamp();
        });
        reset(ui, right - sw - 4, y + 8, draft.fsDelaySec != DEF.fsDelaySec, DEF.fsDelaySec + t(" с", " s"), () -> draft.fsDelaySec = DEF.fsDelaySec);
        y += 30;
        row(ui, x, y, w, 26, t("Показывается", "Shown for"), null, draft.fsShowSec != l.fsShowSec);
        ui.stepper(right - sw, y + 5, sw, draft.fsShowSec + t(" с", " s"), () -> {
            draft.fsShowSec--;
            draft.clamp();
        }, () -> {
            draft.fsShowSec++;
            draft.clamp();
        });
        reset(ui, right - sw - 4, y + 6, draft.fsShowSec != DEF.fsShowSec, DEF.fsShowSec + t(" с", " s"), () -> draft.fsShowSec = DEF.fsShowSec);
        y += 26;
        y = choice(ui, x, y, w, t("Положение", "Position"), new String[]{t("Рядом с REC", "Next to REC"), t("Своё", "Custom")},
                draft.fsNearRec ? 0 : 1, l.fsNearRec ? 0 : 1, DEF.fsNearRec ? 0 : 1, v -> draft.fsNearRec = v == 0);
        if (!draft.fsNearRec) {
            row(ui, x, y, w, 30, t("Своё место", "Custom place"), t("Меняется сразу, без «Применить»", "Changes immediately, no Apply needed"), false);
            int bw = ui.buttonWidth(t("Переместить", "Move"));
            ui.button(right - bw, y + 7, bw, 16, t("Переместить", "Move"), Ui.Kind.SECONDARY, true,
                    () -> Minecraft.getInstance().setScreen(new HudPositionScreen(menu, true, draft)));
            y += 30;
        }
        return y;
    }

    private static void fsChip(Ui ui, Flow fl, String label, boolean on, java.util.function.Consumer<Boolean> set) {
        int cw = ui.font.width(label) + 12;
        int[] p = fl.place(cw, false);
        ui.mark(label, null, p[0] - 3, p[1], cw + 6, 18);
        ui.chip(p[0], p[1] + 3, cw, label, on, () -> set.accept(!on));
    }


    private static final int CARD_W = 104, CARD_H = 44;
    /** HSL of the open color is kept separately so hue isn't lost on greys. */
    private int editRole = -1;
    private double[] editHsl;
    private final List<int[]> sliders = new ArrayList<>();
    private int dragSlider = -1;
    private String themeStatus;
    private int themeStatusColor;
    private long themeStatusUntil;

    dev.combatdata.core.ThemeSpec previewSpec() {
        return (draft != null ? draft : live()).themeSpec();
    }

    private static String[] roleNames() {
        return new String[]{t("Фон окна", "Window background"), t("Панели и карточки", "Panels and cards"),
                t("Поля и графики", "Fields and charts"), t("Рамки и линии", "Borders and lines"), t("Кнопки", "Buttons"),
                t("Текст", "Text"), t("Подписи", "Captions"), t("Акцент", "Accent"), t("Хорошо: попадание, успех", "Good: hit, success"),
                t("Плохо: промах, ошибка", "Bad: miss, mistake"), t("Внимание: 3 block hit", "Warning: 3 block hit"),
                t("Fly out и пометки", "Fly out and notes")};
    }

    private int appearance(Ui ui, int x, int y, int w) {
        sliders.clear();
        String head = t("Тема", "Theme");
        y = heading(ui, x, y, w, head);
        ui.info(x + 10 + ui.font.width(head) + 6, y - 13, Help.themes());
        List<dev.combatdata.core.ThemeSpec> all = new ArrayList<>(dev.combatdata.core.Themes.BUILTIN);
        all.addAll(draft.customThemes);
        int inner = w - 20, gap = 6, cols = Math.max(1, (inner + gap) / (CARD_W + gap));
        int cw = (inner - gap * (cols - 1)) / cols, top = y + 8;
        for (int i = 0; i < all.size(); i++) {
            themeCard(ui, x + 10 + (i % cols) * (cw + gap), top + (i / cols) * (CARD_H + gap), cw, all.get(i));
        }
        y = top + ((all.size() + cols - 1) / cols) * (CARD_H + gap) + 2;

        dev.combatdata.core.ThemeSpec sel = draft.themeSpec();
        int index = draft.customThemes.indexOf(sel);
        boolean full = draft.customThemes.size() >= dev.combatdata.core.Themes.MAX_CUSTOM;
        Flow fl = new Flow(x + 10, y, w - 20);
        flowButton(ui, fl, t("Создать копию", "Make a copy"), Ui.Kind.SECONDARY, !full, () -> {
            dev.combatdata.core.ThemeSpec n = dev.combatdata.core.Themes.copyOf(sel, draft.customThemes);
            draft.customThemes.add(n);
            draft.theme = n.id();
            editRole = -1;
        });
        flowButton(ui, fl, t("Копировать код", "Copy code"), Ui.Kind.GHOST, true, () -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(dev.combatdata.core.Themes.toCode(sel));
            status(t("Код темы скопирован в буфер обмена.", "Theme code copied to the clipboard."), Theme.GREEN);
        });
        flowButton(ui, fl, t("Вставить код", "Paste code"), Ui.Kind.GHOST, !full, this::pasteTheme);
        if (index >= 0) {
            flowButton(ui, fl, t("Переименовать", "Rename"), Ui.Kind.GHOST, true, () -> askRename(index));
            flowButton(ui, fl, t("Удалить", "Delete"), Ui.Kind.DANGER, true, () -> askDelete(index));
        }
        y = fl.bottom() + 4;
        if (full) {
            ui.text(Draw.ellipsize(ui.font, t("Своих тем не больше ", "No more than ") + dev.combatdata.core.Themes.MAX_CUSTOM
                    + t(": удалите ненужную.", " custom themes: delete one."), w - 20), x + 10, y, Theme.DIM);
            y += 12;
        }
        if (themeStatus != null && System.currentTimeMillis() < themeStatusUntil) {
            ui.text(Draw.ellipsize(ui.font, themeStatus, w - 20), x + 10, y, themeStatusColor);
            y += 12;
        }
        if (index < 0) {
            ui.text(Draw.ellipsize(ui.font, t("Встроенная тема не меняется: «Создать копию» сделает свою,", "A built-in theme does not change: \"Make a copy\" creates your own"), w - 20),
                    x + 10, y + 4, Theme.DIM);
            ui.text(Draw.ellipsize(ui.font, t("в ней можно настроить каждый цвет.", "where every color can be adjusted."), w - 20), x + 10, y + 16, Theme.DIM);
            return y + 32;
        }
        y = heading(ui, x, y, w, Draw.ellipsize(ui.font, t("Цвета: ", "Colors: ") + sel.name(), w - 20));
        y = colorRows(ui, x, y + 4, w, sel, index);
        ui.text(Draw.ellipsize(ui.font, t("Клик по цвету: ползунки, по коду: ввести hex. Сохраняется по «Применить».", "Click a color for sliders, its code to type hex. Saved on Apply."), w - 20),
                x + 10, y + 6, Theme.DIM);
        return y + 22;
    }

    private static void flowButton(Ui ui, Flow fl, String label, Ui.Kind kind, boolean enabled, Runnable action) {
        int bw = ui.buttonWidth(label);
        int[] p = fl.place(bw, false);
        ui.button(p[0], p[1], bw, 16, label, kind, enabled, action);
    }

    private void status(String text, int color) {
        themeStatus = text;
        themeStatusColor = color;
        themeStatusUntil = System.currentTimeMillis() + 4000;
    }

    private void themeCard(Ui ui, int x, int y, int w, dev.combatdata.core.ThemeSpec s) {
        ui.mark(dev.combatdata.core.Themes.displayName(s), null, x - 3, y - 3, w + 6, CARD_H + 6);
        boolean sel = s.id().equals(draft.theme), hov = ui.hover(x, y, w, CARD_H);
        Draw.roundBordered(ui.g, x, y, w, CARD_H, Theme.R_PANEL, sel ? Theme.ACCENT : hov ? Theme.CTRL_HOVER : Theme.BORDER,
                s.color(dev.combatdata.core.ThemeSpec.WINDOW));
        if (sel) Draw.roundBordered(ui.g, x + 1, y + 1, w - 2, CARD_H - 2, Theme.R_PANEL - 1, Theme.ACCENT, s.color(dev.combatdata.core.ThemeSpec.WINDOW));
        ui.text(Draw.ellipsize(ui.font, dev.combatdata.core.Themes.displayName(s), w - 10), x + 6, y + 5, s.color(dev.combatdata.core.ThemeSpec.TEXT));
        int px = x + 5, py = y + 16, pw = w - 10, ph = CARD_H - 21;
        Draw.roundBordered(ui.g, px, py, pw, ph, 2, s.color(dev.combatdata.core.ThemeSpec.BORDER), s.color(dev.combatdata.core.ThemeSpec.PANEL));
        Draw.round(ui.g, px + 4, py + 4, 16, 7, 2, s.color(dev.combatdata.core.ThemeSpec.ACCENT));
        int[] dots = {dev.combatdata.core.ThemeSpec.GREEN, dev.combatdata.core.ThemeSpec.RED, dev.combatdata.core.ThemeSpec.AMBER, dev.combatdata.core.ThemeSpec.BLUE};
        for (int k = 0; k < dots.length; k++) Draw.circle(ui.g, px + 26 + k * 8, py + 5, 5, s.color(dots[k]));
        ui.g.fill(px + 4, py + ph - 7, px + Math.max(8, pw / 2), py + ph - 6, s.color(dev.combatdata.core.ThemeSpec.MUTED));
        ui.click(x, y, w, CARD_H, () -> {
            draft.theme = s.id();
            editRole = -1;
        });
    }

    private int colorRows(Ui ui, int x, int y, int w, dev.combatdata.core.ThemeSpec s, int index) {
        String[] names = roleNames();
        int right = x + w - 10, h = 20;
        for (int r = 0; r < dev.combatdata.core.ThemeSpec.COUNT; r++) {
            String hx = dev.combatdata.core.Themes.hex(s.color(r));
            int hw = ui.font.width(hx) + 10, role = r;
            boolean open = r == editRole, hov = ui.hover(x + 8, y, w - 16 - hw - 4, h), hh = ui.hover(right - hw, y + 2, hw, h - 4);
            if (open || hov) Draw.round(ui.g, x + 8, y + 1, w - 16, h - 2, Theme.R_CTRL, open ? Theme.RAISED : Theme.ROW_HOVER);
            Draw.roundBordered(ui.g, x + 12, y + 4, 12, 12, 2, Theme.CTRL_HOVER, s.color(r));
            ui.text(Draw.ellipsize(ui.font, names[r], w - hw - 50), x + 30, Ui.textY(y, h), Theme.TEXT);
            Draw.roundBordered(ui.g, right - hw, y + 2, hw, h - 4, Theme.R_CTRL, hh ? Theme.CTRL_HOVER : Theme.BORDER, Theme.INSET);
            ui.textCenter(hx, right - hw / 2, Ui.textY(y + 2, h - 4), Theme.TEXT_STRONG);
            ui.click(x + 8, y, w - 16 - hw - 4, h, () -> {
                editRole = editRole == role ? -1 : role;
                editHsl = null;
            });
            ui.click(right - hw, y + 2, hw, h - 4, () -> askHex(index, role));
            y += h;
            if (open) y = hslSliders(ui, x, y, w, index, r);
        }
        return y;
    }

    private int hslSliders(Ui ui, int x, int y, int w, int index, int role) {
        int c = draft.customThemes.get(index).color(role) & 0xFFFFFF;
        if (editHsl == null || dev.combatdata.core.Themes.fromHsl(editHsl[0], editHsl[1], editHsl[2]) != c)
            editHsl = dev.combatdata.core.Themes.toHsl(c);
        String[] labels = {t("Оттенок", "Hue"), t("Насыщенность", "Saturation"), t("Светлота", "Lightness")};
        int lw = 0;
        for (String s : labels) lw = Math.max(lw, ui.font.width(s));
        int bx = x + 30 + lw + 8, bw = Math.max(40, x + w - 10 - 34 - bx), bh = 9;
        for (int ch = 0; ch < 3; ch++) {
            ui.text(labels[ch], x + 30, y + 3, Theme.MUTED);
            int steps = Math.max(1, bw / 2);
            for (int i = 0; i < steps; i++) {
                double f = (i + 0.5) / steps;
                double[] v = editHsl.clone();
                v[ch] = ch == 0 ? f * 360 : f;
                int sx = bx + i * bw / steps, ex = bx + (i + 1) * bw / steps;
                ui.g.fill(sx, y + 3, ex, y + 3 + bh, 0xFF000000 | dev.combatdata.core.Themes.fromHsl(v[0], v[1], v[2]));
            }
            ui.g.renderOutline(bx - 1, y + 2, bw + 2, bh + 2, Theme.BORDER);
            double val = ch == 0 ? editHsl[0] / 360 : editHsl[ch];
            int kx = bx + (int) Math.round(val * (bw - 1));
            ui.g.fill(kx - 1, y + 1, kx + 2, y + bh + 5, Theme.TEXT_STRONG);
            ui.g.fill(kx, y + 2, kx + 1, y + bh + 4, Theme.WINDOW);
            ui.textRight(ch == 0 ? Math.round(editHsl[0]) + "°" : Math.round(editHsl[ch] * 100) + "%", x + w - 10, y + 3, Theme.TEXT);
            sliders.add(new int[]{bx, y, bw, bh + 6, ch, index, role});
            y += 16;
        }
        return y + 4;
    }

    @Override
    public boolean pressed(double mx, double my) {
        if (section != 3 || mx < panelX || mx >= panelX + panelW || my < panelY || my >= panelY + panelH) return false;
        for (int i = 0; i < sliders.size(); i++) {
            int[] s = sliders.get(i);
            if (mx >= s[0] - 3 && mx < s[0] + s[2] + 3 && my >= s[1] && my < s[1] + s[3]) {
                dragSlider = i;
                slide(mx);
                return true;
            }
        }
        return false;
    }

    @Override
    public void dragged(double mx, double my, double dx) {
        slide(mx);
    }

    @Override
    public void released(double mx, double my) {
        dragSlider = -1;
    }

    private void slide(double mx) {
        if (dragSlider < 0 || dragSlider >= sliders.size() || editHsl == null) return;
        int[] s = sliders.get(dragSlider);
        int ch = s[4], index = s[5], role = s[6];
        if (index >= draft.customThemes.size()) return;
        double f = Math.max(0, Math.min(1, (mx - s[0]) / (double) Math.max(1, s[2] - 1)));
        editHsl[ch] = ch == 0 ? Math.min(359.9, f * 360) : f;
        dev.combatdata.core.ThemeSpec t = draft.customThemes.get(index);
        draft.customThemes.set(index, t.withColor(role, dev.combatdata.core.Themes.fromHsl(editHsl[0], editHsl[1], editHsl[2])));
    }

    private void askHex(int index, int role) {
        Modal[] self = new Modal[1];
        self[0] = new Modal(roleNames()[role], List.of(t("Цвет в виде #RRGGBB, например #D97757.", "Color as #RRGGBB, for example #D97757.")),
                dev.combatdata.core.Themes.hex(draft.customThemes.get(index).color(role)), List.of(
                new Modal.Btn(t("Отмена", "Cancel"), Ui.Kind.SECONDARY, v -> menu.closeModal()),
                new Modal.Btn("OK", Ui.Kind.PRIMARY, v -> {
                    int c = dev.combatdata.core.Themes.parseHex(v);
                    if (c < 0) {
                        self[0].error = t("Не похоже на цвет: нужно 6 знаков 0–9 и A–F.", "Not a color: needs 6 characters 0–9 and A–F.");
                        return;
                    }
                    if (index < draft.customThemes.size())
                        draft.customThemes.set(index, draft.customThemes.get(index).withColor(role, c));
                    editHsl = null;
                    menu.closeModal();
                })));
        menu.openModal(self[0]);
    }

    private void askRename(int index) {
        dev.combatdata.core.ThemeSpec s = draft.customThemes.get(index);
        Modal[] self = new Modal[1];
        self[0] = new Modal(t("Название темы", "Theme name"), List.of(t("До 24 символов.", "Up to 24 characters.")), s.name(), List.of(
                new Modal.Btn(t("Отмена", "Cancel"), Ui.Kind.SECONDARY, v -> menu.closeModal()),
                new Modal.Btn("OK", Ui.Kind.PRIMARY, v -> {
                    String n = dev.combatdata.core.Themes.cleanName(v);
                    if (n.isEmpty()) {
                        self[0].error = t("Название не может быть пустым.", "The name cannot be empty.");
                        return;
                    }
                    if (index < draft.customThemes.size())
                        draft.customThemes.set(index, s.withName(dev.combatdata.core.Themes.uniqueName(n, draft.customThemes, s.id())));
                    menu.closeModal();
                })));
        menu.openModal(self[0]);
    }

    private void askDelete(int index) {
        dev.combatdata.core.ThemeSpec s = draft.customThemes.get(index);
        menu.openModal(new Modal(t("Удалить тему?", "Delete the theme?"), List.of(
                "«" + s.name() + "»" + t(" удалится после «Применить».", " is removed after Apply.")), null, List.of(
                new Modal.Btn(t("Отмена", "Cancel"), Ui.Kind.SECONDARY, v -> menu.closeModal()),
                new Modal.Btn(t("Удалить", "Delete"), Ui.Kind.DANGER, v -> {
                    draft.customThemes.remove(s);
                    if (draft.theme.equals(s.id())) draft.theme = dev.combatdata.core.Themes.DEFAULT_ID;
                    editRole = -1;
                    menu.closeModal();
                }))));
    }

    private void pasteTheme() {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        dev.combatdata.core.ThemeSpec p = dev.combatdata.core.Themes.fromCode(clip);
        if (p == null) {
            status(t("В буфере обмена нет кода темы (он начинается с cdtheme1;).", "No theme code on the clipboard (it starts with cdtheme1;)."), Theme.RED);
            return;
        }
        List<dev.combatdata.core.ThemeSpec> list = draft.customThemes;
        dev.combatdata.core.ThemeSpec n = new dev.combatdata.core.ThemeSpec(dev.combatdata.core.Themes.newId(list),
                dev.combatdata.core.Themes.uniqueName(p.name(), list, null), p.colors());
        list.add(n);
        draft.theme = n.id();
        editRole = -1;
        status(t("Тема «", "Theme \"") + n.name() + t("» добавлена.", "\" added."), Theme.GREEN);
    }

    private int jumpReset(Ui ui, int x, int y, int w) {
        Settings l = live();
        int right = x + w - 10, sw = 72;
        row(ui, x, y, w, 30, t("Окно успеха: от", "Success window: from"), t("Мс после удара", "Ms after the hit"), draft.jrLowerMs != l.jrLowerMs);
        ui.stepper(right - sw, y + 7, sw, draft.jrLowerMs + t(" мс", " ms"), () -> jr(0, -10), () -> jr(0, 10));
        reset(ui, right - sw - 4, y + 8, draft.jrLowerMs != DEF.jrLowerMs, DEF.jrLowerMs + t(" мс", " ms"), () -> {
            draft.jrLowerMs = DEF.jrLowerMs;
            draft.clamp();
        });
        y += 30;
        row(ui, x, y, w, 30, t("Окно успеха: до", "Success window: to"), t("Мс после удара", "Ms after the hit"), draft.jrUpperMs != l.jrUpperMs);
        ui.stepper(right - sw, y + 7, sw, draft.jrUpperMs + t(" мс", " ms"), () -> jr(1, -10), () -> jr(1, 10));
        reset(ui, right - sw - 4, y + 8, draft.jrUpperMs != DEF.jrUpperMs, DEF.jrUpperMs + t(" мс", " ms"), () -> {
            draft.jrUpperMs = DEF.jrUpperMs;
            draft.clamp();
        });
        y += 30;
        row(ui, x, y, w, 30, t("Прыжки до удара", "Jumps before the hit"), t("Насколько ранний прыжок ещё считается попыткой", "How early a jump still counts as an attempt"), draft.jrPreMs != l.jrPreMs);
        ui.stepper(right - sw, y + 7, sw, draft.jrPreMs + t(" мс", " ms"), () -> jr(2, -25), () -> jr(2, 25));
        reset(ui, right - sw - 4, y + 8, draft.jrPreMs != DEF.jrPreMs, DEF.jrPreMs + t(" мс", " ms"), () -> draft.jrPreMs = DEF.jrPreMs);
        y += 30;
        row(ui, x, y, w, 30, t("Прыжки после удара", "Jumps after the hit"), t("Насколько поздний прыжок ещё считается попыткой", "How late a jump still counts as an attempt"), draft.jrPostMs != l.jrPostMs);
        ui.stepper(right - sw, y + 7, sw, draft.jrPostMs + t(" мс", " ms"), () -> jr(3, -25), () -> jr(3, 25));
        reset(ui, right - sw - 4, y + 8, draft.jrPostMs != DEF.jrPostMs, DEF.jrPostMs + t(" мс", " ms"), () -> draft.jrPostMs = DEF.jrPostMs);
        y += 30;
        row(ui, x, y, w, 30, t("Не учитывать зажатый прыжок", "Ignore held jump"), t("Прыжок, зажатый задолго до удара", "Jump held long before the hit"), draft.jrExcludeHeld != l.jrExcludeHeld);
        ui.toggle(right - 20, y + 9, draft.jrExcludeHeld, v -> draft.jrExcludeHeld = v);
        reset(ui, right - 26, y + 8, draft.jrExcludeHeld != DEF.jrExcludeHeld, onOff(DEF.jrExcludeHeld),
                () -> draft.jrExcludeHeld = DEF.jrExcludeHeld);
        y += 30;
        y = flag(ui, x, y, w, t("Считать fly out отдельно", "Count fly out separately"),
                t("Прыжок с S без W не попытка jump reset", "A jump with S and no W is not a jump reset attempt"),
                draft.jrSeparateFlyOut, l.jrSeparateFlyOut, DEF.jrSeparateFlyOut, v -> draft.jrSeparateFlyOut = v);
        y = calibrationRow(ui, x, y, w);
        ui.text(t("Параметры применяются и к старым записям.", "Settings also apply to old recordings."), x + 10, y + 8, Theme.DIM);
        return y + 24;
    }

    private volatile Calibration.Result calibAll;
    private volatile String calibStatus;
    private volatile boolean calibRunning;

    private int calibrationRow(Ui ui, int x, int y, int w) {
        int h = 42, right = x + w - 10;
        row(ui, x, y, w, h, t("Подобрать окно по записям", "Fit window from recordings"), t("По отлёту после удара во всех сохранённых записях", "By knockback distance in all saved recordings"), false);
        labelInfo(ui, x, y, h, t("Подобрать окно по записям", "Fit window from recordings"), true, Help.calibrate());
        String bl = calibRunning ? t("Считаю…", "Working…") : t("Подобрать", "Fit");
        int bw = ui.buttonWidth(bl);
        ui.button(right - bw, y + 6, bw, 16, bl, Ui.Kind.SECONDARY, !calibRunning, this::runCalibration);
        Calibration.Result c = calibAll;
        String status = calibStatus;
        if (c != null && c.hasRecommendation()) {
            String text = c.recLower() + "…" + c.recUpper() + t(" мс: отлёт ", " ms: knockback ") + Fmt.num(c.recKb(), 2) + t(" бл.", " bl.")
                    + (Double.isFinite(c.baselineKb()) ? t(" против ", " vs ") + Fmt.num(c.baselineKb(), 2) + t(" без прыжка", " without jump") : "")
                    + " · " + c.samples() + t(" попыток, ", " attempts, ") + status;
            String tb = t("В черновик", "To draft");
            int tw = ui.buttonWidth(tb);
            ui.text(Draw.ellipsize(ui.font, text, w - tw - 30), x + 10, y + 28, Theme.AMBER);
            ui.button(right - tw, y + 24, tw, 14, tb, Ui.Kind.GHOST, true, () -> {
                draft.jrLowerMs = (int) c.recLower();
                draft.jrUpperMs = (int) c.recUpper();
                draft.clamp();
            });
        } else if (c != null) {
            ui.text(Draw.ellipsize(ui.font, t("Мало данных: ", "Not enough data: ") + c.samples() + t(" попыток с прыжком из ", " jump attempts of ") + Calibration.MIN_SAMPLES
                    + t(" нужных (", " needed (") + status + ")", w - 20), x + 10, y + 28, Theme.DIM);
        } else if (status != null) {
            ui.text(Draw.ellipsize(ui.font, status, w - 20), x + 10, y + 28, Theme.DIM);
        }
        return y + h;
    }

    private void runCalibration() {
        if (calibRunning) return;
        calibRunning = true;
        calibAll = null;
        calibStatus = t("Считаю…", "Working…");
        JumpResetEvaluator.Params params = draft.jumpParams();
        dev.combatdata.core.StatFilter sf = draft.statFilter();
        Thread t = new Thread(() -> {
            try {
                List<JumpResetEvaluator.Attempt> all = new ArrayList<>();
                int files = 0;
                for (Recordings.Entry e : Recordings.list()) {
                    if (e.header() == null) continue;
                    try {
                        all.addAll(JumpResetEvaluator.evaluate(sf.apply(Cdrec.read(e.file()).events()).kept(), params));
                        files++;
                    } catch (Exception ex) {
                        CombatDataClient.LOG.warn("[CD] калибровка: не читается {}", e.file().getFileName());
                    }
                }
                calibStatus = files + t(" записей", " recordings");
                calibAll = Calibration.analyze(all, params);
            } catch (Exception ex) {
                calibStatus = t("Ошибка: ", "Error: ") + ex.getMessage();
            } finally {
                calibRunning = false;
            }
        }, "combatdata-calibration");
        t.setDaemon(true);
        t.start();
    }

    void adoptJumpWindow(int lower, int upper) {
        Settings l = live();
        l.jrLowerMs = lower;
        l.jrUpperMs = upper;
        l.clamp();
        CombatDataClient.saveSettings();
        if (draft != null) {
            draft.jrLowerMs = l.jrLowerMs;
            draft.jrUpperMs = l.jrUpperMs;
        }
    }

    private void jr(int field, int delta) {
        switch (field) {
            case 0 -> draft.jrLowerMs += delta;
            case 1 -> draft.jrUpperMs += delta;
            case 2 -> draft.jrPreMs += delta;
            default -> draft.jrPostMs += delta;
        }
        draft.clamp();
    }

    private int statistics(Ui ui, int x, int y, int w) {
        int right = x + w - 10, sw = 72;
        row(ui, x, y, w, 30, t("Интерполяция у противника", "Opponent interpolation"), t("Для 3 block hit: насколько позже он видит ваше положение", "For 3 block hit: how late they see your position"),
                draft.interpMs != live().interpMs);
        labelInfo(ui, x, y, 30, t("Интерполяция у противника", "Opponent interpolation"), true, Help.interp());
        ui.stepper(right - sw, y + 7, sw, draft.interpMs + t(" мс", " ms"), () -> {
            draft.interpMs -= 10;
            draft.clamp();
        }, () -> {
            draft.interpMs += 10;
            draft.clamp();
        });
        reset(ui, right - sw - 4, y + 8, draft.interpMs != DEF.interpMs, DEF.interpMs + t(" мс", " ms"), () -> draft.interpMs = DEF.interpMs);
        y += 30;
        ui.text(t("Задержка = (ваш пинг + пинг противника) / 2 + интерполяция.", "Delay = (your ping + opponent ping) / 2 + interpolation."), x + 10, y + 8, Theme.DIM);
        ui.text(t("Применяется и к старым записям.", "Also applies to old recordings."), x + 10, y + 20, Theme.DIM);
        return y + 36;
    }

    private static final int[] LIMITS_MB = {0, 100, 250, 500, 1000, 2000, 5000, 10000};
    private static final int[] AGES_DAYS = {0, 7, 14, 30, 60, 90, 180, 365};
    private List<Recordings.Entry> storageList;
    private long storageReloadAt;
    private boolean storageLoading;

    private int storage(Ui ui, int x, int y, int w) {
        Settings l = live();
        long now = System.currentTimeMillis();
        if (!storageLoading && (storageList == null || (storageReloadAt > 0 && now >= storageReloadAt))) {
            storageLoading = true;
            storageReloadAt = 0;
            Recordings.listAsync(list -> {
                storageList = list;
                storageLoading = false;
            });
        }
        List<Recordings.Entry> files = storageList == null ? List.of() : storageList;
        int right = x + w - 10, sw = 90;
        row(ui, x, y, w, 30, t("Папка записей", "Recordings folder"), Recordings.dir().toString(), false);
        int bw = ui.buttonWidth(t("Открыть", "Open"));
        ui.button(right - bw, y + 7, bw, 16, t("Открыть", "Open"), Ui.Kind.SECONDARY, true, () -> {
            try {
                java.nio.file.Files.createDirectories(Recordings.dir());
                net.minecraft.util.Util.getPlatform().openUri(Recordings.dir().toUri());
            } catch (Exception ex) {
                CombatDataClient.LOG.warn("[CD] не удалось открыть папку: {}", ex.toString());
            }
        });
        y += 30;
        int favs = 0;
        List<Retention.FileInfo> infos = new ArrayList<>();
        for (Recordings.Entry e : files) {
            boolean fav = Recordings.favorite(e);
            if (fav) favs++;
            infos.add(new Retention.FileInfo(e.stem(), e.size(), e.time(), fav));
        }
        row(ui, x, y, w, 26, t("Занято", "Used"), null, false);
        ui.textRight(files.size() + t(" записей · ", " recordings · ") + Fmt.size(Retention.totalBytes(infos)) + t(" · избранных ", " · favorites ") + favs,
                right, Ui.textY(y, 26), Theme.TEXT_STRONG);
        y += 26;
        row(ui, x, y, w, 30, t("Лимит места", "Space limit"), t("Старые записи удаляются, пока папка больше лимита", "Oldest recordings are deleted while the folder is over the limit"), draft.storageLimitMb != l.storageLimitMb);
        ui.stepper(right - sw, y + 7, sw, draft.storageLimitMb == 0 ? t("без лимита", "no limit") : draft.storageLimitMb + t(" МБ", " MB"),
                () -> draft.storageLimitMb = step(LIMITS_MB, draft.storageLimitMb, -1),
                () -> draft.storageLimitMb = step(LIMITS_MB, draft.storageLimitMb, 1));
        reset(ui, right - sw - 4, y + 8, draft.storageLimitMb != DEF.storageLimitMb, t("без лимита", "no limit"),
                () -> draft.storageLimitMb = DEF.storageLimitMb);
        y += 30;
        row(ui, x, y, w, 30, t("Удалять старше", "Delete older than"), t("По дате записи", "By recording date"), draft.autoDeleteDays != l.autoDeleteDays);
        ui.stepper(right - sw, y + 7, sw, draft.autoDeleteDays == 0 ? t("никогда", "never") : draft.autoDeleteDays + t(" дн.", " days"),
                () -> draft.autoDeleteDays = step(AGES_DAYS, draft.autoDeleteDays, -1),
                () -> draft.autoDeleteDays = step(AGES_DAYS, draft.autoDeleteDays, 1));
        reset(ui, right - sw - 4, y + 8, draft.autoDeleteDays != DEF.autoDeleteDays, t("никогда", "never"),
                () -> draft.autoDeleteDays = DEF.autoDeleteDays);
        y += 30;
        List<String> gone = Retention.toDelete(infos, draft.storageLimitMb * 1024L * 1024L, draft.autoDeleteDays, now);
        if (!gone.isEmpty()) {
            long bytes = 0;
            for (Retention.FileInfo f : infos) if (gone.contains(f.id())) bytes += f.sizeBytes();
            ui.text(Draw.ellipsize(ui.font, t("При этих лимитах будет удалено записей: ", "These limits will delete recordings: ") + gone.size() + " (" + Fmt.size(bytes) + ")",
                    w - 20), x + 10, y + 8, Theme.AMBER);
            y += 12;
        }
        ui.text(t("Избранные (★) и самая новая запись не удаляются.", "Favorites (★) and the newest recording are never deleted."), x + 10, y + 8, Theme.DIM);
        ui.text(t("Проверка после каждого сохранения и по «Применить».", "Checked after every save and on Apply."), x + 10, y + 20, Theme.DIM);
        return y + 36;
    }

    private static int step(int[] values, int cur, int dir) {
        if (dir > 0) {
            for (int v : values) if (v > cur) return v;
            return values[values.length - 1];
        }
        for (int i = values.length - 1; i >= 0; i--) if (values[i] < cur) return values[i];
        return values[0];
    }

    private int language(Ui ui, int x, int y, int w) {
        Settings l = live();
        String mc = Minecraft.getInstance().options != null ? Minecraft.getInstance().options.languageCode : null;
        String[] items = {"Русский", "English"};
        int shown = dev.combatdata.core.Lang.resolveEnglish(draft.language, mc) ? 1 : 0;
        int right = x + w - 10, sw = ui.segmentedWidth(items), h = 30;
        row(ui, x, y, w, h, t("Язык", "Language"), draft.language == dev.combatdata.core.Lang.AUTO
                ? t("Как в Minecraft (русский → русский, другие → английский)", "As in Minecraft (Russian → Russian, others → English)")
                : t("Выбран вручную", "Chosen manually"), draft.language != l.language);
        ui.segmented(right - sw, y + 5, items, shown, v -> draft.language = v == 0 ? dev.combatdata.core.Lang.RU : dev.combatdata.core.Lang.EN);
        reset(ui, right - sw - 4, y + 8, draft.language != DEF.language, t("как в Minecraft", "as in Minecraft"),
                () -> draft.language = DEF.language);
        return y + h;
    }

    private int keys(Ui ui, int x, int y, int w) {
        y = language(ui, x, y, w);
        y = key(ui, x, y, w, t("Открыть меню", "Open menu"), CombatDataClient.MENU);
        y = key(ui, x, y, w, t("Сохранить буфер", "Save buffer"), CombatDataClient.SAVE);
        y = key(ui, x, y, w, t("Поставить метку", "Add marker"), CombatDataClient.MARKER);
        y = key(ui, x, y, w, t("Сбросить буфер (дважды)", "Reset buffer (twice)"), CombatDataClient.RESET);
        y = key(ui, x, y, w, t("Показать или скрыть индикаторы", "Show or hide indicators"), CombatDataClient.INDICATORS);
        ui.text(Draw.ellipsize(ui.font, t("Сброс также: Shift + клавиша сохранения дважды, /combatdata reset confirm", "Reset also: Shift + save key twice, /combatdata reset confirm"), w - 20), x + 10, y + 8, Theme.DIM);
        y += 12;
        ui.text(t("Изменить: Настройки → Управление → Combat Data", "Change in: Options → Controls → Combat Data"), x + 10, y + 8, Theme.DIM);
        return y + 24;
    }

    private int key(Ui ui, int x, int y, int w, String label, KeyMapping km) {
        row(ui, x, y, w, 26, label, null, false);
        keyChip(ui, x + w - 10, y + 5, km);
        return y + 26;
    }

    /** The bound key as a raised chip, right-aligned to right. Shared with the Indicators tab. */
    static void keyChip(Ui ui, int right, int y, KeyMapping km) {
        String k = km.isUnbound() ? t("не назначена", "not bound") : km.getTranslatedKeyMessage().getString();
        int kw = ui.font.width(k) + 14, kx = right - kw;
        Draw.roundBordered(ui.g, kx, y, kw, 16, Theme.R_CTRL, Theme.BORDER, Theme.RAISED);
        ui.textCenter(k, kx + kw / 2, Ui.textY(y, 16), km.isUnbound() ? Theme.MUTED : Theme.TEXT_STRONG);
    }
}
