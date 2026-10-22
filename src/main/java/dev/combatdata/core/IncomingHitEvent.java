package dev.combatdata.core;

import java.util.UUID;

/**
 * A hit taken from a player; t is when the client received the damage event.
 * kbAway: knockback distance over KB_MEASURE_MS along the line from the attacker (positive = away), NaN if unknown.
 * kind: PROJECTILE when the direct damage source is not the attacker.
 * reach: attacker's eyes to the nearest point of our hitbox, as seen on our screen.
 * attackerCrit: 1 crit (CRITICAL_HIT packet), 0 no crit, -1 unknown.
 * Fields added in later versions are NaN / -1 / null in older recordings; use the *Or accessors.
 */
public record IncomingHitEvent(long t, int tick, UUID attacker, String attackerName, boolean groundedAtHit,
                               boolean jumpHeld, boolean knockbackApplied, int pingMs, double kbAway, Kind kind,
                               String projectile, Weapon attackerWeapon, String attackerItem, int team, double reach,
                               double range, double attackerTowardBps, double selfAwayBps, int attackerPingMs,
                               int attackerCrit)
        implements Event {
    public static final long KB_MEASURE_MS = 500;

    public enum Kind {
        MELEE, PROJECTILE;

        public static Kind parse(String s) {
            if (s == null) return null;
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public IncomingHitEvent(long t, int tick, UUID attacker, String attackerName, boolean groundedAtHit,
                            boolean jumpHeld, boolean knockbackApplied, int pingMs, double kbAway, Kind kind,
                            String projectile, Weapon attackerWeapon, String attackerItem, int team, double reach,
                            double range, double attackerTowardBps, double selfAwayBps, int attackerPingMs) {
        this(t, tick, attacker, attackerName, groundedAtHit, jumpHeld, knockbackApplied, pingMs, kbAway, kind, projectile,
                attackerWeapon, attackerItem, team, reach, range, attackerTowardBps, selfAwayBps, attackerPingMs, -1);
    }

    public IncomingHitEvent(long t, int tick, UUID attacker, String attackerName, boolean groundedAtHit,
                            boolean jumpHeld, boolean knockbackApplied, int pingMs, double kbAway, Kind kind,
                            String projectile, Weapon attackerWeapon, String attackerItem, int team) {
        this(t, tick, attacker, attackerName, groundedAtHit, jumpHeld, knockbackApplied, pingMs, kbAway, kind, projectile,
                attackerWeapon, attackerItem, team, Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1);
    }

    public IncomingHitEvent(long t, int tick, UUID attacker, String attackerName, boolean groundedAtHit,
                            boolean jumpHeld, boolean knockbackApplied, int pingMs, double kbAway) {
        this(t, tick, attacker, attackerName, groundedAtHit, jumpHeld, knockbackApplied, pingMs, kbAway, null, null,
                null, null, Relation.TEAM_UNKNOWN);
    }

    public IncomingHitEvent(long t, int tick, UUID attacker, String attackerName, boolean groundedAtHit,
                            boolean jumpHeld, boolean knockbackApplied, int pingMs) {
        this(t, tick, attacker, attackerName, groundedAtHit, jumpHeld, knockbackApplied, pingMs, Double.NaN);
    }

    @Override
    public Event withT(long newT) {
        return new IncomingHitEvent(newT, tick, attacker, attackerName, groundedAtHit, jumpHeld, knockbackApplied, pingMs,
                kbAway, kind, projectile, attackerWeapon, attackerItem, team, reach, range, attackerTowardBps, selfAwayBps,
                attackerPingMs, attackerCrit);
    }

    /** Older recordings only logged hits with a player as the source, so null means melee. */
    public Kind kindOr() {
        return kind != null ? kind : Kind.MELEE;
    }

    public Weapon weaponOr() {
        return attackerWeapon != null ? attackerWeapon : Weapon.UNKNOWN;
    }

    public double rangeOr(double fallback) {
        return Double.isFinite(range) && range > 0 ? range : fallback;
    }

    public boolean rated() {
        return Double.isFinite(reach) && kindOr() == Kind.MELEE;
    }

    /** Caught by a 3 block hit: on our screen the attacker was beyond our range, so we could not hit back. */
    public boolean caught() {
        return rated() && reach > rangeOr(AttackEvent.DEFAULT_RANGE);
    }
}
