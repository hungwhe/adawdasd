package com.nova.addon.utils.rotation;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

public final class RotationMath {
    private RotationMath() {
    }

    public static float wrapYawDelta(float currentYaw, float targetYaw) {
        return MathHelper.wrapDegrees(targetYaw - currentYaw);
    }

    public static Vec2f toLookAngles(Vec3d target) {
        Vec3d eyePos = MeteorClient.mc.player.getEyePos();
        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        double horizontalDist = Math.hypot(dx, dz);

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));
        return new Vec2f(MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -90.0f, 90.0f));
    }

    public static Vec2f applyGcdFix(Vec2f previousRotation, Vec2f targetRotation) {
        double sensitivity = (Double) MeteorClient.mc.options.getMouseSensitivity().getValue();
        double f = sensitivity * 0.6 + 0.2;
        double gcd = f * f * f * 8.0 * 0.15;

        float prevYaw = previousRotation.x;
        float prevPitch = previousRotation.y;
        float rawDeltaYaw = wrapYawDelta(prevYaw, targetRotation.x);
        float rawDeltaPitch = targetRotation.y - prevPitch;

        float fixedDeltaYaw = (float) ((double) Math.round((double) rawDeltaYaw / gcd) * gcd);
        float fixedDeltaPitch = (float) ((double) Math.round((double) rawDeltaPitch / gcd) * gcd);

        return new Vec2f(
            prevYaw + fixedDeltaYaw,
            MathHelper.clamp(prevPitch + fixedDeltaPitch, -90.0f, 90.0f)
        );
    }

    public static float lerpAngle(float start, float end, float delta) {
        return start + MathHelper.wrapDegrees(end - start) * delta;
    }

    public static float cubicBezier(float t, float p0, float p1, float p2, float p3) {
        float u = 1.0f - t;
        return u * u * u * p0
            + 3.0f * u * u * t * p1
            + 3.0f * u * t * t * p2
            + t * t * t * p3;
    }

    public static float stepTowards(float current, float target, float maxStep, float speedFactor) {
        float diff = MathHelper.wrapDegrees(target - current);
        float step = Math.min(Math.abs(diff) * speedFactor, maxStep);
        return current + Math.copySign(step, diff);
    }

    public static float smoothSigmoidFactor(float angleDelta) {
        return (float) (1.0 / (1.0 + Math.exp(-0.08 * ((double) angleDelta - 25.0))));
    }
}
