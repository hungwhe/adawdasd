package com.nova.addon.modules.pvp;

import com.nova.addon.NovaCategories;
import com.nova.addon.events.PreTickMovementEvent;
import com.nova.addon.events.SendMovementPacketsEvent;
import com.nova.addon.modules.misc.DiagonalInput;
import com.nova.addon.utils.inventory.InventorySwapUtils;
import com.nova.addon.utils.inventory.SlotSwapResult;
import com.nova.addon.utils.raytrace.RaytraceUtils;
import com.nova.addon.utils.rotation.AngleUtils;
import com.nova.addon.utils.rotation.NovaRotations;
import com.nova.addon.utils.rotation.SmoothRotationController;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.AbstractFireballEntity;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.MaceItem;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

public class KillAuraN extends Module {
    private static final int OLD_DELAY_PRE_TICKS = 1;
    private static final float COOLDOWN_PRE_ROTATE_LEAD = 0.25f;
    private static final int OLD_DELAY_PRE_ROTATE_TICKS = 3;
    private static final int ROTATION_HOLD_TICKS = 10;
    private static final Object ROTATION_OWNER = new Object();

    public static Entity currentTarget;

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = this.settings.createGroup("Advanced");
    private final SettingGroup sgRotation = this.settings.createGroup("Rotation");
    private final SettingGroup sgTargets = this.settings.createGroup("Targets");
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    public final Setting<Double> range = this.sgGeneral.add(new DoubleSetting.Builder()
        .name("Range")
        .defaultValue(3.1)
        .min(1.0)
        .max(6.0)
        .sliderMax(6.0)
        .build()
    );

    public final Setting<Double> throughWallsRange = this.sgGeneral.add(new DoubleSetting.Builder()
        .name("Through Walls Range")
        .defaultValue(0.0)
        .min(0.0)
        .max(6.0)
        .sliderMax(6.0)
        .build()
    );

    public final Setting<Integer> fov = this.sgGeneral.add(new IntSetting.Builder()
        .name("Fov")
        .defaultValue(360)
        .min(1)
        .max(360)
        .sliderMax(360)
        .build()
    );

    public final Setting<RotationMode> rotate = this.sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("Rotate")
        .defaultValue(RotationMode.Always)
        .build()
    );

    public final Setting<Switch> autoWeapon = this.sgGeneral.add(new EnumSetting.Builder<Switch>()
        .name("Auto Weapon")
        .defaultValue(Switch.None)
        .build()
    );

    public final Setting<Boolean> clientRotate = this.sgAdvanced.add(new BoolSetting.Builder()
        .name("Client Rotate")
        .defaultValue(false)
        .visible(() -> this.rotate.get() != RotationMode.None)
        .build()
    );

    public final Setting<Boolean> onlyWeapon = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Only Weapon")
        .defaultValue(false)
        .visible(() -> this.autoWeapon.get() != Switch.Silent)
        .build()
    );

    public final Setting<Boolean> smartCrit = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Smart Crit")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> onlyCrit = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Only Crit")
        .defaultValue(false)
        .visible(this.smartCrit::get)
        .build()
    );

    public final Setting<Boolean> autoJump = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Jump")
        .defaultValue(false)
        .visible(this.smartCrit::get)
        .build()
    );

    public final Setting<Boolean> attack = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Attack")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> shieldBreaker = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Shield Breaker")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> pauseWhileEating = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Pause While Eating")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> oldDelay = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Old Delay")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> minCps = this.sgGeneral.add(new IntSetting.Builder()
        .name("Min Cps")
        .defaultValue(7)
        .min(1)
        .max(20)
        .visible(this.oldDelay::get)
        .build()
    );

    public final Setting<Integer> maxCps = this.sgGeneral.add(new IntSetting.Builder()
        .name("Max Cps")
        .defaultValue(12)
        .min(1)
        .max(20)
        .visible(this.oldDelay::get)
        .build()
    );

    public final Setting<Sort> sort = this.sgGeneral.add(new EnumSetting.Builder<Sort>()
        .name("Sort")
        .defaultValue(Sort.LowestDistance)
        .build()
    );

    public final Setting<Boolean> lockTarget = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Lock Target")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> aimRange = this.sgAdvanced.add(new DoubleSetting.Builder()
        .name("Aim Range")
        .defaultValue(0.0)
        .min(0.0)
        .max(6.0)
        .sliderMax(6.0)
        .build()
    );

    public final Setting<Boolean> pauseInInventory = this.sgAdvanced.add(new BoolSetting.Builder()
        .name("Pause In Inventory")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> dropSprint = this.sgAdvanced.add(new BoolSetting.Builder()
        .name("Drop Sprint")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> returnSprint = this.sgAdvanced.add(new BoolSetting.Builder()
        .name("Return Sprint")
        .defaultValue(true)
        .visible(this.dropSprint::get)
        .build()
    );

    public final Setting<Double> attackCooldown = this.sgAdvanced.add(new DoubleSetting.Builder()
        .name("Attack Cooldown")
        .defaultValue(0.9)
        .min(0.5)
        .max(1.0)
        .build()
    );

    public final Setting<Double> attackBaseTime = this.sgAdvanced.add(new DoubleSetting.Builder()
        .name("Attack Base Time")
        .defaultValue(0.5)
        .min(0.0)
        .max(2.0)
        .build()
    );

    public final Setting<Double> linearSpeed = this.sgRotation.add(new DoubleSetting.Builder()
        .name("Linear Speed")
        .defaultValue(40.0)
        .min(1.0)
        .max(180.0)
        .sliderMax(180.0)
        .visible(() -> this.rotate.get() != RotationMode.None && this.clientRotate.get())
        .build()
    );

    private final SmoothRotationController smoothController = new SmoothRotationController(
        this.sgRotation,
        () -> this.rotate.get() != RotationMode.None,
        () -> Math.max(1, Config.get().rotationHoldTicks.get())
    );

    public final Setting<Set<EntityType<?>>> targets = this.sgTargets.add(new EntityTypeListSetting.Builder()
        .name("Targets")
        .defaultValue(EntityType.PLAYER)
        .build()
    );

    public final Setting<Boolean> ignoreCreative = this.sgTargets.add(new BoolSetting.Builder()
        .name("Ignore Creative")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> attackShieldingEntities = this.sgTargets.add(new BoolSetting.Builder()
        .name("Attack Shielding Entities")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> esp = this.sgRender.add(new BoolSetting.Builder()
        .name("Esp")
        .defaultValue(true)
        .build()
    );

    public final Setting<SettingColor> espColor = this.sgRender.add(new ColorSetting.Builder()
        .name("Esp Color")
        .defaultValue(new SettingColor(255, 0, 0, 60))
        .visible(this.esp::get)
        .build()
    );

    public final Setting<ShapeMode> shapeMode = this.sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("Shape Mode")
        .defaultValue(ShapeMode.Both)
        .visible(this.esp::get)
        .build()
    );

    private Vec3d hitPointOffset = Vec3d.ZERO;
    private boolean attackingThisTick = false;
    private int preAttackCountdown = 0;
    private int attackDelayTicks = 0;
    private boolean firstHitReady = true;
    private float targetYaw;
    private float targetPitch;
    private boolean hasAimRotation;
    private int ticksSinceRotation = ROTATION_HOLD_TICKS;
    private boolean rotatedThisTick;

    private final DiagonalInput.Helper diagonalHelper = new DiagonalInput.Helper(
        () -> this.isActive() && this.rotate.get() != RotationMode.None && DiagonalInput.isEnabled()
    );

    public KillAuraN() {
        super(NovaCategories.NOVA_PVP, "KillAuraN", "KillAuraN");
    }

    private float getRange() {
        return this.range.get().floatValue();
    }

    private float getWallsRange() {
        return this.throughWallsRange.get().floatValue();
    }

    @Override
    public void onActivate() {
        currentTarget = null;
        this.hitPointOffset = Vec3d.ZERO;
        this.attackDelayTicks = 0;
        this.preAttackCountdown = 0;
        this.firstHitReady = true;
        this.smoothController.reset();
        resetRotationState();
    }

    @Override
    public void onDeactivate() {
        currentTarget = null;
        NovaRotations.cancelRotations(ROTATION_OWNER);
        this.smoothController.reset();
        resetRotationState();
    }

    private void resetRotationState() {
        this.hasAimRotation = false;
        this.ticksSinceRotation = ROTATION_HOLD_TICKS;
        this.rotatedThisTick = false;
        this.diagonalHelper.restore();
    }

    private boolean isInstantOnHitRotation() {
        return this.rotate.get() == RotationMode.OnHit && !this.smoothController.isYawStepEnabled();
    }

    private float getEffectiveYaw() {
        return (this.rotate.get() != RotationMode.None && this.hasAimRotation) ? this.targetYaw : this.mc.player.getYaw();
    }

    private float getEffectivePitch() {
        return (this.rotate.get() != RotationMode.None && this.hasAimRotation) ? this.targetPitch : this.mc.player.getPitch();
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        this.attackingThisTick = false;
        this.rotatedThisTick = false;
        this.ticksSinceRotation++;

        boolean keepOnHitAim = this.rotate.get() == RotationMode.OnHit && NovaRotations.rotating && this.hasAimRotation;
        if (!keepOnHitAim && (this.rotate.get() == RotationMode.OnHit || (this.smoothController.isYawStepEnabled() && this.smoothController.tickIdleExpiration()))) {
            this.hasAimRotation = false;
        }

        if (!this.mc.player.isAlive()) {
            return;
        }
        if (this.pauseWhileEating.get() && this.mc.player.isUsingItem()) {
            return;
        }
        if (!hasRequiredWeapon()) {
            currentTarget = null;
            return;
        }

        checkTargetNeutralized();
        updateTargetSelection();
        if (currentTarget == null) {
            this.preAttackCountdown = 0;
            return;
        }

        if (this.autoJump.get()) {
            this.mc.options.jumpKey.setPressed(this.mc.player.isOnGround());
        }

        if (this.smoothController.isYawStepEnabled() && this.rotate.get() != RotationMode.None) {
            handleSmoothStepTick();
            return;
        }

        if (this.rotate.get() == RotationMode.Always) {
            Entity target = currentTarget;
            Runnable attackAction = null;
            if (this.attackDelayTicks > 0) {
                this.attackDelayTicks--;
            } else if (this.preAttackCountdown > 0) {
                this.preAttackCountdown--;
            } else if (canPerformAttack()) {
                this.preAttackCountdown = getPreAttackLeadTicks();
                this.attackingThisTick = true;
                attackAction = () -> engageTarget(target);
            }

            Vec3d aimVec = computeTargetAimVec(target);
            if (aimVec != null) {
                if (!rotateOrExecuteImmediate(aimVec, attackAction) && attackAction != null) {
                    this.preAttackCountdown = 0;
                    this.attackingThisTick = false;
                }
            } else if (attackAction != null) {
                attackAction.run();
            }
            return;
        }

        if (this.attackDelayTicks > 0) {
            this.attackDelayTicks--;
            return;
        }
        if (this.preAttackCountdown > 0) {
            this.preAttackCountdown--;
            return;
        }
        if (!canPerformAttack()) {
            return;
        }

        this.preAttackCountdown = getPreAttackLeadTicks();
        this.attackingThisTick = true;
        if (isInstantOnHitRotation()) {
            Vec3d aimVec = computeTargetAimVec(currentTarget);
            if (aimVec == null || !rotateOrExecuteImmediate(aimVec, () -> engageTarget(currentTarget))) {
                this.preAttackCountdown = 0;
                this.attackingThisTick = false;
            }
            return;
        }
        engageTarget(currentTarget);
    }

    private void sendAttackPacketAndResetCooldown() {
        if (this.attack.get()) {
            this.mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(currentTarget, this.mc.player.isSneaking()));
        }
        this.mc.player.resetLastAttackedTicks();
    }

    private void engageTarget(Entity target) {
        if (target == null) {
            return;
        }
        if (tryBreakShield(false)) {
            return;
        }
        attackIfNotBlockedByShield(target);
    }

    private Vec3d computeTargetAimVec(Entity target) {
        if (this.mc.player.isGliding()) {
            return target.getEntityPos().add(0.0, target.getEyeHeight(target.getPose()), 0.0);
        }
        return findBestMultipointAim(target);
    }

    private boolean rotateOrExecuteImmediate(Vec3d aimPos, Runnable callback) {
        double yaw = NovaRotations.getYaw(aimPos);
        double pitch = NovaRotations.getPitch(aimPos);
        boolean alwaysRotate = this.rotate.get() == RotationMode.Always;
        int maxHold = alwaysRotate ? ROTATION_HOLD_TICKS : Math.min(ROTATION_HOLD_TICKS, Config.get().rotationHoldTicks.get());

        boolean alreadyAimedOnTarget = currentTarget != null
            && this.hasAimRotation
            && RaytraceUtils.canHitEntity(this.targetYaw, this.targetPitch, getRange(), getWallsRange(), RaytraceUtils.RtxMode.TARGET_ONLY, currentTarget);

        if (this.ticksSinceRotation < maxHold && (alreadyAimedOnTarget || isWithinAngleTolerance(yaw, pitch))) {
            if (!alwaysRotate) {
                this.ticksSinceRotation = 0;
            }
            if (callback != null) {
                callback.run();
            }
            return true;
        }

        if (this.rotatedThisTick) {
            return false;
        }
        submitRotation(yaw, pitch, null);
        return false;
    }

    private boolean isWithinAngleTolerance(double yaw, double pitch) {
        return (double) Math.abs(MathHelper.wrapDegrees((float) (yaw - (double) this.targetYaw))) <= 1.0
            && Math.abs(pitch - (double) this.targetPitch) <= 1.0;
    }

    private void submitRotation(double yaw, double pitch, Runnable callback) {
        this.rotatedThisTick = true;
        this.targetYaw = (float) yaw;
        this.targetPitch = (float) pitch;
        this.hasAimRotation = true;
        this.ticksSinceRotation = 0;

        if (this.clientRotate.get()) {
            NovaRotations.smoothLook(yaw, pitch, this.linearSpeed.get());
        }
        NovaRotations.rotate(yaw, pitch, 50, this.clientRotate.get(), ROTATION_OWNER, callback);
    }

    @EventHandler(priority = -200)
    private void onTickPreHoldRotation(TickEvent.Pre event) {
        if (this.mc.player == null || this.rotate.get() != RotationMode.Always) {
            return;
        }
        if (this.rotatedThisTick || this.ticksSinceRotation >= ROTATION_HOLD_TICKS) {
            return;
        }
        if (this.clientRotate.get()) {
            NovaRotations.smoothLook(this.targetYaw, this.targetPitch, this.linearSpeed.get());
        }
        NovaRotations.rotate(this.targetYaw, this.targetPitch, -100, this.clientRotate.get(), ROTATION_OWNER, null);
    }

    private void handleSmoothStepTick() {
        Entity target = currentTarget;
        boolean onCooldown = false;
        if (this.attackDelayTicks > 0) {
            this.attackDelayTicks--;
            onCooldown = true;
        } else if (this.preAttackCountdown > 0) {
            this.preAttackCountdown--;
            onCooldown = true;
        }

        if (this.rotate.get() == RotationMode.OnHit && !shouldPreRotateForHit()) {
            return;
        }

        Vec3d aimPos = this.mc.player.isGliding()
            ? target.getEntityPos().add(0.0, target.getEyeHeight(target.getPose()), 0.0)
            : findBestMultipointAim(target);
        if (aimPos == null) {
            return;
        }

        Vec2f stepped = this.smoothController.stepTowards(NovaRotations.getYaw(aimPos), NovaRotations.getPitch(aimPos));
        this.targetYaw = stepped.x;
        this.targetPitch = stepped.y;
        this.hasAimRotation = true;

        Runnable callback = null;
        if (!onCooldown && canPerformAttack()) {
            this.preAttackCountdown = getPreAttackLeadTicks();
            this.attackingThisTick = true;
            callback = () -> engageTarget(target);
        }
        submitRotation(stepped.x, stepped.y, callback);
    }

    private boolean shouldPreRotateForHit() {
        if (this.oldDelay.get()) {
            return this.attackDelayTicks <= OLD_DELAY_PRE_ROTATE_TICKS;
        }
        return (double) getAttackCooldownProgress() >= this.attackCooldown.get() - (double) COOLDOWN_PRE_ROTATE_LEAD;
    }

    @EventHandler
    private void onPreTickMovement(PreTickMovementEvent event) {
        if (NovaRotations.hasPendingRotation()) {
            this.diagonalHelper.apply(NovaRotations.getPendingYaw(), NovaRotations.getPendingPitch());
        } else {
            this.diagonalHelper.restore();
        }
    }

    @EventHandler(priority = 200)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        this.diagonalHelper.restore();
    }

    private void attackIfNotBlockedByShield(Entity target) {
        if (target instanceof PlayerEntity player
            && player.isUsingItem()
            && player.getOffHandStack().getItem() == Items.SHIELD
            && !this.attackShieldingEntities.get()) {
            return;
        }
        performAttackWithSprintAndWeapon();
    }

    public Vec3d findBestMultipointAim(Entity target) {
        double width = target.getBoundingBox().getLengthX();
        this.hitPointOffset = new Vec3d(0.0, target.getEyeHeight(target.getPose()), 0.0);

        if (!RaytraceUtils.canHitEntity(getEffectiveYaw(), getEffectivePitch(), getRange(), getWallsRange(), RaytraceUtils.RtxMode.TARGET_ONLY, target)) {
            Vec3d centerBody = target.getEntityPos().add(0.0, target.getEyeHeight(target.getPose()) / 2.0f, 0.0);
            float[] bodyAngles = RaytraceUtils.getRotationAngles(centerBody);
            if (bodyAngles != null
                && squaredDistanceToEyes(centerBody) <= (double) getMaxAimRangeSq()
                && RaytraceUtils.canHitEntity(bodyAngles[0], bodyAngles[1], getRange(), 0.0f, RaytraceUtils.RtxMode.TARGET_ONLY, target)) {
                this.hitPointOffset = new Vec3d(0.0, target.getEyeHeight(target.getPose()) / 2.0f, 0.0);
            } else {
                float halfWidth = (float) (width / 2.0);
                for (float dx = -halfWidth; dx <= halfWidth; dx += 0.05f) {
                    for (float dz = -halfWidth; dz <= halfWidth; dz += 0.05f) {
                        for (float dy = 0.05f; (double) dy <= target.getBoundingBox().getLengthY(); dy += 0.15f) {
                            Vec3d point = new Vec3d(target.getX() + (double) dx, target.getY() + (double) dy, target.getZ() + (double) dz);
                            if (squaredDistanceToEyes(point) <= (double) getMaxAimRangeSq()) {
                                float[] rot = RaytraceUtils.getRotationAngles(point);
                                if (rot != null && RaytraceUtils.canHitEntity(rot[0], rot[1], getRange(), 0.0f, RaytraceUtils.RtxMode.TARGET_ONLY, target)) {
                                    this.hitPointOffset = new Vec3d(dx, dy, dz);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        return target.getEntityPos().add(this.hitPointOffset);
    }

    private boolean hasRequiredWeapon() {
        Item mainHandItem = this.mc.player.getMainHandStack().getItem();
        if (!this.onlyWeapon.get()) {
            return true;
        }
        if (this.autoWeapon.get() == Switch.None) {
            return this.mc.player.getMainHandStack().isIn(ItemTags.SWORDS)
                || mainHandItem instanceof AxeItem
                || mainHandItem instanceof MaceItem;
        }
        return InventorySwapUtils.findWeaponInHotbar().found() || InventorySwapUtils.findWeapon().found();
    }

    public void performAttackWithSprintAndWeapon() {
        int previousSlot = switchToWeaponIfNeeded();
        boolean wasSprinting = this.mc.player.isSprinting();
        if (wasSprinting && this.dropSprint.get()) {
            this.mc.player.setSprinting(false);
            this.mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(this.mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        }

        sendAttackPacketAndResetCooldown();
        swingMainHand();
        this.firstHitReady = false;
        this.attackDelayTicks = computeOldDelayTicks();

        if (previousSlot != -1) {
            InventorySwapUtils.selectSlot(previousSlot);
        }
        if (wasSprinting && this.dropSprint.get() && this.returnSprint.get()) {
            this.mc.player.setSprinting(true);
            this.mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(this.mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
        }
    }

    private int switchToWeaponIfNeeded() {
        int previousSlot = -1;
        SlotSwapResult weapon = InventorySwapUtils.findWeaponInHotbar();
        if (weapon.found() && this.autoWeapon.get() != Switch.None) {
            if (this.autoWeapon.get() == Switch.Silent) {
                previousSlot = this.mc.player.getInventory().getSelectedSlot();
            }
            weapon.swapHotbar();
        }
        return previousSlot;
    }

    private int getPreAttackLeadTicks() {
        return this.oldDelay.get() ? OLD_DELAY_PRE_TICKS : 0;
    }

    private int computeOldDelayTicks() {
        if (!this.oldDelay.get()) {
            return 0;
        }
        int min = Math.min(this.minCps.get(), this.maxCps.get());
        int max = Math.max(this.minCps.get(), this.maxCps.get());
        float cps = AngleUtils.randomFloat((float) min, (float) max);
        return Math.max(0, Math.round(20.0f / cps) - 1);
    }

    private void swingMainHand() {
        this.mc.player.swingHand(Hand.MAIN_HAND);
    }

    public void checkTargetNeutralized() {
        if (currentTarget instanceof LivingEntity living && (living.getHealth() <= 0.0f || living.isDead())) {
            ChatUtils.info("Aura » Target successfully neutralized!");
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof EntityStatusS2CPacket packet
            && packet.getStatus() == 30
            && packet.getEntity(this.mc.world) != null
            && currentTarget != null
            && packet.getEntity(this.mc.world) == currentTarget) {
            ChatUtils.info("Aura » Successfully destroyed " + currentTarget.getName().getString() + "'s shield");
        }
    }

    private boolean canPerformAttack() {
        boolean cannotCrit = !this.smartCrit.get()
            || this.mc.player.getAbilities().flying
            || this.mc.player.isGliding()
            || this.mc.player.hasStatusEffect(StatusEffects.BLINDNESS)
            || this.mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING)
            || isInCobweb();

        if (this.pauseInInventory.get() && this.mc.currentScreen != null) {
            return false;
        }
        if (!this.firstHitReady && (double) getAttackCooldownProgress() < this.attackCooldown.get() && !this.oldDelay.get()) {
            return false;
        }
        if (!isInstantOnHitRotation() && !canRaytraceCurrentTarget()) {
            return false;
        }
        if (!this.mc.options.jumpKey.isPressed() && !this.onlyCrit.get() && !this.autoJump.get()) {
            return true;
        }
        if (this.mc.player.isInLava() || this.mc.player.isSubmergedInWater()) {
            return true;
        }
        if (!this.mc.options.jumpKey.isPressed() && isStandingInWater()) {
            return true;
        }
        if (this.mc.player.fallDistance > 1.0f && this.mc.player.fallDistance < 1.14f) {
            return false;
        }
        if (!cannotCrit) {
            return !this.mc.player.isOnGround() && this.mc.player.fallDistance > 0.0f;
        }
        return true;
    }

    private boolean canRaytraceCurrentTarget() {
        if (currentTarget == null) {
            return false;
        }
        return RaytraceUtils.canHitEntity(getEffectiveYaw(), getEffectivePitch(), getRange(), getWallsRange(), RaytraceUtils.RtxMode.TARGET_ONLY, currentTarget);
    }

    private boolean tryBreakShield(boolean ignoreUseCheck) {
        int axeSlot = InventorySwapUtils.findAxe().slot();
        if (axeSlot == -1 || !this.shieldBreaker.get() || !(currentTarget instanceof PlayerEntity player)) {
            return false;
        }
        if (!player.isUsingItem() && !ignoreUseCheck) {
            return false;
        }
        if (player.getOffHandStack().getItem() != Items.SHIELD && player.getMainHandStack().getItem() != Items.SHIELD) {
            return false;
        }

        if (axeSlot >= 9) {
            this.mc.interactionManager.clickSlot(
                this.mc.player.currentScreenHandler.syncId,
                axeSlot,
                this.mc.player.getInventory().getSelectedSlot(),
                SlotActionType.SWAP,
                this.mc.player
            );
            this.mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(this.mc.player.currentScreenHandler.syncId));
            sendAttackPacketAndResetCooldown();
            swingMainHand();
            this.mc.interactionManager.clickSlot(
                this.mc.player.currentScreenHandler.syncId,
                axeSlot,
                this.mc.player.getInventory().getSelectedSlot(),
                SlotActionType.SWAP,
                this.mc.player
            );
            this.mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(this.mc.player.currentScreenHandler.syncId));
        } else {
            this.mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(axeSlot));
            sendAttackPacketAndResetCooldown();
            swingMainHand();
            this.mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(this.mc.player.getInventory().getSelectedSlot()));
        }
        this.attackDelayTicks = 10;
        return true;
    }

    public boolean isStandingInWater() {
        return this.mc.player.isSubmergedInWater()
            || this.mc.world.getBlockState(BlockPos.ofFloored(this.mc.player.getEntityPos().add(0.0, -0.4, 0.0))).getBlock() == Blocks.WATER;
    }

    public boolean isInCobweb() {
        Box box = this.mc.player.getBoundingBox();
        BlockPos base = BlockPos.ofFloored(this.mc.player.getEntityPos());
        for (int x = base.getX() - 2; x <= base.getX() + 2; x++) {
            for (int y = base.getY() - 1; y <= base.getY() + 4; y++) {
                for (int z = base.getZ() - 2; z <= base.getZ() + 2; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (box.intersects(new Box(pos)) && this.mc.world.getBlockState(pos).getBlock() == Blocks.COBWEB) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public float getAttackCooldownProgress() {
        return this.mc.player.getAttackCooldownProgress(this.attackBaseTime.get().floatValue());
    }

    private void updateTargetSelection() {
        Entity best = findBestTarget();
        if (currentTarget == null) {
            currentTarget = best;
            return;
        }
        if (this.sort.get() == Sort.FOV || !this.lockTarget.get()) {
            currentTarget = best;
        }
        if (best instanceof TridentEntity) {
            currentTarget = best;
        }
        if (isInvalidTarget(currentTarget)) {
            currentTarget = best;
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!hasRequiredWeapon() || currentTarget == null) {
            return;
        }
        if (this.esp.get()) {
            event.renderer.box(currentTarget.getBoundingBox(), this.espColor.get(), this.espColor.get(), this.shapeMode.get(), 0);
        }
    }

    private double squaredDistanceToEyes(Vec3d pos) {
        return this.mc.player.getEyePos().squaredDistanceTo(pos);
    }

    public float getMaxAimRangeSq() {
        float maxRange = getRange() + this.aimRange.get().floatValue();
        return maxRange * maxRange;
    }

    public Entity getCurrentTarget() {
        return currentTarget;
    }

    public boolean isAttackingThisTick() {
        return this.attackingThisTick;
    }

    public boolean isTargetReachable(Entity entity) {
        if (squaredDistanceToEyes(entity.getEntityPos().add(0.0, entity.getEyeHeight(entity.getPose()), 0.0)) > (double) (getMaxAimRangeSq() + 4.0f)) {
            return false;
        }
        float halfWidth = (float) (entity.getBoundingBox().getLengthX() / 2.0);
        for (float dx = -halfWidth; dx <= halfWidth; dx += 0.15f) {
            for (float dz = -halfWidth; dz <= halfWidth; dz += 0.15f) {
                for (float dy = 0.05f; (double) dy <= entity.getBoundingBox().getLengthY(); dy += 0.25f) {
                    Vec3d point = new Vec3d(entity.getX() + (double) dx, entity.getY() + (double) dy, entity.getZ() + (double) dz);
                    if (squaredDistanceToEyes(point) <= (double) getMaxAimRangeSq()) {
                        float[] rot = RaytraceUtils.getRotationAngles(point);
                        if (rot != null && RaytraceUtils.canHitEntity(rot[0], rot[1], (float) Math.sqrt(getMaxAimRangeSq()), getWallsRange(), RaytraceUtils.RtxMode.TARGET_ONLY, entity)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public Entity findBestTarget() {
        List<LivingEntity> candidates = new CopyOnWriteArrayList<>();
        for (Entity entity : this.mc.world.getEntities()) {
            if ((entity instanceof ShulkerBulletEntity || entity instanceof AbstractFireballEntity)
                && entity.isAlive()
                && isTargetReachable(entity)
                && this.targets.get().contains(entity.getType())) {
                return entity;
            }
            if (!isInvalidTarget(entity) && entity instanceof LivingEntity living) {
                candidates.add(living);
            }
        }

        return switch (this.sort.get()) {
            case LowestDistance -> candidates.stream()
                .min(Comparator.comparing(e -> this.mc.player.squaredDistanceTo(e.getEntityPos())))
                .orElse(null);
            case LowestHealth -> candidates.stream()
                .min(Comparator.comparing(e -> e.getHealth() + e.getAbsorptionAmount()))
                .orElse(null);
            case FOV -> candidates.stream()
                .min(Comparator.comparing(this::getEntityYawAngleDiff))
                .orElse(null);
        };
    }

    private boolean isInvalidTarget(Entity entity) {
        if (isValidProjectileTarget(entity)) {
            return false;
        }
        if (!(entity instanceof LivingEntity living)) {
            return true;
        }
        if (living.isDead() || !entity.isAlive()) {
            return true;
        }
        if (!this.targets.get().contains(entity.getType())) {
            return true;
        }
        if (!isWithinFov(living.getEntityPos(), this.fov.get())) {
            return true;
        }
        if (entity instanceof PlayerEntity player) {
            if (player == this.mc.player || !Friends.get().shouldAttack(player)) {
                return true;
            }
            if (player.isCreative() && this.ignoreCreative.get()) {
                return true;
            }
        }
        return !isTargetReachable(entity);
    }

    private boolean isWithinFov(Vec3d pos, int maxFov) {
        return getYawDifferenceTo(pos) <= (float) maxFov / 2.0f;
    }

    private float getYawDifferenceTo(Vec3d pos) {
        double dx = pos.x - this.mc.player.getX();
        double dz = pos.z - this.mc.player.getZ();
        float yaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        return Math.abs(MathHelper.wrapDegrees(yaw - this.mc.player.getYaw()));
    }

    private boolean isValidProjectileTarget(Entity entity) {
        return (entity instanceof ShulkerBulletEntity || entity instanceof AbstractFireballEntity)
            && entity.isAlive()
            && squaredDistanceToEyes(entity.getEntityPos()) < (double) getMaxAimRangeSq()
            && this.targets.get().contains(entity.getType());
    }

    private float getEntityYawAngleDiff(LivingEntity entity) {
        return getYawDifferenceTo(entity.getEntityPos());
    }

    @Override
    public String getInfoString() {
        return currentTarget != null ? currentTarget.getName().getString() : null;
    }

    public enum RotationMode {
        Always,
        OnHit,
        None
    }

    public enum Switch {
        Normal,
        Silent,
        None
    }

    public enum Sort {
        LowestDistance,
        LowestHealth,
        FOV
    }
}
