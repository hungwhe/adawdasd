package com.nova.addon.mixin;

import com.nova.addon.events.PreTickMovementEvent;
import com.nova.addon.events.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPlayerEntity.class, priority = 900)
public class NClientPlayerEntityMixin {
    @Inject(
        method = "tickMovement",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/input/Input;tick()V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterInputTick(CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(PreTickMovementEvent.INSTANCE);
    }

    @Inject(method = "sendMovementPackets", at = @At("HEAD"))
    private void onSendMovementPacketsHead(CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(new SendMovementPacketsEvent.Pre());
    }

    @Inject(method = "sendMovementPackets", at = @At("RETURN"))
    private void onSendMovementPacketsReturn(CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(new SendMovementPacketsEvent.Post());
    }
}
