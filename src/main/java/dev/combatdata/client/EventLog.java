package dev.combatdata.client;

import dev.combatdata.CombatDataClient;
import dev.combatdata.core.*;

import java.util.Locale;

public final class EventLog {

    private EventLog() {
    }

    public static void log(Event e) {
        if (!dev.combatdata.CombatDataClient.settings().debugLog) return;
        String s = switch (e) {
            case AttackEvent a -> String.format(Locale.ROOT,
                    "удар по %s: %s%s, тип %s, заряд %.2f, дистанция %.2f, прицел %.1f° (yaw %+.1f, pitch %+.1f), UV %.2f/%.2f, клик %s",
                    a.targetName(), a.outcome(), a.outcome() != Outcome.MISS ? ""
                            : Double.isNaN(a.rayDist()) ? " (мимо прицелом)" : String.format(Locale.ROOT, " (луч %.2f)", a.rayDist()),
                    a.type(), a.strength(), a.reach(), a.aimTotalDeg(), a.aimYawDeg(),
                    a.aimPitchDeg(), a.hitU(), a.hitV(), a.clickT() < 0 ? "нет" : (a.t() - a.clickT()) + " мс назад")
                    + String.format(Locale.ROOT, ", перезарядка %.1f т.", a.cooldownOr())
                    + ", оружие " + a.weaponOr() + (a.item() != null ? " (" + a.item() + ")" : "") + ", команда " + a.team()
                    + threeBlock(a);
            case IncomingHitEvent h -> String.format(Locale.ROOT, "получен удар от %s: %s, нокбэк %s%s",
                    h.attackerName(), h.groundedAtHit() ? "на земле" : "в воздухе", h.knockbackApplied() ? "да" : "нет",
                    h.jumpHeld() ? ", прыжок был зажат" : "")
                    + (Double.isNaN(h.kbAway()) ? ", отлёт не измерен" : String.format(Locale.ROOT, ", отлёт %.2f бл.", h.kbAway()))
                    + ", " + h.kindOr() + (h.projectile() != null ? " " + h.projectile() : "") + ", оружие " + h.weaponOr()
                    + (h.attackerItem() != null ? " (" + h.attackerItem() + ")" : "") + ", команда " + h.team()
                    + (Double.isFinite(h.reach()) ? String.format(Locale.ROOT, ", на экране %.2f бл. (дальность %.1f), его скорость %+.1f, моя %+.1f, пинг %d%s",
                    h.reach(), h.rangeOr(3.0), h.attackerTowardBps(), h.selfAwayBps(), h.attackerPingMs(), h.caught() ? ", 3 block hit против меня" : "") : "")
                    + (h.attackerCrit() == 1 ? ", крит" : "");
            case JumpEvent j -> "прыжок" + (j.pressT() >= 0 ? ", клавиша нажата " + (j.t() - j.pressT()) + " мс назад" : "")
                    + ", клавиши " + keys(j) + (j.flyOut() ? " (fly out)" : "");
            case SegmentEvent g -> "сегмент: " + g.server() + ", " + g.dimension();
            case MarkerEvent m -> "метка: " + m.label();
            case PingEvent p -> null;
            case InputEvent in -> null;
            case PositionEvent p -> null;
        };
        if (s != null) CombatDataClient.LOG.info("[CD] t={} {}", formatTime(e.t()), s);
    }

    private static String keys(JumpEvent j) {
        if (!j.keysKnown()) return "?";
        String s = (j.has(JumpEvent.KEY_FORWARD) ? "W" : "") + (j.has(JumpEvent.KEY_LEFT) ? "A" : "")
                + (j.has(JumpEvent.KEY_BACK) ? "S" : "") + (j.has(JumpEvent.KEY_RIGHT) ? "D" : "")
                + (j.has(JumpEvent.KEY_SPRINT) ? "+спринт" : "");
        return s.isEmpty() ? "нет" : s;
    }

    private static String threeBlock(AttackEvent a) {
        int interp = CombatDataClient.settings().interpMs;
        double d = ThreeBlockHit.opponentDistance(a, interp);
        return String.format(Locale.ROOT, ", дальность %.2f, к цели %+.1f, цель от нас %+.1f б/с, пинг цели %s%s",
                a.range(), a.selfTowardBps(), a.targetAwayBps(), a.targetPingMs() < 0 ? "?" : a.targetPingMs() + " мс",
                Double.isNaN(d) ? "" : String.format(Locale.ROOT, ", у цели ≈%.2f%s", d,
                        ThreeBlockHit.is(a, interp) ? " (3 block hit)" : ""));
    }

    public static String formatTime(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d.%03d", s / 60, s % 60, ms % 1000);
    }
}
