package dev.combatdata.mixin;

import dev.combatdata.client.AttackCapture;
import dev.combatdata.client.Safe;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void combatdata$attack(Player player, Entity entity, CallbackInfo ci) {
        Safe.run("удар", () -> AttackCapture.onAttack(player, entity));
    }
}
