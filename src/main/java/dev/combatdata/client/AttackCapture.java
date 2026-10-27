package dev.combatdata.client;

import dev.combatdata.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Captures attacks on players. A hit waits for a damage event from the target with us as the source;
 * if none arrives in time it is recorded as HIT_UNCONFIRMED. Misses are recorded only with an intended target.
 */
public final class AttackCapture {
    private static final double TARGET_RANGE = 6.0;
    private static final double TARGET_CONE_DEG = 30.0;

    private record Pending(AttackEvent base, int entityId, long deadlineMs) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    private AttackCapture() {
    }

    /** HEAD of MultiPlayerGameMode.attack: the hit is not applied yet and attack strength is not reset. */
    public static void onAttack(Player attacker, Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!Recorder.active() || p == null || attacker != p || !(entity instanceof Player target) || target == p) return;
        Vec3 hit = null;
        if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == entity) hit = ehr.getLocation();
        AttackEvent e = build(p, target, Outcome.HIT_UNCONFIRMED, hit);
        long wait = Math.max(400L, 2L * Recorder.lastPing() + 200L);
        PENDING.add(new Pending(e, entity.getId(), e.t() + wait));
    }

    /** HEAD of Minecraft.startAttack. Conditions mirror vanilla so swings that never happen are not logged. */
    public static void onStartAttack(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!Recorder.active() || p == null || mc.level == null || mc.gameMode == null) return;
        if (mc.missTime > 0 || mc.hitResult == null || p.isHandsBusy() || mc.gameMode.isSpectator()) return;
        if (p.getItemInHand(InteractionHand.MAIN_HAND).get(DataComponents.PIERCING_WEAPON) != null) return;
        HitResult hr = mc.hitResult;
        if (hr.getType() == HitResult.Type.ENTITY) return;
        if (hr.getType() == HitResult.Type.BLOCK && hr instanceof BlockHitResult bhr
                && !mc.level.getBlockState(bhr.getBlockPos()).isAir()) return;
        Player target = intendedTarget(mc, p);
        if (target == null) return;
        AttackEvent miss = build(p, target, Outcome.MISS, null);
        Recorder.add(miss);
        Indicators.onMiss(miss);
    }

    /** HEAD of LivingEntity.handleDamageEvent for other entities: confirms our hit. */
    public static void onEntityDamaged(Entity victim, DamageSource source) {
        Minecraft mc = Minecraft.getInstance();
        Entity cause = source.getEntity();
        // Only our damage confirms a hit; fire, fall and other damage without an attacker (cause == null) does not.
        if (cause != mc.player) return;
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending pd = it.next();
            if (pd.entityId() == victim.getId()) {
                it.remove();
                AttackEvent hit = pd.base().withOutcome(Outcome.HIT_CONFIRMED);
                Recorder.add(hit);
                Indicators.onHit(hit);
                return;
            }
        }
    }

    public static void tick() {
        long n = Recorder.now();
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending pd = it.next();
            if (n > pd.deadlineMs()) {
                it.remove();
                Recorder.add(pd.base());
                Indicators.onMiss(pd.base());
            }
        }
    }

    public static void flushPending() {
        for (Pending pd : PENDING) Recorder.add(pd.base());
        PENDING.clear();
    }

    public static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    public static void clearPending() {
        PENDING.clear();
    }

    private static Player intendedTarget(Minecraft mc, LocalPlayer p) {
        Geometry.V3 eye = v(p.getEyePosition()), look = v(p.getViewVector(1.0F)).norm();
        Player best = null;
        double bestAngle = TARGET_CONE_DEG;
        for (Player other : mc.level.players()) {
            if (other == p || !other.isAlive() || other.isSpectator()) continue;
            Geometry.V3 to = box(other.getBoundingBox()).center().sub(eye);
            if (to.len() > TARGET_RANGE) continue;
            double a = Geometry.angleDeg(look, to);
            if (a < bestAngle) {
                bestAngle = a;
                best = other;
            }
        }
        return best;
    }

    private static AttackEvent build(LocalPlayer p, Player target, Outcome outcome, Vec3 hitPos) {
        float strength = p.getAttackStrengthScale(0.5F);
        double maxSweep = p.getSpeed() * 2.5;
        // The server compares against getKnownMovement (movement from the packet), not velocity, so use per-tick displacement.
        double mdx = p.getX() - p.xo, mdz = p.getZ() - p.zo;
        boolean slow = mdx * mdx + mdz * mdz < maxSweep * maxSweep;
        var held = p.getItemInHand(InteractionHand.MAIN_HAND);
        AttackFlags f = new AttackFlags(p.isSprinting(), p.onGround(), p.fallDistance, p.onClimbable(), p.isInWater(),
                p.isMobilityRestricted(), p.isPassenger(), held.is(ItemTags.SWORDS), slow);
        Geometry.V3 eye = v(p.getEyePosition()), look = v(p.getViewVector(1.0F)).norm();
        Geometry.Aabb box = box(target.getBoundingBox());
        Geometry.V3 center = box.center(), point;
        double reach;
        if (hitPos != null) {
            point = v(hitPos);
            reach = point.sub(eye).len();
        } else {
            reach = box.nearestDistance(eye);
            point = eye.add(look.scale(Math.max(0, center.sub(eye).dot(look))));
        }
        Geometry.Aim aim = Geometry.aim(eye, look, center);
        double[] uv = Geometry.hitLocal(box, point, look);
        double ray = hitPos != null ? reach : box.rayDistance(eye, look);
        // Velocities along the horizontal line from us to the target, from last tick's displacement (x - xo).
        Geometry.V3 u = new Geometry.V3(center.x() - eye.x(), 0, center.z() - eye.z()).norm();
        double selfToward = ((p.getX() - p.xo) * u.x() + (p.getZ() - p.zo) * u.z()) * 20.0;
        double targetAway = ((target.getX() - target.xo) * u.x() + (target.getZ() - target.zo) * u.z()) * 20.0;
        // Screen right for that line is (-u.z, u.x), the same convention as the hit point across the hitbox.
        double targetSide = ((target.getX() - target.xo) * -u.z() + (target.getZ() - target.zo) * u.x()) * 20.0;
        double targetVert = (target.getY() - target.yo) * 20.0;
        // yRotO is the yaw at the start of the last player tick, mouse turns since then are already in getYRot.
        // Yaw grows when turning right (south 0, west 90), the same sign as aimYaw.
        double turn = Geometry.wrap180(p.getYRot() - p.yRotO) * 20.0;
        long n = Recorder.now();
        return new AttackEvent(n, Recorder.tick(), target.getUUID(), target.getName().getString(), outcome,
                HitClassifier.classify(strength, f), f, strength, reach, aim.total(), aim.yaw(), aim.pitch(), uv[0],
                uv[1], Recorder.lastPing(), InputTimes.clickAt(n), ray, p.entityInteractionRange(), selfToward,
                targetAway, ping(target), p.getCurrentItemAttackStrengthDelay(), Weapons.id(held), Weapons.of(held),
                Weapons.team(p, target), targetSide, targetVert, turn);
    }

    /** Target ping from the player list; -1 if absent or hidden by the server (reported as 0). */
    static int ping(Player target) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) return -1;
        PlayerInfo info = conn.getPlayerInfo(target.getUUID());
        return info != null && info.getLatency() > 0 ? info.getLatency() : -1;
    }

    private static Geometry.V3 v(Vec3 x) { return new Geometry.V3(x.x, x.y, x.z); }

    private static Geometry.Aabb box(AABB b) {
        return new Geometry.Aabb(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }
}
