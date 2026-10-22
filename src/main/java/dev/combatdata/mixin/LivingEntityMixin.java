package dev.combatdata.mixin;

import dev.combatdata.client.AttackCapture;
import dev.combatdata.client.IncomingCapture;
import dev.combatdata.client.Safe;
import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void combatdata$jump(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && (Object) this == mc.player) Safe.run("прыжок", () -> IncomingCapture.onLocalJump(mc.player));
    }

    /** Called on the client from ClientPacketListener.handleDamageEvent for any living entity. */
    @Inject(method = "handleDamageEvent", at = @At("HEAD"))
    private void combatdata$damage(DamageSource source, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && (Object) this == mc.player) Safe.run("полученный удар", () -> IncomingCapture.onLocalDamaged(mc.player, source));
        else Safe.run("урон цели", () -> AttackCapture.onEntityDamaged((Entity) (Object) this, source));
    }
}
