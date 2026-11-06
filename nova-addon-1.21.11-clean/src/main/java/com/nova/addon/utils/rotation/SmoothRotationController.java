package com.nova.addon.utils.rotation;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;

public class SmoothRotationController {
    private final BooleanSupplier parentVisible;
    private final IntSupplier holdTicksSupplier;

    public final Setting<Boolean> yawStep;
    public final Setting<Integer> yawSteps;
    public final Setting<Double> yawStepAngle;
    public final Setting<Boolean> smoothCurve;
    public final Setting<Double> curveStrength;

    private float currentYaw;
    private float currentPitch;
    private boolean initialized;
    private int ticksSinceStep;

    public SmoothRotationController(SettingGroup group, BooleanSupplier parentVisible, IntSupplier holdTicksSupplier) {
        this.parentVisible = parentVisible != null ? parentVisible : () -> true;
        this.holdTicksSupplier = holdTicksSupplier != null ? holdTicksSupplier : () -> 1;

        this.yawStep = group.add(new BoolSetting.Builder()
            .name("Yaw Step")
            .description("Limits how far the rotation can turn in a single tick.")
            .defaultValue(false)
            .visible(this.parentVisible::getAsBoolean)
            .build()
        );

        this.yawSteps = group.add(new IntSetting.Builder()
            .name("Yaw Steps")
            .description("Maximum number of yaw steps allowed per tick.")
            .defaultValue(1)
            .min(1)
            .max(10)
            .sliderMin(1)
            .sliderMax(5)
            .visible(this::isYawStepVisible)
            .build()
        );

        this.yawStepAngle = group.add(new DoubleSetting.Builder()
            .name("Yaw Step Angle")
            .description("Maximum degrees moved per yaw step.")
            .defaultValue(45.0)
            .min(1.0)
            .max(180.0)
            .sliderMin(5.0)
            .sliderMax(180.0)
            .visible(this::isYawStepVisible)
            .build()
        );

        this.smoothCurve = group.add(new BoolSetting.Builder()
            .name("Smooth Curve")
            .description("Blends each step with a Bezier curve and human jitter.")
            .defaultValue(false)
            .visible(this::isYawStepVisible)
            .build()
        );

        this.curveStrength = group.add(new DoubleSetting.Builder()
            .name("Curve Strength")
            .description("How strongly the Bezier curve bends the rotation path.")
            .defaultValue(0.55)
            .min(0.1)
            .max(1.0)
            .sliderMin(0.1)
            .sliderMax(1.0)
            .visible(() -> this.isYawStepVisible() && this.smoothCurve.get())
            .build()
        );

        this.initialized = false;
        this.ticksSinceStep = 0;
    }

    private boolean isYawStepVisible() {
        return this.parentVisible.getAsBoolean() && this.yawStep.get();
    }

    public boolean isYawStepEnabled() {
        return this.yawStep.get();
    }

    public float getCurrentYaw() {
        return this.currentYaw;
    }

    public float getCurrentPitch() {
        return this.currentPitch;
    }

    public void reset() {
        this.initialized = false;
        this.ticksSinceStep = 0;
    }

    public void recordStepTick() {
        this.ticksSinceStep = 0;
    }

    public boolean isWithinTolerance(double targetYaw, double targetPitch, double tolerance) {
        if (!this.initialized) {
            return false;
        }
        return (double) Math.abs(MathHelper.wrapDegrees((float) targetYaw - this.currentYaw)) <= tolerance
            && (double) Math.abs((float) targetPitch - this.currentPitch) <= tolerance;
    }

    public Vec2f stepTowards(double targetYaw, double targetPitch) {
        Vec2f desired = new Vec2f((float) targetYaw, (float) targetPitch);
        int maxHoldTicks = Math.max(1, this.holdTicksSupplier.getAsInt());

        if (!this.initialized || this.ticksSinceStep >= maxHoldTicks) {
            syncFromCurrentAngles();
        }
        this.ticksSinceStep = 0;

        if (!this.yawStep.get()) {
            Vec2f fixed = RotationMath.applyGcdFix(new Vec2f(this.currentYaw, this.currentPitch), desired);
            this.currentYaw = fixed.x;
            this.currentPitch = fixed.y;
            return fixed;
        }

        float curYaw = this.currentYaw;
        float curPitch = this.currentPitch;
        int steps = Math.max(1, this.yawSteps.get());
        float maxAngle = this.yawStepAngle.get().floatValue();

        for (int i = 0; i < steps; i++) {
            float yawDiff = RotationMath.wrapYawDelta(curYaw, desired.x);
            float pitchDiff = desired.y - curPitch;
            float totalDist = (float) Math.hypot(yawDiff, pitchDiff);
            if (totalDist <= 1.0E-4f) {
                break;
            }

            float nextYaw;
            float nextPitch;

            if (this.smoothCurve.get()) {
                float strength = this.curveStrength.get().floatValue();
                float sigmoid = RotationMath.smoothSigmoidFactor(totalDist);
                float t = MathHelper.clamp((0.35f + 0.55f * sigmoid) * strength, 0.08f, 0.95f);

                float bezierYawFactor = RotationMath.cubicBezier(t, 0.0f, 0.25f * strength, Math.min(1.0f, 0.85f + 0.15f * strength), 1.0f);
                float bezierPitchFactor = RotationMath.cubicBezier(t, 0.0f, 0.2f * strength, 0.9f, 1.0f);

                float desiredYawStep = MathHelper.clamp(yawDiff * bezierYawFactor, -maxAngle, maxAngle);
                float ratio = Math.abs(yawDiff) > 0.001f ? Math.abs(desiredYawStep / yawDiff) : 1.0f;
                float maxPitchStep = Math.max(maxAngle * 0.65f, Math.abs(pitchDiff) * ratio);
                float desiredPitchStep = MathHelper.clamp(pitchDiff * bezierPitchFactor, -maxPitchStep, maxPitchStep);

                nextYaw = RotationMath.stepTowards(curYaw, curYaw + desiredYawStep, maxAngle, 1.0f);
                nextPitch = MathHelper.clamp(curPitch + desiredPitchStep, -90.0f, 90.0f);

                if (totalDist > 1.5f) {
                    float jitterScale = Math.min(1.0f, totalDist / 30.0f) * 0.18f * strength;
                    nextYaw += AngleUtils.randomFloat(-jitterScale, jitterScale);
                    nextPitch = MathHelper.clamp(nextPitch + AngleUtils.randomFloat(-jitterScale, jitterScale), -90.0f, 90.0f);
                }
            } else {
                float stepYaw = MathHelper.clamp(yawDiff, -maxAngle, maxAngle);
                float ratio = Math.abs(yawDiff) > 0.001f ? Math.abs(stepYaw / yawDiff) : 1.0f;
                float stepPitch = MathHelper.clamp(pitchDiff * ratio, -maxAngle, maxAngle);
                nextYaw = curYaw + stepYaw;
                nextPitch = MathHelper.clamp(curPitch + stepPitch, -90.0f, 90.0f);
            }

            Vec2f fixed = RotationMath.applyGcdFix(new Vec2f(curYaw, curPitch), new Vec2f(nextYaw, nextPitch));
            curYaw = fixed.x;
            curPitch = fixed.y;

            if (Math.abs(RotationMath.wrapYawDelta(curYaw, desired.x)) < 0.05f && Math.abs(desired.y - curPitch) < 0.05f) {
                break;
            }
        }

        this.currentYaw = curYaw;
        this.currentPitch = curPitch;
        return new Vec2f(curYaw, curPitch);
    }

    private void syncFromCurrentAngles() {
        if (NovaRotations.rotating) {
            this.currentYaw = NovaRotations.serverYaw;
            this.currentPitch = NovaRotations.serverPitch;
        } else if (MeteorClient.mc.player != null) {
            this.currentYaw = MeteorClient.mc.player.getYaw();
            this.currentPitch = MeteorClient.mc.player.getPitch();
        } else {
            this.currentYaw = 0.0f;
            this.currentPitch = 0.0f;
        }
        this.initialized = true;
    }

    public boolean tickIdleExpiration() {
        if (!this.initialized) {
            return false;
        }
        this.ticksSinceStep++;
        int maxHoldTicks = Math.max(1, this.holdTicksSupplier.getAsInt());
        if (this.ticksSinceStep >= maxHoldTicks) {
            this.initialized = false;
            return true;
        }
        return false;
    }
}
