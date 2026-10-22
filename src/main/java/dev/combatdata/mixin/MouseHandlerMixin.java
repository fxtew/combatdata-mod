package dev.combatdata.mixin;

import dev.combatdata.client.InputTimes;
import dev.combatdata.client.Safe;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onButton", at = @At("HEAD"))
    private void combatdata$onButton(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
        Safe.run("клик мыши", () -> InputTimes.onMouseButton(info.button(), action));
    }
}
