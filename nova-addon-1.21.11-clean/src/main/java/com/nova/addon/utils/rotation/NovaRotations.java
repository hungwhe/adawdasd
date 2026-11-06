package com.nova.addon.utils.rotation;

import com.nova.addon.events.SendMovementPacketsEvent;
import java.util.ArrayList;
import java.util.List;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent.Pre;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class NovaRotations {
    private static final Pool<Rotation> rotationPool = new Pool<>(Rotation::new);
    private static final List<Rotation> rotations = new ArrayList<>();
    public static float serverYaw;
    public static float serverPitch;
    public static int rotationTimer;
    private static float preYaw;
    private static float prePitch;
    private static int i = 0;
    private static Rotation lastRotation;
    private static int lastRotationTimer;
    private static boolean sentLastRotation;
    public static boolean rotating = false;
    private static boolean movementPacketActive = false;
    private static float activeMovementYaw;
    private static float activeMovementPitch;
    private static final CameraRotationSmoother smoother = new CameraRotationSmoother();
    private static boolean initialized = false;

    @PreInit
    public static void init() {
        if (!initialized) {
            MeteorClient.EVENT_BUS.subscribe(NovaRotations.class);
            smoother.init();
            initialized = true;
        }
    }

    public static void smoothLook(double yaw, double pitch, double speed) {
        smoother.setTarget(yaw, pitch, speed);
    }

    public static void stopSmoothLook() {
        smoother.stop();
    }

    public static void rotate(double yaw, double pitch, int priority, boolean clientSide, Object owner, Runnable callback) {
        Rotation rotation = rotationPool.get();
        rotation.set(yaw, pitch, priority, clientSide, owner, callback);

        if (owner != null) {
            for (int idx = rotations.size() - 1; idx >= 0; idx--) {
                Rotation existing = rotations.get(idx);
                if (existing.owner == owner && existing != lastRotation) {
                    if (existing.callback != null && rotation.callback == null) {
                        rotation.callback = existing.callback;
                    }
                    rotations.remove(idx);
                    rotationPool.free(existing);
                }
            }
        }

        int insertIdx = 0;
        while (insertIdx < rotations.size() && priority <= rotations.get(insertIdx).priority) {
            insertIdx++;
        }
        rotations.add(insertIdx, rotation);

        if (movementPacketActive && insertIdx == 0 && MeteorClient.mc.player != null) {
            setupMovementPacketRotation(rotation);
        }
    }

    public static void rotate(double yaw, double pitch, int priority, boolean clientSide, Runnable callback) {
        rotate(yaw, pitch, priority, clientSide, null, callback);
    }

    public static void rotate(double yaw, double pitch, int priority, Runnable callback) {
        rotate(yaw, pitch, priority, false, null, callback);
    }

    public static void rotate(double yaw, double pitch, Runnable callback) {
        rotate(yaw, pitch, 0, callback);
    }

    public static void rotate(double yaw, double pitch) {
        rotate(yaw, pitch, 0, null);
    }

    public static void cancelRotations(Object owner) {
        if (owner == null) {
            return;
        }
        for (int idx = rotations.size() - 1; idx >= 0; idx--) {
            Rotation r = rotations.get(idx);
            if (r.owner == owner) {
                rotations.remove(idx);
                rotationPool.free(r);
            }
        }
        if (lastRotation != null && lastRotation.owner == owner) {
            rotationPool.free(lastRotation);
            lastRotation = null;
            lastRotationTimer = Integer.MAX_VALUE;
        }
    }

    @EventHandler(priority = -200)
    private static void onSendMovementPacketsPre(SendMovementPacketsEvent.Pre event) {
        if (MeteorClient.mc.cameraEntity != MeteorClient.mc.player) {
            return;
        }
        sentLastRotation = false;

        if (!rotations.isEmpty()) {
            rotating = true;
            resetLastRotation();

            Rotation rotation = rotations.get(i);
            setupMovementPacketRotation(rotation);
            if (rotations.size() > 1) {
                rotationPool.free(rotation);
            }
            i++;
        } else if (lastRotation != null) {
            if (lastRotationTimer >= Config.get().rotationHoldTicks.get()) {
                resetLastRotation();
                rotating = false;
            } else {
                setupMovementPacketRotation(lastRotation);
                sentLastRotation = true;
                lastRotationTimer++;
            }
        }
    }

    private static void setupMovementPacketRotation(Rotation rotation) {
        if (!movementPacketActive) {
            preYaw = MeteorClient.mc.player.getYaw();
            prePitch = MeteorClient.mc.player.getPitch();
            movementPacketActive = true;
        }
        activeMovementYaw = (float) rotation.yaw;
        activeMovementPitch = (float) rotation.pitch;
        MeteorClient.mc.player.setYaw(activeMovementYaw);
        MeteorClient.mc.player.setPitch(activeMovementPitch);
    }

    @EventHandler(priority = 200)
    private static void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        if (!rotations.isEmpty()) {
            if (MeteorClient.mc.cameraEntity == MeteorClient.mc.player) {
                rotations.get(i - 1).runCallback();
                if (rotations.size() == 1) {
                    lastRotation = rotations.get(i - 1);
                }
                if (movementPacketActive) {
                    Rotation top = rotations.get(0);
                    if (!top.clientSide) {
                        MeteorClient.mc.player.setYaw(preYaw);
                        MeteorClient.mc.player.setPitch(prePitch);
                    }
                }
            }

            while (++i < rotations.size()) {
                Rotation r = rotations.get(i);
                setCamRotation(r.yaw, r.pitch);
                if (r.clientSide) {
                    setClientRotation(r.yaw, r.pitch);
                }
                r.sendPacket();
                r.runCallback();
                rotationPool.free(r);
            }
            rotations.clear();
            i = 0;
        } else if (sentLastRotation) {
            if (movementPacketActive && (lastRotation == null || !lastRotation.clientSide)) {
                MeteorClient.mc.player.setYaw(preYaw);
                MeteorClient.mc.player.setPitch(prePitch);
            }
        }
        movementPacketActive = false;
    }

    private static void resetLastRotation() {
        if (lastRotation != null) {
            rotationPool.free(lastRotation);
            lastRotation = null;
            lastRotationTimer = 0;
        }
    }

    @EventHandler
    private static void onTick(TickEvent.Pre event) {
        rotationTimer++;
    }

    public static boolean hasPendingRotation() {
        return !rotations.isEmpty() || (lastRotation != null && lastRotationTimer < Config.get().rotationHoldTicks.get());
    }

    public static float getPendingYaw() {
        if (!rotations.isEmpty()) {
            return (float) rotations.get(0).yaw;
        }
        if (lastRotation != null) {
            return (float) lastRotation.yaw;
        }
        return serverYaw;
    }

    public static float getPendingPitch() {
        if (!rotations.isEmpty()) {
            return (float) rotations.get(0).pitch;
        }
        if (lastRotation != null) {
            return (float) lastRotation.pitch;
        }
        return serverPitch;
    }

    public static double getYaw(Entity entity) {
        return MeteorClient.mc.player.getYaw() + MathHelper.wrapDegrees(
            (float) Math.toDegrees(Math.atan2(entity.getZ() - MeteorClient.mc.player.getZ(), entity.getX() - MeteorClient.mc.player.getX())) - 90.0f - MeteorClient.mc.player.getYaw()
        );
    }

    public static double getYaw(Vec3d pos) {
        return MeteorClient.mc.player.getYaw() + MathHelper.wrapDegrees(
            (float) Math.toDegrees(Math.atan2(pos.getZ() - MeteorClient.mc.player.getZ(), pos.getX() - MeteorClient.mc.player.getX())) - 90.0f - MeteorClient.mc.player.getYaw()
        );
    }

    public static double getPitch(Vec3d pos) {
        double diffX = pos.getX() - MeteorClient.mc.player.getX();
        double diffY = pos.getY() - (MeteorClient.mc.player.getY() + (double) MeteorClient.mc.player.getEyeHeight(MeteorClient.mc.player.getPose()));
        double diffZ = pos.getZ() - MeteorClient.mc.player.getZ();
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
        return MeteorClient.mc.player.getPitch() + MathHelper.wrapDegrees(
            (float) (-Math.toDegrees(Math.atan2(diffY, diffXZ))) - MeteorClient.mc.player.getPitch()
        );
    }

    public static double getPitch(Entity entity, Target target) {
        double y;
        if (target == Target.Head) {
            y = entity.getEyeY();
        } else if (target == Target.Body) {
            y = entity.getY() + (double) (entity.getHeight() / 2.0f);
        } else {
            y = entity.getY();
        }
        double diffX = entity.getX() - MeteorClient.mc.player.getX();
        double diffY = y - (MeteorClient.mc.player.getY() + (double) MeteorClient.mc.player.getEyeHeight(MeteorClient.mc.player.getPose()));
        double diffZ = entity.getZ() - MeteorClient.mc.player.getZ();
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
        return MeteorClient.mc.player.getPitch() + MathHelper.wrapDegrees(
            (float) (-Math.toDegrees(Math.atan2(diffY, diffXZ))) - MeteorClient.mc.player.getPitch()
        );
    }

    public static double getPitch(Entity entity) {
        return getPitch(entity, Target.Body);
    }

    public static double getYaw(BlockPos pos) {
        return MeteorClient.mc.player.getYaw() + MathHelper.wrapDegrees(
            (float) Math.toDegrees(Math.atan2((double) pos.getZ() + 0.5 - MeteorClient.mc.player.getZ(), (double) pos.getX() + 0.5 - MeteorClient.mc.player.getX())) - 90.0f - MeteorClient.mc.player.getYaw()
        );
    }

    public static double getPitch(BlockPos pos) {
        double diffX = (double) pos.getX() + 0.5 - MeteorClient.mc.player.getX();
        double diffY = (double) pos.getY() + 0.5 - (MeteorClient.mc.player.getY() + (double) MeteorClient.mc.player.getEyeHeight(MeteorClient.mc.player.getPose()));
        double diffZ = (double) pos.getZ() + 0.5 - MeteorClient.mc.player.getZ();
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
        return MeteorClient.mc.player.getPitch() + MathHelper.wrapDegrees(
            (float) (-Math.toDegrees(Math.atan2(diffY, diffXZ))) - MeteorClient.mc.player.getPitch()
        );
    }

    public static void setCamRotation(double yaw, double pitch) {
        serverYaw = (float) yaw;
        serverPitch = (float) pitch;
        rotationTimer = 0;
    }

    private static void setClientRotation(double yaw, double pitch) {
        if (MeteorClient.mc.player == null) {
            return;
        }
        float currentYaw = MeteorClient.mc.player.getYaw();
        float newYaw = currentYaw + MathHelper.wrapDegrees((float) yaw - currentYaw);
        float newPitch = MathHelper.clamp((float) pitch, -90.0f, 90.0f);
        MeteorClient.mc.player.setYaw(newYaw);
        MeteorClient.mc.player.setPitch(newPitch);
    }

    private static class Rotation {
        public double yaw;
        public double pitch;
        public int priority;
        public boolean clientSide;
        public Object owner;
        public Runnable callback;

        public void set(double yaw, double pitch, int priority, boolean clientSide, Object owner, Runnable callback) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.priority = priority;
            this.clientSide = clientSide;
            this.owner = owner;
            this.callback = callback;
        }

        public void sendPacket() {
            MeteorClient.mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround((float) this.yaw, (float) this.pitch, MeteorClient.mc.player.isOnGround(), MeteorClient.mc.player.horizontalCollision)
            );
        }

        public void runCallback() {
            if (this.callback != null) {
                this.callback.run();
            }
        }
    }
}
