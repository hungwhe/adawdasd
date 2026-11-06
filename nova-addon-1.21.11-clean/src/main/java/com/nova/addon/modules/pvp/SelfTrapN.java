package com.nova.addon.modules.pvp;

import com.nova.addon.NovaCategories;
import com.nova.addon.events.PreTickMovementEvent;
import com.nova.addon.events.SendMovementPacketsEvent;
import com.nova.addon.modules.misc.DiagonalInput;
import com.nova.addon.utils.rotation.NovaRotations;
import com.nova.addon.utils.world.BlockPlaceUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.GameMode;

public class SelfTrapN extends Module {
    private static final int ROTATION_PRIORITY = 80;

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgPause = this.settings.createGroup("Pause");
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<PlaceShape> mode = this.sgGeneral.add(new EnumSetting.Builder<PlaceShape>()
        .name("Mode")
        .defaultValue(PlaceShape.Around)
        .build()
    );

    private final Setting<List<Block>> blocks = this.sgGeneral.add(new BlockListSetting.Builder()
        .name("Blocks")
        .defaultValue(Blocks.OBSIDIAN)
        .build()
    );

    private final Setting<InteractMode> interact = this.sgGeneral.add(new EnumSetting.Builder<InteractMode>()
        .name("Interact")
        .description("Strict requires a solid neighbor block to place against. AirPlace allows placing without support.")
        .defaultValue(InteractMode.Strict)
        .build()
    );

    private final Setting<SendMode> placeMode = this.sgGeneral.add(new EnumSetting.Builder<SendMode>()
        .name("Place Mode")
        .description("Normal uses the real interaction manager. Packet sends the placement packet directly.")
        .defaultValue(SendMode.Normal)
        .build()
    );

    private final Setting<Integer> blocksPerTick = this.sgGeneral.add(new IntSetting.Builder()
        .name("Blocks Per Tick")
        .defaultValue(8)
        .min(1)
        .sliderMax(12)
        .build()
    );

    private final Setting<Integer> delay = this.sgGeneral.add(new IntSetting.Builder()
        .name("Delay")
        .defaultValue(3)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> feet = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Feet")
        .description("Places a block under your feet.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Rotate")
        .description("Silently rotates towards the block being placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Swing")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableOnFinish = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Disable On Finish")
        .description("Turns the module off once all trap positions are filled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onEat = this.sgPause.add(new BoolSetting.Builder()
        .name("On Eat")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onBreak = this.sgPause.add(new BoolSetting.Builder()
        .name("On Break")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> render = this.sgRender.add(new BoolSetting.Builder()
        .name("Render")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = this.sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("Shape Mode")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = this.sgRender.add(new ColorSetting.Builder()
        .name("Side Color")
        .defaultValue(new SettingColor(255, 255, 255, 50))
        .build()
    );

    private final Setting<SettingColor> lineColor = this.sgRender.add(new ColorSetting.Builder()
        .name("Line Color")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    public float lastRotationYaw = Float.NaN;
    public float lastRotationPitch = Float.NaN;
    private final DiagonalInput.Helper diagonalHelper = new DiagonalInput.Helper(
        () -> this.isActive() && this.rotate.get() && DiagonalInput.isEnabled()
    );
    private int delayTicks = 0;
    private volatile int activePlaceTimer = 0;
    private final Set<BlockPos> placedThisTick = new HashSet<>();
    private final List<BlockPos> renderPositions = new ArrayList<>();

    public SelfTrapN() {
        super(NovaCategories.NOVA_PVP, "SelfTrapN", "Places trap blocks around yourself.");
    }

    public boolean isActivelyPlacing() {
        return this.isActive() && this.activePlaceTimer > 0;
    }

    @Override
    public void onDeactivate() {
        this.renderPositions.clear();
        this.lastRotationYaw = Float.NaN;
        this.lastRotationPitch = Float.NaN;
        this.diagonalHelper.restore();
        this.delayTicks = 0;
        this.activePlaceTimer = 0;
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (this.mc.player == null || this.mc.world == null) {
            return;
        }
        this.lastRotationYaw = Float.NaN;
        this.lastRotationPitch = Float.NaN;

        if (this.mc.interactionManager == null || this.mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (PlayerUtils.shouldPause(this.onBreak.get(), this.onEat.get(), false)) {
            return;
        }

        if (this.activePlaceTimer > 0) {
            this.activePlaceTimer--;
        }
        if (this.delayTicks > 0) {
            this.delayTicks--;
            return;
        }

        FindItemResult blockItem = InvUtils.findInHotbar(this::isValidTrapItem);
        if (!blockItem.found()) {
            return;
        }

        List<BlockPos> targetPositions = computeTrapPositions();
        this.renderPositions.clear();
        this.renderPositions.addAll(targetPositions);
        this.placedThisTick.clear();

        int placedCount = 0;
        while (placedCount < this.blocksPerTick.get()) {
            BlockPos nextPos = findNextPlaceablePos(targetPositions);
            if (nextPos == null || !placeTrapBlock(nextPos, blockItem)) {
                break;
            }
            this.placedThisTick.add(nextPos);
            placedCount++;
        }

        if (placedCount > 0) {
            this.delayTicks = this.delay.get();
        }
        if (placedCount > 0 || findNextPlaceablePos(targetPositions) != null) {
            this.activePlaceTimer = this.delay.get() + 2;
        }
        if (this.disableOnFinish.get() && placedCount == 0 && findNextPlaceablePos(targetPositions) == null) {
            this.toggle();
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!this.render.get() || this.renderPositions.isEmpty()) {
            return;
        }
        for (BlockPos pos : this.renderPositions) {
            event.renderer.box(pos, this.sideColor.get(), this.lineColor.get(), this.shapeMode.get(), 0);
        }
    }

    private boolean placeTrapBlock(BlockPos pos, FindItemResult item) {
        Vec3d hitVec = BlockPlaceUtils.getTargetHitVec(pos);
        boolean placed = BlockPlaceUtils.place(
            pos,
            item,
            this.rotate.get(),
            ROTATION_PRIORITY,
            this.swing.get(),
            true,
            this.placeMode.get() == SendMode.Packet,
            this.interact.get() == InteractMode.AirPlace
        );
        if (placed && this.rotate.get() && Float.isNaN(this.lastRotationYaw)) {
            this.lastRotationYaw = (float) NovaRotations.getYaw(hitVec);
            this.lastRotationPitch = (float) NovaRotations.getPitch(hitVec);
        }
        return placed;
    }

    private BlockPos findNextPlaceablePos(List<BlockPos> candidates) {
        if (this.mc.player == null) {
            return null;
        }
        return candidates.stream()
            .filter(pos -> !this.placedThisTick.contains(pos))
            .filter(pos -> this.mc.world.getBlockState(pos).isReplaceable())
            .filter(this::canPlaceAt)
            .min(Comparator.comparingInt(Vec3i::getY)
                .thenComparing(pos -> -this.mc.player.squaredDistanceTo(pos.toCenterPos())))
            .orElse(null);
    }

    private boolean canPlaceAt(BlockPos pos) {
        if (this.interact.get() == InteractMode.AirPlace) {
            return true;
        }
        if (BlockUtils.getPlaceSide(pos) != null) {
            return true;
        }
        return hasSolidNeighbor(pos);
    }

    private List<BlockPos> computeTrapPositions() {
        List<BlockPos> result = new ArrayList<>();
        if (this.mc.player == null || this.mc.world == null) {
            return result;
        }

        Vec3d playerPos = this.mc.player.getEntityPos();
        List<BlockPos> footprint = computeFootprintPositions(playerPos);
        List<BlockPos> ring = computePerimeterPositions(playerPos);

        if (this.feet.get()) {
            result.addAll(footprint.stream().map(BlockPos::down).toList());
        }

        switch (this.mode.get()) {
            case Around -> {
                result.addAll(computeSupportPositions(ring));
                result.addAll(ring);
                result.addAll(ring.stream().map(BlockPos::up).toList());
            }
            case Top -> {
                result.addAll(computeSupportPositions(ring));
                result.addAll(ring);
                ring.stream().findFirst().ifPresent(first -> {
                    result.add(first.up());
                    result.add(first.up(2));
                });
                result.addAll(footprint.stream().map(pos -> pos.up(2)).toList());
            }
            case Full -> {
                result.addAll(computeSupportPositions(ring));
                result.addAll(ring);
                result.addAll(ring.stream().map(BlockPos::up).toList());
                ring.stream().map(pos -> pos.up(2)).findFirst().ifPresent(result::add);
                result.addAll(footprint.stream().map(pos -> pos.up(2)).toList());
            }
        }
        return result;
    }

    private List<BlockPos> computePerimeterPositions(Vec3d pos) {
        BlockPos base = BlockPos.ofFloored(pos);
        List<BlockPos> perimeter = new ArrayList<>();

        double fracX = Math.abs(pos.getX()) - Math.floor(Math.abs(pos.getX()));
        double fracZ = Math.abs(pos.getZ()) - Math.floor(Math.abs(pos.getZ()));

        int expandPosX = fracX >= 0.7 ? 1 : 0;
        int expandNegX = fracX <= 0.3 ? 1 : 0;
        int expandPosZ = fracZ >= 0.7 ? 1 : 0;
        int expandNegZ = fracZ <= 0.3 ? 1 : 0;

        for (int dx = 1; dx < expandPosX + 1; dx++) {
            perimeter.add(offsetSigned(base, dx, 0.0, 1 + expandPosZ));
            perimeter.add(offsetSigned(base, dx, 0.0, -(1 + expandNegZ)));
        }
        for (int dx = 0; dx <= expandNegX; dx++) {
            perimeter.add(offsetSigned(base, -dx, 0.0, 1 + expandPosZ));
            perimeter.add(offsetSigned(base, -dx, 0.0, -(1 + expandNegZ)));
        }
        for (int dz = 1; dz < expandPosZ + 1; dz++) {
            perimeter.add(offsetSigned(base, 1 + expandPosX, 0.0, dz));
            perimeter.add(offsetSigned(base, -(1 + expandNegX), 0.0, dz));
        }
        for (int dz = 0; dz <= expandNegZ; dz++) {
            perimeter.add(offsetSigned(base, 1 + expandPosX, 0.0, -dz));
            perimeter.add(offsetSigned(base, -(1 + expandNegX), 0.0, -dz));
        }
        return perimeter;
    }

    private List<BlockPos> computeFootprintPositions(Vec3d pos) {
        List<BlockPos> footprint = new ArrayList<>();
        double fracX = pos.getX() - Math.floor(pos.getX());
        double fracZ = pos.getZ() - Math.floor(pos.getZ());

        int stepX = fracX >= 0.7 ? 1 : (fracX <= 0.3 ? -1 : 0);
        int stepZ = fracZ >= 0.7 ? 1 : (fracZ <= 0.3 ? -1 : 0);

        BlockPos base = getPlayerAdjustedBlockPos(pos);
        footprint.add(base);
        for (int ix = 0; ix <= Math.abs(stepX); ix++) {
            for (int iz = 0; iz <= Math.abs(stepZ); iz++) {
                footprint.add(Objects.requireNonNull(base).add(ix * stepX, 0, iz * stepZ));
            }
        }
        return footprint;
    }

    private BlockPos getPlayerAdjustedBlockPos(Vec3d pos) {
        double y = pos.getY() - Math.floor(pos.getY()) > 0.8 ? Math.floor(pos.getY()) + 1.0 : Math.floor(pos.getY());
        return BlockPos.ofFloored(pos.getX(), y, pos.getZ());
    }

    private BlockPos offsetSigned(BlockPos origin, double dx, double dy, double dz) {
        if (origin.getX() < 0) {
            dx = -dx;
        }
        if (origin.getY() < 0) {
            dy = -dy;
        }
        if (origin.getZ() < 0) {
            dz = -dz;
        }
        return origin.add(BlockPos.ofFloored(dx, dy, dz));
    }

    private boolean hasSolidNeighbor(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (!this.mc.world.getBlockState(pos.offset(dir)).isReplaceable()) {
                return true;
            }
        }
        return false;
    }

    private List<BlockPos> computeSupportPositions(List<BlockPos> ring) {
        List<BlockPos> supports = new ArrayList<>();
        if (this.mc.world == null || this.mc.player == null) {
            return supports;
        }
        for (BlockPos pos : ring) {
            if (hasSolidNeighbor(pos)) {
                continue;
            }
            BlockPos below = pos.down();
            for (int depth = 0; depth < 3; depth++) {
                if (this.mc.world.getBlockState(below).isReplaceable()) {
                    supports.add(below);
                }
                if (hasSolidNeighbor(below)) {
                    break;
                }
                below = below.down();
            }
        }
        return supports;
    }

    @EventHandler
    private void onPreTickMovement(PreTickMovementEvent event) {
        this.diagonalHelper.apply(this.lastRotationYaw, this.lastRotationPitch);
    }

    @EventHandler
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        this.diagonalHelper.restore();
    }

    private boolean isValidTrapItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && this.blocks.get().contains(blockItem.getBlock());
    }

    public enum PlaceShape {
        Around,
        Top,
        Full
    }

    public enum InteractMode {
        Strict,
        AirPlace
    }

    public enum SendMode {
        Normal,
        Packet
    }
}
