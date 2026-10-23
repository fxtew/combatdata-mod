package dev.combatdata.client;

import dev.combatdata.core.IncomingHitEvent;
import dev.combatdata.core.JumpEvent;
import dev.combatdata.core.Weapon;
import dev.combatdata.core.Geometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Knockback counts as applied if horizontal speed exceeds 0.065 in any of the three ticks after the hit.
 * Knockback distance is measured over 10 ticks along the line from the attacker to us; if another hit lands
 * during that window the measurement is discarded (NaN).
 */
public final class IncomingCapture {
    private static final double KNOCKBACK_SPEED = 0.065;
    private static final int KNOCKBACK_TICKS = 3;
    private static final int KB_MEASURE_TICKS = (int) (IncomingHitEvent.KB_MEASURE_MS / 50);
    private static final long HELD_MS = 150;

    private static final class Pending {
        final long t;
        final int tick, ping;
        final UUID attacker;
        final String name;
        final boolean grounded, held;
        final double sx, sz, ux, uz;
        int kbTicksLeft = KNOCKBACK_TICKS, measureLeft = KB_MEASURE_TICKS;
        boolean kbKnown, kb, interrupted;
        boolean measure = true;
        IncomingHitEvent.Kind kind;
        String projectile, item;
        Weapon weapon;
        int team;
        double reach = Double.NaN, range = Double.NaN, attV = Double.NaN, selfV = Double.NaN;
        int attPing = -1;
        int crit;

        Pending(long t, int tick, UUID attacker, String name, boolean grounded, boolean held, int ping,
                double sx, double sz, double ux, double uz) {
            this.t = t;
            this.tick = tick;
            this.attacker = attacker;
            this.name = name;
            this.grounded = grounded;
            this.held = held;
            this.ping = ping;
            this.sx = sx;
            this.sz = sz;
            this.ux = ux;
            this.uz = uz;
        }

        IncomingHitEvent event(double kbAway) {
            return new IncomingHitEvent(t, tick, attacker, name, grounded, held, kbKnown && kb, ping, kbAway, kind,
                    projectile, weapon, item, team, reach, range, attV, selfV, attPing, crit);
        }
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static boolean jumpDown;
    private static long jumpPressMs = -1;

    private IncomingCapture() {
    }

    /** HEAD of LivingEntity.handleDamageEvent for the local player. */
    public static void onLocalDamaged(LocalPlayer p, DamageSource source) {
        if (!Recorder.active()) return;
        Entity cause = source.getEntity();
        if (!(cause instanceof Player attacker) || attacker == p) return;
        long n = Recorder.now();
        boolean held = jumpDown && jumpPressMs >= 0 && n - jumpPressMs > HELD_MS;
        for (Pending pd : PENDING) pd.interrupted = true;
        double dx = p.getX() - attacker.getX(), dz = p.getZ() - attacker.getZ(), len = Math.hypot(dx, dz);
        double ux = len < 1e-6 ? 0 : dx / len, uz = len < 1e-6 ? 0 : dz / len;
        Pending pd = new Pending(n, Recorder.tick(), attacker.getUUID(), attacker.getName().getString(), p.onGround(),
                held, Recorder.lastPing(), p.getX(), p.getZ(), ux, uz);
        // Direct source differs from the attacker for projectiles (rod, arrow, snowball). No direct source means melee.
        Entity direct = source.getDirectEntity();
        boolean ranged = direct != null && direct != attacker;
        pd.kind = ranged ? IncomingHitEvent.Kind.PROJECTILE : IncomingHitEvent.Kind.MELEE;
        pd.projectile = ranged ? Weapons.typeId(direct) : null;
        var stack = attacker.getMainHandItem();
        pd.weapon = Weapons.of(stack);
        pd.item = Weapons.id(stack);
        pd.team = Weapons.team(p, attacker);
        if (!dev.combatdata.CombatDataClient.settings().recordKnockback) {
            pd.measure = false;
            pd.measureLeft = 0;
        }
        // Attacker's view: from their eyes to the nearest point of our hitbox. Velocities from per-tick displacement.
        if (dev.combatdata.CombatDataClient.settings().recordTaken) {
            var eye = attacker.getEyePosition();
            var bb = p.getBoundingBox();
            pd.reach = new Geometry.Aabb(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ)
                    .nearestDistance(new Geometry.V3(eye.x, eye.y, eye.z));
            pd.range = p.entityInteractionRange();
            pd.attV = ((attacker.getX() - attacker.xo) * ux + (attacker.getZ() - attacker.zo) * uz) * 20.0;
            pd.selfV = ((p.getX() - p.xo) * ux + (p.getZ() - p.zo) * uz) * 20.0;
            pd.attPing = AttackCapture.ping(attacker);
        }
        PENDING.add(pd);
        Indicators.onTaken(held);
    }

    /** HEAD of LivingEntity.jumpFromGround for the local player. */
    public static void onLocalJump(LocalPlayer p) {
        if (!Recorder.active()) return;
        var o = Minecraft.getInstance().options;
        int keys = JumpEvent.keys(o.keyUp.isDown(), o.keyDown.isDown(), o.keyLeft.isDown(), o.keyRight.isDown(),
                o.keySprint.isDown() || p.isSprinting());
        JumpEvent j = new JumpEvent(Recorder.now(), Recorder.tick(), p.onGround(), jumpDown ? jumpPressMs : -1, keys);
        Recorder.add(j);
        Indicators.onJump(j.flyOut());
    }

    public static void tick(Minecraft mc) {
        boolean down = mc.player != null && mc.options.keyJump.isDown();
        if (down && !jumpDown) jumpPressMs = Recorder.now();
        if (!down) jumpPressMs = -1;
        jumpDown = down;
        if (mc.player == null) return;
        LocalPlayer p = mc.player;
        double speed = p.getDeltaMovement().horizontalDistance();
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending pd = it.next();
            if (!pd.kbKnown) {
                if (speed > KNOCKBACK_SPEED) {
                    pd.kbKnown = true;
                    pd.kb = true;
                } else if (--pd.kbTicksLeft <= 0) pd.kbKnown = true;
            }
            if (!pd.interrupted) pd.measureLeft--;
            if (pd.kbKnown && (pd.interrupted || pd.measureLeft <= 0)) {
                it.remove();
                double away = !pd.measure || pd.interrupted || (pd.ux == 0 && pd.uz == 0) ? Double.NaN
                        : (p.getX() - pd.sx) * pd.ux + (p.getZ() - pd.sz) * pd.uz;
                Recorder.add(pd.event(away));
            }
        }
    }

    public static void flushPending() {
        for (Pending pd : PENDING) Recorder.add(pd.event(Double.NaN));
        PENDING.clear();
    }

    /** CRITICAL_HIT packet targeting us: marks the most recent hit taken (at most 300 ms old). */
    public static void onCritOnMe() {
        if (PENDING.isEmpty()) return;
        Pending last = PENDING.get(PENDING.size() - 1);
        if (Recorder.now() - last.t <= 300) last.crit = 1;
    }

    public static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    public static void clearPending() {
        PENDING.clear();
    }
}
