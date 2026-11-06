package com.nova.addon.utils.raytrace;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class RaytraceUtils {
    private static final Map<ScanKey, Optional<FaceScan>> FACE_SCAN_CACHE = new ConcurrentHashMap<>();
    private static long lastCacheClearTick = -1L;

    private RaytraceUtils() {
    }

    public static float[] getRotationAngles(Vec3d target) {
        if (MeteorClient.mc.player == null) {
            return null;
        }
        return getRotationAnglesFrom(MeteorClient.mc.player.getEyePos(), target);
    }

    public static float[] getRotationAnglesFrom(Vec3d from, Vec3d to) {
        if (to == null) {
            return null;
        }
        Vec3d diff = to.subtract(from);
        double horizontalDist = MathHelper.hypot(diff.x, diff.z);
        return new float[]{
            MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0f),
            MathHelper.wrapDegrees((float) (-Math.toDegrees(Math.atan2(diff.y, horizontalDist))))
        };
    }

    public static boolean canHitEntity(float yaw, float pitch, float range, float wallsRange, RtxMode mode, Entity target) {
        if (MeteorClient.mc.player == null || MeteorClient.mc.world == null || target == null) {
            return false;
        }
        switch (mode) {
            case ANY_ENTITY -> {
                EntityHitResult hit = raycastEntity(range, yaw, pitch);
                Entity entity = hit != null ? hit.getEntity() : null;
                return entity != null && entity.equals(target);
            }
            case TARGET_ONLY -> {
                HitResult hit = raycastTargetBox(yaw, pitch, range, wallsRange, target);
                return hit instanceof EntityHitResult entityHit && entityHit.getEntity() == target;
            }
            default -> throw new MatchException(null, null);
        }
    }

    public static EntityHitResult raycastEntity(double maxDistance, float yaw, float pitch) {
        if (MeteorClient.mc.player == null || MeteorClient.mc.world == null) {
            return null;
        }
        Vec3d start = MeteorClient.mc.player.getEyePos();
        Vec3d dir = getRotationVector(yaw, pitch);
        Vec3d end = start.add(dir.x * maxDistance, dir.y * maxDistance, dir.z * maxDistance);

        BlockHitResult blockHit = MeteorClient.mc.world.raycast(new RaycastContext(
            start,
            end,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            MeteorClient.mc.player
        ));

        double maxSq = maxDistance * maxDistance;
        if (blockHit != null && blockHit.getType() != HitResult.Type.MISS) {
            maxSq = blockHit.getPos().squaredDistanceTo(start);
        }

        EntityHitResult entityHit = ProjectileUtil.raycast(
            MeteorClient.mc.player,
            start,
            end,
            MeteorClient.mc.player.getBoundingBox().stretch(dir.multiply(maxDistance)).expand(1.0, 1.0, 1.0),
            entity -> !entity.isSpectator() && entity.isAlive() && entity.canHit(),
            maxSq
        );

        if (entityHit != null && entityHit.getPos().squaredDistanceTo(start) <= maxSq) {
            return entityHit;
        }
        return null;
    }

    public static HitResult raycastTargetBox(float yaw, float pitch, float range, float wallsRange, Entity target) {
        if (MeteorClient.mc.player == null || MeteorClient.mc.world == null || target == null) {
            return null;
        }
        double maxDist = Math.max(range, wallsRange);
        Vec3d start = MeteorClient.mc.player.getEyePos();
        Vec3d dir = getRotationVector(yaw, pitch);
        Vec3d end = start.add(dir.x * maxDist, dir.y * maxDist, dir.z * maxDist);

        HitResult blockHit = MeteorClient.mc.world.raycast(new RaycastContext(
            start,
            end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            MeteorClient.mc.player
        ));

        Box box = target.getBoundingBox();
        Optional<Vec3d> entityHitOpt = box.raycast(start, end);
        if (entityHitOpt.isEmpty()) {
            return blockHit;
        }

        Vec3d hitPos = entityHitOpt.get();
        double entityDistSq = start.squaredDistanceTo(hitPos);
        double blockDistSq = blockHit != null && blockHit.getType() != HitResult.Type.MISS
            ? start.squaredDistanceTo(blockHit.getPos())
            : Double.POSITIVE_INFINITY;

        if (entityDistSq <= blockDistSq) {
            if (entityDistSq <= (double) (range * range)) {
                return new EntityHitResult(target, hitPos);
            }
            return blockHit;
        }
        if (entityDistSq <= (double) (wallsRange * wallsRange)) {
            return new EntityHitResult(target, hitPos);
        }
        return blockHit;
    }

    public static Direction getVisiblePlaceSide(BlockPos pos) {
        return getBestVisibleFace(4.5, pos, true).map(FaceScan::direction).orElse(null);
    }

    private static Optional<FaceScan> getBestVisibleFace(double maxRange, BlockPos pos, boolean checkRange) {
        if (MeteorClient.mc.player == null || MeteorClient.mc.world == null) {
            return Optional.empty();
        }
        long tick = MeteorClient.mc.world.getTime();
        if (tick != lastCacheClearTick) {
            FACE_SCAN_CACHE.clear();
            lastCacheClearTick = tick;
        }
        ScanKey key = new ScanKey(MeteorClient.mc.player.getBlockPos(), pos.toImmutable());
        return FACE_SCAN_CACHE.computeIfAbsent(key, k -> scanBlockFaces(maxRange, pos, checkRange));
    }

    private static Optional<FaceScan> scanBlockFaces(double maxRange, BlockPos pos, boolean checkRange) {
        Vec3d eyePos = MeteorClient.mc.player.getEyePos();
        return getCandidateDirections(eyePos, pos.toCenterPos())
            .stream()
            .filter(dir -> !checkRange || isPointWithinRange(pos.toCenterPos().offset(dir, 0.5), maxRange))
            .flatMap(dir -> sampleFacePoints(pos, dir)
                .map(point -> raycastPoint(eyePos, point))
                .filter(hit -> hit instanceof BlockHitResult blockHit
                    && blockHit.getBlockPos().equals(pos)
                    && blockHit.getSide() == dir)
                .map(hit -> (BlockHitResult) hit)
                .map(blockHit -> {
                    float[] rot = getRotationAngles(blockHit.getPos());
                    return rot == null ? null : new FaceScan(rot, blockHit, blockHit.getSide());
                })
                .filter(scan -> scan != null)
            )
            .min(Comparator.comparingDouble(RaytraceUtils::getRotationDeltaToCurrent));
    }

    private static double getRotationDeltaToCurrent(FaceScan scan) {
        float[] rot = scan.rotation();
        if (rot == null || MeteorClient.mc.player == null) {
            return Double.MAX_VALUE;
        }
        return Math.hypot(
            MathHelper.wrapDegrees(rot[0] - MeteorClient.mc.player.getYaw()),
            rot[1] - MeteorClient.mc.player.getPitch()
        );
    }

    private static Stream<Vec3d> sampleFacePoints(BlockPos pos, Direction dir) {
        Vec3d faceCenter = pos.toCenterPos().offset(dir, 0.4999);
        Direction[] axes = getPerpendicularAxes(dir.getAxis());
        Vec3d axisA = new Vec3d(axes[0].getUnitVector());
        Vec3d axisB = new Vec3d(axes[1].getUnitVector());
        return StreamSupport.stream(BlockPos.iterate(-2, 0, -2, 2, 0, 2).spliterator(), false)
            .map(offset -> faceCenter.add(
                axisA.multiply((double) offset.getX() * 0.2).add(axisB.multiply((double) offset.getZ() * 0.2))
            ));
    }

    private static Direction[] getPerpendicularAxes(Direction.Axis axis) {
        return switch (axis) {
            case X -> new Direction[]{Direction.UP, Direction.SOUTH};
            case Y -> new Direction[]{Direction.EAST, Direction.SOUTH};
            case Z -> new Direction[]{Direction.UP, Direction.EAST};
        };
    }

    private static List<Direction> getCandidateDirections(Vec3d eyePos, Vec3d blockCenter) {
        return Stream.of(
            getSignDirection(eyePos.x - blockCenter.x, Direction.WEST, Direction.EAST),
            getSignDirection(eyePos.y - blockCenter.y, Direction.DOWN, Direction.UP),
            getSignDirection(eyePos.z - blockCenter.z, Direction.NORTH, Direction.SOUTH)
        ).filter(Optional::isPresent).map(Optional::get).toList();
    }

    private static Optional<Direction> getSignDirection(double delta, Direction negativeDir, Direction positiveDir) {
        if (delta < 0.0) {
            return Optional.of(negativeDir);
        }
        if (delta > 0.0) {
            return Optional.of(positiveDir);
        }
        return Optional.empty();
    }

    private static HitResult raycastPoint(Vec3d start, Vec3d targetPoint) {
        Vec3d delta = targetPoint.subtract(start);
        Vec3d end = start.add(delta.multiply(1.00001));
        return MeteorClient.mc.world.raycast(new RaycastContext(
            start,
            end,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            MeteorClient.mc.player
        ));
    }

    private static boolean isPointWithinRange(Vec3d point, double maxRange) {
        return MeteorClient.mc.player != null
            && MeteorClient.mc.player.getEyePos().squaredDistanceTo(point) <= maxRange * maxRange;
    }

    public static Vec3d getRotationVector(float yaw, float pitch) {
        float pitchRad = pitch * ((float) Math.PI / 180.0f);
        float yawRad = -yaw * ((float) Math.PI / 180.0f);
        float cosYaw = MathHelper.cos(yawRad);
        float sinYaw = MathHelper.sin(yawRad);
        float cosPitch = MathHelper.cos(pitchRad);
        float sinPitch = MathHelper.sin(pitchRad);
        return new Vec3d(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }

    public enum RtxMode {
        ANY_ENTITY,
        TARGET_ONLY
    }

    private record ScanKey(BlockPos playerBlock, BlockPos targetPos) {
    }

    private record FaceScan(float[] rotation, BlockHitResult hitResult, Direction direction) {
    }
}
