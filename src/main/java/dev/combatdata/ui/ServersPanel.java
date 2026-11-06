package dev.combatdata.ui;

import dev.combatdata.CombatDataClient;
import dev.combatdata.client.Recordings;
import dev.combatdata.core.*;

import java.util.*;

import static dev.combatdata.core.Lang.t;

/** Settings → Servers: the profile list and the editor. Profiles apply at once, they are not part of the draft. */
final class ServersPanel {
    private final MenuScreen menu;
    /** Address of the profile being edited; null shows the list. */
    private String open;
    private volatile boolean scanning;
    private volatile String scanStatus;
    private volatile Map<String, ServerCalibration.Result> pending;
    private boolean scannedOnce;

    ServersPanel(MenuScreen menu) {
        this.menu = menu;
    }

    static String presetName(ServerProfile.Preset p) {
        return switch (p) {
            case VANILLA -> t("Ваниль", "Vanilla");
            case KB_SYNC -> "KnockbackSync";
            case CUSTOM -> t("Свой", "Custom");
        };
    }

    private static String modeName(ServerProfile.JrMode m) {
        return switch (m) {
            case SETTINGS -> t("как в настройках", "as in settings");
            case CALIBRATION -> t("по калибровке", "by calibration");
            case MANUAL -> t("вручную", "manual");
        };
    }

    private static ServerProfiles profiles() {
        return CombatDataClient.profiles();
    }

    private static void changed(ServerProfile p) {
        p.origin = ServerProfile.Origin.MANUAL;
        p.clamp();
        CombatDataClient.saveProfiles();
    }

    int render(Ui ui, int x, int y, int w) {
        applyPending();
        if (!scannedOnce && ui.find == null) {
            scannedOnce = true;
            scan(null);
        }
        ServerProfile p = open == null ? null : profiles().find(open);
        if (p != null && p.address.equals(open)) return editor(ui, x, y, w, p);
        open = null;
        return list(ui, x, y, w);
    }

    private int list(Ui ui, int x, int y, int w) {
        y = SettingsTab.heading(ui, x, y, w, t("Профили серверов", "Server profiles"));
        y = dim(ui, x, y, w, t("Каждая часть записи считается по профилю своего сервера; изменения пересчитывают и старые записи.",
                "Each part of a recording is rated by its server's profile; changes re-rate old recordings too."));
        y = dim(ui, x, y, w, t("Профиль создаётся сам при первом заходе на сервер.", "A profile is created on the first visit to a server."));
        int right = x + w - 10;
        String add = t("Добавить", "Add"), sc = scanning ? t("Считаю…", "Working…") : t("Пересчитать по записям", "Recompute from recordings");
        int aw = ui.buttonWidth(add), sw = ui.buttonWidth(sc);
        ui.button(right - aw, y + 4, aw, 16, add, Ui.Kind.SECONDARY, true, this::addProfile);
        ui.button(right - aw - 4 - sw, y + 4, sw, 16, sc, Ui.Kind.SECONDARY, !scanning, () -> scan(null));
        String st = scanStatus;
        if (st != null) ui.text(Draw.ellipsize(ui.font, st, w - aw - sw - 34), x + 10, y + 8, Theme.DIM);
        y += 24;
        List<ServerProfile> list = new ArrayList<>(profiles().list());
        list.sort(Comparator.comparingLong((ServerProfile s) -> s.lastSeenMs).reversed());
        if (list.isEmpty()) {
            ui.text(t("Профилей пока нет: зайдите на сервер или нажмите «Добавить».", "No profiles yet: join a server or press Add."),
                    x + 10, y + 8, Theme.MUTED);
            return y + 24;
        }
        for (ServerProfile s : list) y = profileRow(ui, x, y, w, s);
        return y + 6;
    }

    private int profileRow(Ui ui, int x, int y, int w, ServerProfile s) {
        int h = 30, right = x + w - 10;
        boolean hov = ui.hover(x + 4, y, w - 8, h);
        if (hov) Draw.round(ui.g, x + 4, y + 1, w - 8, h - 2, Theme.R_CTRL, Theme.ROW_HOVER);
        String el = t("Изменить", "Edit");
        int ew = ui.buttonWidth(el);
        String name = s.shownName() + (s.subdomains ? t(" и поддомены", " and subdomains") : "");
        ui.mark(s.shownName(), null, x + 4, y, w - 8, h);
        ui.text(Draw.ellipsize(ui.font, name, w - ew - 40), x + 10, y + 5, Theme.TEXT);
        int missing = s.calibrationMissing();
        String meta = presetName(s.preset) + " · JR " + modeName(s.jrMode)
                + (missing > 0 ? t(" · калибровке нужно ещё ", " · calibration needs ") + missing + t(" попыток", " more attempts")
                : t(" · калибровка: ", " · calibration: ") + s.cal.samples() + t(" попыток", " attempts"))
                + (s.noRecord ? t(" · не записывать", " · not recorded") : "")
                + (s.lastSeenMs > 0 ? t(" · заход ", " · visited ") + Fmt.date(s.lastSeenMs) : "");
        int mx = x + 10;
        if (missing > 0 && s.jrMode == ServerProfile.JrMode.CALIBRATION) {
            Draw.circle(ui.g, mx, y + 17, 6, Theme.AMBER);
            mx += 10;
        }
        ui.text(Draw.ellipsize(ui.font, meta, right - ew - 8 - mx), mx, y + 16, Theme.MUTED);
        ui.g.fill(x + 8, y + h - 1, x + w - 8, y + h, Theme.BORDER);
        ui.click(x + 4, y, w - 8, h, () -> open = s.address);
        ui.button(right - ew, y + 7, ew, 16, el, Ui.Kind.GHOST, true, () -> open = s.address);
        return y + h;
    }

    private int editor(Ui ui, int x, int y, int w, ServerProfile p) {
        int right = x + w - 10;
        String back = t("← Все серверы", "← All servers");
        int bw = ui.buttonWidth(back);
        ui.button(x + 8, y + 6, bw, 16, back, Ui.Kind.GHOST, true, () -> open = null);
        y = SettingsTab.heading(ui, x, y + 20, w, p.shownName());

        y = row(ui, x, y, w, t("Адрес", "Address"), p.address, false);
        y = toggleRow(ui, x, y, w, t("С поддоменами", "Include subdomains"),
                t("Адрес подходит и для поддоменов: stray.gg для eu.stray.gg", "Also matches subdomains: stray.gg for eu.stray.gg"),
                p.subdomains, v -> { p.subdomains = v; changed(p); });
        y = editRow(ui, x, y, w, t("Имя для показа", "Display name"), p.name.isBlank() ? t("как адрес", "same as address") : p.name,
                () -> prompt(t("Имя для показа", "Display name"), p.name, ServerProfile.MAX_NAME, v -> { p.name = v; changed(p); }));

        y = SettingsTab.heading(ui, x, y, w, t("Пресет и нокбэк", "Preset and knockback"));
        String[] presets = {presetName(ServerProfile.Preset.VANILLA), "KnockbackSync", presetName(ServerProfile.Preset.CUSTOM)};
        y = segRow(ui, x, y, w, t("Пресет", "Preset"), presets, p.preset.ordinal(), i -> {
            p.applyPreset(ServerProfile.Preset.values()[i]);
            changed(p);
        });
        if (p.preset == ServerProfile.Preset.KB_SYNC) {
            y = dim(ui, x, y, w, t("Как KnockbackSync меняет окно jump reset, заранее не известно: окно подбирается по вашим записям с этого сервера.",
                    "How KnockbackSync shifts the jump reset window is not known in advance: the window is fitted from your recordings on this server."));
        }
        String[] models = {t("Ванильная", "Vanilla"), "KnockbackSync", t("Неизвестно", "Unknown")};
        y = segRow(ui, x, y, w, t("Модель нокбэка", "Knockback model"), models, p.kbModel.ordinal(), i -> {
            p.kbModel = ServerProfile.KbModel.values()[i];
            changed(p);
        });
        y = row(ui, x, y, w, t("Измеренный отлёт", "Measured knockback"), kbText(p.kb), false);
        y = toggleRow(ui, x, y, w, t("Использовать измеренный отлёт", "Use measured knockback"),
                t("Для разбора противников: ожидаемый отлёт на этом сервере", "For opponent checks: expected knockback on this server"),
                p.useMeasuredKb, v -> { p.useMeasuredKb = v; changed(p); });

        y = SettingsTab.heading(ui, x, y, w, "Jump reset");
        String[] modes = {t("Как в настройках", "As in settings"), t("По калибровке", "By calibration"), t("Вручную", "Manual")};
        y = segRow(ui, x, y, w, t("Окно успеха", "Success window"), modes, p.jrMode.ordinal(), i -> {
            p.jrMode = ServerProfile.JrMode.values()[i];
            changed(p);
        });
        Settings s = CombatDataClient.settings();
        JumpResetEvaluator.Params jp = p.jumpParams(s);
        y = row(ui, x, y, w, t("Сейчас действует", "In effect"), jp.lowerMs() + "…" + jp.upperMs() + t(" мс после удара", " ms after the hit"), false);
        String calText = p.cal.valid()
                ? p.cal.lowerMs() + "…" + p.cal.upperMs() + t(" мс, ", " ms, ") + p.cal.samples() + t(" попыток", " attempts")
                + (p.cal.updatedMs() > 0 ? ", " + Fmt.date(p.cal.updatedMs()) : "")
                : t("мало данных: ", "not enough data: ") + p.cal.samples() + t(" из ", " of ") + Calibration.MIN_SAMPLES
                + t(" попыток", " attempts");
        y = row(ui, x, y, w, t("Калибровка", "Calibration"), calText, false);
        if (p.jrMode == ServerProfile.JrMode.CALIBRATION && !p.cal.valid()) {
            y = dim(ui, x, y, w, t("Пока данных мало, используется окно из настроек.", "Until there is enough data the window from the settings is used."));
        }
        if (p.jrMode == ServerProfile.JrMode.MANUAL) {
            y = stepRow(ui, x, y, w, t("От", "From"), p.jrLowerMs + t(" мс", " ms"), () -> { p.jrLowerMs -= 10; changed(p); },
                    () -> { p.jrLowerMs += 10; changed(p); });
            y = stepRow(ui, x, y, w, t("До", "To"), p.jrUpperMs + t(" мс", " ms"), () -> { p.jrUpperMs -= 10; changed(p); },
                    () -> { p.jrUpperMs += 10; changed(p); });
            y = stepRow(ui, x, y, w, t("Прыжки до удара", "Jumps before the hit"), p.jrPreMs + t(" мс", " ms"),
                    () -> { p.jrPreMs -= 25; changed(p); }, () -> { p.jrPreMs += 25; changed(p); });
            y = stepRow(ui, x, y, w, t("Прыжки после удара", "Jumps after the hit"), p.jrPostMs + t(" мс", " ms"),
                    () -> { p.jrPostMs -= 25; changed(p); }, () -> { p.jrPostMs += 25; changed(p); });
            String rl = t("Вернуть измеренные значения", "Use measured values");
            int rw = ui.buttonWidth(rl);
            ui.button(right - rw, y + 4, rw, 16, rl, Ui.Kind.SECONDARY, p.cal.valid(), () -> {
                p.jrLowerMs = p.cal.lowerMs();
                p.jrUpperMs = p.cal.upperMs();
                changed(p);
            });
            y += 24;
        }

        y = SettingsTab.heading(ui, x, y, w, "3 block hit");
        String interp = p.interpMs < 0 ? t("как в настройках (", "as in settings (") + s.interpMs + t(" мс)", " ms)") : p.interpMs + t(" мс", " ms");
        y = stepRow(ui, x, y, w, t("Задержка сглаживания", "Smoothing delay"), interp, () -> {
            p.interpMs = p.interpMs <= 0 ? -1 : p.interpMs - 10;
            changed(p);
        }, () -> {
            p.interpMs = p.interpMs < 0 ? s.interpMs : p.interpMs + 10;
            changed(p);
        });
        String range = Double.isFinite(p.oppRange) ? Fmt.num(p.oppRange, 1) + t(" бл.", " bl.") : t("как у вас", "same as yours");
        y = stepRow(ui, x, y, w, t("Дальность противника", "Opponent range"), range, () -> {
            p.oppRange = !Double.isFinite(p.oppRange) || p.oppRange <= 2.05 ? Double.NaN : Math.round((p.oppRange - 0.1) * 10) / 10.0;
            changed(p);
        }, () -> {
            p.oppRange = Double.isFinite(p.oppRange) ? Math.round((p.oppRange + 0.1) * 10) / 10.0 : AttackEvent.DEFAULT_RANGE;
            changed(p);
        });

        y = SettingsTab.heading(ui, x, y, w, t("Прочее", "Other"));
        y = toggleRow(ui, x, y, w, t("Не записывать на этом сервере", "Do not record on this server"),
                t("Запись выключается сама при заходе, индикаторы работают", "Recording turns off on join, indicators keep working"),
                p.noRecord, v -> { p.noRecord = v; changed(p); });
        y = editRow(ui, x, y, w, t("Заметка", "Note"), p.note.isBlank() ? "—" : p.note,
                () -> prompt(t("Заметка", "Note"), p.note, ServerProfile.MAX_NOTE, v -> { p.note = v; changed(p); }));
        String origin = switch (p.origin) {
            case AUTO -> t("создан автоматически", "created automatically");
            case BUILTIN -> t("из встроенного списка", "from the built-in list");
            case MANUAL -> t("изменён вручную", "edited manually");
        };
        y = row(ui, x, y, w, t("Происхождение", "Origin"), origin + (p.createdMs > 0 ? t(" · создан ", " · created ") + Fmt.date(p.createdMs) : "")
                + (p.lastSeenMs > 0 ? t(" · заход ", " · visited ") + Fmt.date(p.lastSeenMs) : ""), false);
        String sc = scanning ? t("Считаю…", "Working…") : t("Пересчитать по записям", "Recompute from recordings");
        String del = t("Удалить профиль", "Delete profile");
        int sw = ui.buttonWidth(sc), dw = ui.buttonWidth(del);
        ui.button(right - dw, y + 6, dw, 16, del, Ui.Kind.DANGER, true, () -> confirmDelete(p));
        ui.button(right - dw - 4 - sw, y + 6, sw, 16, sc, Ui.Kind.SECONDARY, !scanning, () -> scan(p.address));
        String st = scanStatus;
        if (st != null) ui.text(Draw.ellipsize(ui.font, st, w - dw - sw - 34), x + 10, y + 10, Theme.DIM);
        return y + 30;
    }

    private static String kbText(ServerProfile.Knockback k) {
        if (k.allN() == 0) return t("нет данных", "no data");
        StringBuilder b = new StringBuilder();
        if (k.normalN() > 0) b.append(t("обычный ", "normal ")).append(hv(k.normalH(), k.normalV())).append(" (").append(k.normalN()).append(") · ");
        if (k.sprintN() > 0) b.append(t("спринт ", "sprint ")).append(hv(k.sprintH(), k.sprintV())).append(" (").append(k.sprintN()).append(") · ");
        b.append(t("всего ", "all ")).append(Fmt.num(k.allH(), 2)).append(t(" бл. (", " bl. (")).append(k.allN()).append(")");
        if (k.allN() < ServerCalibration.MIN_KB) b.append(t(" · мало данных", " · not enough data"));
        return b.toString();
    }

    private static String hv(double h, double v) {
        return Fmt.num(h, 2) + (Double.isFinite(v) ? " / " + Fmt.num(v, 2) : "") + t(" бл.", " bl.");
    }

    private int row(Ui ui, int x, int y, int w, String label, String value, boolean dimValue) {
        int h = 24;
        ui.mark(label, null, x + 4, y, w - 8, h);
        int lw = ui.font.width(label);
        ui.text(label, x + 10, Ui.textY(y, h), Theme.TEXT);
        ui.textRight(Draw.ellipsize(ui.font, value, w - lw - 40), x + w - 10, Ui.textY(y, h), dimValue ? Theme.DIM : Theme.MUTED);
        ui.g.fill(x + 8, y + h - 1, x + w - 8, y + h, Theme.BORDER);
        return y + h;
    }

    private int toggleRow(Ui ui, int x, int y, int w, String label, String hint, boolean value, java.util.function.Consumer<Boolean> set) {
        int h = 30, right = x + w - 10;
        ui.mark(label, hint, x + 4, y, w - 8, h);
        ui.text(label, x + 10, y + h / 2 - 9, Theme.TEXT);
        ui.text(Draw.ellipsize(ui.font, hint, w - 60), x + 10, y + h / 2 + 1, Theme.MUTED);
        ui.toggle(right - 20, y + (h - 11) / 2, value, set);
        ui.g.fill(x + 8, y + h - 1, x + w - 8, y + h, Theme.BORDER);
        return y + h;
    }

    private int segRow(Ui ui, int x, int y, int w, String label, String[] items, int value, java.util.function.Consumer<Integer> set) {
        int h = 26, right = x + w - 10, sw = ui.segmentedWidth(items);
        ui.mark(label, null, x + 4, y, w - 8, h);
        ui.text(label, x + 10, Ui.textY(y, h), Theme.TEXT);
        ui.segmented(right - sw, y + 4, items, value, set);
        ui.g.fill(x + 8, y + h - 1, x + w - 8, y + h, Theme.BORDER);
        return y + h;
    }

    private int stepRow(Ui ui, int x, int y, int w, String label, String value, Runnable minus, Runnable plus) {
        int h = 26, right = x + w - 10, sw = Math.max(72, ui.font.width(value) + 36);
        ui.mark(label, null, x + 4, y, w - 8, h);
        ui.text(label, x + 10, Ui.textY(y, h), Theme.TEXT);
        ui.stepper(right - sw, y + 5, sw, value, minus, plus);
        ui.g.fill(x + 8, y + h - 1, x + w - 8, y + h, Theme.BORDER);
        return y + h;
    }

    private int editRow(Ui ui, int x, int y, int w, String label, String value, Runnable edit) {
        int h = 24, right = x + w - 10;
        String el = t("Изменить", "Edit");
        int ew = ui.buttonWidth(el), lw = ui.font.width(label);
        ui.mark(label, null, x + 4, y, w - 8, h);
        ui.text(label, x + 10, Ui.textY(y, h), Theme.TEXT);
        ui.textRight(Draw.ellipsize(ui.font, value, w - lw - ew - 44), right - ew - 6, Ui.textY(y, h), Theme.MUTED);
        ui.button(right - ew, y + 4, ew, 16, el, Ui.Kind.GHOST, true, edit);
        ui.g.fill(x + 8, y + h - 1, x + w - 8, y + h, Theme.BORDER);
        return y + h;
    }

    private static int dim(Ui ui, int x, int y, int w, String text) {
        for (String l : ui.wrap(text, w - 20)) {
            ui.text(l, x + 10, y + 6, Theme.DIM);
            y += 10;
        }
        return y + 4;
    }

    private void prompt(String title, String value, int max, java.util.function.Consumer<String> set) {
        Modal m = new Modal(title, List.of(), value, List.of(
                new Modal.Btn(t("Отмена", "Cancel"), Ui.Kind.SECONDARY, v -> menu.closeModal()),
                new Modal.Btn(t("Сохранить", "Save"), Ui.Kind.PRIMARY, v -> {
                    set.accept(v == null ? "" : v);
                    menu.closeModal();
                })));
        m.maxLength = max;
        menu.openModal(m);
    }

    private void addProfile() {
        Modal[] self = new Modal[1];
        self[0] = new Modal(t("Новый профиль", "New profile"), List.of(t("Адрес сервера, например eu.stray.gg или stray.gg:", "Server address, e.g. eu.stray.gg or stray.gg:")),
                "", List.of(
                new Modal.Btn(t("Отмена", "Cancel"), Ui.Kind.SECONDARY, v -> menu.closeModal()),
                new Modal.Btn(t("Добавить", "Add"), Ui.Kind.PRIMARY, v -> {
                    String a = ServerProfiles.normalize(v);
                    if (a.isEmpty() || a.contains(" ")) {
                        self[0].error = t("Введите адрес без пробелов", "Enter an address without spaces");
                        return;
                    }
                    if (profiles().list().stream().anyMatch(p -> p.address.equals(a))) {
                        self[0].error = t("Такой профиль уже есть", "This profile already exists");
                        return;
                    }
                    ServerProfile p = ServerProfile.create(a, ServerProfile.Preset.VANILLA, ServerProfile.Origin.MANUAL,
                            CombatDataClient.settings(), System.currentTimeMillis());
                    p.lastSeenMs = 0;
                    p.subdomains = a.chars().filter(c -> c == '.').count() == 1;
                    profiles().put(p);
                    CombatDataClient.saveProfiles();
                    menu.closeModal();
                    open = p.address;
                })));
        menu.openModal(self[0]);
    }

    private void confirmDelete(ServerProfile p) {
        menu.openModal(new Modal(t("Удалить профиль?", "Delete the profile?"), List.of(
                p.shownName() + t(": записи с этого сервера будут считаться по настройкам по умолчанию.",
                        ": recordings from this server will be rated with the default settings."),
                t("При следующем заходе профиль создастся заново.", "It is created again on the next visit.")), null, List.of(
                new Modal.Btn(t("Отмена", "Cancel"), Ui.Kind.SECONDARY, v -> menu.closeModal()),
                new Modal.Btn(t("Удалить", "Delete"), Ui.Kind.DANGER, v -> {
                    profiles().remove(p.address);
                    CombatDataClient.saveProfiles();
                    open = null;
                    menu.closeModal();
                }))));
    }

    /** Results are stored on the render thread, so a profile is never changed under the editor's feet. */
    private void applyPending() {
        Map<String, ServerCalibration.Result> r = pending;
        if (r == null) return;
        pending = null;
        long now = System.currentTimeMillis();
        boolean any = false;
        for (ServerProfile p : profiles().list()) {
            ServerCalibration.Result res = r.get(p.address);
            if (res == null) continue;
            ServerCalibration.apply(p, res, now);
            any = true;
        }
        if (any) CombatDataClient.saveProfiles();
    }

    /** Reads saved recordings in the background; only address (or every profile if null) is recomputed. */
    private void scan(String address) {
        if (scanning) return;
        List<ServerProfile> targets = new ArrayList<>();
        for (ServerProfile p : profiles().list()) if (address == null || p.address.equals(address)) targets.add(p.copy());
        if (targets.isEmpty()) return;
        scanning = true;
        scanStatus = t("Считаю…", "Working…");
        Settings s = CombatDataClient.settings().copy();
        StatFilter sf = s.statFilter();
        ServerProfiles all = profiles();
        Thread th = new Thread(() -> {
            try {
                Map<String, ServerCalibration> acc = new LinkedHashMap<>();
                for (ServerProfile p : targets) acc.put(p.address, new ServerCalibration(p, all, s));
                int files = 0;
                for (Recordings.Entry e : Recordings.list()) {
                    if (e.header() == null) continue;
                    List<ServerCalibration> hit = new ArrayList<>();
                    for (ServerProfile p : targets) {
                        for (String srv : e.header().servers()) {
                            ServerProfile f = all.find(srv);
                            if (f != null && f.address.equals(p.address)) {
                                hit.add(acc.get(p.address));
                                break;
                            }
                        }
                    }
                    if (hit.isEmpty()) continue;
                    try {
                        Recording r = Cdrec.read(e.file());
                        for (ServerCalibration c : hit) c.add(r, sf);
                        files++;
                    } catch (Exception ex) {
                        CombatDataClient.LOG.warn("[CD] профили: не читается {}", e.file().getFileName());
                    }
                }
                long now = System.currentTimeMillis();
                Map<String, ServerCalibration.Result> out = new HashMap<>();
                for (Map.Entry<String, ServerCalibration> en : acc.entrySet()) out.put(en.getKey(), en.getValue().build(now));
                pending = out;
                scanStatus = t("Записей учтено: ", "Recordings used: ") + files;
            } catch (Exception ex) {
                scanStatus = t("Ошибка: ", "Error: ") + ex.getMessage();
            } finally {
                scanning = false;
            }
        }, "combatdata-servers");
        th.setDaemon(true);
        th.start();
    }
}
