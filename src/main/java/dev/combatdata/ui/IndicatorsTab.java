package dev.combatdata.ui;

import dev.combatdata.CombatDataClient;
import dev.combatdata.client.HudPositionScreen;
import dev.combatdata.client.IndicatorHud;
import dev.combatdata.client.IndicatorLayoutScreen;
import dev.combatdata.client.Indicators;
import dev.combatdata.core.IndicatorConfig;
import dev.combatdata.core.IndicatorConfig.Kind;
import dev.combatdata.core.Notes;
import dev.combatdata.core.Settings;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.function.Consumer;

import static dev.combatdata.core.Lang.t;

/** Indicator settings. Unlike the Settings tab, every change applies and saves at once, so the HUD is the preview. */
final class IndicatorsTab implements Tab {
    private static final int INDENT = 12;
    private boolean expandAll;
    private final MenuScreen menu;
    private double scroll;
    private int maxScroll;
    private int panelX, panelY, panelW, panelH;

    IndicatorsTab(MenuScreen menu) {
        this.menu = menu;
    }

    private static IndicatorConfig cfg() {
        return CombatDataClient.settings().indicators;
    }

    private static void changed() {
        CombatDataClient.saveSettings();
    }

    @Override
    public void render(Ui ui, int x, int y, int w, int h) {
        IndicatorConfig c = cfg();
        ui.toggle(x, y + 3, c.enabled, v -> {
            c.enabled = v;
            changed();
        });
        String head = c.enabled ? t("Индикаторы включены", "Indicators on") : t("Индикаторы скрыты", "Indicators hidden");
        ui.text(head, x + 26, Ui.textY(y, 16), c.enabled ? Theme.TEXT_STRONG : Theme.MUTED);
        String place = t("Расставить на экране", "Place on screen");
        int bw = ui.buttonWidth(place);
        ui.button(x + w - bw, y, bw, 16, place, Ui.Kind.PRIMARY, c.anyOn(),
                () -> Minecraft.getInstance().setScreen(new IndicatorLayoutScreen(menu)));

        int footerY = y + h - 10;
        panelX = x;
        panelY = y + 24;
        panelW = w;
        panelH = footerY - 6 - panelY;
        Draw.roundBordered(ui.g, panelX, panelY, panelW, panelH, Theme.R_PANEL, Theme.BORDER, Theme.PANEL);
        int ix = panelX + 1, iy = panelY + 1, iw = panelW - 2, ih = panelH - 2;
        ui.g.enableScissor(ix, iy, ix + iw, iy + ih);
        ui.clip(ix, iy, iw, ih);
        int top = iy - (int) scroll;
        if (ui.find != null) ui.find.at(MenuScreen.TabId.INDICATORS, 0);
        int content = content(ui, ix, top, iw) - top;
        maxScroll = Math.max(0, content - ih);
        if (ui.find != null) scroll += ui.find.scrollDelta(iy, ih);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        ui.unclip();
        ui.g.disableScissor();
        ui.scrollbar(panelX + panelW - 5, iy + 2, ih - 4, scroll, ih, content);
        ui.text(Draw.ellipsize(ui.font, t("Только ваши данные о том, что уже случилось. Меняется сразу.",
                "Only your own data about what already happened. Applies at once."), w), x, footerY, Theme.DIM);
    }

    private int content(Ui ui, int x, int y, int w) {
        for (Kind k : Kind.values()) y = indicator(ui, x, y, w, k);
        y = common(ui, x, y, w);
        y = SettingsTab.heading(ui, x, y, w, t("Надпись записи и итог боя", "Recording label and fight summary"));
        ui.text(Draw.ellipsize(ui.font, t("Не зависят от переключателя индикаторов сверху.", "Not affected by the indicators switch on top."), w - 20),
                x + 10, y + 6, Theme.DIM);
        y += 18;
        y = recLabel(ui, x, y, w);
        return fightSummary(ui, x, y, w) + 6;
    }

    private static Settings live() {
        return CombatDataClient.settings();
    }

    /** Header row of a block: name, hint, an optional sample on sky and grass, and the on/off switch. */
    private int blockHead(Ui ui, int x, int y, int w, String name, String hint, String info, boolean on, Consumer<Boolean> set,
                          int sampleW, java.util.function.BiConsumer<Integer, Integer> sample) {
        int rh = 32, right = x + w - 10;
        if (ui.find != null) ui.find.group(name);
        row(ui, x, y, w, rh, 0, name, hint, Theme.TEXT_STRONG);
        if (info != null) ui.info(x + 10 + ui.font.width(name) + 5, y + rh / 2 - 10, info);
        ui.toggle(right - 20, y + (rh - 11) / 2, on, v -> {
            set.accept(v);
            changed();
        });
        if (sample != null) {
            int bw = sampleW + 12, bh = 22, bx = right - 26 - bw, by = y + (rh - bh) / 2;
            int minX = x + 10 + Math.max(ui.font.width(name) + (info != null ? Ui.INFO + 5 : 0), Math.min(ui.font.width(hint), w / 2)) + 8;
            if (bx >= minX) {
                ui.g.enableScissor(bx, by, bx + bw, by + bh);
                ui.g.fill(bx, by, bx + bw, by + bh / 2, 0xFF7EA6D6);
                ui.g.fill(bx, by + bh / 2, bx + bw, by + bh, 0xFF5D8C3A);
                sample.accept(bx + 6, by + (bh - 13) / 2);
                ui.g.disableScissor();
            }
        }
        return y + rh;
    }

    private int recLabel(Ui ui, int x, int y, int w) {
        Settings l = live();
        String name = t("Надпись записи (REC)", "Recording label (REC)");
        int sw = dev.combatdata.client.RecHud.previewWidth(ui.font, l);
        y = blockHead(ui, x, y, w, name, t("Время буфера и сохранения, по умолчанию слева сверху", "Buffer time and saves, top left by default"),
                null, l.hudVisible, v -> l.hudVisible = v, sw, (px, py) -> dev.combatdata.client.RecHud.preview(ui.g, ui.font, px, py, l));
        if (!l.hudVisible && !expandAll) return y;
        if (ui.find != null) ui.find.parent(name);
        int y0 = y;
        y = recOptions(ui, x, y, w, INDENT * 2);
        ui.g.fill(x + 12, y0 + 3, x + 14, y - 4, Theme.alpha(Theme.ACCENT, 0x90));
        return y;
    }

    /**
     * REC label options, also shown in Settings → HUD (indent 0 there). Options that do nothing in the chosen format
     * are hidden: in the dot-only format there is no text and the dot is always on.
     */
    int recOptions(Ui ui, int x, int y, int w, int indent) {
        Settings l = live();
        boolean dotOnly = l.hudFormat == Settings.HUD_DOT, dot = dotOnly || l.hudShowDot;
        y = stepAt(ui, x, y, w, indent, t("Масштаб", "Scale"), null, String.format(Locale.ROOT, "%.2fx", l.hudScale),
                () -> l.hudScale -= 0.25, () -> l.hudScale += 0.25);
        y = stepAt(ui, x, y, w, indent, t("Непрозрачность фона", "Background opacity"), null, l.hudBgOpacity + "%",
                () -> l.hudBgOpacity -= 10, () -> l.hudBgOpacity += 10);
        y = choiceAt(ui, x, y, w, indent, t("Формат", "Format"), new String[]{t("Полный", "Full"), t("Время", "Time"), t("Кружок", "Dot")},
                l.hudFormat, v -> l.hudFormat = v);
        if (!dotOnly) {
            y = choiceAt(ui, x, y, w, indent, t("Цвет текста", "Text color"), new String[]{t("Состояние", "State"), t("Белый", "White"), t("Серый", "Grey")},
                    l.hudTextColor, v -> l.hudTextColor = v);
            y = flagAt(ui, x, y, w, indent, t("Кружок", "Dot"), null, l.hudShowDot, v -> l.hudShowDot = v);
        }
        if (dot) y = flagAt(ui, x, y, w, indent, t("Мигание кружка", "Blinking dot"), null, l.hudBlink, v -> l.hudBlink = v);
        if (!dotOnly) y = flagAt(ui, x, y, w, indent, t("Тень у текста", "Text shadow"), null, l.hudShadow, v -> l.hudShadow = v);
        y = flagAt(ui, x, y, w, indent, t("Скрывать на паузе", "Hide when paused"), t("Вне мира и в паузе одиночной игры", "Outside a world and in singleplayer pause"),
                l.hudHideWhenPaused, v -> l.hudHideWhenPaused = v);
        return placeAt(ui, x, y, w, indent, t("Положение", "Position"), () -> Minecraft.getInstance().setScreen(new HudPositionScreen(menu)));
    }

    private int fightSummary(Ui ui, int x, int y, int w) {
        Settings l = live();
        String name = t("Итог боя", "Fight summary");
        y = blockHead(ui, x, y, w, name, t("После боя на несколько секунд", "For a few seconds after a fight"), Help.fightSummary(),
                l.fightSummary, v -> l.fightSummary = v, 0, null);
        if (!l.fightSummary && !expandAll) return y;
        if (ui.find != null) ui.find.parent(name);
        int y0 = y, lx = x + 10 + INDENT * 2, lw = w - 20 - INDENT * 2;
        int fw = dev.combatdata.client.FightHud.previewWidth(ui.font, l) + 12, fh = dev.combatdata.client.FightHud.previewHeight(ui.font, l) + 8;
        int fx = lx, fy = y + 6;
        ui.g.enableScissor(Math.max(fx, x + 1), fy, Math.min(fx + fw, x + w - 1), fy + fh);
        ui.g.fill(fx, fy, fx + fw, fy + fh / 2, 0xFF7EA6D6);
        ui.g.fill(fx, fy + fh / 2, fx + fw, fy + fh, 0xFF5D8C3A);
        dev.combatdata.client.FightHud.preview(ui.g, ui.font, fx + 6, fy + 4, l);
        ui.g.disableScissor();
        y = fy + fh + 6;
        ui.g.fill(x + 8 + INDENT * 2, y - 1, x + w - 8, y, Theme.BORDER);

        ui.text(t("Что показывать", "What to show"), lx, y + 8, Theme.TEXT);
        Flow fl = new Flow(lx, y + 22, lw);
        chip(ui, fl, t("Ник и время", "Name and time"), l.fsTitle, v -> l.fsTitle = v);
        chip(ui, fl, t("Попадания", "Hits"), l.fsHits, v -> l.fsHits = v);
        chip(ui, fl, "Jump reset", l.fsJr, v -> l.fsJr = v);
        chip(ui, fl, t("Комбо", "Combo"), l.fsCombo, v -> l.fsCombo = v);
        chip(ui, fl, t("Размены", "Trades"), l.fsTrades, v -> l.fsTrades = v);
        chip(ui, fl, t("Дистанция", "Reach"), l.fsReach, v -> l.fsReach = v);
        chip(ui, fl, "3 block hit", l.fsThreeBlock, v -> l.fsThreeBlock = v);
        chip(ui, fl, t("Получено ударов", "Hits taken"), l.fsTaken, v -> l.fsTaken = v);
        y = fl.bottom() + 2;
        ui.g.fill(x + 8 + INDENT * 2, y - 1, x + w - 8, y, Theme.BORDER);

        y = choice(ui, x, y, w, t("Вид", "Layout"), new String[]{t("Две строки", "Two lines"), t("Одна строка", "One line"), t("Столбиком", "Column")},
                l.fsLayout, v -> l.fsLayout = v);
        y = flag(ui, x, y, w, t("Цветные цифры", "Colored numbers"), t("Зелёный хорошо, жёлтый средне, красный плохо", "Green good, yellow fair, red bad"),
                l.fsColors, v -> l.fsColors = v);
        y = stepRow(ui, x, y, w, t("Непрозрачность фона", "Background opacity"), null, l.fsBgOpacity + "%",
                () -> l.fsBgOpacity -= 10, () -> l.fsBgOpacity += 10);
        y = stepRow(ui, x, y, w, t("Масштаб", "Scale"), null, String.format(Locale.ROOT, "%.2fx", l.fsScale),
                () -> l.fsScale -= 0.25, () -> l.fsScale += 0.25);
        y = stepRow(ui, x, y, w, t("Показывается", "Shown for"), null, l.fsShowSec + t(" с", " s"), () -> l.fsShowSec--, () -> l.fsShowSec++);
        y = choice(ui, x, y, w, t("Положение", "Position"), new String[]{t("Рядом с REC", "Next to REC"), t("Своё", "Custom")},
                l.fsNearRec ? 0 : 1, v -> l.fsNearRec = v == 0);
        if (!l.fsNearRec) y = place(ui, x, y, w, t("Своё место", "Custom place"),
                () -> Minecraft.getInstance().setScreen(new HudPositionScreen(menu, true, null)));
        ui.text(Draw.ellipsize(ui.font, t("Когда бой считается оконченным: Настройки → Основные (" + l.fsDelaySec + " с без ударов)",
                "When a fight counts as over: Settings → General (" + l.fsDelaySec + " s without hits)"), lw), lx, y + 6, Theme.DIM);
        y += 18;
        ui.g.fill(x + 12, y0 + 3, x + 14, y - 4, Theme.alpha(Theme.ACCENT, 0x90));
        return y;
    }

    /** "Move" button that opens the drag screen; the place is saved there. */
    private int place(Ui ui, int x, int y, int w, String label, Runnable open) {
        return placeAt(ui, x, y, w, INDENT * 2, label, open);
    }

    private int placeAt(Ui ui, int x, int y, int w, int indent, String label, Runnable open) {
        row(ui, x, y, w, 26, indent, label, null, Theme.TEXT);
        String bl = t("Переместить", "Move");
        int bw = ui.buttonWidth(bl);
        ui.button(x + w - 10 - bw, y + 5, bw, 16, bl, Ui.Kind.SECONDARY, true, open);
        return y + 26;
    }

    /** Lays out everything for the search index, with the options of switched-off indicators too. */
    void index(Ui ui, int w) {
        ui.find.at(MenuScreen.TabId.INDICATORS, 0);
        expandAll = true;
        try {
            content(ui, 0, 0, w);
        } finally {
            expandAll = false;
        }
    }

    @Override
    public boolean scrolled(double mx, double my, double dy) {
        if (mx < panelX || mx >= panelX + panelW || my < panelY || my >= panelY + panelH) return false;
        scroll = Math.max(0, Math.min(scroll - dy * 20, maxScroll));
        return true;
    }

    private static String name(Kind k) {
        return switch (k) {
            case HEALTH -> t("Здоровье", "Health");
            case REACH -> t("Дистанция удара", "Reach");
            case COMBO -> t("Комбо", "Combo");
            case PING -> t("Пинг", "Ping");
            case CHARGE -> t("Заряд атаки", "Attack charge");
            case JUMP_RESET -> "Jump reset";
            case THREE_BLOCK -> "3 block hit";
            case SESSION -> t("Сессия", "Session");
        };
    }

    private static String hint(Kind k) {
        return switch (k) {
            case HEALTH -> t("Точное ХП, по умолчанию над полоской голода", "Exact HP, above the hunger bar by default");
            case REACH -> t("Дистанция последнего попадания", "Distance of your last hit");
            case COMBO -> t("Попадания подряд, пока вас не ударили", "Hits in a row until you get hit");
            case PING -> t("Ваш пинг из списка игроков", "Your ping from the player list");
            case CHARGE -> t("Сколько набрался удар, у прицела", "How charged the hit is, by the crosshair");
            case JUMP_RESET -> t("Вовремя ли прыжок после удара по вам", "Whether you jumped in time after a hit");
            case THREE_BLOCK -> t("Ваш удар, когда он не мог достать вас", "Your hit while they could not reach you");
            case SESSION -> t("Итоги с входа на сервер", "Totals since joining the server");
        };
    }

    private int indicator(Ui ui, int x, int y, int w, Kind k) {
        IndicatorConfig c = cfg();
        IndicatorConfig.Element e = c.get(k);
        int rh = 32, right = x + w - 10;
        if (ui.find != null) ui.find.group(name(k));
        row(ui, x, y, w, rh, 0, name(k), hint(k), Theme.TEXT_STRONG);
        ui.toggle(right - 20, y + (rh - 11) / 2, e.on, v -> {
            e.on = v;
            changed();
        });
        // A strip of sky and grass behind the sample, the same as the HUD previews in Settings.
        int pw = IndicatorHud.previewWidth(ui.font, k, c), ph = IndicatorHud.previewHeight(k, c);
        int bw = pw + 12, bh = 22, bx = right - 26 - bw, by = y + (rh - bh) / 2;
        int minX = x + 10 + Math.max(ui.font.width(name(k)), Math.min(ui.font.width(hint(k)), w / 2)) + 8;
        if (bx >= minX) {
            ui.g.enableScissor(bx, by, bx + bw, by + bh);
            ui.g.fill(bx, by, bx + bw, by + bh / 2, 0xFF7EA6D6);
            ui.g.fill(bx, by + bh / 2, bx + bw, by + bh, 0xFF5D8C3A);
            IndicatorHud.preview(ui.g, ui.font, k, bx + 6, by + (bh - ph) / 2, c);
            ui.g.disableScissor();
        }
        y += rh;
        if (!e.on && !expandAll) return y;
        if (ui.find != null) ui.find.parent(name(k));
        int y0 = y;
        y = options(ui, x, y, w, k, c, e);
        ui.g.fill(x + 12, y0 + 3, x + 14, y - 4, Theme.alpha(Theme.ACCENT, 0x90));
        return y;
    }

    private int options(Ui ui, int x, int y, int w, Kind k, IndicatorConfig c, IndicatorConfig.Element e) {
        switch (k) {
            case HEALTH -> {
                y = choice(ui, x, y, w, t("Точность", "Precision"), new String[]{"20", "20.0", "20.00", "20.000"}, c.hpDecimals,
                        v -> c.hpDecimals = v);
                y = choice(ui, x, y, w, t("Цвет", "Color"), new String[]{t("Красный", "Red"), t("Белый", "White"),
                        t("Золотой", "Gold"), t("По ХП", "By HP")}, c.hpColor, v -> c.hpColor = v);
                y = choice(ui, x, y, w, t("Единицы", "Units"), new String[]{t("ХП", "HP"), t("Сердца", "Hearts")},
                        c.hpHearts ? 1 : 0, v -> c.hpHearts = v == 1);
                y = flag(ui, x, y, w, t("Поглощение", "Absorption"), t("Золотые сердца: «+4.00» после числа", "Golden hearts: \"+4.00\" after the number"),
                        c.hpAbsorption, v -> c.hpAbsorption = v);
                y = flag(ui, x, y, w, t("Значок сердца", "Heart icon"), null, c.hpIcon, v -> c.hpIcon = v);
            }
            case REACH -> {
                y = choice(ui, x, y, w, t("Точность", "Precision"), new String[]{"2.9", "2.87", "2.874"}, c.reachDecimals - 1,
                        v -> c.reachDecimals = v + 1);
                y = stepRow(ui, x, y, w, t("Скрывать через", "Hide after"), t("Иначе через 3 с значение сереет", "Otherwise it greys out after 3 s"),
                        c.reachHideSec == 0 ? t("никогда", "never") : c.reachHideSec + t(" с", " s"),
                        () -> c.reachHideSec = Math.max(0, c.reachHideSec - (c.reachHideSec > 10 ? 5 : 1)),
                        () -> c.reachHideSec += c.reachHideSec >= 10 ? 5 : 1);
            }
            case COMBO -> y = flag(ui, x, y, w, t("Только от 2 ударов", "Only from 2 hits"), t("Иначе виден всегда, даже 0", "Otherwise always shown, even 0"),
                    c.comboHideZero, v -> c.comboHideZero = v);
            case CHARGE -> {
                y = choice(ui, x, y, w, t("Вид", "Style"), new String[]{t("Полоска", "Bar"), t("Проценты", "Percent")}, c.chargeStyle,
                        v -> c.chargeStyle = v);
                y = flag(ui, x, y, w, t("Скрывать при полном заряде", "Hide when fully charged"), t("Виден только пока удар набирается", "Shown only while charging"),
                        c.chargeHideFull, v -> c.chargeHideFull = v);
            }
            case JUMP_RESET -> {
                y = stepRow(ui, x, y, w, t("Показывать", "Show for"), null, c.jrShowSec + t(" с", " s"),
                        () -> c.jrShowSec--, () -> c.jrShowSec++);
                y = sound(ui, x, y, w, t("Звук: вовремя", "Sound: in time"), c.jrSoundOk, s -> c.jrSoundOk = s);
                y = sound(ui, x, y, w, t("Звук: рано или поздно", "Sound: early or late"), c.jrSoundBad, s -> c.jrSoundBad = s);
                var p = CombatDataClient.settings().jumpParams();
                ui.text(Draw.ellipsize(ui.font, t("Окно " + p.lowerMs() + "–" + p.upperMs() + " мс после удара: Настройки → Дополнительно",
                        "Window " + p.lowerMs() + "–" + p.upperMs() + " ms after the hit: Settings → Advanced"), w - 20 - INDENT * 2),
                        x + 10 + INDENT * 2, y + 6, Theme.DIM);
                ui.text(Draw.ellipsize(ui.font, t("Без подписей промах коротко: -20 мс значит рано, +35 мс поздно",
                        "Without captions a miss is short: -20 ms means early, +35 ms late"), w - 20 - INDENT * 2),
                        x + 10 + INDENT * 2, y + 18, Theme.DIM);
                y += 30;
            }
            case THREE_BLOCK -> {
                y = flag(ui, x, y, w, t("Дистанция у противника", "Distance on their screen"), t("Насколько вы были дальше его досягаемости", "How far beyond their reach you were"),
                        c.tbDistance, v -> c.tbDistance = v);
                y = stepRow(ui, x, y, w, t("Показывать", "Show for"), null, c.tbShowSec + t(" с", " s"),
                        () -> c.tbShowSec--, () -> c.tbShowSec++);
                y = sound(ui, x, y, w, t("Звук", "Sound"), c.tbSound, s -> c.tbSound = s);
            }
            case SESSION -> {
                String pl = t("Что показывать", "What to show");
                ui.text(pl, x + 10 + INDENT * 2, y + 8, Theme.TEXT);
                Flow fl = new Flow(x + 10 + INDENT * 2, y + 22, w - 20 - INDENT * 2);
                chip(ui, fl, t("Точность", "Accuracy"), c.sesAccuracy, v -> c.sesAccuracy = v);
                chip(ui, fl, t("Попадания", "Hits"), c.sesHits, v -> c.sesHits = v);
                chip(ui, fl, "Jump reset", c.sesJr, v -> c.sesJr = v);
                chip(ui, fl, t("Лучшее комбо", "Best combo"), c.sesCombo, v -> c.sesCombo = v);
                chip(ui, fl, "3 block hit", c.sesThreeBlock, v -> c.sesThreeBlock = v);
                y = fl.bottom() + 2;
                ui.g.fill(x + 8 + INDENT * 2, y - 1, x + w - 8, y, Theme.BORDER);
                row(ui, x, y, w, 30, INDENT * 2, t("Начать заново", "Start over"), t("Само сбрасывается при входе на сервер", "Resets by itself when you join a server"), Theme.TEXT);
                String rs = t("Сбросить", "Reset");
                int bw = ui.buttonWidth(rs);
                ui.button(x + w - 10 - bw, y + 7, bw, 16, rs, Ui.Kind.SECONDARY, true, Indicators::newSession);
                y += 30;
            }
            default -> {
            }
        }
        boolean bar = k == Kind.CHARGE && c.chargeStyle == IndicatorConfig.CHARGE_BAR;
        if (!bar) y = flag(ui, x, y, w, t("Фон", "Background"), null, e.bg, v -> e.bg = v);
        return y;
    }

    private int common(Ui ui, int x, int y, int w) {
        IndicatorConfig c = cfg();
        y = SettingsTab.heading(ui, x, y, w, t("Общий вид", "Common style"));
        y = flagAt(ui, x, y, w, 0, t("Подписи", "Captions"), t("«Пинг», «Комбо» перед числом", "\"Ping\", \"Combo\" before the number"), c.labels, v -> c.labels = v);
        y = flagAt(ui, x, y, w, 0, t("Цветные значения", "Colored values"), t("Зелёный хорошо, жёлтый средне, красный плохо", "Green good, yellow fair, red bad"),
                c.colors, v -> c.colors = v);
        y = flagAt(ui, x, y, w, 0, t("Тень у текста", "Text shadow"), null, c.shadow, v -> c.shadow = v);
        y = stepAt(ui, x, y, w, 0, t("Непрозрачность фона", "Background opacity"), null, c.bgOpacity + "%",
                () -> c.bgOpacity -= 10, () -> c.bgOpacity += 10);
        y = stepAt(ui, x, y, w, 0, t("Громкость звуков", "Sound volume"), null, c.soundVolume + "%",
                () -> c.soundVolume -= 10, () -> c.soundVolume += 10);
        row(ui, x, y, w, 26, 0, t("Показать или скрыть индикаторы", "Show or hide indicators"), null, Theme.TEXT);
        SettingsTab.keyChip(ui, x + w - 10, y + 5, CombatDataClient.INDICATORS);
        y += 26;
        ui.text(Draw.ellipsize(ui.font, t("Изменить: Настройки → Управление → Combat Data", "Change in: Options → Controls → Combat Data"), w - 20), x + 10, y + 8, Theme.DIM);
        y += 24;
        row(ui, x, y, w, 30, 0, t("Всё по умолчанию", "Reset everything"), t("Вид, звуки и места; включённые остаются", "Style, sounds and places; what is on stays on"), Theme.TEXT);
        String rs = t("Сбросить", "Reset");
        int bw = ui.buttonWidth(rs);
        ui.button(x + w - 10 - bw, y + 7, bw, 16, rs, Ui.Kind.DANGER, true, () -> {
            IndicatorConfig d = new IndicatorConfig();
            for (Kind k : Kind.values()) d.get(k).on = c.get(k).on;
            d.enabled = c.enabled;
            CombatDataClient.settings().indicators = d;
            changed();
        });
        return y + 30 + 6;
    }

    private static void row(Ui ui, int x, int y, int w, int h, int indent, String label, String hint, int color) {
        ui.mark(label, hint, x + 4 + indent, y, w - 8 - indent, h);
        int lx = x + 10 + indent, lw = w / 2 - indent;
        int ly = hint == null ? Ui.textY(y, h) : y + h / 2 - 9;
        ui.text(Draw.ellipsize(ui.font, label, lw), lx, ly, color);
        if (hint != null) ui.text(Draw.ellipsize(ui.font, hint, lw), lx, y + h / 2 + 1, Theme.MUTED);
        ui.g.fill(x + 8 + indent, y + h - 1, x + w - 8, y + h, Theme.BORDER);
    }

    private int flag(Ui ui, int x, int y, int w, String label, String hint, boolean value, Consumer<Boolean> set) {
        return flagAt(ui, x, y, w, INDENT * 2, label, hint, value, set);
    }

    private int flagAt(Ui ui, int x, int y, int w, int indent, String label, String hint, boolean value, Consumer<Boolean> set) {
        int h = hint == null ? 26 : 30, right = x + w - 10;
        row(ui, x, y, w, h, indent, label, hint, Theme.TEXT);
        ui.toggle(right - 20, y + (h - 11) / 2, value, v -> {
            set.accept(v);
            changed();
        });
        return y + h;
    }

    private int choice(Ui ui, int x, int y, int w, String label, String[] items, int value, Consumer<Integer> set) {
        return choiceAt(ui, x, y, w, INDENT * 2, label, items, value, set);
    }

    private int choiceAt(Ui ui, int x, int y, int w, int indent, String label, String[] items, int value, Consumer<Integer> set) {
        int right = x + w - 10, sw = ui.segmentedWidth(items);
        row(ui, x, y, w, 26, indent, label, null, Theme.TEXT);
        ui.segmented(right - sw, y + 4, items, value, v -> {
            set.accept(v);
            changed();
        });
        return y + 26;
    }

    private int stepRow(Ui ui, int x, int y, int w, String label, String hint, String value, Runnable minus, Runnable plus) {
        return stepAt(ui, x, y, w, INDENT * 2, label, hint, value, minus, plus);
    }

    private int stepAt(Ui ui, int x, int y, int w, int indent, String label, String hint, String value, Runnable minus, Runnable plus) {
        int h = hint == null ? 26 : 30, sw = 72;
        row(ui, x, y, w, h, indent, label, hint, Theme.TEXT);
        ui.stepper(x + w - 10 - sw, y + (h - Ui.CTRL_H) / 2, sw, value, () -> {
            minus.run();
            changed();
        }, () -> {
            plus.run();
            changed();
        });
        return y + h;
    }

    /** On/off, then instrument, note and a test button on a second line. */
    private int sound(Ui ui, int x, int y, int w, String label, IndicatorConfig.Sound s, Consumer<IndicatorConfig.Sound> set) {
        y = flag(ui, x, y, w, label, null, s.on(), v -> set.accept(s.withOn(v)));
        if (!s.on()) return y;
        int right = x + w - 10, h = 26;
        String test = t("Проба", "Test");
        int tw = ui.buttonWidth(test), nw = 56, iw = Math.min(110, Math.max(70, w - 20 - INDENT * 2 - tw - nw - 8 - 60));
        int bx = right - tw;
        ui.button(bx, y + 5, tw, 16, test, Ui.Kind.GHOST, cfg().soundVolume > 0,
                () -> Indicators.playNow(Minecraft.getInstance(), s, cfg().soundVolume, true));
        bx -= 4 + nw;
        ui.stepper(bx, y + 5, nw, Notes.name(s.note()), () -> {
            set.accept(s.withNote(s.note() - 1));
            Indicators.playNow(Minecraft.getInstance(), s.withNote(s.note() - 1), cfg().soundVolume, true);
            changed();
        }, () -> {
            set.accept(s.withNote(s.note() + 1));
            Indicators.playNow(Minecraft.getInstance(), s.withNote(s.note() + 1), cfg().soundVolume, true);
            changed();
        });
        bx -= 4 + iw;
        ui.stepper(bx, y + 5, iw, Draw.ellipsize(ui.font, Indicators.instrumentName(s.instrument()), iw - 30), () -> {
            IndicatorConfig.Sound n = s.withInstrument(Notes.step(s.instrument(), -1));
            set.accept(n);
            Indicators.playNow(Minecraft.getInstance(), n, cfg().soundVolume, true);
            changed();
        }, () -> {
            IndicatorConfig.Sound n = s.withInstrument(Notes.step(s.instrument(), 1));
            set.accept(n);
            Indicators.playNow(Minecraft.getInstance(), n, cfg().soundVolume, true);
            changed();
        });
        ui.text(t("Нота", "Note"), x + 10 + INDENT * 2, Ui.textY(y, h), Theme.MUTED);
        ui.g.fill(x + 8 + INDENT * 2, y + h - 1, x + w - 8, y + h, Theme.BORDER);
        return y + h;
    }

    private static void chip(Ui ui, Flow fl, String label, boolean on, Consumer<Boolean> set) {
        int cw = ui.font.width(label) + 12;
        int[] p = fl.place(cw, false);
        ui.mark(label, null, p[0] - 3, p[1], cw + 6, 18);
        ui.chip(p[0], p[1] + 3, cw, label, on, () -> {
            set.accept(!on);
            changed();
        });
    }
}
