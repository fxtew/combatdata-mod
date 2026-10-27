package dev.combatdata.core;

import java.util.UUID;

/**
 * An attack on a player: a hit, or a miss on an intended target.
 * reach: for a hit, eye to the ray/hitbox intersection; for a miss, eye to the nearest hitbox point.
 * rayDist: distance along the look ray to the hitbox, NaN if the ray misses. Separates "out of range" from "missed aim".
 * selfTowardBps / targetAwayBps: horizontal speeds along the line to the target, blocks/s (for 3 block hit).
 * targetSideBps: target speed across the screen, blocks/s, plus is to the right; targetVertBps: its vertical speed,
 * plus is up; turnDps: how fast the crosshair turned over the last tick, degrees/s, plus is to the right.
 * Fields added in later versions are NaN / -1 / null in older recordings; use the *Or accessors.
 */
public record AttackEvent(long t, int tick, UUID target, String targetName, Outcome outcome, HitType type,
                          AttackFlags flags, float strength, double reach, double aimTotalDeg, double aimYawDeg,
                          double aimPitchDeg, double hitU, double hitV, int pingMs, long clickT, double rayDist,
                          double range, double selfTowardBps, double targetAwayBps, int targetPingMs,
                          float cooldownTicks, String item, Weapon weapon, int team, double targetSideBps,
                          double targetVertBps, double turnDps) implements Event {
    /** Vanilla attack range, used when the event has none. */
    public static final double DEFAULT_RANGE = 3.0;

    public AttackEvent(long t, int tick, UUID target, String targetName, Outcome outcome, HitType type,
                       AttackFlags flags, float strength, double reach, double aimTotalDeg, double aimYawDeg,
                       double aimPitchDeg, double hitU, double hitV, int pingMs, long clickT, double rayDist,
                       double range, double selfTowardBps, double targetAwayBps, int targetPingMs,
                       float cooldownTicks, String item, Weapon weapon, int team) {
        this(t, tick, target, targetName, outcome, type, flags, strength, reach, aimTotalDeg, aimYawDeg, aimPitchDeg,
                hitU, hitV, pingMs, clickT, rayDist, range, selfTowardBps, targetAwayBps, targetPingMs, cooldownTicks,
                item, weapon, team, Double.NaN, Double.NaN, Double.NaN);
    }

    public AttackEvent(long t, int tick, UUID target, String targetName, Outcome outcome, HitType type,
                       AttackFlags flags, float strength, double reach, double aimTotalDeg, double aimYawDeg,
                       double aimPitchDeg, double hitU, double hitV, int pingMs, long clickT, double rayDist,
                       double range, double selfTowardBps, double targetAwayBps, int targetPingMs, float cooldownTicks) {
        this(t, tick, target, targetName, outcome, type, flags, strength, reach, aimTotalDeg, aimYawDeg, aimPitchDeg,
                hitU, hitV, pingMs, clickT, rayDist, range, selfTowardBps, targetAwayBps, targetPingMs, cooldownTicks,
                null, null, Relation.TEAM_UNKNOWN);
    }

    public AttackEvent(long t, int tick, UUID target, String targetName, Outcome outcome, HitType type,
                       AttackFlags flags, float strength, double reach, double aimTotalDeg, double aimYawDeg,
                       double aimPitchDeg, double hitU, double hitV, int pingMs, long clickT, double rayDist,
                       double range, double selfTowardBps, double targetAwayBps, int targetPingMs) {
        this(t, tick, target, targetName, outcome, type, flags, strength, reach, aimTotalDeg, aimYawDeg, aimPitchDeg,
                hitU, hitV, pingMs, clickT, rayDist, range, selfTowardBps, targetAwayBps, targetPingMs, Float.NaN);
    }

    public AttackEvent(long t, int tick, UUID target, String targetName, Outcome outcome, HitType type,
                       AttackFlags flags, float strength, double reach, double aimTotalDeg, double aimYawDeg,
                       double aimPitchDeg, double hitU, double hitV, int pingMs, long clickT, double rayDist) {
        this(t, tick, target, targetName, outcome, type, flags, strength, reach, aimTotalDeg, aimYawDeg, aimPitchDeg,
                hitU, hitV, pingMs, clickT, rayDist, Double.NaN, Double.NaN, Double.NaN, -1);
    }

    @Override
    public Event withT(long newT) {
        return new AttackEvent(newT, tick, target, targetName, outcome, type, flags, strength, reach, aimTotalDeg,
                aimYawDeg, aimPitchDeg, hitU, hitV, pingMs, clickT < 0 ? clickT : clickT + (newT - t), rayDist,
                range, selfTowardBps, targetAwayBps, targetPingMs, cooldownTicks, item, weapon, team, targetSideBps,
                targetVertBps, turnDps);
    }

    public AttackEvent withOutcome(Outcome o) {
        return new AttackEvent(t, tick, target, targetName, o, type, flags, strength, reach, aimTotalDeg, aimYawDeg,
                aimPitchDeg, hitU, hitV, pingMs, clickT, rayDist, range, selfTowardBps, targetAwayBps, targetPingMs,
                cooldownTicks, item, weapon, team, targetSideBps, targetVertBps, turnDps);
    }

    public AttackEvent withMotion(double sideBps, double vertBps, double turn) {
        return new AttackEvent(t, tick, target, targetName, outcome, type, flags, strength, reach, aimTotalDeg, aimYawDeg,
                aimPitchDeg, hitU, hitV, pingMs, clickT, rayDist, range, selfTowardBps, targetAwayBps, targetPingMs,
                cooldownTicks, item, weapon, team, sideBps, vertBps, turn);
    }

    /** True when the recording has target motion and crosshair turn speed (newer versions). */
    public boolean hasMotion() {
        return Double.isFinite(targetSideBps) && Double.isFinite(targetVertBps) && Double.isFinite(turnDps);
    }

    public Weapon weaponOr() {
        return weapon != null ? weapon : Weapon.fromSwordFlag(flags != null && flags.sword());
    }

    public double cooldownOr() {
        return Float.isFinite(cooldownTicks) && cooldownTicks > 0 ? cooldownTicks : Cooldown.DEFAULT_TICKS;
    }

    public double rangeOr(double fallback) {
        return Double.isFinite(range) && range > 0 ? range : fallback;
    }

    /** Miss where the ray hit the hitbox but beyond attack range. */
    public boolean outOfRangeMiss(double range) {
        return outcome == Outcome.MISS && !Double.isNaN(rayDist) && rayDist > range;
    }
}
