package dev.combatdata.mixin;

import dev.combatdata.client.IncomingCapture;
import dev.combatdata.client.Safe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server (ServerPlayer.crit) sends ClientboundAnimatePacket CRITICAL_HIT with the target id to everyone
 * tracking the attacker. TAIL because handleAnimate first runs on the network thread and returns early in
 * PacketUtils.ensureRunningOnSameThread; it only completes on the main thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void combatdata$animate(ClientboundAnimatePacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (packet.getAction() == ClientboundAnimatePacket.CRITICAL_HIT && mc.player != null && packet.getId() == mc.player.getId()) {
            Safe.run("крит по нам", IncomingCapture::onCritOnMe);
        }
    }
}
