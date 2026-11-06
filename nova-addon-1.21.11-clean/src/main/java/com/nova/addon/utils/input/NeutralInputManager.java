package com.nova.addon.utils.input;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

public final class NeutralInputManager {
    private static NeutralPhase phase = NeutralPhase.IDLE;
    private static boolean preserveJump = false;
    private static boolean preserveSneak = false;
    private static int holdTicks = 0;
    private static boolean inputReplaced = false;
    private static Input savedInput = null;

    private NeutralInputManager() {
    }

    public static void requestNeutral(boolean keepJump, boolean keepSneak) {
        preserveJump = keepJump;
        preserveSneak = keepSneak;
        if (phase == NeutralPhase.IDLE) {
            phase = NeutralPhase.PENDING;
            holdTicks = 0;
        }
    }

    public static void activateImmediate() {
        preserveJump = false;
        preserveSneak = false;
        holdTicks = 0;
        phase = NeutralPhase.ACTIVE;
    }

    public static void onPreTickMovement() {
        if (phase != NeutralPhase.PENDING && phase != NeutralPhase.ACTIVE) {
            return;
        }
        if (MeteorClient.mc.player == null) {
            reset();
            return;
        }

        savedInput = MeteorClient.mc.player.input;
        boolean jump = preserveJump && savedInput.playerInput.jump();
        boolean sneak = preserveSneak && savedInput.playerInput.sneak();

        MeteorClient.mc.player.input = new NeutralInput(jump, sneak);
        inputReplaced = true;
        MeteorClient.mc.player.setSprinting(false);
    }

    public static void onPostSendMovementPackets() {
        restoreInput();
        if (phase == NeutralPhase.PENDING && isServerMovementNeutral()) {
            phase = NeutralPhase.ACTIVE;
            holdTicks = 0;
        } else if (phase == NeutralPhase.ACTIVE) {
            holdTicks++;
        }
    }

    public static void restoreInput() {
        if (inputReplaced && MeteorClient.mc.player != null && savedInput != null) {
            MeteorClient.mc.player.input = savedInput;
        }
        savedInput = null;
        inputReplaced = false;
    }

    public static void reset() {
        restoreInput();
        phase = NeutralPhase.IDLE;
        preserveJump = false;
        preserveSneak = false;
        holdTicks = 0;
    }

    public static boolean isActive() {
        return phase == NeutralPhase.ACTIVE;
    }

    public static boolean isPending() {
        return phase == NeutralPhase.PENDING;
    }

    public static boolean isEngaged() {
        return phase != NeutralPhase.IDLE;
    }

    public static boolean hasTimedOut() {
        return phase == NeutralPhase.ACTIVE && holdTicks > 2;
    }

    private static boolean isServerMovementNeutral() {
        if (MeteorClient.mc.player == null) {
            return false;
        }
        PlayerInput lastInput = MeteorClient.mc.player.lastPlayerInput;
        boolean moveNeutral = !lastInput.forward() && !lastInput.backward() && !lastInput.left() && !lastInput.right() && !lastInput.sprint();
        boolean jumpOk = preserveJump || !lastInput.jump();
        boolean sneakOk = preserveSneak || !lastInput.sneak();
        boolean notSprinting = !MeteorClient.mc.player.lastSprinting;
        return moveNeutral && jumpOk && sneakOk && notSprinting;
    }

    private enum NeutralPhase {
        IDLE,
        PENDING,
        ACTIVE
    }

    private static final class NeutralInput extends Input {
        NeutralInput(boolean jump, boolean sneak) {
            this.playerInput = new PlayerInput(false, false, false, false, jump, sneak, false);
            this.movementVector = Vec2f.ZERO;
        }

        @Override
        public void tick() {
        }
    }
}
