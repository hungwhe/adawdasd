package com.nova.addon.utils.rotation;

import com.nova.addon.events.MouseUpdateEvent;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.MathHelper;

public class CameraRotationSmoother {
    private boolean initialized;
    private boolean active;
    private double targetYaw;
    private double targetPitch;
    private double speedDegPerSec;
    private long lastUpdateNanos;

    public CameraRotationSmoother() {
        this.initialized = false;
        this.active = false;
        this.speedDegPerSec = 40.0;
        this.lastUpdateNanos = 0L;
    }

    public void init() {
        if (!this.initialized) {
            MeteorClient.EVENT_BUS.subscribe(this);
            this.initialized = true;
        }
    }

    public void setTarget(double yaw, double pitch, double speed) {
        this.init();
        if (MeteorClient.mc.player == null) {
            return;
        }
        this.targetYaw = yaw;
        this.targetPitch = MathHelper.clamp(pitch, -90.0, 90.0);
        this.speedDegPerSec = Math.max(1.0, speed);
        if (!this.active) {
            this.lastUpdateNanos = System.nanoTime();
        }
        this.active = true;
    }

    public void stop() {
        this.active = false;
    }

    public boolean isActive() {
        return this.active;
    }

    @EventHandler(priority = 200)
    private void onMouseUpdate(MouseUpdateEvent event) {
        if (!this.active || MeteorClient.mc.player == null) {
            this.active = false;
            return;
        }

        long now = System.nanoTime();
        double dt = (double) (now - this.lastUpdateNanos) / 1.0E9;
        this.lastUpdateNanos = now;
        if (dt <= 0.0 || dt > 0.25) {
            dt = 0.005;
        }

        double curYaw = MeteorClient.mc.player.getYaw();
        double curPitch = MeteorClient.mc.player.getPitch();
        double yawDiff = MathHelper.wrapDegrees(this.targetYaw - curYaw);
        double pitchDiff = this.targetPitch - curPitch;
        double dist = Math.hypot(yawDiff, pitchDiff);

        if (dist <= 0.02) {
            MeteorClient.mc.player.setYaw((float) (curYaw + yawDiff));
            MeteorClient.mc.player.setPitch((float) MathHelper.clamp(this.targetPitch, -90.0, 90.0));
            this.active = false;
            return;
        }

        double maxStep = this.speedDegPerSec * 20.0 * dt;
        double ratio = Math.min(1.0, maxStep / dist);
        double stepYaw = yawDiff * ratio;
        double stepPitch = pitchDiff * ratio;

        double cursorScale = computeCursorScale();
        if (cursorScale <= 1.0E-9) {
            MeteorClient.mc.player.setYaw((float) (curYaw + stepYaw));
            MeteorClient.mc.player.setPitch((float) MathHelper.clamp(curPitch + stepPitch, -90.0, 90.0));
        } else {
            double invertY = (Boolean) MeteorClient.mc.options.getInvertYMouse().getValue() ? -1.0 : 1.0;
            event.cursorDeltaX += stepYaw / cursorScale;
            event.cursorDeltaY += stepPitch * invertY / cursorScale;
        }

        if (ratio >= 1.0) {
            this.active = false;
        }
    }

    private static double computeCursorScale() {
        double sensitivity = (Double) MeteorClient.mc.options.getMouseSensitivity().getValue();
        double f = sensitivity * 0.6 + 0.2;
        double multiplier = f * f * f * 8.0;
        if (MeteorClient.mc.player != null && MeteorClient.mc.player.isUsingSpyglass()) {
            return f * f * f * 0.15;
        }
        return multiplier * 0.15;
    }
}
