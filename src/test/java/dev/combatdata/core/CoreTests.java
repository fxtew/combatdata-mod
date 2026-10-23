package dev.combatdata.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;

/** Core regression tests. Run without Gradle: scripts/core-tests.sh. */
public final class CoreTests {
    private static int checks, failures;

    public static void main(String[] args) throws Exception {
        bufferClock();
        bufferTrimAndSnapshot();
        geometry();
        threeBlockHit();
        chartMath();
        calibration();
        classifier();
        jumpReset();
        stats();
        jsonAndCdrec();
        settings();
        recordingsList();
        lang();
        cooldown();
        insights();
        statFilters();
        progress();
        trades();
        wtap();
        taken();
        performance();
        tradeWeights();
        positions();
        replay();
        liteAndThemes();
        fightSummary();
        autosaveAndFixes();
        // Check names are ASCII: the Windows console (CP866) garbles Cyrillic.
        System.out.println("CoreTests: " + checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    static void autosaveAndFixes() throws Exception {
        Settings st = new Settings();
        check("автосохранение включено, подробный лог выключен по умолчанию", st.autoSave && !st.debugLog);
        st.autoSave = false;
        check("автосохранение сохраняется в настройках", !st.copy().autoSave);

        List<Event> ev = List.of(new SegmentEvent(0, "srv", "minecraft:overworld", 1_000_000L), new PingEvent(1000, 40),
                atk(5000, A, Outcome.HIT_CONFIRMED, 2.5, 1f), from(6000, A));
        Recording r = new Recording(1, "me", B, "26.1.2", 1_000_000L, 9000, ev);
        check("первое боевое событие", r.firstCombatMs() == 5000
                && new Recording(1, "me", B, "26.1.2", 0, 1000, List.of(new PingEvent(1, 1))).firstCombatMs() == -1);
        Recording cut = r.trimmedFrom(4000);
        check("обрезка: сегмент переносится в 0, время сдвигается", cut.events().size() == 3
                && cut.events().get(0) instanceof SegmentEvent s && s.t() == 0 && s.server().equals("srv")
                && cut.events().get(1).t() == 1000 && cut.events().get(2).t() == 2000);
        check("обрезка: длительность и время начала", cut.durationMs() == 5000 && cut.wallStartMs() == 1_004_000L
                && r.trimmedFrom(0) == r && r.trimmedFrom(-5) == r);

        RecordingBuffer buf = new RecordingBuffer();
        buf.add(new SegmentEvent(0, "srv", "minecraft:overworld", 1_000_000L));
        buf.add(atk(4000, A, Outcome.HIT_CONFIRMED, 2.5, 1f));
        // An hour of wall time passed (pauses), but only 5 s of active time.
        Recording live = buf.copy(5000, 3000, "me", B, "26.1.2", 1_000_000L + 3_600_000L);
        check("время начала берётся из сегмента, а не из текущего времени", live.wallStartMs() == 1_002_000L);
        RecordingBuffer noSeg = new RecordingBuffer();
        noSeg.add(atk(100, A, Outcome.HIT_CONFIRMED, 2.5, 1f));
        check("без сегмента время начала как раньше", noSeg.copy(1000, 5000, "me", B, "26.1.2", 50_000).wallStartMs() == 49_000);

        Path f = Files.createTempFile("cd", Cdrec.EXT);
        Cdrec.write(f, r, "0.1.0");
        List<String> lines = new ArrayList<>();
        try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.util.zip.GZIPInputStream(Files.newInputStream(f)), java.nio.charset.StandardCharsets.UTF_8))) {
            for (String l; (l = in.readLine()) != null; ) lines.add(l);
        }
        lines.add(2, "{\"k\":\"A\",\"t\":");
        try (var out = new java.io.OutputStreamWriter(new java.util.zip.GZIPOutputStream(Files.newOutputStream(f)),
                java.nio.charset.StandardCharsets.UTF_8)) {
            for (String l : lines) out.write(l + "\n");
        }
        check("испорченная строка пропускается", Cdrec.read(f).events().equals(r.events()));
        List<Event> many = new ArrayList<>();
        for (int i = 0; i < 3000; i++) many.add(new PingEvent(i * 10L, 40 + i % 7));
        Cdrec.write(f, new Recording(1, "me", B, "26.1.2", 0, 30_000, many), "0.1.0");
        byte[] all = Files.readAllBytes(f);
        Files.write(f, Arrays.copyOf(all, all.length / 2));
        int got = Cdrec.read(f).events().size();
        check("обрезанный файл читается до места обрыва", got > 0 && got < many.size());
        Files.write(f, Arrays.copyOf(all, 12));
        boolean threw = false;
        try {
            Cdrec.read(f);
        } catch (java.io.IOException ex) {
            threw = true;
        }
        check("файл без единой строки не читается", threw);
        Files.deleteIfExists(f);

        var F = InputEvent.Input.FORWARD;
        WTap.Result w = WTap.analyze(List.of(hitW(0, true), in(100, F, false), in(160, F, true),
                atk(300, A, Outcome.MISS, 3.5, 1f), hitW(600, true)));
        check("W-tap: отсчёт от прошлого попадания, а не от промаха", w.hits().size() == 1
                && w.hits().get(0).method() == WTap.Method.W_TAP && w.hits().get(0).sincePrevMs() == 600);

        Stats.JumpStats goodJr = new Stats.JumpStats(10, 8, 1, 1, 0, 0, 0, 80, 100, 40, 40, 20, 40, 0, Double.NaN, Double.NaN);
        Stats.HitStats goodHits = new Stats.HitStats(25, 0, 3, 1, 89, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.95, 0, Map.of(), 0, Double.NaN, Double.NaN);
        var praise = Insights.evaluate(Insights.defaults(), insightIn(goodJr, goodHits), 10);
        check("похвала: jump reset и точность", hasInsight(praise, "jr-good") && hasInsight(praise, "accuracy-good")
                && praise.stream().allMatch(i -> i.tone() == Insights.Tone.GOOD));
        Stats.HitStats lowCharge = new Stats.HitStats(25, 0, 3, 1, 89, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.5, 0, Map.of(), 0, Double.NaN, Double.NaN);
        var mixed = Insights.evaluate(insightIn(goodJr, lowCharge));
        check("похвала идёт после замечаний", mixed.get(0).id().equals("low-charge")
                && mixed.get(mixed.size() - 1).tone() == Insights.Tone.GOOD);
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL " + what); }
    }

    static boolean near(double a, double b, double eps) { return Math.abs(a - b) <= eps; }

    static final long MS = 1_000_000L;
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final AttackFlags GROUND = new AttackFlags(false, true, 0, false, false, false, false, true, true);

    static AttackEvent atk(long t, UUID who, Outcome o, double reach, float str) {
        return new AttackEvent(t, 0, who, who == A ? "Alpha" : "Beta", o, HitType.NORMAL, GROUND, str, reach,
                5, 3, -4, 0.1, 0.6, 40, t - 10, o == Outcome.MISS ? reach + 0.2 : reach);
    }

    static IncomingHitEvent hit(long t, boolean grounded) {
        return new IncomingHitEvent(t, 0, A, "Alpha", grounded, false, true, 40);
    }

    static void bufferClock() {
        BufferClock c = new BufferClock();
        check("часы стоят до resume", c.activeMs(10 * MS) == 0);
        c.resume(0);
        check("часы идут", c.activeMs(5000 * MS) == 5000);
        c.pause(5000 * MS);
        check("пауза держит значение", c.activeMs(99_000 * MS) == 5000);
        c.resume(100_000 * MS);
        check("продолжение с того же места", c.activeMs(102_000 * MS) == 7000);
        c.reset(102_000 * MS);
        check("сброс на 0:00", c.activeMs(103_000 * MS) == 1000);
    }

    static void bufferTrimAndSnapshot() {
        RecordingBuffer b = new RecordingBuffer();
        long cap = 10 * 60_000L;
        b.add(new SegmentEvent(0, "play.example.net", "overworld", 1000));
        for (long t = 1000; t <= 30 * 60_000L; t += 60_000L) b.add(atk(t, A, Outcome.HIT_CONFIRMED, 2.5, 1f));
        b.add(atk(500, A, Outcome.MISS, 3.5, 1f));
        Recording r = b.snapshot(30 * 60_000L, cap, "Me", B, "26.1.2", 5_000_000L);
        check("длительность = ёмкость", r.durationMs() == cap);
        check("первое событие сегмент перенесён в 0", r.events().get(0) instanceof SegmentEvent s && s.t() == 0
                && "play.example.net".equals(s.server()));
        boolean inRange = true, sorted = true;
        long prev = -1;
        for (Event e : r.events()) {
            if (e.t() < 0 || e.t() > cap) inRange = false;
            if (e.t() < prev) sorted = false;
            prev = e.t();
        }
        check("время нормализовано в [0; ёмкость]", inRange);
        check("порядок по времени", sorted);
        check("старые события вытеснены", r.events().size() == 1 + 10);
        RecordingBuffer b2 = new RecordingBuffer();
        b2.add(atk(2000, A, Outcome.HIT_CONFIRMED, 2, 1f));
        Recording r2 = b2.snapshot(5000, cap, "Me", B, "26.1.2", 0);
        check("короткий буфер: длительность = время", r2.durationMs() == 5000 && r2.events().get(0).t() == 2000);
        check("пустой буфер без боевых событий", !new RecordingBuffer().hasCombatEvents());
        AttackEvent a = (AttackEvent) atk(1000, A, Outcome.HIT_CONFIRMED, 2, 1f).withT(300);
        check("withT сдвигает и clickT", a.clickT() == 290);
        RecordingBuffer b3 = new RecordingBuffer();
        b3.add(new SegmentEvent(0, "play.example.net", "overworld", 1000));
        for (long t = 1000; t <= 30 * 60_000L; t += 60_000L) b3.add(atk(t, A, Outcome.HIT_CONFIRMED, 2.5, 1f));
        int before = b3.size();
        Recording c = b3.copy(30 * 60_000L, cap, "Me", B, "26.1.2", 5_000_000L);
        check("copy не урезает буфер", b3.size() == before);
        check("copy совпадает со snapshot", c.equals(b3.snapshot(30 * 60_000L, cap, "Me", B, "26.1.2", 5_000_000L)));
        check("copy переносит сегмент в 0", c.events().get(0) instanceof SegmentEvent s0 && s0.t() == 0);
    }

    static AttackEvent tb(double reach, double selfV, double tgtV, int ping, int tgtPing, double range) {
        return new AttackEvent(100, 0, A, "Alpha", Outcome.HIT_CONFIRMED, HitType.SPRINT, GROUND, 1f, reach, 2, 1, 1,
                0, 0.6, ping, -1, reach, range, selfV, tgtV, tgtPing);
    }

    static void threeBlockHit() {
        int ip = ThreeBlockHit.DEFAULT_INTERP_MS;
        check("задержка L = (50+50)/2 + 100", near(ThreeBlockHit.latencyMs(tb(2.9, 0, 0, 50, 50, 3), ip), 150, 1e-9));
        AttackEvent headOn = tb(2.9, 6, -6, 50, 50, 3);
        check("бег навстречу: преимущества нет", near(ThreeBlockHit.opponentDistance(headOn, ip), 2.9, 1e-9)
                && !ThreeBlockHit.is(headOn, ip));
        AttackEvent chase = tb(2.9, 6, 5.6, 50, 50, 3);
        check("погоня: дистанция у цели больше", near(ThreeBlockHit.opponentDistance(chase, ip), 2.9 + 0.15 * 11.6, 1e-9)
                && ThreeBlockHit.is(chase, ip));
        AttackEvent strafe = tb(2.9, 0.2, -0.1, 50, 50, 3);
        check("стрейф: около нуля", Math.abs(ThreeBlockHit.opponentDistance(strafe, ip) - 2.9) < 0.05
                && !ThreeBlockHit.is(strafe, ip));
        check("пинг цели неизвестен: не классифицируется", !ThreeBlockHit.classifiable(tb(2.9, 6, 5.6, 50, -1, 3), ip));
        check("старая запись без данных: не классифицируется",
                !ThreeBlockHit.classifiable(atk(100, A, Outcome.HIT_CONFIRMED, 2.9, 1f), ip));
        AttackEvent miss = tb(2.9, 6, 5.6, 50, 50, 3).withOutcome(Outcome.MISS);
        check("промах не бывает 3 block hit", !ThreeBlockHit.is(miss, ip));
        check("порог по дальности из события", !ThreeBlockHit.is(tb(2.9, 3, 0, 50, 50, 4.0), ip)
                && ThreeBlockHit.is(tb(2.9, 3, 0, 50, 50, 3.0), ip));
        check("интерполяция из настроек влияет", !ThreeBlockHit.is(tb(2.9, 3, 0, 0, 0, 3.0), 0)
                && ThreeBlockHit.is(tb(2.9, 3, 0, 0, 0, 3.0), 100));
    }

    static void geometry() {
        Geometry.Aabb box = new Geometry.Aabb(-0.3, 0, 2.7, 0.3, 1.8, 3.3);
        Geometry.V3 eye = new Geometry.V3(0, 1.6, 0), look = new Geometry.V3(0, 0, 1);
        check("ближайшая точка хитбокса", near(box.nearestDistance(eye), 2.7, 1e-9));
        check("пересечение луча", near(box.rayDistance(eye, look), 2.7, 1e-9));
        check("луч мимо", Double.isNaN(box.rayDistance(eye, new Geometry.V3(1, 0, 0))));
        Geometry.Aim aim = Geometry.aim(eye, look, box.center());
        double expect = Math.toDegrees(Math.atan2(0.7, 3.0));
        check("отклонение по pitch (цель ниже)", near(aim.pitch(), expect, 1e-6) && near(aim.yaw(), 0, 1e-9));
        check("общее отклонение", near(aim.total(), expect, 1e-6));
        Geometry.Aim right = Geometry.aim(new Geometry.V3(0, 0.9, 0), look, new Geometry.V3(-1, 0.9, 3));
        check("цель правее прицела даёт плюс yaw", right.yaw() > 0);
        check("wrap180", near(Geometry.wrap180(190), -170, 1e-9) && near(Geometry.wrap180(-190), 170, 1e-9));
        double[] uv = Geometry.hitLocal(box, new Geometry.V3(-0.3, 1.8, 2.7), look);
        check("точка на хитбоксе: правый край, макушка", near(uv[0], 1, 1e-9) && near(uv[1], 1, 1e-9));
    }

    static IncomingHitEvent kbHit(long t, double kb) {
        return new IncomingHitEvent(t, 0, A, "Alpha", true, false, true, 40, kb);
    }

    static void calibration() {
        var p = JumpResetEvaluator.Params.DEFAULT;
        List<JumpResetEvaluator.Attempt> at = new ArrayList<>();
        long t = 0;
        for (long d = -200; d <= 200; d += 5) {
            t += 1000;
            double kb = d >= 30 && d <= 110 ? 0.5 : 2.0;
            at.add(new JumpResetEvaluator.Attempt(kbHit(t, kb), true, t + d, d, JumpResetEvaluator.classify(d, p), false));
        }
        for (int i = 0; i < 4; i++) {
            t += 1000;
            at.add(new JumpResetEvaluator.Attempt(kbHit(t, 2.5), false, -1, 0, JumpResetEvaluator.Result.NO_ATTEMPT, false));
        }
        at.add(new JumpResetEvaluator.Attempt(kbHit(t + 1000, Double.NaN), true, t + 1040, 40,
                JumpResetEvaluator.Result.SUCCESS, false));
        var r = Calibration.analyze(at, p);
        check("калибровка: попытки без отлёта не считаются", r.samples() == 81);
        check("калибровка: отлёт без прыжка", r.baselineN() == 4 && near(r.baselineKb(), 2.5, 1e-9));
        check("калибровка: окно с наименьшим отлётом", r.hasRecommendation() && r.recLower() == 30 && r.recUpper() == 110
                && near(r.recKb(), 0.5, 1e-9));
        check("калибровка: отлёт в текущем окне 0..80", r.curN() == 17 && r.curKb() > 0.5 && r.curKb() < 2.0);
        check("калибровка: корзины по 20 мс", r.bins().size() == 20 && r.bins().get(0).fromMs() == -200
                && r.bins().get(0).n() == 4 && near(r.bins().get(0).meanKb(), 2.0, 1e-9));
        var few = Calibration.analyze(at.subList(0, 10), p);
        check("калибровка: мало данных, без рекомендации", !few.hasRecommendation());
        check("отлёт сохраняется в .cdrec", Cdrec.decode(Cdrec.encode(kbHit(5, 1.25))).equals(kbHit(5, 1.25))
                && Double.isNaN(((IncomingHitEvent) Cdrec.decode(Cdrec.encode(hit(5, true)))).kbAway()));
    }

    static void chartMath() {
        var all = ChartMath.View.all(60_000);
        check("время в пиксели и обратно", near(ChartMath.timeToPx(30_000, all, 10, 600), 310, 1e-9)
                && ChartMath.pxToTime(310, all, 10, 600) == 30_000);
        check("значение в пиксели: max сверху, min снизу", near(ChartMath.valueToPx(6, 0, 6, 20, 100), 20, 1e-9)
                && near(ChartMath.valueToPx(0, 0, 6, 20, 100), 120, 1e-9));
        check("выброс прижат к краю", near(ChartMath.valueToPx(9, 0, 6, 20, 100), 20, 1e-9) && ChartMath.outside(9, 0, 6)
                && !ChartMath.outside(3, 0, 6));
        var z = ChartMath.zoom(all, 15_000, 0.5, 5_000, 60_000);
        check("зум вдвое вокруг курсора: точка остаётся на месте", z.span() == 30_000
                && near(ChartMath.timeToPx(15_000, z, 0, 600), ChartMath.timeToPx(15_000, all, 0, 600), 1.0));
        check("зум не уже минимума", ChartMath.zoom(all, 30_000, 0.0001, 5_000, 60_000).span() == 5_000);
        check("отдаление не шире записи", ChartMath.zoom(z, 15_000, 10, 5_000, 60_000).equals(all));
        var zl = ChartMath.zoom(all, 0, 0.5, 5_000, 60_000);
        check("зум у левого края не уходит в минус", zl.from() == 0 && zl.to() == 30_000);
        check("сдвиг в пределах записи", ChartMath.pan(z, 1_000_000, 60_000).equals(new ChartMath.View(30_000, 60_000))
                && ChartMath.pan(z, -1_000_000, 60_000).from() == 0);
        check("шаг подписей: минута на 10 мин при 500 px", ChartMath.timeStep(600_000, 500, 40) == 60_000);
        check("шаг подписей: секунда на 10 с", ChartMath.timeStep(10_000, 500, 40) == 1_000);
        check("первая подпись кратна шагу", ChartMath.firstTick(61_000, 30_000) == 90_000 && ChartMath.firstTick(0, 5_000) == 0);
        double[] xs = {10, 50, Double.NaN, 52}, ys = {10, 50, 50, 54};
        check("ближайшая точка", ChartMath.nearest(xs, ys, 51, 51, 5) == 1);
        check("точка дальше радиуса не находится", ChartMath.nearest(xs, ys, 100, 100, 5) == -1);
    }

    static void classifier() {
        AttackFlags air = new AttackFlags(false, false, 0.5, false, false, false, false, true, false);
        AttackFlags sprint = new AttackFlags(true, true, 0, false, false, false, false, true, false);
        AttackFlags sprintAir = new AttackFlags(true, false, 0.5, false, false, false, false, true, false);
        AttackFlags walk = new AttackFlags(false, true, 0, false, false, false, false, true, false);
        AttackFlags noSword = new AttackFlags(false, true, 0, false, false, false, false, false, true);
        check("WEAK при недозаряде", HitClassifier.classify(0.5f, air) == HitType.WEAK);
        check("CRIT в падении", HitClassifier.classify(1f, air) == HitType.CRIT);
        check("SPRINT на бегу", HitClassifier.classify(1f, sprint) == HitType.SPRINT);
        check("спринт исключает крит", HitClassifier.classify(1f, sprintAir) == HitType.SPRINT);
        check("SWEEP стоя с мечом", HitClassifier.classify(1f, GROUND) == HitType.SWEEP);
        check("NORMAL при быстром шаге", HitClassifier.classify(1f, walk) == HitType.NORMAL);
        check("NORMAL без меча", HitClassifier.classify(1f, noSword) == HitType.NORMAL);
        check("порог 0.9 не включительно", HitClassifier.classify(0.9f, GROUND) == HitType.WEAK);
    }

    static void jumpReset() {
        var p = JumpResetEvaluator.Params.DEFAULT;
        List<Event> ev = new ArrayList<>(List.of(
                hit(1000, true), new JumpEvent(1040, 0, true, -1),
                hit(3000, true), new JumpEvent(3100, 0, true, -1),
                hit(5000, true), new JumpEvent(4950, 0, true, -1),
                hit(7000, true),
                hit(9000, false),
                hit(11000, true), new JumpEvent(11300, 0, true, -1),
                new IncomingHitEvent(13000, 0, A, "Alpha", true, false, false, 40), new JumpEvent(13040, 0, true, -1)));
        ev.sort(Comparator.comparingLong(Event::t));
        var at = JumpResetEvaluator.evaluate(ev, p);
        check("попыток по числу ударов", at.size() == 7);
        check("+40 мс успех", at.get(0).result() == JumpResetEvaluator.Result.SUCCESS && at.get(0).deltaMs() == 40);
        check("+100 мс поздно", at.get(1).result() == JumpResetEvaluator.Result.TOO_LATE);
        check("-50 мс рано", at.get(2).result() == JumpResetEvaluator.Result.TOO_EARLY && at.get(2).deltaMs() == -50);
        check("на земле без прыжка", at.get(3).result() == JumpResetEvaluator.Result.NO_ATTEMPT);
        check("в воздухе без прыжка", at.get(4).result() == JumpResetEvaluator.Result.NOT_APPLICABLE);
        check("прыжок дальше 200 мс не считается", at.get(5).result() == JumpResetEvaluator.Result.NO_ATTEMPT);
        check("без нокбэка не считается", at.get(6).result() == JumpResetEvaluator.Result.NOT_APPLICABLE);
        List<Event> two = List.of(hit(1000, true), hit(1030, true), new JumpEvent(1060, 0, true, -1));
        var t2 = JumpResetEvaluator.evaluate(two, p);
        check("один прыжок не засчитывается двум ударам", t2.get(0).hasJump() != t2.get(1).hasJump());
        var custom = new JumpResetEvaluator.Params(0, 120, 200, 200, false);
        check("пересчёт с другим окном", JumpResetEvaluator.evaluate(ev, custom).get(1).result() == JumpResetEvaluator.Result.SUCCESS);
        flyOut();
    }

    static void stats() {
        List<Event> ev = new ArrayList<>();
        ev.add(new PingEvent(0, 30));
        ev.add(atk(100, A, Outcome.HIT_CONFIRMED, 2.0, 1f));
        ev.add(atk(700, A, Outcome.HIT_CONFIRMED, 3.0, 1f));
        ev.add(atk(1300, A, Outcome.HIT_CONFIRMED, 3.2, 0.5f));
        ev.add(atk(1900, A, Outcome.MISS, 3.6, 1f));
        ev.add(atk(2500, B, Outcome.HIT_UNCONFIRMED, 2.9, 1f));
        ev.add(hit(3000, true));
        ev.add(new JumpEvent(3040, 0, true, -1));
        ev.add(new PingEvent(4000, 50));
        Recording r = new Recording(1, "Me", null, "26.1.2", 0, 5000, ev);
        var s = Stats.compute(r, JumpResetEvaluator.Params.DEFAULT, Stats.Filter.ALL, 3.0);
        check("подтверждённые / неподтверждённые / промахи", s.hits().confirmed() == 3 && s.hits().unconfirmed() == 1
                && s.hits().misses() == 1);
        check("процент попаданий 3/5", near(s.hits().hitRatePct(), 60, 1e-9));
        check("средняя дистанция", near(s.hits().reachMean(), (2.0 + 3.0 + 3.2) / 3, 1e-9));
        check("медиана и максимум", near(s.hits().reachMedian(), 3.0, 1e-9) && near(s.hits().reachMax(), 3.2, 1e-9));
        check("доля дальше 3.0", near(s.hits().sharePastRangePct(), 100.0 / 3, 1e-9));
        check("заряд: доля полных", near(s.hits().fullChargePct(), 200.0 / 3, 1e-9));
        check("отклонение yaw по модулю", near(s.hits().aimYawAbsMean(), 3, 1e-9) && near(s.hits().aimPitchBias(), -4, 1e-9));
        check("комбо", s.combo().maxCombo() == 3 && s.combo().dealt() == 3 && s.combo().taken() == 1);
        check("jump reset в статистике", s.jump().success() == 1 && near(s.jump().meanSuccessMs(), 40, 1e-9));
        check("пинг средний", near(s.pingMean(), 40, 1e-9));
        check("противники по убыванию активности", s.opponents().get(0).uuid().equals(A) && s.opponents().size() == 2);
        var onlyB = Stats.compute(r, JumpResetEvaluator.Params.DEFAULT, new Stats.Filter(0, Long.MAX_VALUE, B), 3.0);
        check("фильтр по противнику", onlyB.hits().unconfirmed() == 1 && onlyB.hits().confirmed() == 0);
        var range = Stats.compute(r, JumpResetEvaluator.Params.DEFAULT, new Stats.Filter(0, 1000, null), 3.0);
        check("фильтр по времени", range.hits().confirmed() == 2);
        check("промах дальше дальности (луч 3.8 > 3.0)", s.hits().missesOutOfRange() == 1);
        List<Event> close = new ArrayList<>(ev);
        close.add(new AttackEvent(2600, 0, A, "Alpha", Outcome.HIT_CONFIRMED, HitType.SPRINT, GROUND, 1f, 0.2, 35, 30, 20,
                0, 0.8, 40, -1, 0.2));
        var sc = Stats.compute(new Recording(1, "Me", null, "26.1.2", 0, 5000, close), JumpResetEvaluator.Params.DEFAULT,
                Stats.Filter.ALL, 3.0);
        check("удар вплотную не портит статистику прицела", sc.hits().aimSamples() == 3
                && near(sc.hits().aimYawAbsMean(), 3, 1e-9) && sc.hits().confirmed() == 4);
        AttackEvent aimMiss = new AttackEvent(1, 0, A, "Alpha", Outcome.MISS, HitType.NORMAL, GROUND, 1f, 2.5, 20, 18, 5,
                -1, 0.5, 40, -1, Double.NaN);
        check("промах прицелом не считается недотянутым", !aimMiss.outOfRangeMiss(3.0));
        check("разброс пинга", near(s.pingSigma(), 10, 1e-9));
        check("разброс дистанции", near(s.hits().reachSigma(), Stats.sigma(new double[]{2.0, 3.0, 3.2}), 1e-9));
        check("средняя высота попадания", near(s.hits().hitVMean(), 0.6, 1e-9));
        check("размен 3 к 1", near(s.combo().trade(), 3, 1e-9));
        check("старые удары не попадают в 3 block hit", s.threeBlock().classified() == 0);
        List<Event> tbe = new ArrayList<>(List.of(tb(2.9, 6, -6, 50, 50, 3), tb(2.9, 6, 5.6, 50, 50, 3),
                tb(2.9, 6, 5.6, 50, -1, 3), tb(3.4, 0, 0, 50, 50, 4.0)));
        var st = Stats.compute(new Recording(1, "Me", null, "26.1.2", 0, 5000, tbe), JumpResetEvaluator.Params.DEFAULT,
                Stats.Filter.ALL, 3.0, 100);
        check("3 block hit: 1 из 3 классифицированных", st.threeBlock().classified() == 3 && st.threeBlock().count() == 1
                && near(st.threeBlock().sharePct(), 100.0 / 3, 1e-9));
        check("дальность из события: 3.4 при 4.0 не дальше порога", near(st.hits().sharePastRangePct(), 0, 1e-9));
        var evB = Stats.events(r, new Stats.Filter(0, Long.MAX_VALUE, B));
        check("фильтр событий: удары по другим убраны, пинг и прыжки остались", evB.stream()
                .noneMatch(e -> e instanceof AttackEvent a && !B.equals(a.target())) && evB.stream().anyMatch(e -> e instanceof PingEvent)
                && evB.stream().anyMatch(e -> e instanceof JumpEvent));
        List<Event> fe = new ArrayList<>(ev);
        fe.add(atk(20_000, B, Outcome.HIT_CONFIRMED, 2.5, 1f));
        fe.add(atk(21_000, B, Outcome.MISS, 3.5, 1f));
        int[] hist = Stats.histogram(new double[]{-20, -1, 0.5, 1, 1.9, 2, 50, Double.NaN}, -2, 2, 4);
        check("гистограмма: корзины и выбросы в крайних", Arrays.equals(hist, new int[]{1, 1, 1, 4}));
        check("гистограмма: пустой ввод", Arrays.equals(Stats.histogram(new double[0], 0, 1, 3), new int[]{0, 0, 0}));
        List<Event> ce = new ArrayList<>(List.of(atk(1, A, Outcome.HIT_CONFIRMED, 2, 1f), atk(2, A, Outcome.MISS, 3, 1f),
                atk(3, A, Outcome.HIT_CONFIRMED, 2, 1f), hit(4, true), hit(5, true), atk(6, A, Outcome.HIT_CONFIRMED, 2, 1f)));
        check("длины комбо: промах не рвёт, удар по нам рвёт", Stats.comboLengths(ce).equals(List.of(2, 1)));
        var fights = Stats.fights(fe, Stats.FIGHT_GAP_MS);
        check("бои делятся по паузе 10 с", fights.size() == 2 && fights.get(0).startMs() == 100 && fights.get(0).endMs() == 3000
                && fights.get(0).dealt() == 3 && fights.get(0).taken() == 1 && fights.get(0).opponents().size() == 2);
        check("второй бой", fights.get(1).attacks() == 2 && fights.get(1).dealt() == 1
                && fights.get(1).opponents().equals(List.of("Beta")));
    }

    static void settings() throws Exception {
        Path dir = Files.createTempDirectory("cdset");
        Path f = dir.resolve("combatdata.json");
        Settings def = Settings.load(f);
        check("нет файла: настройки по умолчанию", def.bufferMinutes == 10 && def.hudVisible && def.capacityMs() == 600_000L);
        Settings s = new Settings();
        s.bufferMinutes = 25;
        s.hudX = 0.8;
        s.hudY = 0.1;
        s.hudScale = 1.25;
        s.hudVisible = false;
        s.save(f);
        Settings back = Settings.load(f);
        check("настройки сохраняются и читаются", back.bufferMinutes == 25 && !back.hudVisible && near(back.hudX, 0.8, 1e-12)
                && near(back.hudScale, 1.25, 1e-12));
        Files.writeString(f, "{\"bufferMinutes\": 999, \"hudX\": -5, \"hudScale\": \"большой\", \"лишнее\": 1}");
        Settings cl = Settings.load(f);
        check("значения вне диапазона обрезаются, мусор игнорируется", cl.bufferMinutes == 120 && near(cl.hudX, 0, 1e-12)
                && near(cl.hudScale, 1.0, 1e-12));
        Files.writeString(f, "{сломанный json");
        check("повреждённый файл: настройки по умолчанию", Settings.load(f).bufferMinutes == 10);
        Settings live = new Settings(), draft = live.copy();
        check("копия без отличий", draft.diffCount(live) == 0);
        draft.bufferMinutes = 30;
        draft.jrUpperMs = 120;
        draft.hudX = 0.5;
        check("отличия считаются, положение надписи нет", draft.diffCount(live) == 2);
        live.copyFrom(draft);
        check("применение черновика", live.bufferMinutes == 30 && live.jumpParams().upperMs() == 120 && live.diffCount(draft) == 0);
        Settings jr = new Settings();
        jr.jrLowerMs = 150;
        jr.jrUpperMs = 100;
        jr.clamp();
        check("верхняя граница окна не ниже нижней", jr.jrUpperMs == 150);
        check("параметры jump reset по умолчанию", new Settings().jumpParams().equals(JumpResetEvaluator.Params.DEFAULT));
        Settings ip = new Settings();
        ip.interpMs = 999;
        check("интерполяция обрезается до 300", ip.clamp().interpMs == 300);
        ip.interpMs = 150;
        ip.save(f);
        check("интерполяция сохраняется", Settings.load(f).interpMs == 150 && new Settings().interpMs == 100);
        Settings hv = new Settings();
        check("вид надписи по умолчанию как раньше", hv.hudBgOpacity == 60 && hv.hudFormat == Settings.HUD_FULL
                && hv.hudShowDot && hv.hudBlink && !hv.hudShadow && !hv.hudHideWhenPaused);
        hv.hudBgOpacity = 150;
        hv.hudFormat = 7;
        hv.hudTextColor = -1;
        hv.clamp();
        check("вид надписи: значения вне диапазона", hv.hudBgOpacity == 100 && hv.hudFormat == Settings.HUD_FULL
                && hv.hudTextColor == Settings.HUD_COLOR_STATE);
        hv.hudBgOpacity = 20;
        hv.hudFormat = Settings.HUD_DOT;
        hv.hudShadow = true;
        hv.hudHideWhenPaused = true;
        hv.save(f);
        Settings hb = Settings.load(f);
        check("вид надписи сохраняется", hb.hudBgOpacity == 20 && hb.hudFormat == Settings.HUD_DOT && hb.hudShadow
                && hb.hudHideWhenPaused && hb.diffCount(hv) == 0);
        Settings hc = new Settings();
        hc.copyFrom(hv);
        check("вид надписи копируется", hc.diffCount(hv) == 0);
        Settings st = new Settings();
        check("хранилище и звук по умолчанию", st.storageLimitMb == 0 && st.autoDeleteDays == 0 && st.soundOnSave && st.soundVolume == 50);
        st.storageLimitMb = -5;
        st.autoDeleteDays = 99_999;
        st.soundVolume = 300;
        st.clamp();
        check("хранилище и звук: значения вне диапазона", st.storageLimitMb == 0 && st.autoDeleteDays == 3650 && st.soundVolume == 100);
        st.storageLimitMb = 500;
        st.autoDeleteDays = 30;
        st.soundOnSave = false;
        st.save(f);
        Settings sb = Settings.load(f);
        check("хранилище и звук сохраняются", sb.storageLimitMb == 500 && sb.autoDeleteDays == 30 && !sb.soundOnSave && sb.diffCount(st) == 0);
        Settings sc = new Settings();
        sc.copyFrom(st);
        check("хранилище и звук копируются", sc.diffCount(st) == 0);
        Settings lg = new Settings();
        check("язык по умолчанию: авто", lg.language == Lang.AUTO);
        lg.language = 9;
        check("язык: мусор даёт авто", lg.clamp().language == Lang.AUTO);
        lg.language = Lang.EN;
        lg.save(f);
        check("язык сохраняется", Settings.load(f).language == Lang.EN);
        Files.deleteIfExists(f);
        Files.deleteIfExists(dir);
    }

    static void jsonAndCdrec() throws Exception {
        Object parsed = Json.parse("{\"a\":[1,2.5,-3e2],\"s\":\"При\\\"вет\\n\\u0041\",\"b\":true,\"n\":null}");
        check("JSON разбор", parsed instanceof Map<?, ?> m && ((List<?>) m.get("a")).size() == 3
                && "При\"вет\nA".equals(m.get("s")) && Boolean.TRUE.equals(m.get("b")) && m.containsKey("n"));
        check("JSON запись и чтение строки", "x\"y\\z".equals(Json.parse(Json.write("x\"y\\z"))));
        List<Event> ev = new ArrayList<>(List.of(
                new SegmentEvent(0, "play.example.net", "minecraft:overworld", 1_700_000_000_000L),
                new AttackEvent(120, 7, A, "Älpha_Ник", Outcome.HIT_CONFIRMED, HitType.CRIT,
                        new AttackFlags(false, false, 0.75, false, false, false, false, true, false), 0.95f, 2.875,
                        4.25, -1.5, 3.0, Double.NaN, 0.5, 38, 110, 2.875),
                new IncomingHitEvent(500, 12, B, "Beta", true, true, true, 41),
                new JumpEvent(540, 13, true, 530),
                new PingEvent(1000, 42),
                new MarkerEvent(1500, "хороший комбо")));
        Recording r = new Recording(1, "Me", B, "26.1.2", 1_700_000_000_000L, 2000, ev);
        Path f = Files.createTempFile("cd", Cdrec.EXT);
        Cdrec.write(f, r, "0.1.0");
        Recording back = Cdrec.read(f);
        check("запись читается без потерь", back.equals(r));
        Cdrec.Header h = Cdrec.readHeader(f);
        check("заголовок со сводкой", h.attacks() == 1 && h.hitsConfirmed() == 1 && h.incoming() == 1 && h.jumps() == 1
                && h.servers().equals(List.of("play.example.net")) && h.opponents().size() == 2);
        check("неизвестный тип события пропускается", Cdrec.decode(Map.of("k", "Z", "t", 1.0)) == null);
        AttackEvent full = tb(2.9, 6, 5.6, 50, 70, 3.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> viaJson = (Map<String, Object>) Json.parse(Json.write(Cdrec.encode(full)));
        check("новые поля удара сохраняются", full.equals(Cdrec.decode(viaJson)));
        Map<String, Object> old = new LinkedHashMap<>(Cdrec.encode(atk(100, A, Outcome.HIT_CONFIRMED, 2.5, 1f)));
        old.remove("range");
        old.remove("selfV");
        old.remove("tgtV");
        old.remove("tgtPing");
        AttackEvent oa = (AttackEvent) Cdrec.decode(old);
        check("старый удар без новых ключей читается", Double.isNaN(oa.range()) && Double.isNaN(oa.selfTowardBps())
                && oa.targetPingMs() == -1 && near(oa.rangeOr(3.0), 3.0, 1e-12));
        Files.deleteIfExists(f);
    }

    static RecordingQuery.Item item(int i, String name, long time, long dur, int hits, int attacks, long size, boolean fav) {
        return new RecordingQuery.Item(i, name, name + " eu.stray.gg Beta", time, dur, hits, attacks, size, fav, false);
    }

    static void recordingsList() throws Exception {
        List<RecordingQuery.Item> items = List.of(
                item(0, "Тренировка", 3000, 60_000, 10, 20, 500, false),
                item(1, "дуэль с Alpha", 1000, 120_000, 5, 5, 900, true),
                item(2, "2026-10-01_12-00-00_hypixel", 2000, 30_000, 30, 100, 100, false));
        check("поиск: пустой запрос пропускает всё", RecordingQuery.apply(items, "  ", RecordingQuery.Sort.NEWEST, false).size() == 3);
        check("поиск без учёта регистра", RecordingQuery.apply(items, "ТРЕН", RecordingQuery.Sort.NEWEST, false).size() == 1);
        check("поиск: все слова должны встретиться", RecordingQuery.apply(items, "alpha дуэль", RecordingQuery.Sort.NEWEST, false).size() == 1
                && RecordingQuery.apply(items, "alpha hypixel", RecordingQuery.Sort.NEWEST, false).isEmpty());
        check("только избранные", RecordingQuery.apply(items, "", RecordingQuery.Sort.NEWEST, true).stream()
                .map(RecordingQuery.Item::index).toList().equals(List.of(1)));
        java.util.function.Function<RecordingQuery.Sort, List<Integer>> order = s ->
                RecordingQuery.apply(items, "", s, false).stream().map(RecordingQuery.Item::index).toList();
        check("сортировка: новые", order.apply(RecordingQuery.Sort.NEWEST).equals(List.of(0, 2, 1)));
        check("сортировка: старые", order.apply(RecordingQuery.Sort.OLDEST).equals(List.of(1, 2, 0)));
        check("сортировка: длинные", order.apply(RecordingQuery.Sort.LONGEST).equals(List.of(1, 0, 2)));
        check("сортировка: попадания", order.apply(RecordingQuery.Sort.MOST_HITS).equals(List.of(2, 0, 1)));
        check("сортировка: точность", order.apply(RecordingQuery.Sort.ACCURACY).equals(List.of(1, 0, 2)));
        check("сортировка: имя", order.apply(RecordingQuery.Sort.NAME).equals(List.of(2, 1, 0)));
        check("сортировка: размер", order.apply(RecordingQuery.Sort.LARGEST).equals(List.of(1, 0, 2)));

        long day = Retention.DAY_MS, now = 100 * day;
        List<Retention.FileInfo> files = List.of(
                new Retention.FileInfo("a", 400, now - 50 * day, false),
                new Retention.FileInfo("b", 300, now - 40 * day, true),
                new Retention.FileInfo("c", 200, now - 10 * day, false),
                new Retention.FileInfo("d", 100, now - 1 * day, false));
        check("автоудаление выключено", Retention.toDelete(files, 0, 0, now).isEmpty());
        check("по возрасту: избранная остаётся", Retention.toDelete(files, 0, 30, now).equals(List.of("a")));
        check("по месту: удаляются старые, пока не влезет", Retention.toDelete(files, 650, 0, now).equals(List.of("a")));
        check("по месту: избранная не удаляется даже сверх лимита", Retention.toDelete(files, 350, 0, now).equals(List.of("a", "c")));
        check("самая новая запись остаётся всегда", Retention.toDelete(files, 1, 0, now).equals(List.of("a", "c"))
                && Retention.toDelete(List.of(new Retention.FileInfo("x", 999, 0, false)), 1, 1, now).isEmpty());
        check("размер всех записей", Retention.totalBytes(files) == 1000);

        Path dir = Files.createTempDirectory("cdfav");
        Path f = dir.resolve("favorites.json");
        Favorites fav = Favorites.load(f);
        check("избранное: нет файла", fav.size() == 0);
        fav.set("одна", true);
        fav.set("две", true);
        fav.set("две", false);
        fav.renamed("одна", "первая");
        fav.save(f);
        Favorites back = Favorites.load(f);
        check("избранное сохраняется и следует за переименованием", back.size() == 1 && back.has("первая") && !back.has("одна"));
        Files.writeString(f, "{битый");
        check("избранное: повреждённый файл", Favorites.load(f).size() == 0);
        Files.deleteIfExists(f);
        Files.deleteIfExists(dir);
    }

    static void lang() {
        check("язык: авто по Minecraft", !Lang.resolveEnglish(Lang.AUTO, "ru_ru") && Lang.resolveEnglish(Lang.AUTO, "en_us")
                && Lang.resolveEnglish(Lang.AUTO, "de_de") && Lang.resolveEnglish(Lang.AUTO, null));
        check("язык: выбран явно", !Lang.resolveEnglish(Lang.RU, "en_us") && Lang.resolveEnglish(Lang.EN, "ru_ru"));
        Lang.apply(Lang.EN, "ru_ru");
        boolean en = Lang.t("да", "yes").equals("yes");
        Lang.apply(Lang.RU, "en_us");
        check("перевод строки", en && Lang.t("да", "yes").equals("да"));
        check("склонение", Lang.plural(1, "запись", "записи", "записей").equals("запись")
                && Lang.plural(3, "запись", "записи", "записей").equals("записи")
                && Lang.plural(11, "запись", "записи", "записей").equals("записей")
                && Lang.plural(21, "запись", "записи", "записей").equals("запись")
                && Lang.plural(112, "запись", "записи", "записей").equals("записей")
                && Lang.plural(0, "запись", "записи", "записей").equals("записей"));
    }

    static JumpEvent jumpKeys(long t, int keys) {
        return new JumpEvent(t, 0, true, -1, keys);
    }

    static void flyOut() {
        var p = JumpResetEvaluator.Params.DEFAULT;
        int s = JumpEvent.KEY_BACK, ws = JumpEvent.KEY_FORWARD | JumpEvent.KEY_BACK, w = JumpEvent.KEY_FORWARD;
        List<Event> ev = new ArrayList<>(List.of(
                kbHit(1000, 3.0), jumpKeys(900, s),
                kbHit(3000, 0.5), jumpKeys(3040, ws),
                kbHit(5000, 0.6), jumpKeys(5040, w),
                kbHit(7000, 0.7), new JumpEvent(7040, 0, true, -1),
                kbHit(9000, 2.0)));
        ev.sort(Comparator.comparingLong(Event::t));
        var at = JumpResetEvaluator.evaluate(ev, p);
        check("fly out: S без W", at.get(0).result() == JumpResetEvaluator.Result.FLY_OUT);
        check("fly out: W+S не fly out", at.get(1).result() == JumpResetEvaluator.Result.SUCCESS);
        check("fly out: W обычная попытка", at.get(2).result() == JumpResetEvaluator.Result.SUCCESS);
        check("fly out: старая запись без клавиш как раньше", at.get(3).result() == JumpResetEvaluator.Result.SUCCESS
                && !new JumpEvent(1, 0, true, -1).keysKnown());
        var off = new JumpResetEvaluator.Params(0, 80, 200, 200, false, false);
        check("fly out выключен: обычная попытка", JumpResetEvaluator.evaluate(ev, off).get(0).result()
                == JumpResetEvaluator.Result.TOO_EARLY);
        Stats.JumpStats js = Stats.compute(new Recording(1, "me", A, "26.1.2", 0, 10_000, ev), p, Stats.Filter.ALL,
                3.0).jump();
        check("fly out не входит в попытки и успех", js.attempts() == 3 && js.success() == 3 && js.flyOut() == 1
                && near(js.successRatePct(), 100, 1e-9));
        check("fly out: средний отлёт и без прыжка", near(js.flyOutKbMean(), 3.0, 1e-9) && near(js.noJumpKbMean(), 2.0, 1e-9));
        var cal = Calibration.samples(at, p);
        check("fly out не влияет на калибровку", cal.size() == 3 && cal.stream().noneMatch(x -> x.deltaMs() == -100));
        JumpEvent j = jumpKeys(5, ws | JumpEvent.KEY_SPRINT);
        check("клавиши прыжка сохраняются в .cdrec", Cdrec.decode(Cdrec.encode(j)).equals(j)
                && ((JumpEvent) Cdrec.decode(Map.of("k", "J", "t", 1.0))).keys() == JumpEvent.KEYS_UNKNOWN);
        Settings st = new Settings();
        check("настройка fly out по умолчанию включена", st.jrSeparateFlyOut && st.jumpParams().separateFlyOut());
    }

    static void cooldown() {
        check("перезарядка: меч 12.5 тика, заряд с 11 тиков", Cooldown.chargedTicks(12.5) == 11 && Cooldown.chargedMs(12.5) == 550
                && Cooldown.fullMs(12.5) == 625);
        check("перезарядка: граница строго больше 0.9", Cooldown.chargedTicks(15) == 14 && Cooldown.chargedTicks(10) == 9
                && Cooldown.chargedTicks(20) == 18);
        check("заряжен только выше 0.9", Cooldown.charged(0.95f) && !Cooldown.charged(0.9f) && !Cooldown.charged(0.5f));
        AttackEvent old = atk(100, A, Outcome.HIT_CONFIRMED, 2.5, 1f);
        check("старая запись без перезарядки: меч", Float.isNaN(old.cooldownTicks()) && near(old.cooldownOr(), 12.5, 1e-9));
        List<Event> ev = List.of(atk(0, A, Outcome.HIT_CONFIRMED, 2.5, 1f), atk(600, A, Outcome.HIT_CONFIRMED, 2.5, 1f),
                atk(900, A, Outcome.MISS, 3.5, 0.5f), atk(1600, B, Outcome.HIT_CONFIRMED, 2.5, 1f),
                atk(5000, A, Outcome.HIT_CONFIRMED, 2.5, 1f));
        var iv = Cooldown.intervals(ev);
        check("интервалы: промах тоже атака, пауза больше 2 с рвёт", iv.size() == 3 && iv.get(0).ms() == 600
                && iv.get(1).ms() == 300 && iv.get(2).ms() == 700);
        var sm = Cooldown.summary(iv);
        check("интервалы: поспешные и лишнее ожидание", sm.n() == 3 && sm.rushed() == 1 && near(sm.waitMs(), (50 + 150) / 2.0, 1e-9)
                && near(sm.meanMs(), 1600 / 3.0, 1e-9));
        AttackEvent full = new AttackEvent(1, 0, A, "Alpha", Outcome.HIT_CONFIRMED, HitType.NORMAL, GROUND, 1f, 2.5, 5, 3, -4,
                0.1, 0.6, 40, -1, 2.5, 3.0, 1, 2, 50, 20f);
        check("перезарядка сохраняется в .cdrec", Cdrec.decode(Cdrec.encode(full)).equals(full)
                && near(full.cooldownOr(), 20, 1e-9));
    }

    static Stats.HitStats hitStats(int conf, int unconf, int miss, int oor, double strength) {
        return new Stats.HitStats(conf, unconf, miss, oor, 0, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, strength, 0, Map.of(), 0, Double.NaN, Double.NaN);
    }

    static Stats.JumpStats jumpStats(int attempts, double bias) {
        return new Stats.JumpStats(attempts, 0, 0, 0, 0, 0, 0, 0, 0, Double.NaN, Double.NaN, Double.NaN, bias, 0,
                Double.NaN, Double.NaN);
    }

    static Insights.Input insightIn(Stats.JumpStats j, Stats.HitStats h) {
        return new Insights.Input(new Stats.Result(j, h, new Stats.ComboStats(0, 0, 0, 0), List.of(), 0, 0,
                new Stats.ThreeBlockStats(0, 0, 0, Double.NaN, Double.NaN)), JumpResetEvaluator.Params.DEFAULT);
    }

    static boolean hasInsight(List<Insights.Insight> l, String id) {
        return l.stream().anyMatch(i -> i.id().equals(id));
    }

    static void insights() {
        Stats.HitStats calm = hitStats(30, 0, 2, 1, 0.97);
        Stats.JumpStats noJr = jumpStats(0, Double.NaN);
        check("выводы: спокойная запись без выводов", Insights.evaluate(insightIn(jumpStats(10, 40), calm)).isEmpty());
        check("выводы: пустая запись", Insights.evaluate(insightIn(noJr, hitStats(0, 0, 0, 0, Double.NaN))).isEmpty());
        var early = Insights.evaluate(insightIn(jumpStats(10, -40), calm));
        check("выводы: jump reset раньше окна", early.size() == 1 && early.get(0).id().equals("jr-offset")
                && early.get(0).ru().contains("раньше") && early.get(0).ru().contains("40 мс")
                && early.get(0).page() == Insights.Page.DEFENSE_JUMP);
        var late = Insights.evaluate(insightIn(jumpStats(10, 120), calm));
        check("выводы: jump reset позже окна", late.size() == 1 && late.get(0).ru().contains("позже") && late.get(0).en().contains("40 ms"));
        check("выводы: рядом с окном и внутри окна не срабатывает", Insights.evaluate(insightIn(jumpStats(10, -10), calm)).isEmpty()
                && Insights.evaluate(insightIn(jumpStats(10, 90), calm)).isEmpty());
        check("выводы: мало попыток jump reset", Insights.evaluate(insightIn(jumpStats(3, -100), calm)).isEmpty());
        check("выводы: низкий заряд", hasInsight(Insights.evaluate(insightIn(noJr, hitStats(20, 0, 0, 0, 0.7))), "low-charge"));
        check("выводы: заряд в норме или мало ударов", Insights.evaluate(insightIn(noJr, hitStats(20, 0, 0, 0, 0.9))).isEmpty()
                && Insights.evaluate(insightIn(noJr, hitStats(5, 0, 0, 0, 0.5))).isEmpty());
        var oor = Insights.evaluate(insightIn(noJr, hitStats(30, 0, 10, 10, 1)));
        check("выводы: много «не дотянулся»", oor.size() == 1 && oor.get(0).id().equals("out-of-range")
                && oor.get(0).page() == Insights.Page.ATTACK_HITS && oor.get(0).ru().contains("25%"));
        check("выводы: мало промахов не судим", Insights.evaluate(insightIn(noJr, hitStats(5, 0, 4, 4, 1))).isEmpty());
        var aim = Insights.evaluate(insightIn(noJr, hitStats(28, 0, 12, 2, 1)));
        check("выводы: много промахов прицелом", aim.size() == 1 && aim.get(0).id().equals("aim-miss")
                && aim.get(0).page() == Insights.Page.ATTACK_AIM);
        Insights.Input bad = insightIn(jumpStats(10, -200), hitStats(20, 0, 20, 8, 0.5));
        var all = Insights.evaluate(bad);
        boolean sorted = true;
        for (int i = 1; i < all.size(); i++) sorted &= all.get(i - 1).importance() >= all.get(i).importance();
        check("выводы: не больше трёх, важные первыми", all.size() == 3 && sorted
                && Insights.evaluate(Insights.defaults(), bad, 10).size() == 4);
        Lang.setEnglish(true);
        boolean en = all.get(0).text().equals(all.get(0).en());
        Lang.setEnglish(false);
        check("выводы: текст на двух языках", en && all.get(0).text().equals(all.get(0).ru()));
        List<Event> ev = new ArrayList<>();
        for (int i = 0; i < 12; i++) ev.add(atk(i * 600L, A, Outcome.MISS, 3.6, 1f));
        Stats.Result r = Stats.compute(new Recording(1, "me", B, "26.1.2", 0, 10_000, ev), JumpResetEvaluator.Params.DEFAULT,
                Stats.Filter.ALL, 3.0);
        check("выводы: по настоящей статистике", hasInsight(Insights.evaluate(new Insights.Input(r, JumpResetEvaluator.Params.DEFAULT)), "out-of-range"));
    }

    static final UUID NPC = UUID.fromString("00000000-0000-2000-8000-000000000001");

    static AttackEvent atkW(long t, UUID who, Outcome o, Weapon w, int team, int ping) {
        return new AttackEvent(t, 0, who, "Alpha", o, HitType.NORMAL, GROUND, 1f, 2.5, 5, 3, -4, 0.1, 0.6, ping, t - 10, 2.5,
                3.0, Double.NaN, Double.NaN, -1, 12.5f, w == null ? null : "minecraft:test", w, team);
    }

    static IncomingHitEvent inc(long t, UUID from, IncomingHitEvent.Kind k, String proj, int team, int ping) {
        return new IncomingHitEvent(t, 0, from, "Alpha", true, false, true, ping, 0.8, k, proj, Weapon.SWORD,
                "minecraft:iron_sword", team);
    }

    static Stats.Result statsOf(List<Event> ev, StatFilter sf) {
        return Stats.compute(new Recording(1, "me", B, "26.1.2", 0, 60_000, ev), JumpResetEvaluator.Params.DEFAULT,
                Stats.Filter.ALL, 3.0, 100, sf);
    }

    static void statFilters() throws Exception {
        StatFilter sword = new Settings().statFilter();
        check("фильтры: по умолчанию меч, без тиммейтов, NPC и пинг выкл., бой от 3",
                sword.weapon() == StatFilter.WeaponView.SWORD && sword.noTeammates() && !sword.noNpc()
                        && !sword.highPing() && sword.pingLimitMs() == 150 && sword.minFightHits() == 3 && sword.handNoTrade());

        List<Event> ev = new ArrayList<>(List.of(
                atkW(1000, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, Relation.TEAM_NONE, 40),
                atkW(1500, A, Outcome.HIT_CONFIRMED, Weapon.HAND, Relation.TEAM_NONE, 40),
                atkW(2000, A, Outcome.HIT_CONFIRMED, Weapon.HAND, Relation.TEAM_NONE, 40),
                atkW(2500, A, Outcome.HIT_CONFIRMED, Weapon.AXE, Relation.TEAM_NONE, 40),
                atkW(3000, A, Outcome.MISS, Weapon.SWORD, Relation.TEAM_NONE, 40)));
        Stats.Result s = statsOf(ev, sword), n = statsOf(ev, StatFilter.NONE);
        check("фильтр меча: рука и топор не входят", s.hits().confirmed() == 1 && s.hits().misses() == 1
                && s.combo().dealt() == 1 && n.hits().confirmed() == 4);
        check("фильтр меча: скрыто рука 2, топор 1", s.hiddenTotal() == 3 && s.hidden().get(StatFilter.Hidden.HAND) == 2
                && s.hidden().get(StatFilter.Hidden.AXE) == 1 && n.hiddenTotal() == 0);
        Stats.Result hand = statsOf(ev, StatFilter.NONE.withWeapon(StatFilter.WeaponView.HAND));
        Stats.Result other = statsOf(ev, StatFilter.NONE.withWeapon(StatFilter.WeaponView.OTHER));
        check("быстрый фильтр: рука и другое", hand.hits().confirmed() == 2 && other.hits().confirmed() == 1);

        AttackEvent oldSword = atk(100, A, Outcome.HIT_CONFIRMED, 2.5, 1f);
        AttackEvent oldOther = new AttackEvent(200, 0, A, "Alpha", Outcome.HIT_CONFIRMED, HitType.NORMAL,
                new AttackFlags(false, true, 0, false, false, false, false, false, true), 1f, 2.5, 5, 3, -4, 0.1, 0.6, 40, 190, 2.5);
        check("старые записи: sword=true это меч, false это «не меч»", oldSword.weaponOr() == Weapon.SWORD
                && oldOther.weaponOr() == Weapon.UNKNOWN);
        check("старые записи: фильтр меча", sword.attack(oldSword) == null && sword.attack(oldOther) == StatFilter.Hidden.UNKNOWN_WEAPON
                && StatFilter.NONE.withWeapon(StatFilter.WeaponView.OTHER).attack(oldOther) == null);

        AttackEvent poke = atkW(10, A, Outcome.HIT_CONFIRMED, Weapon.HAND, Relation.TEAM_NONE, 40);
        check("тычок рукой не открывает размен", !sword.opensTrade(poke)
                && sword.opensTrade(atkW(10, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, Relation.TEAM_NONE, 40))
                && StatFilter.NONE.opensTrade(poke) && !sword.opensTrade(new AttackEvent(1, 0, A, "Alpha", Outcome.HIT_CONFIRMED,
                HitType.NORMAL, GROUND, 1f, 2.5, 5, 3, -4, 0.1, 0.6, 40, 0, 2.5, 3.0, Double.NaN, Double.NaN, -1, 12.5f,
                null, Weapon.HAND, Relation.TEAM_NONE)));

        List<Event> rod = new ArrayList<>(List.of(
                inc(10_000, A, IncomingHitEvent.Kind.PROJECTILE, "minecraft:fishing_bobber", Relation.TEAM_NONE, 40),
                new JumpEvent(10_040, 0, true, -1),
                inc(20_000, A, IncomingHitEvent.Kind.MELEE, null, Relation.TEAM_NONE, 40),
                new JumpEvent(20_040, 0, true, -1)));
        Stats.Result rs = statsOf(rod, sword);
        check("удочка идёт в jump reset как раньше (фильтра снарядов нет)", rs.jump().attempts() == 2
                && rs.hiddenTotal() == 0 && rs.combo().taken() == 2);
        check("старый полученный удар считается ближним", sword.incoming(hit(1, true)) == null
                && hit(1, true).kindOr() == IncomingHitEvent.Kind.MELEE);

        List<Event> mates = List.of(
                atkW(1000, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, Relation.TEAM_SAME, 40),
                atkW(1500, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, Relation.TEAM_SAME_FF, 40),
                inc(2000, A, IncomingHitEvent.Kind.MELEE, null, Relation.TEAM_SAME, 40));
        Stats.Result ms = statsOf(mates, sword);
        check("тиммейты исключаются по настройке", ms.hits().confirmed() == 1 && ms.combo().taken() == 0
                && ms.hidden().get(StatFilter.Hidden.TEAMMATE) == 2 && statsOf(mates, StatFilter.NONE).hits().confirmed() == 2);
        check("общая команда с уроном по своим не тиммейт", !Relation.teammate(Relation.TEAM_SAME_FF)
                && Relation.teammate(Relation.TEAM_SAME) && !Relation.teammate(Relation.TEAM_UNKNOWN));

        List<Event> npc = List.of(atkW(1000, NPC, Outcome.HIT_CONFIRMED, Weapon.SWORD, Relation.TEAM_NONE, 40),
                atkW(1500, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, Relation.TEAM_NONE, 40));
        StatFilter noNpc = new StatFilter(StatFilter.WeaponView.ALL, false, true, false, 150, 1, false);
        check("NPC по UUID версии 2", Relation.npc(NPC) && !Relation.npc(A) && !Relation.npc(null));
        check("NPC исключаются по настройке", statsOf(npc, noNpc).hits().confirmed() == 1
                && statsOf(npc, sword).hits().confirmed() == 2 && statsOf(npc, sword).opponents().size() == 2);

        List<Event> lag = List.of(atkW(1000, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, Relation.TEAM_NONE, 200),
                atkW(1500, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, Relation.TEAM_NONE, 100),
                inc(2000, A, IncomingHitEvent.Kind.MELEE, null, Relation.TEAM_NONE, 300),
                atkW(2500, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, Relation.TEAM_NONE, 0));
        StatFilter ping = new StatFilter(StatFilter.WeaponView.ALL, false, false, true, 150, 1, false);
        Stats.Result ps = statsOf(lag, ping);
        check("порог пинга режет события", ps.hits().confirmed() == 2 && ps.combo().taken() == 0
                && ps.hidden().get(StatFilter.Hidden.HIGH_PING) == 2 && statsOf(lag, sword).hits().confirmed() == 3);

        List<Event> fights = new ArrayList<>(List.of(
                atkW(1000, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, Relation.TEAM_NONE, 40),
                atkW(1500, A, Outcome.MISS, Weapon.SWORD, Relation.TEAM_NONE, 40),
                inc(2000, A, IncomingHitEvent.Kind.MELEE, null, Relation.TEAM_NONE, 40)));
        for (int i = 0; i < 4; i++) fights.add(atkW(60_000 + i * 500L, B, Outcome.HIT_CONFIRMED, Weapon.SWORD, Relation.TEAM_NONE, 40));
        check("минимальный размер боя", Stats.fights(fights, Stats.FIGHT_GAP_MS).size() == 2
                && Stats.fights(fights, Stats.FIGHT_GAP_MS, sword).size() == 1
                && Stats.fights(fights, Stats.FIGHT_GAP_MS, sword).get(0).dealt() == 4
                && Stats.fights(fights, Stats.FIGHT_GAP_MS, StatFilter.NONE).size() == 2);

        AttackEvent fa = atkW(1000, A, Outcome.HIT_CONFIRMED, Weapon.MACE, Relation.TEAM_SAME, 40);
        IncomingHitEvent fi = inc(2000, B, IncomingHitEvent.Kind.PROJECTILE, "minecraft:fishing_bobber", Relation.TEAM_SAME_FF, 40);
        @SuppressWarnings("unchecked")
        Map<String, Object> ja = (Map<String, Object>) Json.parse(Json.write(Cdrec.encode(fa)));
        @SuppressWarnings("unchecked")
        Map<String, Object> ji = (Map<String, Object>) Json.parse(Json.write(Cdrec.encode(fi)));
        check("оружие и команда удара сохраняются", fa.equals(Cdrec.decode(ja)));
        check("вид удара, снаряд и оружие атакующего сохраняются", fi.equals(Cdrec.decode(ji)));
        AttackEvent oa = (AttackEvent) Cdrec.decode(Cdrec.encode(atk(1, A, Outcome.MISS, 3, 1f)));
        IncomingHitEvent oi = (IncomingHitEvent) Cdrec.decode(Cdrec.encode(hit(1, true)));
        check("старые события без новых ключей", oa.weapon() == null && oa.item() == null && oa.team() == Relation.TEAM_UNKNOWN
                && oi.kind() == null && oi.attackerWeapon() == null && oi.team() == Relation.TEAM_UNKNOWN
                && oi.weaponOr() == Weapon.UNKNOWN && !Cdrec.encode(oa).containsKey("weapon"));
        check("неизвестное оружие в файле", Weapon.parse("LASER") == null && Weapon.parse(null) == null
                && Weapon.parse("SPEAR") == Weapon.SPEAR && IncomingHitEvent.Kind.parse("X") == null);

        Path f = Files.createTempFile("cdset", ".json");
        Settings st = new Settings();
        st.statSwordOnly = false;
        st.statNoNpc = true;
        st.statHighPing = true;
        st.statPingLimitMs = 225;
        st.statMinFightHits = 5;
        st.save(f);
        Settings sb = Settings.load(f);
        check("фильтры статистики сохраняются", !sb.statSwordOnly && sb.statNoNpc && sb.statHighPing && sb.statPingLimitMs == 225
                && sb.statMinFightHits == 5 && sb.statFilter().weapon() == StatFilter.WeaponView.ALL && sb.diffCount(st) == 0);
        Settings sc = new Settings();
        sc.statPingLimitMs = 5;
        sc.statMinFightHits = 999;
        sc.clamp();
        check("фильтры статистики: обрезка значений", sc.statPingLimitMs == Settings.MIN_PING_LIMIT
                && sc.statMinFightHits == Settings.MAX_FIGHT_HITS);
        Settings cp = new Settings();
        cp.copyFrom(st);
        check("фильтры статистики копируются", cp.diffCount(st) == 0 && !cp.statSwordOnly);
        Files.deleteIfExists(f);
    }

    static Recording progRec(long wallStart, String server, List<Event> ev) {
        List<Event> all = new ArrayList<>();
        all.add(new SegmentEvent(0, server, "minecraft:overworld", wallStart));
        all.addAll(ev);
        return new Recording(1, "me", B, "26.1.2", wallStart, 120_000, all);
    }

    static List<Event> swings(long t0, UUID who, int n, int conf, Weapon w) {
        List<Event> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(atkW(t0 + i * 500L, who, i < conf ? Outcome.HIT_CONFIRMED : Outcome.MISS, w,
                Relation.TEAM_NONE, 40));
        return out;
    }

    static void progress() throws Exception {
        StatFilter sf = new Settings().statFilter();
        JumpResetEvaluator.Params p = JumpResetEvaluator.Params.DEFAULT;
        long day1 = 1_700_000_000_000L - 1_700_000_000_000L % 86_400_000L, h = 3_600_000L;
        List<Event> e1 = new ArrayList<>(swings(1000, A, 5, 4, Weapon.SWORD));
        e1.addAll(swings(4000, A, 2, 2, Weapon.HAND));
        e1.addAll(swings(40_000, B, 1, 1, Weapon.SWORD));
        Progress.Summary s1 = Progress.summarize("r1.cdrec", progRec(day1 + 10 * h, "S1", e1), p, 100, sf);
        Progress.Summary n1 = Progress.summarize("r1.cdrec", progRec(day1 + 10 * h, "S1", e1), p, 100, StatFilter.NONE);
        check("прогресс: фильтры и маленький бой", s1.all().attacks() == 5 && s1.all().confirmed() == 4
                && n1.all().attacks() == 8 && n1.all().confirmed() == 7);
        check("прогресс: сводка по противнику", s1.opponents().containsKey(A) && !s1.opponents().containsKey(B)
                && s1.select(A).attacks() == 5 && s1.select(B).equals(Progress.Metrics.ZERO) && "Alpha".equals(s1.opponents().get(A).name()));
        check("прогресс: метрики", near(Progress.Metric.HIT_RATE.value(s1.all()), 80, 1e-9)
                && near(Progress.Metric.REACH.value(s1.all()), 2.5, 1e-9) && near(Progress.Metric.CHARGE.value(s1.all()), 100, 1e-9)
                && Double.isNaN(Progress.Metric.JR_SUCCESS.value(s1.all())) && s1.servers().equals(List.of("S1")));
        check("прогресс: мало данных в точке", Double.isNaN(Progress.Metric.HIT_RATE.value(
                new Progress.Metrics(4, 4, 0, 0, 10, 0, 0, 4))));

        Progress.Summary s2 = Progress.summarize("r2.cdrec", progRec(day1 + 18 * h, "S2", swings(1000, A, 10, 3, Weapon.SWORD)), p, 100, sf);
        Progress.Summary s3 = Progress.summarize("r3.cdrec", progRec(day1 + 34 * h, "S1", swings(1000, B, 10, 5, Weapon.SWORD)), p, 100, sf);
        List<Progress.Summary> all = List.of(s3, s1, s2);
        ZoneId utc = ZoneId.of("UTC");
        var byRec = Progress.points(all, Progress.Metric.HIT_RATE, Progress.Group.RECORDING, null, null, utc);
        check("прогресс: точки по записям по времени", byRec.size() == 3 && near(byRec.get(0).value(), 80, 1e-9)
                && near(byRec.get(1).value(), 30, 1e-9) && near(byRec.get(2).value(), 50, 1e-9));
        var byDay = Progress.points(all, Progress.Metric.HIT_RATE, Progress.Group.DAY, null, null, utc);
        check("прогресс: день считается по всем ударам, а не средним средних", byDay.size() == 2
                && near(byDay.get(0).value(), 100.0 * 7 / 15, 1e-9) && byDay.get(0).samples() == 15
                && byDay.get(0).sources().size() == 2 && byDay.get(0).t() == day1 && near(byDay.get(1).value(), 50, 1e-9));
        var bySrv = Progress.points(all, Progress.Metric.HIT_RATE, Progress.Group.RECORDING, "S1", null, utc);
        var byOpp = Progress.points(all, Progress.Metric.HIT_RATE, Progress.Group.RECORDING, null, A, utc);
        check("прогресс: фильтр по серверу и противнику", bySrv.size() == 2 && bySrv.get(1).sources().get(0) == s3
                && byOpp.size() == 2 && near(byOpp.get(1).value(), 30, 1e-9));
        var prev = Progress.previous(all, s3.wallStartMs(), 5);
        check("прогресс: прошлые записи, новые первыми", prev.size() == 2 && prev.get(0) == s2 && prev.get(1) == s1
                && Progress.previous(all, s3.wallStartMs(), 1).size() == 1);
        var cmp = Progress.compare(s3.all(), prev);
        check("прогресс: сравнение с прошлыми", !cmp.isEmpty() && cmp.get(0).metric() == Progress.Metric.HIT_RATE
                && near(cmp.get(0).diff(), 50 - 100.0 * 7 / 15, 1e-9)
                && cmp.stream().noneMatch(d -> d.metric() == Progress.Metric.JR_SUCCESS));

        String key = Progress.cacheKey(sf, p, 100);
        check("прогресс: ключ кэша зависит от фильтров", !key.equals(Progress.cacheKey(StatFilter.NONE, p, 100))
                && !key.equals(Progress.cacheKey(sf, p, 120)) && key.equals(Progress.cacheKey(new Settings().statFilter(), p, 100)));
        Path cf = Files.createTempFile("cdprog", ".json");
        ProgressCache c = new ProgressCache();
        c.put("r1.cdrec", 111, key, s1);
        c.put("r2.cdrec", 222, key, s2);
        c.save(cf);
        ProgressCache back = ProgressCache.load(cf);
        check("кэш прогресса: сохраняется без потерь", s1.equals(back.get("r1.cdrec", 111, key)) && s2.equals(back.get("r2.cdrec", 222, key))
                && !back.dirty());
        check("кэш прогресса: другое время файла или фильтры", back.get("r1.cdrec", 112, key) == null
                && back.get("r1.cdrec", 111, Progress.cacheKey(StatFilter.NONE, p, 100)) == null && back.get("r9.cdrec", 1, key) == null);
        back.retain(Set.of("r2.cdrec"));
        check("кэш прогресса: удалённые файлы убираются", back.size() == 1 && back.dirty());
        Files.writeString(cf, "{битый");
        check("кэш прогресса: повреждённый файл", ProgressCache.load(cf).size() == 0);
        Files.deleteIfExists(cf);
        check("кэш прогресса: нет файла", ProgressCache.load(cf).size() == 0);
    }

    static AttackEvent atkS(long t, UUID who, Outcome o, Weapon w, float str) {
        return new AttackEvent(t, 0, who, "Alpha", o, HitType.NORMAL, GROUND, str, 2.5, 5, 3, -4, 0.1, 0.6, 40, t - 10, 2.5,
                3.0, Double.NaN, Double.NaN, -1, 12.5f, null, w, Relation.TEAM_NONE);
    }

    static IncomingHitEvent from(long t, UUID who) {
        return inc(t, who, IncomingHitEvent.Kind.MELEE, null, Relation.TEAM_NONE, 40);
    }

    static Insights.Input tradeIn(Trades.Summary s) {
        return new Insights.Input(new Stats.Result(jumpStats(0, Double.NaN), hitStats(0, 0, 0, 0, Double.NaN),
                new Stats.ComboStats(0, 0, 0, 0), List.of(), 0, 0, new Stats.ThreeBlockStats(0, 0, 0, Double.NaN, Double.NaN),
                Map.of(), s), JumpResetEvaluator.Params.DEFAULT);
    }

    static void trades() throws Exception {
        JumpResetEvaluator.Params p = JumpResetEvaluator.Params.DEFAULT;
        StatFilter any = new StatFilter(StatFilter.WeaponView.ALL, false, false, false, 150, 1, true);
        StatFilter anyPoke = new StatFilter(StatFilter.WeaponView.ALL, false, false, false, 150, 1, false);
        List<Event> ev = new ArrayList<>(List.of(
                atkS(0, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, 1f), new JumpEvent(150, 0, true, -1),
                atkS(300, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, 1f),
                from(600, A), new JumpEvent(640, 0, true, -1),
                from(3000, A), new JumpEvent(3040, 0, true, -1),
                from(3300, A), new JumpEvent(3340, 0, true, -1),
                atkS(3600, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, 1f)));
        List<Trades.Trade> tr = Trades.split(ev, 1500, any, p);
        check("размены: разбиение по паузе", tr.size() == 2 && tr.get(0).first() == Trades.Side.ME && tr.get(0).mine() == 2
                && tr.get(0).theirs() == 1 && tr.get(0).result() == Trades.Result.WIN && tr.get(1).first() == Trades.Side.OPPONENT
                && tr.get(1).result() == Trades.Result.LOSS && tr.get(0).cause() == null);
        check("размены: пауза из настройки", Trades.split(ev, 3000, any, p).size() == 1);
        check("размены: успешный jump reset без своей ошибки это «без явной причины»", tr.get(1).cause() == Trades.Cause.NONE);

        List<Event> poke = List.of(atkS(10_000, A, Outcome.HIT_CONFIRMED, Weapon.HAND, 1f), from(10_300, A),
                atkS(10_600, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, 1f));
        List<Trades.Trade> pk = Trades.split(poke, 1500, any, p), pk2 = Trades.split(poke, 1500, anyPoke, p);
        check("тычок рукой не открывает размен", pk.size() == 1 && pk.get(0).first() == Trades.Side.OPPONENT
                && pk.get(0).mine() == 1 && pk.get(0).result() == Trades.Result.DRAW);
        check("без настройки тычок открывает размен", pk2.get(0).first() == Trades.Side.ME && pk2.get(0).mine() == 2);

        List<Event> miss = List.of(atkS(20_000, A, Outcome.MISS, Weapon.SWORD, 1f), from(20_200, A), new JumpEvent(20_240, 0, true, -1));
        List<Event> weak = List.of(atkS(30_000, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, 0.5f), from(30_200, A),
                new JumpEvent(30_240, 0, true, -1), from(30_500, A), new JumpEvent(30_540, 0, true, -1));
        List<Event> jr = List.of(from(40_000, A));
        List<Event> oldMiss = List.of(atkS(50_000, A, Outcome.MISS, Weapon.SWORD, 1f), from(55_000, A),
                new JumpEvent(55_040, 0, true, -1));
        check("причина проигрыша: промах", Trades.split(miss, 1500, any, p).get(0).cause() == Trades.Cause.MISS);
        check("причина проигрыша: удар без заряда", Trades.split(weak, 1500, any, p).get(0).cause() == Trades.Cause.WEAK);
        check("причина проигрыша: неудачный jump reset", Trades.split(jr, 1500, any, p).get(0).cause() == Trades.Cause.FAILED_JR);
        check("причина: старый промах не считается", Trades.split(oldMiss, 1500, any, p).get(0).cause() == Trades.Cause.NONE);

        List<Event> two = List.of(atkS(0, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, 1f), from(200, B),
                atkS(400, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, 1f), from(600, B));
        List<Trades.Trade> tw = Trades.split(two, 1500, any, p);
        check("размены отдельно по противникам", tw.size() == 2 && tw.get(0).opponent().equals(A) && tw.get(0).mine() == 2
                && tw.get(1).opponent().equals(B) && tw.get(1).theirs() == 2);

        List<Event> mix = new ArrayList<>(ev);
        mix.addAll(miss);
        mix.addAll(jr);
        Trades.Summary s = Trades.summary(Trades.split(mix, 1500, any, p));
        check("сводка разменов", s.total() == 4 && s.first() == 1 && s.wins() == 1 && s.losses() == 3 && s.draws() == 0
                && near(s.firstPct(), 25, 1e-9) && s.countSecond() == 3 && s.winsSecond() == 0 && near(s.winFirstPct(), 100, 1e-9)
                && s.lossCauses().get(Trades.Cause.MISS) == 1 && s.lossCauses().get(Trades.Cause.FAILED_JR) == 1);
        check("пустая сводка", Double.isNaN(Trades.Summary.EMPTY.firstPct()) && Trades.summary(List.of()).total() == 0);
        Stats.Result r = Stats.compute(new Recording(1, "me", B, "26.1.2", 0, 60_000, mix), p, Stats.Filter.ALL, 3.0, 100, any, 1500);
        check("размены в статистике записи", r.trades().equals(s));

        Trades.Summary few = new Trades.Summary(8, 2, 2, 2, 6, 1, 3, 5, 0, Map.of(Trades.Cause.MISS, 4, Trades.Cause.NONE, 1));
        var ins = Insights.evaluate(Insights.defaults(), tradeIn(few), 10);
        check("выводы: мало первых ударов", hasInsight(ins, "first-hit") && ins.stream().filter(i -> i.id().equals("first-hit"))
                .findFirst().get().page() == Insights.Page.FIGHTS_TRADES);
        check("выводы: проигрыш, когда бьют первым", hasInsight(ins, "second-loss"));
        check("выводы: причина проигрышей", ins.stream().anyMatch(i -> i.id().equals("loss-cause") && i.ru().contains("промах")
                && i.page() == Insights.Page.ATTACK_HITS));
        Trades.Summary good = new Trades.Summary(8, 6, 6, 5, 2, 1, 6, 2, 0, Map.of(Trades.Cause.NONE, 2));
        var goodIns = Insights.evaluate(Insights.defaults(), tradeIn(good), 10);
        check("выводы: хорошие размены только с похвалой", goodIns.size() == 1 && goodIns.get(0).id().equals("first-hit-good")
                && goodIns.get(0).tone() == Insights.Tone.GOOD);
        Trades.Summary small = new Trades.Summary(3, 0, 0, 0, 3, 0, 0, 3, 0, Map.of(Trades.Cause.MISS, 3));
        check("выводы: мало разменов не судим", Insights.evaluate(Insights.defaults(), tradeIn(small), 10).isEmpty());

        Progress.Summary ps = Progress.summarize("t.cdrec", progRec(0, "S1", mix), p, 100, any, 1500);
        check("прогресс: первый удар %", ps.all().trades() == 4 && ps.all().firstHits() == 1
                && near(Progress.Metric.FIRST_HIT.value(ps.all()), 25, 1e-9));
        check("прогресс: ключ кэша зависит от паузы разменов", !Progress.cacheKey(any, p, 100, 1500).equals(Progress.cacheKey(any, p, 100, 2000)));
        Settings st = new Settings();
        check("пауза разменов по умолчанию 1.5 с", st.tradeGapMs == 1500);
        st.tradeGapMs = 99_999;
        st.clamp();
        Path f = Files.createTempFile("cdtr", ".json");
        st.save(f);
        check("пауза разменов: обрезка и сохранение", Settings.load(f).tradeGapMs == 5000);
        Files.deleteIfExists(f);
    }

    static final AttackFlags SPRINTING = new AttackFlags(true, true, 0, false, false, false, false, true, false);

    static AttackEvent hitW(long t, boolean sprint) {
        return new AttackEvent(t, 0, A, "Alpha", Outcome.HIT_CONFIRMED, sprint ? HitType.SPRINT : HitType.NORMAL,
                sprint ? SPRINTING : GROUND, 1f, 2.5, 5, 3, -4, 0.1, 0.6, 40, t - 10, 2.5, 3.0, Double.NaN, Double.NaN, -1,
                12.5f, "minecraft:iron_sword", Weapon.SWORD, Relation.TEAM_NONE);
    }

    static InputEvent in(long t, InputEvent.Input what, boolean on) {
        return new InputEvent(t, 0, what, on);
    }

    static void wtap() {
        var F = InputEvent.Input.FORWARD;
        var S = InputEvent.Input.BACK;
        var K = InputEvent.Input.SPRINT_KEY;
        var R = InputEvent.Input.SPRINTING;
        List<Event> ev = List.of(
                hitW(0, true), in(10, R, false), in(100, F, false), in(160, F, true), in(170, R, true),
                hitW(600, true), in(610, R, false), in(660, R, true),
                hitW(1200, true), in(1210, R, false), in(1300, S, true), in(1350, S, false), in(1360, R, true),
                hitW(1800, true),
                hitW(2400, false),
                from(2600, A),
                hitW(5000, true), in(5300, K, true),
                hitW(5500, true));
        WTap.Result r = WTap.analyze(ev);
        check("W-tap: разобраны удары в сериях", r.hasInput() && r.hits().size() == 5);
        check("W-tap: способы", r.hits().get(0).method() == WTap.Method.W_TAP && r.hits().get(1).method() == WTap.Method.AUTO
                && r.hits().get(2).method() == WTap.Method.S_TAP && r.hits().get(3).method() == WTap.Method.NONE
                && r.hits().get(4).method() == WTap.Method.SPRINT_KEY);
        check("W-tap: через сколько отпущена W и сколько длится сброс", r.hits().get(0).releaseMs() == 100
                && r.hits().get(0).resetMs() == 60 && r.hits().get(2).releaseMs() == 100 && r.hits().get(2).resetMs() == 50
                && near(r.releaseMeanMs(), 100, 1e-9) && near(r.resetMeanMs(), 55, 1e-9) && r.resetDurations().length == 2);
        check("W-tap: доля SPRINT-ударов со сбросом", r.sprintHits() == 4 && r.afterReset() == 3 && near(r.afterResetPct(), 75, 1e-9));
        check("W-tap: удары без спринта и порванные комбо", r.noSprintHits() == 1 && r.combos() == 1 && r.brokenNoSprint() == 1);
        check("W-tap: пауза больше 2 с рвёт серию", r.hits().stream().noneMatch(h -> h.hit().t() == 5000));
        WTap.Result old = WTap.analyze(List.of(hitW(0, true), hitW(600, true)));
        check("W-tap: старая запись без ввода", !old.hasInput() && old.hits().isEmpty() && Double.isNaN(old.afterResetPct()));
        InputEvent ie = in(100, K, true);
        check("событие ввода сохраняется", ie.equals(Cdrec.decode(Cdrec.encode(ie)))
                && Cdrec.decode(Map.of("k", "N", "t", 1.0, "in", "LASER", "on", true)) == null);
        check("событие ввода проходит фильтры", new Settings().statFilter().apply(List.of(ie)).kept().size() == 1);
    }

    static IncomingHitEvent took(long t, double reach, Weapon w, IncomingHitEvent.Kind k) {
        return new IncomingHitEvent(t, 0, A, "Alpha", true, false, true, 40, 0.8, k, null, w, null, Relation.TEAM_NONE,
                reach, 3.0, 4.2, -1.5, 55);
    }

    static void taken() throws Exception {
        IncomingHitEvent far = took(1000, 3.4, Weapon.SWORD, IncomingHitEvent.Kind.MELEE);
        IncomingHitEvent near = took(2000, 2.6, Weapon.SWORD, IncomingHitEvent.Kind.MELEE);
        IncomingHitEvent axe = took(3000, 3.2, Weapon.AXE, IncomingHitEvent.Kind.MELEE);
        IncomingHitEvent rod = took(4000, 6.0, Weapon.OTHER, IncomingHitEvent.Kind.PROJECTILE);
        check("3 block hit против меня: дальше дальности", far.caught() && !near.caught() && far.rated());
        check("снаряд и старая запись не оцениваются", !rod.rated() && !rod.caught() && !hit(1, true).rated() && !hit(1, true).caught());
        IncomingHitEvent noRange = new IncomingHitEvent(1, 0, A, "Alpha", true, false, true, 40, 0.8, IncomingHitEvent.Kind.MELEE,
                null, Weapon.SWORD, null, Relation.TEAM_NONE, 3.1, Double.NaN, Double.NaN, Double.NaN, -1);
        check("дальность по умолчанию 3.0", noRange.caught() && near(noRange.rangeOr(3.0), 3.0, 1e-12));
        Stats.Result r = Stats.compute(new Recording(1, "me", B, "26.1.2", 0, 60_000, List.of(far, near, axe, rod)),
                JumpResetEvaluator.Params.DEFAULT, Stats.Filter.ALL, 3.0);
        Stats.TakenStats tk = r.taken();
        check("удары по мне: количество и дистанция", tk.rated() == 3 && near(tk.reachMean(), (3.4 + 2.6 + 3.2) / 3, 1e-9)
                && near(tk.reachMax(), 3.4, 1e-9) && tk.caught() == 2 && near(tk.caughtPct(), 200.0 / 3, 1e-9));
        check("удары по мне: по оружию", tk.byWeapon().get(Weapon.SWORD).n() == 2 && tk.byWeapon().get(Weapon.SWORD).caught() == 1
                && near(tk.byWeapon().get(Weapon.AXE).reachMean(), 3.2, 1e-9) && !tk.byWeapon().containsKey(Weapon.OTHER));
        check("удары по мне: пусто", Stats.compute(new Recording(1, "me", B, "26.1.2", 0, 1, List.of(hit(1, true))),
                JumpResetEvaluator.Params.DEFAULT, Stats.Filter.ALL, 3.0).taken().rated() == 0);
        StatFilter any = new StatFilter(StatFilter.WeaponView.ALL, false, false, false, 150, 1, true);
        List<Event> lost = List.of(far, new JumpEvent(1040, 0, true, -1));
        check("причина проигрыша: 3 block hit против вас", Trades.split(lost, 1500, any, JumpResetEvaluator.Params.DEFAULT)
                .get(0).cause() == Trades.Cause.CAUGHT);
        List<Event> missFirst = List.of(atkS(800, A, Outcome.MISS, Weapon.SWORD, 1f), far, new JumpEvent(1040, 0, true, -1));
        check("промах перед ударом важнее", Trades.split(missFirst, 1500, any, JumpResetEvaluator.Params.DEFAULT)
                .get(0).cause() == Trades.Cause.MISS);
        @SuppressWarnings("unchecked")
        Map<String, Object> j = (Map<String, Object>) Json.parse(Json.write(Cdrec.encode(far)));
        check("дистанция и скорости удара по мне сохраняются", far.equals(Cdrec.decode(j)));
        IncomingHitEvent old = (IncomingHitEvent) Cdrec.decode(Cdrec.encode(hit(1, true)));
        check("старый удар по мне без замера", Double.isNaN(old.reach()) && old.attackerPingMs() == -1 && !Cdrec.encode(old).containsKey("reach"));
        List<Event> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) many.add(took(i * 3000L, i < 3 ? 3.5 : 2.5, Weapon.SWORD, IncomingHitEvent.Kind.MELEE));
        Stats.Result mr = Stats.compute(new Recording(1, "me", B, "26.1.2", 0, 60_000, many), JumpResetEvaluator.Params.DEFAULT,
                Stats.Filter.ALL, 3.0);
        var ins = Insights.evaluate(Insights.defaults(), new Insights.Input(mr, JumpResetEvaluator.Params.DEFAULT), 10);
        check("вывод: часто ловят на 3 block hit", ins.stream().anyMatch(i -> i.id().equals("caught") && i.ru().contains("30%")
                && i.page() == Insights.Page.DEFENSE_TAKEN));
        Stats.Result few = Stats.compute(new Recording(1, "me", B, "26.1.2", 0, 60_000, many.subList(0, 9)),
                JumpResetEvaluator.Params.DEFAULT, Stats.Filter.ALL, 3.0);
        check("вывод: мало ударов не судим", Insights.evaluate(Insights.defaults(), new Insights.Input(few, JumpResetEvaluator.Params.DEFAULT), 10)
                .stream().noneMatch(i -> i.id().equals("caught")));
    }

    /** Reference: the old brute-force jump reset evaluation. */
    static List<JumpResetEvaluator.Attempt> evaluateBrute(List<Event> events, JumpResetEvaluator.Params p) {
        List<IncomingHitEvent> hits = new ArrayList<>();
        List<JumpEvent> jumps = new ArrayList<>();
        for (Event e : events) {
            if (e instanceof IncomingHitEvent h) hits.add(h);
            else if (e instanceof JumpEvent j) jumps.add(j);
        }
        boolean[] used = new boolean[jumps.size()];
        List<JumpResetEvaluator.Attempt> out = new ArrayList<>();
        for (IncomingHitEvent h : hits) {
            if (!h.knockbackApplied()) {
                out.add(new JumpResetEvaluator.Attempt(h, false, -1, 0, JumpResetEvaluator.Result.NOT_APPLICABLE, h.jumpHeld()));
                continue;
            }
            int best = -1;
            double bestDist = Double.MAX_VALUE;
            long bestAbs = Long.MAX_VALUE;
            for (int i = 0; i < jumps.size(); i++) {
                if (used[i]) continue;
                long d = jumps.get(i).t() - h.t();
                if (d < -p.preMs() || d > p.postMs()) continue;
                double dist = d < p.lowerMs() ? p.lowerMs() - d : d > p.upperMs() ? d - p.upperMs() : 0;
                long abs = Math.abs(d);
                if (dist < bestDist || (dist == bestDist && abs < bestAbs)) {
                    best = i;
                    bestDist = dist;
                    bestAbs = abs;
                }
            }
            if (best >= 0) {
                used[best] = true;
                long d = jumps.get(best).t() - h.t();
                JumpResetEvaluator.Result r = p.separateFlyOut() && jumps.get(best).flyOut() ? JumpResetEvaluator.Result.FLY_OUT
                        : JumpResetEvaluator.classify(d, p);
                out.add(new JumpResetEvaluator.Attempt(h, true, jumps.get(best).t(), d, r, h.jumpHeld()));
            } else {
                out.add(new JumpResetEvaluator.Attempt(h, false, -1, 0, h.groundedAtHit() ? JumpResetEvaluator.Result.NO_ATTEMPT
                        : JumpResetEvaluator.Result.NOT_APPLICABLE, h.jumpHeld()));
            }
        }
        return out;
    }

    static void performance() {
        Random rnd = new Random(42);
        boolean same = true;
        for (int round = 0; round < 30; round++) {
            List<Event> ev = new ArrayList<>();
            long t = 0;
            for (int i = 0; i < 300; i++) {
                t += rnd.nextInt(120);
                if (rnd.nextInt(3) == 0) ev.add(new IncomingHitEvent(t, 0, A, "Alpha", rnd.nextBoolean(), false, rnd.nextInt(5) > 0, 40));
                else ev.add(new JumpEvent(t, 0, true, -1, rnd.nextInt(4) == 0 ? JumpEvent.KEY_BACK : JumpEvent.KEY_FORWARD));
            }
            JumpResetEvaluator.Params p = new JumpResetEvaluator.Params(rnd.nextInt(60) - 30, 80, 200, 200, false, rnd.nextBoolean());
            same &= JumpResetEvaluator.evaluate(ev, p).equals(evaluateBrute(ev, p));
        }
        check("быстрая оценка jump reset совпадает с полным перебором", same);

        // An hour of combat: 20,000 jumps and 4,000 hits. Brute force would be 80M comparisons.
        List<Event> big = new ArrayList<>();
        for (int i = 0; i < 24_000; i++) {
            long t = i * 150L;
            big.add(i % 6 == 0 ? new IncomingHitEvent(t, 0, A, "Alpha", true, false, true, 40) : new JumpEvent(t, 0, true, -1));
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 10; i++) JumpResetEvaluator.evaluate(big, JumpResetEvaluator.Params.DEFAULT);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("оценка jump reset на часе боя быстрая (10 раз меньше 500 мс, было " + ms + ")", ms < 500);

        RecordingBuffer b = new RecordingBuffer();
        long v0 = b.version();
        b.add(new PingEvent(10, 40));
        b.add(new InputEvent(20, 0, InputEvent.Input.FORWARD, true));
        check("буфер: пинг и клавиши не меняют версию", b.version() == v0 && !b.hasCombatEvents());
        b.add(atk(30, A, Outcome.HIT_CONFIRMED, 2.5, 1f));
        check("буфер: удар меняет версию и счётчик боя", b.version() > v0 && b.hasCombatEvents());
        b.trim(100_000, 1000);
        check("буфер: вытеснение обнуляет счётчик боя", !b.hasCombatEvents() && b.size() == 0);
        b.add(atk(200_000, A, Outcome.MISS, 3, 1f));
        b.clear();
        check("буфер: очистка", !b.hasCombatEvents());
    }

    static IncomingHitEvent tookCrit(long t, int crit) {
        return new IncomingHitEvent(t, 0, A, "Alpha", true, false, true, 40, 0.8, IncomingHitEvent.Kind.MELEE, null,
                Weapon.SWORD, null, Relation.TEAM_NONE, 2.5, 3.0, 4.0, -1.0, 50, crit);
    }

    static void tradeWeights() throws Exception {
        AttackEvent crit = new AttackEvent(0, 0, A, "Alpha", Outcome.HIT_CONFIRMED, HitType.CRIT, GROUND, 1f, 2.5, 5, 3, -4,
                0.1, 0.6, 40, -1, 2.5, 3.0, Double.NaN, Double.NaN, -1, 12.5f, null, Weapon.SWORD, Relation.TEAM_NONE);
        AttackEvent weak = atkS(0, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, 0.5f);
        check("вес своего удара: крит 1,5, полный 1, заряд 50% это 0,4", near(Trades.weight(crit), 1.5, 1e-9)
                && near(Trades.weight(atkS(0, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, 1f)), 1.0, 1e-9)
                && near(Trades.weight(weak), 0.4, 1e-6));
        check("вес удара противника: крит 1,5, иначе и в старых записях 1", near(Trades.weight(tookCrit(0, 1)), 1.5, 1e-9)
                && near(Trades.weight(tookCrit(0, 0)), 1.0, 1e-9) && near(Trades.weight(hit(0, true)), 1.0, 1e-9));
        StatFilter any = new StatFilter(StatFilter.WeaponView.ALL, false, false, false, 150, 1, true);
        JumpResetEvaluator.Params p = JumpResetEvaluator.Params.DEFAULT;
        List<Event> ev = List.of(crit, tookCrit(300, 0), new JumpEvent(340, 0, true, -1));
        Trades.Trade byHits = Trades.split(ev, new Trades.Params(1500, false), any, p).get(0);
        Trades.Trade byW = Trades.split(ev, new Trades.Params(1500, true), any, p).get(0);
        check("крит против удара с разбега: по ударам ничья, по весу победа", byHits.result() == Trades.Result.DRAW
                && byW.result() == Trades.Result.WIN && near(byW.mineWeight(), 1.5, 1e-9) && near(byW.theirsWeight(), 1.0, 1e-9));
        List<Event> ev2 = List.of(weak, atkS(400, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, 0.5f), tookCrit(600, 1),
                new JumpEvent(640, 0, true, -1));
        Trades.Trade w2 = Trades.split(ev2, new Trades.Params(1500, true), any, p).get(0);
        check("два слабых против крита: по ударам победа, по весу поражение с причиной",
                Trades.split(ev2, new Trades.Params(1500, false), any, p).get(0).result() == Trades.Result.WIN
                        && w2.result() == Trades.Result.LOSS && w2.cause() == Trades.Cause.WEAK);
        Trades.Summary s = Trades.summary(List.of(byW, w2));
        check("сводка: сумма весов", near(s.weightMine(), 1.5 + 0.8, 1e-6) && near(s.weightTheirs(), 2.5, 1e-9));
        IncomingHitEvent c = tookCrit(5, 1);
        check("крит противника сохраняется", c.equals(Cdrec.decode(Cdrec.encode(c)))
                && ((IncomingHitEvent) Cdrec.decode(Cdrec.encode(hit(1, true)))).attackerCrit() == -1);
        Settings st = new Settings();
        check("победитель по ударам по умолчанию", !st.tradeByWeight && !st.tradeParams().byWeight());
        st.tradeByWeight = true;
        Path f = Files.createTempFile("cdw", ".json");
        st.save(f);
        check("победитель по весу сохраняется", Settings.load(f).tradeByWeight && Settings.load(f).tradeParams().byWeight());
        Files.deleteIfExists(f);
        Stats.Result r = Stats.compute(new Recording(1, "me", B, "26.1.2", 0, 10_000, ev), p, Stats.Filter.ALL, 3.0, 100, any,
                new Trades.Params(1500, true));
        check("статистика с победителем по весу", r.trades().wins() == 1 && r.trades().draws() == 0);
    }

    static PositionEvent pos(long t, double myX, double oppX) {
        return new PositionEvent(t, 0, myX, 64, 0, 90f, List.of(new PositionEvent.Other(A, oppX, 64, 0, -90f)));
    }

    static void positions() throws Exception {
        PositionEvent p = pos(100, 0, 3);
        check("дистанция до хитбокса от глаз", near(p.distanceTo(p.others().get(0)), 2.7, 1e-9)
                && p.find(B) == null && p.find(A) != null);
        PositionEvent rounded = new PositionEvent(5, 3, 10.123456, 64.5, -3.987, 12.345f,
                List.of(new PositionEvent.Other(B, 1.005, 2.0, 3.0, 45.06f)));
        PositionEvent back = (PositionEvent) Cdrec.decode(Cdrec.encode(rounded));
        check("позиция сохраняется с точностью 0,01 блока", near(back.x(), 10.12, 1e-9) && near(back.z(), -3.99, 1e-9)
                && near(back.yaw(), 12.3, 1e-4) && back.others().size() == 1 && back.others().get(0).id().equals(B)
                && near(back.others().get(0).yaw(), 45.1, 1e-4) && back.tick() == 3);
        check("повреждённая позиция пропускается", Cdrec.decode(Map.of("k", "X", "t", 1.0, "p", List.of(1.0))) == null);
        List<Event> ev = new ArrayList<>(List.of(pos(0, 0, 4), pos(50, 0, 3.5), pos(100, 0, 3), pos(1000, 0, 2),
                atkS(60, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, 1f), from(90, A), from(95, A), from(500, B)));
        List<Distance.Sample> s = Distance.series(ev, A);
        check("ряд дистанции по позициям", s.size() == 4 && near(s.get(0).d(), 3.7, 1e-9) && Distance.series(ev, B).isEmpty()
                && Distance.series(ev, null).isEmpty());
        check("дистанция между отсчётами и разрывы", near(Distance.at(s, 75), 2.95, 1e-9) && near(Distance.at(s, 50), 3.2, 1e-9)
                && Double.isNaN(Distance.at(s, 600)) && near(Distance.at(s, 1100), 1.7, 1e-9) && Double.isNaN(Distance.at(s, 5000)));
        check("основной противник по числу ударов", A.equals(Distance.mainOpponent(ev)) && Distance.mainOpponent(List.of()) == null);
        check("позиции проходят фильтры и не меняют версию буфера", new Settings().statFilter().apply(List.of(p)).kept().size() == 1);
        RecordingBuffer b = new RecordingBuffer();
        long v = b.version();
        b.add(p);
        check("позиции не запускают пересчёт живого буфера", b.version() == v && !b.hasCombatEvents());
        check("запись позиций по умолчанию включена", new Settings().recordPositions);

        // 20 minutes of continuous combat: 24,000 ticks, 2 nearby players plus hits. File size must stay reasonable.
        List<Event> fight = new ArrayList<>();
        Random rnd = new Random(7);
        for (int i = 0; i < 24_000; i++) {
            long t = i * 50L;
            double a = i * 0.05;
            fight.add(new PositionEvent(t, i, 100 + Math.sin(a) * 5 + rnd.nextDouble() * 0.01, 64, 200 + Math.cos(a) * 5, (float) (i % 360),
                    List.of(new PositionEvent.Other(A, 102 + Math.sin(a) * 5, 64, 201 + Math.cos(a) * 5, (float) ((i + 180) % 360)),
                            new PositionEvent.Other(B, 98 - Math.sin(a) * 4, 64.5, 199, (float) (i % 90)))));
            if (i % 12 == 0) fight.add(atkW(t + 1, A, Outcome.HIT_CONFIRMED, Weapon.SWORD, Relation.TEAM_NONE, 40));
            if (i % 15 == 0) fight.add(from(t + 2, A));
        }
        Path f = Files.createTempFile("cdpos", Cdrec.EXT);
        Cdrec.write(f, new Recording(1, "me", B, "26.1.2", 0, 1_200_000, fight), "test");
        long kb = Files.size(f) / 1024;
        Files.deleteIfExists(f);
        System.out.println("20 min of fights with positions = " + kb + " KB");
        check("20 минут боя с позициями меньше 3 МБ (вышло " + kb + " КБ)", kb < 3 * 1024);
    }

    static void replay() {
        List<Event> ev = new ArrayList<>();
        for (int i = 0; i < 40; i++) ev.add(pos(i * 50L, 0, 3));
        for (int i = 0; i < 20; i++) ev.add(pos(10_000 + i * 50L, 0, 4));
        ev.add(new PositionEvent(500, 0, 0, 64, 0, 0f, List.of(new PositionEvent.Other(B, 1, 64, 0, 0f))));
        List<PositionEvent> f = Replay.frames(ev, A, 0, Long.MAX_VALUE);
        check("повтор: кадры только с противником и по участку", f.size() == 60 && Replay.frames(ev, A, 0, 1000).size() == 21
                && Replay.frames(ev, null, 0, 1).isEmpty());
        check("повтор: кадр в момент времени", Replay.indexAt(f, 75) == 1 && Replay.indexAt(f, -1) == -1
                && Replay.indexAt(f, 1_000_000) == 59 && Replay.indexAt(f, 100) == 2);
        check("повтор: масштаб не меньше 10 блоков", near(Replay.span(f, A), Replay.MIN_SPAN, 1e-9));
        List<PositionEvent> far = new ArrayList<>();
        for (int i = 0; i < 100; i++) far.add(pos(i * 50L, 0, i == 99 ? 100 : 12));
        check("повтор: масштаб по 95-му перцентилю, редкий разлёт не мельчит", near(Replay.span(far, A), 12 * 1.4 + 4, 1e-9));
        check("повтор: обычный шаг", Replay.advance(f, 100, 30) == 130);
        check("повтор: пустой промежуток между боями пропускается", Replay.advance(f, 1950, 100) == 10_000);
        check("повтор: не дальше последнего кадра", Replay.advance(f, 10_900, 500) == 10_950 && Replay.advance(List.of(), 5, 10) == 5);

        // Flat-ground jump: 12 ticks airborne, peak 1.25 blocks.
        List<PositionEvent> jump = new ArrayList<>(), hill = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double air = i >= 20 && i < 32 ? 1.25 - Math.pow((i - 26) / 6.0, 2) * 1.25 : 0;
            jump.add(new PositionEvent(i * 50L, i, 0, 64 + air, 0, 0f, List.of(new PositionEvent.Other(A, 3, 64 + air / 2, 0, 0f))));
            hill.add(new PositionEvent(i * 50L, i, 0, 64 + i * 0.1, 0, 0f, List.of()));
        }
        check("высота прыжка: пик над землёй", near(Replay.lift(jump, 26, null), 1.25, 1e-9) && Replay.lift(jump, 10, null) == 0
                && Replay.lift(jump, 45, null) == 0 && near(Replay.lift(jump, 26, A), 0.625, 1e-9));
        check("высота прыжка: подъём в горку не прыжок", Replay.lift(hill, 30, null) == 0 && Replay.lift(hill, 59, null) == 0);
        check("высота прыжка: нет игрока или кадра", Replay.lift(jump, 26, B) == 0 && Replay.lift(jump, -1, null) == 0
                && Replay.lift(List.of(), 0, null) == 0);
    }

    static void liteAndThemes() throws Exception {
        check("лёгкая запись: ключи маски туда и обратно", Features.parse(Features.names(Features.LITE)) == Features.LITE
                && Features.names(0).isEmpty() && Features.parse(List.of("pos", "future")) == Features.POSITIONS
                && Features.parse("мусор") == 0);
        Settings s = new Settings();
        check("лёгкая запись: по умолчанию полная", s.recordMode() == Settings.REC_FULL && s.notRecordedMask() == 0);
        s.setRecordMode(Settings.REC_LITE);
        check("лёгкая запись: пресет выключает позиции, ввод и отлёт", !s.recordPositions && !s.recordInput
                && !s.recordKnockback && s.recordTaken && s.recordMode() == Settings.REC_LITE);
        s.recordTaken = false;
        check("лёгкая запись: другой набор это своя", s.recordMode() == Settings.REC_CUSTOM
                && s.notRecordedMask() == (Features.LITE | Features.TAKEN));
        s.setRecordMode(Settings.REC_FULL);
        check("лёгкая запись: полная включает всё", s.notRecordedMask() == 0);

        Path dir = Files.createTempDirectory("cd-r10");
        Path f = dir.resolve("lite.cdrec"), old = dir.resolve("old.cdrec");
        List<Event> ev = List.of(atk(1000, A, Outcome.HIT_CONFIRMED, 2.5, 1f));
        Cdrec.write(f, new Recording(1, "me", B, "26.1.2", 0, 5000, ev).withNotRecorded(Features.LITE), "test");
        Cdrec.write(old, new Recording(1, "me", B, "26.1.2", 0, 5000, ev), "test");
        Recording back = Cdrec.read(f);
        check("лёгкая запись: маска сохраняется в заголовке", back.notRecorded() == Features.LITE
                && back.skipped(Features.POSITIONS) && !back.skipped(Features.TAKEN)
                && Cdrec.readHeader(f).notRecorded() == Features.LITE);
        check("лёгкая запись: обычная и старая запись без маски", Cdrec.read(old).notRecorded() == 0
                && Cdrec.readHeader(old).notRecorded() == 0);

        check("тема: hex в обе стороны", Themes.parseHex("#262624") == 0x262624 && Themes.parseHex("abc") == 0xAABBCC
                && Themes.parseHex("0xFF0000") == 0xFF0000 && Themes.parseHex("#12345") == -1 && Themes.parseHex("#GG0000") == -1
                && Themes.parseHex(null) == -1 && Themes.hex(0xFF262624).equals("#262624"));
        ThemeSpec quartz = Themes.builtin("quartz");
        ThemeSpec code = Themes.fromCode("  " + Themes.toCode(quartz) + "\n");
        check("тема: код темы в обе стороны", code != null && java.util.Arrays.equals(code.colors(), quartz.colors()));
        check("тема: неверный код не принимается", Themes.fromCode("cdtheme1;x;262624") == null && Themes.fromCode("hello") == null
                && Themes.fromCode(Themes.toCode(quartz).replace("2B2A27", "ZZZZZZ")) == null);
        boolean hsl = true;
        for (ThemeSpec t : Themes.BUILTIN) {
            for (int c : t.colors()) {
                double[] h = Themes.toHsl(c);
                int r = Themes.fromHsl(h[0], h[1], h[2]);
                for (int sh = 0; sh <= 16; sh += 8) if (Math.abs(((r >> sh) & 0xFF) - ((c >> sh) & 0xFF)) > 1) hsl = false;
            }
        }
        check("тема: HSL туда и обратно без потерь (±1)", hsl && Themes.fromHsl(0, 1, 0.5) == 0xFF0000
                && Themes.fromHsl(120, 1, 0.5) == 0x00FF00 && Themes.fromHsl(0, 0, 1) == 0xFFFFFF);
        check("тема: смесь и яркость", Themes.mix(0xFF000000, 0xFFFFFFFF, 0.5) == 0xFF808080
                && Themes.luma(0xFFFFFFFF) > 0.99 && Themes.luma(quartz.color(ThemeSpec.WINDOW)) > 0.5
                && Themes.luma(Themes.defaultTheme().color(ThemeSpec.WINDOW)) < 0.2);
        boolean contrast = true;
        for (ThemeSpec t : Themes.BUILTIN)
            if (Math.abs(Themes.luma(t.color(ThemeSpec.TEXT)) - Themes.luma(t.color(ThemeSpec.WINDOW))) < 0.5) contrast = false;
        check("тема: во встроенных текст читается на фоне", contrast && Themes.BUILTIN.size() == 6);

        List<ThemeSpec> mine = new ArrayList<>();
        ThemeSpec c1 = Themes.copyOf(Themes.defaultTheme(), mine);
        mine.add(c1);
        ThemeSpec c2 = Themes.copyOf(Themes.defaultTheme(), mine);
        check("тема: копия получает свободные id и имя", c1.id().equals("custom-1") && c2.id().equals("custom-2")
                && !c1.name().equalsIgnoreCase(c2.name()) && !Themes.isBuiltin(c1.id()));
        check("тема: имя чистится и обрезается", Themes.cleanName("a;b\n").equals("a b")
                && Themes.cleanName("x".repeat(40)).length() == Themes.MAX_NAME
                && Themes.uniqueName(c1.name(), mine, c1.id()).equals(c1.name()));

        Settings st = new Settings();
        st.customThemes.add(c1.withColor(ThemeSpec.ACCENT, 0x123456));
        st.theme = c1.id();
        Path cfg = dir.resolve("combatdata.json");
        st.save(cfg);
        Settings ld = Settings.load(cfg);
        check("тема: своя тема и выбор сохраняются", ld.theme.equals(c1.id()) && ld.customThemes.size() == 1
                && ld.themeSpec().color(ThemeSpec.ACCENT) == 0xFF123456 && ld.diffCount(st) == 0);
        ld.customThemes.clear();
        ld.clamp();
        check("тема: удалённая тема заменяется темой по умолчанию", ld.theme.equals(Themes.DEFAULT_ID)
                && ld.diffCount(st) == 2);
        Settings bad = Settings.fromMap(Map.of("theme", "nope", "customThemes", List.of(Map.of("id", "terracotta"), "x",
                Map.of("id", "c", "name", "N", "colors", Map.of("accent", "#00FF00", "text", "zz")))));
        check("тема: повреждённые записи тем отбрасываются, цвета из темы по умолчанию",
                bad.theme.equals(Themes.DEFAULT_ID) && bad.customThemes.size() == 1
                && bad.customThemes.get(0).color(ThemeSpec.ACCENT) == 0xFF00FF00
                && bad.customThemes.get(0).color(ThemeSpec.TEXT) == Themes.defaultTheme().color(ThemeSpec.TEXT));
    }

    static void fightSummary() {
        List<Event> ev = new ArrayList<>();
        for (int i = 0; i < 6; i++) ev.add(atk(1000 + i * 600L, A, i == 2 ? Outcome.MISS : Outcome.HIT_CONFIRMED, 2.5, 1f));
        ev.add(3, hit(2500, true));
        Recording r = new Recording(1, "me", B, "26.1.2", 0, 60_000, ev);
        JumpResetEvaluator.Params p = JumpResetEvaluator.Params.DEFAULT;
        StatFilter sf = new StatFilter(StatFilter.WeaponView.ALL, false, false, false, 150, 3, false);
        long end = 1000 + 5 * 600;
        check("итог боя: пока не прошло 10 с, ничего", FightSummary.last(r, end + 9_000, FightSummary.QUIET_MS, p, 100, sf, Trades.Params.DEFAULT) == null);
        FightSummary.Result fr = FightSummary.last(r, end + 10_000, FightSummary.QUIET_MS, p, 100, sf, Trades.Params.DEFAULT);
        check("итог боя: попадания, противник, длительность", fr != null && fr.hits() == 5 && fr.swings() == 6
                && "Alpha".equals(fr.opponent()) && fr.startMs() == 1000 && fr.endMs() == end && fr.jrAttempts() == 0);
        Lang.setEnglish(false);
        check("итог боя: строка", fr != null && FightSummary.text(fr).startsWith("5/6 попаданий")
                && FightSummary.title(fr).equals("Бой с Alpha · 0:03"));
        StatFilter big = new StatFilter(StatFilter.WeaponView.ALL, false, false, false, 150, 50, false);
        check("итог боя: бой меньше минимального размера не показывается",
                FightSummary.last(r, end + 20_000, FightSummary.QUIET_MS, p, 100, big, Trades.Params.DEFAULT) == null);
        FightSummary.Result smp = FightSummary.sample();
        List<FightSummary.Part> parts = FightSummary.parts(smp, FightSummary.Style.DEFAULT);
        check("итог боя: части по умолчанию и оценки", parts.size() == 4 && parts.get(0).quality() == FightSummary.GOOD
                && parts.get(1).text().equals("JR 3/5") && parts.get(3).text().equals("размены 3:1")
                && FightSummary.text(smp).equals("8/11 попаданий · JR 3/5 · комбо 4 · размены 3:1"));
        FightSummary.Style only = new FightSummary.Style(false, false, false, false, false, true, true, true, FightSummary.LAYOUT_COLUMN, false);
        check("итог боя: свой набор частей", FightSummary.join(FightSummary.parts(smp, only)).equals("дист. 2.84 · 3BH 2 · получено 6"));
        FightSummary.Result bad = new FightSummary.Result(0, 1000, null, 2, 10, 1, 4, 1, 0, 3);
        List<FightSummary.Part> bp = FightSummary.parts(bad, FightSummary.Style.DEFAULT);
        check("итог боя: плохие цифры красные, комбо 1 пропускается", bp.size() == 3 && bp.get(0).quality() == FightSummary.BAD
                && bp.get(1).quality() == FightSummary.BAD && bp.get(2).quality() == FightSummary.BAD
                && FightSummary.title(bad).equals("Бой · 0:01"));
        Settings fs = new Settings();
        fs.fsDelaySec = 100;
        fs.fsShowSec = 0;
        fs.fsLayout = 9;
        fs.fsReach = true;
        fs.clamp();
        Settings fs2 = Settings.fromMap(fs.toMap());
        fs2.fsX = 0.5;
        check("итог боя: настройки вида, пределы, положение не считается изменением", fs.fsDelaySec == 30 && fs.fsShowSec == 2
                && fs.fsLayout == FightSummary.LAYOUT_TWO_LINES && fs2.fsReach && fs2.fightStyle().reach() && fs2.diffCount(fs) == 0);
        List<Event> cev = new ArrayList<>();
        for (long t : new long[]{0, 500, 1000, 60_000, 60_500}) cev.add(atk(t, A, Outcome.HIT_CONFIRMED, 2.5, 1f));
        cev.add(atk(61_000, B, Outcome.HIT_CONFIRMED, 2.5, 1f));
        Stats.Result cr = Stats.compute(new Recording(1, "me", B, "26.1.2", 0, 70_000, cev), p, Stats.Filter.ALL, 3.0);
        check("комбо: пауза и смена цели прерывают серию", cr.combo().maxCombo() == 3
                && Stats.comboLengths(cev).equals(List.of(3, 2, 1)) && near(cr.combo().avgCombo(), 2.5, 1e-9));
        check("итог боя: пустая запись", FightSummary.last(new Recording(1, "me", B, "26.1.2", 0, 0, List.of()), 99_999,
                FightSummary.QUIET_MS, p, 100, sf, Trades.Params.DEFAULT) == null);
    }
}
