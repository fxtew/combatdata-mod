package dev.combatdata.core;

/**
 * Raw player state at attack time, stored so hits can be reclassified later.
 * restricted = Player.isMobilityRestricted(), which replaced the blindness check for crits in 26.1.2.
 */
public record AttackFlags(boolean sprinting, boolean onGround, double fallDistance, boolean onClimbable,
                          boolean inWater, boolean restricted, boolean passenger, boolean sword,
                          boolean slowHorizontal) {
}
