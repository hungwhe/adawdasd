package com.nova.addon.modules.misc;

import com.nova.addon.NovaCategories;
import java.util.function.BooleanSupplier;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;

public class DiagonalInput extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    public final Setting<DiagonalMode> mode = this.sgGeneral.add(new EnumSetting.Builder<DiagonalMode>()
        .name("mode")
        .description("Diagonal = 8-direction movement fix. Free = smooth analog movement fix.")
        .defaultValue(DiagonalMode.Diagonal)
        .build()
    );

    public DiagonalInput() {
        super(NovaCategories.NOVA_MISC, "DiagonalInput", "Controls movement input correction during silent rotations.");
    }

    public static boolean isEnabled() {
        DiagonalInput instance = Modules.get().get(DiagonalInput.class);
        return instance != null && instance.isActive();
    }

    public static DiagonalMode getCurrentMode() {
        DiagonalInput instance = Modules.get().get(DiagonalInput.class);
        return instance != null ? instance.mode.get() : DiagonalMode.Diagonal;
    }

    public enum DiagonalMode {
        Diagonal,
        Free
    }

    public static final class Helper {
        private final BooleanSupplier enabledSupplier;
        private Input previousInput;
        private boolean active;

        public Helper(BooleanSupplier enabledSupplier) {
            this.enabledSupplier = enabledSupplier;
        }

        public void apply(float serverYaw, float serverPitch) {
            restore();
            if (!this.enabledSupplier.getAsBoolean()) {
                return;
            }
            if (Float.isNaN(serverYaw) || Float.isNaN(serverPitch)) {
                return;
            }
            if (MeteorClient.mc.player == null) {
                return;
            }

            Input currentInput = MeteorClient.mc.player.input;
            if (currentInput == null) {
                return;
            }

            PlayerInput rawKeys = currentInput.playerInput;
            Vec2f rawVec = currentInput.getMovementInput();
            float rawForward = rawVec.y;
            float rawSideways = rawVec.x;

            float clientYaw = MeteorClient.mc.player.getYaw();
            float deltaRad = (clientYaw - serverYaw) * ((float) Math.PI / 180.0f);
            float cos = MathHelper.cos(deltaRad);
            float sin = MathHelper.sin(deltaRad);

            double rotatedSideways = (double) rawSideways * (double) cos - (double) rawForward * (double) sin;
            double rotatedForward = (double) rawForward * (double) cos + (double) rawSideways * (double) sin;

            boolean forwardKey;
            boolean backwardKey;
            boolean leftKey;
            boolean rightKey;
            float outForward;
            float outSideways;

            if (DiagonalInput.getCurrentMode() == DiagonalMode.Free) {
                forwardKey = rotatedForward > 0.001;
                backwardKey = rotatedForward < -0.001;
                leftKey = rotatedSideways > 0.001;
                rightKey = rotatedSideways < -0.001;
                outForward = MathHelper.clamp((float) rotatedForward, -1.0f, 1.0f);
                outSideways = MathHelper.clamp((float) rotatedSideways, -1.0f, 1.0f);
            } else {
                long roundedForward = Math.round(rotatedForward);
                long roundedSideways = Math.round(rotatedSideways);
                forwardKey = roundedForward > 0L;
                backwardKey = roundedForward < 0L;
                leftKey = roundedSideways > 0L;
                rightKey = roundedSideways < 0L;
                outForward = encodeAxis(forwardKey, backwardKey);
                outSideways = encodeAxis(leftKey, rightKey);
                if (outForward != 0.0f && outSideways != 0.0f) {
                    outForward *= 0.70710677f;
                    outSideways *= 0.70710677f;
                }
            }

            boolean sneaking = rawKeys.sneak();
            if (sneaking) {
                outForward *= 0.3f;
                outSideways *= 0.3f;
            }

            PlayerInput correctedInput = new PlayerInput(
                forwardKey,
                backwardKey,
                leftKey,
                rightKey,
                rawKeys.jump(),
                sneaking,
                rawKeys.sprint()
            );

            this.previousInput = currentInput;
            this.active = true;
            MeteorClient.mc.player.input = new Wrapped(currentInput, correctedInput, new Vec2f(outSideways, outForward));
        }

        private static float encodeAxis(boolean positive, boolean negative) {
            if (positive == negative) {
                return 0.0f;
            }
            return positive ? 1.0f : -1.0f;
        }

        public void restore() {
            if (this.active) {
                if (MeteorClient.mc.player != null && this.previousInput != null) {
                    MeteorClient.mc.player.input = this.previousInput;
                }
                this.previousInput = null;
                this.active = false;
            }
        }

        private static final class Wrapped extends Input {
            private final Input delegate;

            Wrapped(Input delegate, PlayerInput playerInput, Vec2f movementVector) {
                this.delegate = delegate;
                this.playerInput = playerInput;
                this.movementVector = movementVector;
            }

            @Override
            public void tick() {
                this.delegate.tick();
            }

            @Override
            public void jump() {
                this.delegate.jump();
            }
        }
    }
}
