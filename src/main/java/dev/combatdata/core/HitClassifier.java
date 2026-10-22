package dev.combatdata.core;

/**
 * Mirrors Player.attack in 26.1.2: full charge is getAttackStrengthScale(0.5) > 0.9, no crits while sprinting,
 * sweep needs a sword, ground contact and horizontal speed below getSpeed() * 2.5.
 */
public final class HitClassifier {
    public static final float FULL_CHARGE = 0.9f;

    private HitClassifier() {
    }

    public static HitType classify(float strength, AttackFlags f) {
        if (!(strength > FULL_CHARGE)) return HitType.WEAK;
        boolean crit = f.fallDistance() > 0 && !f.onGround() && !f.onClimbable() && !f.inWater()
                && !f.restricted() && !f.passenger() && !f.sprinting();
        if (crit) return HitType.CRIT;
        if (f.sprinting()) return HitType.SPRINT;
        if (f.sword() && f.onGround() && f.slowHorizontal()) return HitType.SWEEP;
        return HitType.NORMAL;
    }
}
