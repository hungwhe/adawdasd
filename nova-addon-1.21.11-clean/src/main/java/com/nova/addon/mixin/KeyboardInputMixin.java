package com.nova.addon.mixin;

import com.nova.addon.modules.pvp.AutoTotemN;
import com.nova.addon.utils.input.InputOverrideModule;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickTail(CallbackInfo info) {
        Modules modules = Modules.get();
        if (modules == null) {
            return;
        }

        boolean unsprint = false;
        boolean stopForward = false;

        for (Module module : modules.getAll()) {
            if (module instanceof InputOverrideModule override) {
                if (override.shouldUnsprint()) {
                    unsprint = true;
                }
                if (override.shouldStopForward()) {
                    stopForward = true;
                }
            }
        }

        AutoTotemN autoTotem = modules.get(AutoTotemN.class);
        boolean lockMovement = autoTotem != null && autoTotem.isLockingMovement();

        if (!unsprint && !stopForward && !lockMovement) {
            return;
        }

        PlayerInput current = this.playerInput;
        boolean forward = lockMovement ? false : (stopForward ? false : current.forward());
        boolean backward = !lockMovement && current.backward();
        boolean left = !lockMovement && current.left();
        boolean right = !lockMovement && current.right();
        boolean jump = !lockMovement && current.jump();
        boolean sneak = current.sneak();
        boolean sprint = (lockMovement || unsprint) ? false : current.sprint();

        this.playerInput = new PlayerInput(forward, backward, left, right, jump, sneak, sprint);
    }
}
