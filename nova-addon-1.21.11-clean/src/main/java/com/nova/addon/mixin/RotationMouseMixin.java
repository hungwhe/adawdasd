package com.nova.addon.mixin;

import com.nova.addon.events.MouseUpdateEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class RotationMouseMixin {
    @Shadow
    private double cursorDeltaX;

    @Shadow
    private double cursorDeltaY;

    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void onUpdateMouse(double timeDelta, CallbackInfo ci) {
        MouseUpdateEvent event = MouseUpdateEvent.get(this.cursorDeltaX, this.cursorDeltaY);
        MeteorClient.EVENT_BUS.post(event);
        this.cursorDeltaX = event.cursorDeltaX;
        this.cursorDeltaY = event.cursorDeltaY;
    }
}
