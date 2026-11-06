package com.nova.addon.modules.pvp;

import com.nova.addon.NovaCategories;
import com.nova.addon.events.PreTickMovementEvent;
import com.nova.addon.events.SendMovementPacketsEvent;
import com.nova.addon.modules.misc.DiagonalInput;
import com.nova.addon.utils.raytrace.RaytraceUtils;
import com.nova.addon.utils.rotation.NovaRotations;
import com.nova.addon.utils.rotation.SmoothRotationController;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IRaycastContext;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3d;

public class CrystalAuraN extends Module {
    private static final Object ROTATION_OWNER = new Object();
    private static final int ROTATION_HOLD_TICKS = 10;
    private static final long TARGET_CACHE_TTL_MS = 0L;

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgSwitch = this.settings.createGroup("Switch");
    private final SettingGroup sgPlace = this.settings.createGroup("Place");
    private final SettingGroup sgFacePlace = this.settings.createGroup("Face Place");
    private final SettingGroup sgBreak = this.settings.createGroup("Break");
    private final SettingGroup sgPause = this.settings.createGroup("Pause");
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Double> targetRange = this.sgGeneral.add(new DoubleSetting.Builder()
        .name("Target Range")
        .defaultValue(10.0)
        .min(0.0)
        .sliderMax(16.0)
        .build()
    );

    private final Setting<TargetMode> targetMode = this.sgGeneral.add(new EnumSetting.Builder<TargetMode>()
        .name("Target Mode")
        .description("Which target to calculate for when several are in range.")
        .defaultValue(TargetMode.All)
        .build()
    );

    private final Setting<Double> minDamage = this.sgGeneral.add(new DoubleSetting.Builder()
        .name("Min Damage")
        .defaultValue(6.0)
        .range(0.0, 36.0)
        .sliderMax(36.0)
        .build()
    );

    private final Setting<Double> maxSelfDamage = this.sgGeneral.add(new DoubleSetting.Builder()
        .name("Max Self Damage")
        .description("At 20 the limit is off and self damage is ignored.")
        .defaultValue(10.0)
        .range(0.0, 20.0)
        .sliderMax(20.0)
        .build()
    );

    private final Setting<Boolean> raytrace = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Raytrace")
        .description("Always require line of sight. When off, line of sight is only required beyond Walls Range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Sequential> sequential = this.sgGeneral.add(new EnumSetting.Builder<Sequential>()
        .name("Sequential")
        .description("None: alternate attack and place. Strict: both every tick. Strong: also place right after an instant attack.")
        .defaultValue(Sequential.Strong)
        .build()
    );

    private final Setting<Boolean> rotate = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Rotate")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> multiRotate = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Multi Rotate")
        .description("Allows attack and place in the same tick with different rotations. Faster, but the aim can jitter between two spots.")
        .defaultValue(false)
        .visible(this.rotate::get)
        .build()
    );

    private final Setting<Boolean> clientRotate = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Client Rotate")
        .defaultValue(false)
        .visible(this.rotate::get)
        .build()
    );

    private final Setting<Double> linearSpeed = this.sgGeneral.add(new DoubleSetting.Builder()
        .name("Linear Speed")
        .defaultValue(40.0)
        .min(1.0)
        .max(180.0)
        .sliderMax(180.0)
        .visible(() -> this.rotate.get() && this.clientRotate.get())
        .build()
    );

    private final SmoothRotationController smoothController = new SmoothRotationController(
        this.sgGeneral,
        this.rotate::get,
        () -> ROTATION_HOLD_TICKS
    );

    private final Setting<Set<EntityType<?>>> entities = this.sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("Entities")
        .onlyAttackable()
        .defaultValue(EntityType.PLAYER, EntityType.WARDEN, EntityType.WITHER)
        .build()
    );

    private final Setting<Boolean> asynchronous = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Asynchronous")
        .description("Runs calculations on a separate thread.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> asyncDelay = this.sgGeneral.add(new IntSetting.Builder()
        .name("Async Delay")
        .description("Milliseconds to wait between async calculations. Lower is faster but uses more CPU.")
        .defaultValue(5)
        .min(0)
        .sliderMax(50)
        .visible(this.asynchronous::get)
        .build()
    );

    private final Setting<AutoSwitchMode> autoSwitch = this.sgSwitch.add(new EnumSetting.Builder<AutoSwitchMode>()
        .name("Auto Switch")
        .defaultValue(AutoSwitchMode.Normal)
        .build()
    );

    private final Setting<Integer> swapDelay = this.sgSwitch.add(new IntSetting.Builder()
        .name("Swap Delay")
        .description("Ticks to wait after swapping before placing or attacking.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> noGapSwitch = this.sgSwitch.add(new BoolSetting.Builder()
        .name("No Gap Switch")
        .defaultValue(true)
        .visible(() -> this.autoSwitch.get() == AutoSwitchMode.Normal)
        .build()
    );

    private final Setting<Boolean> antiWeakness = this.sgSwitch.add(new BoolSetting.Builder()
        .name("Anti Weakness")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> place = this.sgPlace.add(new BoolSetting.Builder()
        .name("Place")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> placeSpeed = this.sgPlace.add(new DoubleSetting.Builder()
        .name("Place Speed")
        .description("Crystals placed per second.")
        .defaultValue(10.0)
        .range(0.1, 20.0)
        .sliderRange(0.1, 20.0)
        .build()
    );

    private final Setting<Double> placeRange = this.sgPlace.add(new DoubleSetting.Builder()
        .name("Place Range")
        .defaultValue(4.5)
        .min(0.0)
        .sliderMax(8.0)
        .build()
    );

    private final Setting<Double> placeWallsRange = this.sgPlace.add(new DoubleSetting.Builder()
        .name("Walls Range")
        .defaultValue(4.5)
        .min(0.0)
        .sliderMax(8.0)
        .build()
    );

    private final Setting<SendMode> crystalPlaceMode = this.sgPlace.add(new EnumSetting.Builder<SendMode>()
        .name("Crystal Place Mode")
        .description("Normal uses the real interaction manager. Packet sends the placement packet directly.")
        .defaultValue(SendMode.Normal)
        .build()
    );

    private final Setting<SupportMode> support = this.sgPlace.add(new EnumSetting.Builder<SupportMode>()
        .name("Support")
        .description("Places Obsidian first when a crystal position has no base block.")
        .defaultValue(SupportMode.Disabled)
        .build()
    );

    private final Setting<Boolean> noAirPlace = this.sgPlace.add(new BoolSetting.Builder()
        .name("No Air Place")
        .description("Only places Support Obsidian next to an existing block.")
        .defaultValue(true)
        .visible(() -> this.support.get() != SupportMode.Disabled)
        .build()
    );

    private final Setting<Integer> supportDelay = this.sgPlace.add(new IntSetting.Builder()
        .name("Support Delay")
        .description("Ticks to wait after placing Obsidian before placing the crystal.")
        .defaultValue(1)
        .min(0)
        .sliderMax(10)
        .visible(() -> this.support.get() != SupportMode.Disabled)
        .build()
    );

    private final Setting<SendMode> supportPlaceMode = this.sgPlace.add(new EnumSetting.Builder<SendMode>()
        .name("Support Place Mode")
        .description("Normal uses the real interaction manager. Packet sends the placement packet directly.")
        .defaultValue(SendMode.Normal)
        .visible(() -> this.support.get() != SupportMode.Disabled)
        .build()
    );

    private final Setting<Boolean> facePlace = this.sgFacePlace.add(new BoolSetting.Builder()
        .name("Face Place")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> facePlaceHealth = this.sgFacePlace.add(new DoubleSetting.Builder()
        .name("Face Place Health")
        .defaultValue(8.0)
        .min(1.0)
        .sliderMin(1.0)
        .sliderMax(36.0)
        .visible(this.facePlace::get)
        .build()
    );

    private final Setting<Double> facePlaceDurability = this.sgFacePlace.add(new DoubleSetting.Builder()
        .name("Face Place Durability")
        .defaultValue(10.0)
        .min(1.0)
        .sliderMin(1.0)
        .sliderMax(100.0)
        .visible(this.facePlace::get)
        .build()
    );

    private final Setting<Boolean> facePlaceMissingArmor = this.sgFacePlace.add(new BoolSetting.Builder()
        .name("Face Place Missing Armor")
        .defaultValue(false)
        .visible(this.facePlace::get)
        .build()
    );

    private final Setting<Keybind> forceFacePlace = this.sgFacePlace.add(new KeybindSetting.Builder()
        .name("Force Face Place")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Boolean> doBreak = this.sgBreak.add(new BoolSetting.Builder()
        .name("Break")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> attackSpeed = this.sgBreak.add(new DoubleSetting.Builder()
        .name("Attack Speed")
        .description("Crystals attacked per second.")
        .defaultValue(20.0)
        .range(0.1, 20.0)
        .sliderRange(0.1, 20.0)
        .build()
    );

    private final Setting<Double> breakRange = this.sgBreak.add(new DoubleSetting.Builder()
        .name("Break Range")
        .defaultValue(4.5)
        .min(0.0)
        .sliderMax(8.0)
        .build()
    );

    private final Setting<Double> breakWallsRange = this.sgBreak.add(new DoubleSetting.Builder()
        .name("Walls Range")
        .defaultValue(4.5)
        .min(0.0)
        .sliderMax(8.0)
        .build()
    );

    private final Setting<Boolean> instant = this.sgBreak.add(new BoolSetting.Builder()
        .name("Instant")
        .description("Attacks crystals the moment they spawn.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> inhibit = this.sgBreak.add(new BoolSetting.Builder()
        .name("Inhibit")
        .description("Temporarily ignores crystals that were just attacked.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> burstMode = this.sgBreak.add(new BoolSetting.Builder()
        .name("Burst Mode")
        .description("Explode N times at the same spot then pause M ticks before continuing.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> burstExplosions = this.sgBreak.add(new IntSetting.Builder()
        .name("Burst Explosions")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .visible(this.burstMode::get)
        .build()
    );

    private final Setting<Integer> burstPauseTicks = this.sgBreak.add(new IntSetting.Builder()
        .name("Burst Pause Ticks")
        .description("Ticks to pause after a burst.")
        .defaultValue(4)
        .min(1)
        .sliderMax(20)
        .visible(this.burstMode::get)
        .build()
    );

    private final Setting<Boolean> lootProtect = this.sgBreak.add(new BoolSetting.Builder()
        .name("Loot Protect")
        .description("Slows down attacks when a target without a totem is about to die, so dropped loot is not blown up.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> lootProtectHealth = this.sgBreak.add(new DoubleSetting.Builder()
        .name("Loot Protect Health")
        .description("Target health (including absorption) at or below which Loot Protect activates.")
        .defaultValue(8.0)
        .min(1.0)
        .sliderMin(1.0)
        .sliderMax(36.0)
        .visible(this.lootProtect::get)
        .build()
    );

    private final Setting<Integer> lootProtectDelay = this.sgBreak.add(new IntSetting.Builder()
        .name("Loot Protect Delay")
        .description("Minimum milliseconds between attacks while Loot Protect is active.")
        .defaultValue(250)
        .min(0)
        .sliderMax(1000)
        .visible(this.lootProtect::get)
        .build()
    );

    public final Setting<PauseMode> pauseOnUse = this.sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name("Pause On Use")
        .defaultValue(PauseMode.Place)
        .build()
    );

    public final Setting<PauseMode> pauseOnMine = this.sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name("Pause On Mine")
        .defaultValue(PauseMode.None)
        .build()
    );

    private final Setting<Boolean> pauseOnLag = this.sgPause.add(new BoolSetting.Builder()
        .name("Pause On Lag")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> pauseHealth = this.sgPause.add(new DoubleSetting.Builder()
        .name("Pause Health")
        .defaultValue(5.0)
        .range(0.0, 36.0)
        .sliderRange(0.0, 36.0)
        .build()
    );

    private final Setting<Boolean> yieldToTrap = this.sgPause.add(new BoolSetting.Builder()
        .name("Yield To Trap")
        .description("Pauses while SelfTrap or Surround is placing blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = this.sgRender.add(new BoolSetting.Builder()
        .name("Render")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderPlace = this.sgRender.add(new BoolSetting.Builder()
        .name("Render Place")
        .defaultValue(true)
        .visible(this.render::get)
        .build()
    );

    private final Setting<Integer> placeTime = this.sgRender.add(new IntSetting.Builder()
        .name("Place Time")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> this.render.get() && this.renderPlace.get())
        .build()
    );

    private final Setting<Boolean> renderBreak = this.sgRender.add(new BoolSetting.Builder()
        .name("Render Break")
        .defaultValue(false)
        .visible(this.render::get)
        .build()
    );

    private final Setting<Integer> breakTime = this.sgRender.add(new IntSetting.Builder()
        .name("Break Time")
        .defaultValue(13)
        .min(0)
        .sliderMax(20)
        .visible(() -> this.render.get() && this.renderBreak.get())
        .build()
    );

    private final Setting<ShapeMode> shapeMode = this.sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("Shape Mode")
        .defaultValue(ShapeMode.Both)
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = this.sgRender.add(new ColorSetting.Builder()
        .name("Side Color")
        .defaultValue(new SettingColor(255, 255, 255, 45))
        .visible(() -> this.shapeMode.get().sides() && this.render.get())
        .build()
    );

    private final Setting<SettingColor> lineColor = this.sgRender.add(new ColorSetting.Builder()
        .name("Line Color")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> this.shapeMode.get().lines() && this.render.get())
        .build()
    );

    private final Setting<Boolean> damage = this.sgRender.add(new BoolSetting.Builder()
        .name("Damage")
        .defaultValue(true)
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> damageColor = this.sgRender.add(new ColorSetting.Builder()
        .name("Damage Color")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> this.render.get() && this.damage.get())
        .build()
    );

    private final Setting<Double> damageScale = this.sgRender.add(new DoubleSetting.Builder()
        .name("Damage Scale")
        .defaultValue(1.25)
        .min(1.0)
        .sliderMax(4.0)
        .visible(() -> this.render.get() && this.damage.get())
        .build()
    );

    public float lastRotationYaw = Float.NaN;
    public float lastRotationPitch = Float.NaN;
    private final DiagonalInput.Helper diagonalHelper = new DiagonalInput.Helper(
        () -> this.isActive() && DiagonalInput.isEnabled()
    );

    private Item mainHandItem;
    private Item offHandItem;

    private final ExecutorService calcExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "S-AutoCrystal-Calc");
        t.setDaemon(true);
        return t;
    });

    private volatile Future<?> calcFuture = null;
    private volatile boolean asyncRunning = false;
    private volatile List<Entity> worldEntitiesSnapshot = Collections.emptyList();
    private volatile List<LivingEntity> activeTargets = Collections.emptyList();
    private volatile List<LivingEntity> cachedTargets = Collections.emptyList();
    private volatile long lastTargetScanMs = 0L;
    private volatile EndCrystalEntity bestBreakCrystal = null;
    private volatile PlaceTarget bestPlaceTarget = null;

    private final Map<Integer, Long> attackedCrystals = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> placedCrystalBases = new ConcurrentHashMap<>();

    private long lastBreakTimeMs;
    private long lastPlaceTimeMs;
    private long lastSlotSwapTimeMs;
    private boolean nextSeqBreak = false;
    private boolean nextSeqPlace = true;
    private boolean strongPlacedThisTick = false;
    private int currentBurstCount;
    private int burstPauseRemainingTicks;

    private boolean rotatedThisTick;
    private boolean hasSavedTargetPos;
    private final Vec3d savedRotationVec = new Vec3d(0.0, 0.0, 0.0);
    private double savedRotationYaw;
    private double savedRotationPitch;
    private int ticksSinceLastRotate;
    private boolean actedThisTick;
    private boolean fullDamageAction;
    private boolean recentlyPlaced;
    private int recentPlaceTicks;
    public int postPlaceHoldTicks;
    private int renderPlaceTimer;
    private int renderBreakTimer;

    private final BlockPos.Mutable renderPlacePos = new BlockPos.Mutable();
    private final BlockPos.Mutable renderBreakPos = new BlockPos.Mutable();
    private double lastPlacedDamage;
    private LivingEntity currentDisplayTarget;
    private int displayTargetTimer;
    private final Vector3d nametagPos = new Vector3d();
    private double trackedServerYaw;
    private double trackedServerPitch;

    private final Scratch mainThreadScratch = new Scratch();
    private final Scratch asyncThreadScratch = new Scratch();

    public CrystalAuraN() {
        super(NovaCategories.NOVA_PVP, "CrystalAuraN", "Automatically places and attacks crystals.");
    }

    @Override
    public void onActivate() {
        this.mainThreadScratch.raycastContext = new RaycastContext(
            new Vec3d(0.0, 0.0, 0.0),
            new Vec3d(0.0, 0.0, 0.0),
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            this.mc.player
        );
        this.asyncThreadScratch.raycastContext = new RaycastContext(
            new Vec3d(0.0, 0.0, 0.0),
            new Vec3d(0.0, 0.0, 0.0),
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            this.mc.player
        );
        this.recentlyPlaced = false;
        this.recentPlaceTicks = 0;
        this.postPlaceHoldTicks = 0;
        this.lastBreakTimeMs = 0L;
        this.lastPlaceTimeMs = 0L;
        this.lastSlotSwapTimeMs = 0L;
        this.nextSeqBreak = false;
        this.nextSeqPlace = true;
        this.strongPlacedThisTick = false;
        this.trackedServerYaw = this.mc.player.getYaw();
        this.trackedServerPitch = this.mc.player.getPitch();
        this.displayTargetTimer = 0;
        this.smoothController.reset();
        this.ticksSinceLastRotate = ROTATION_HOLD_TICKS;
        this.renderPlaceTimer = 0;
        this.renderBreakTimer = 0;
        this.currentBurstCount = 0;
        this.burstPauseRemainingTicks = 0;
    }

    @Override
    public void onDeactivate() {
        this.asyncRunning = false;
        if (this.calcFuture != null) {
            this.calcFuture.cancel(false);
            this.calcFuture = null;
        }
        this.bestBreakCrystal = null;
        this.bestPlaceTarget = null;
        this.cachedTargets = Collections.emptyList();
        this.activeTargets = Collections.emptyList();
        this.worldEntitiesSnapshot = Collections.emptyList();
        this.lastTargetScanMs = 0L;
        NovaRotations.cancelRotations(ROTATION_OWNER);
        this.smoothController.reset();
        this.lastRotationYaw = Float.NaN;
        this.lastRotationPitch = Float.NaN;
        this.diagonalHelper.restore();
        this.attackedCrystals.clear();
        this.placedCrystalBases.clear();
        this.currentDisplayTarget = null;
        this.currentBurstCount = 0;
        this.burstPauseRemainingTicks = 0;
    }

    @EventHandler(priority = 200)
    private void onTickPre(TickEvent.Pre event) {
        if (this.mc.player == null || this.mc.world == null) {
            return;
        }
        this.rotatedThisTick = false;
        this.actedThisTick = false;
        this.lastRotationYaw = Float.NaN;
        this.lastRotationPitch = Float.NaN;
        this.ticksSinceLastRotate++;

        if (this.recentlyPlaced) {
            if (this.recentPlaceTicks > 0) {
                this.recentPlaceTicks--;
            } else {
                this.recentlyPlaced = false;
            }
        }
        if (this.postPlaceHoldTicks > 0) {
            this.postPlaceHoldTicks--;
        }
        if (this.displayTargetTimer > 0) {
            this.displayTargetTimer--;
        }
        if (this.burstPauseRemainingTicks > 0) {
            this.burstPauseRemainingTicks--;
        }
        if (this.renderPlaceTimer > 0) {
            this.renderPlaceTimer--;
        }
        if (this.renderBreakTimer > 0) {
            this.renderBreakTimer--;
        }

        this.mainHandItem = this.mc.player.getMainHandStack().getItem();
        this.offHandItem = this.mc.player.getOffHandStack().getItem();

        long now = System.currentTimeMillis();
        int ping = getPlayerPingMs();
        long attackExpireMs = Math.max((long) ping * 2L, 50L);
        long placeExpireMs = Math.max((long) ping * 2L, 500L) + (long) ((20.0 - this.attackSpeed.get()) * 50.0);

        this.attackedCrystals.entrySet().removeIf(e -> now - e.getValue() > attackExpireMs);
        this.placedCrystalBases.entrySet().removeIf(e -> now - e.getValue() > placeExpireMs);

        double eyeY = this.mc.player.getEntityPos().y + (double) this.mc.player.getEyeHeight(this.mc.player.getPose());
        ((IVec3d) this.mainThreadScratch.playerEyePos).meteor$set(this.mc.player.getEntityPos().x, eyeY, this.mc.player.getEntityPos().z);
        ((IVec3d) this.asyncThreadScratch.playerEyePos).meteor$set(this.mc.player.getEntityPos().x, eyeY, this.mc.player.getEntityPos().z);

        updateTargetList();
        if (this.activeTargets.isEmpty()) {
            this.currentBurstCount = 0;
        }

        if (this.asynchronous.get()) {
            List<Entity> snapshot = new ArrayList<>();
            for (Entity entity : this.mc.world.getEntities()) {
                snapshot.add(entity);
            }
            this.worldEntitiesSnapshot = snapshot;
            if (!this.asyncRunning || this.calcFuture == null || this.calcFuture.isDone() || this.calcFuture.isCancelled()) {
                this.asyncRunning = true;
                this.calcFuture = this.calcExecutor.submit(this::runAsyncCalculation);
            }
        } else {
            this.asyncRunning = false;
            computeBreakAndPlaceTargets(this.mc.world.getEntities(), this.mainThreadScratch);
        }

        executeTickActions();
    }

    @EventHandler(priority = -200)
    private void onTickPreHoldRotation(TickEvent.Pre event) {
        if (this.mc.player == null) {
            return;
        }
        if (this.rotate.get() && this.ticksSinceLastRotate < ROTATION_HOLD_TICKS && !this.rotatedThisTick && !isPausedByBurst()) {
            double yaw = this.hasSavedTargetPos ? NovaRotations.getYaw(this.savedRotationVec) : this.savedRotationYaw;
            double pitch = this.hasSavedTargetPos ? NovaRotations.getPitch(this.savedRotationVec) : this.savedRotationPitch;
            if (this.smoothController.isYawStepEnabled()) {
                this.smoothController.recordStepTick();
            }
            applyClientSmoothLookIfEnabled(yaw, pitch);
            NovaRotations.rotate(yaw, pitch, -100, this.clientRotate.get(), ROTATION_OWNER, null);
            this.lastRotationYaw = (float) yaw;
            this.lastRotationPitch = (float) pitch;
        }
    }

    private void executeTickActions() {
        if (!hasLivingTarget()) {
            this.bestBreakCrystal = null;
            this.bestPlaceTarget = null;
            return;
        }
        if (this.sequential.get() == Sequential.None) {
            if (this.nextSeqBreak) {
                this.nextSeqBreak = false;
                this.nextSeqPlace = true;
                executeBreakPhase();
                return;
            }
            if (this.nextSeqPlace) {
                this.nextSeqBreak = true;
                this.nextSeqPlace = false;
                executePlacePhase(false);
            }
        } else {
            executeBreakPhase();
            executePlacePhase(false);
        }
    }

    private void runAsyncCalculation() {
        if (!this.asyncRunning || !this.asynchronous.get() || !this.isActive()) {
            this.asyncRunning = false;
            return;
        }
        try {
            List<Entity> entitiesSnapshot = this.worldEntitiesSnapshot;
            if (this.mc.player != null && this.mc.world != null && entitiesSnapshot != null) {
                computeBreakAndPlaceTargets(entitiesSnapshot, this.asyncThreadScratch);
            }
        } catch (Throwable ignored) {
        } finally {
            if (this.asyncRunning && this.asynchronous.get() && this.isActive()) {
                try {
                    int delayMs = this.asyncDelay.get();
                    if (delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
                    this.calcFuture = this.calcExecutor.submit(this::runAsyncCalculation);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    this.asyncRunning = false;
                } catch (Throwable t) {
                    this.asyncRunning = false;
                }
            } else {
                this.asyncRunning = false;
            }
        }
    }

    private void computeBreakAndPlaceTargets(Iterable<Entity> entities, Scratch scratch) {
        List<LivingEntity> targets = this.activeTargets;
        if (targets.isEmpty()) {
            this.bestBreakCrystal = null;
            this.bestPlaceTarget = null;
            return;
        }
        this.bestBreakCrystal = findBestBreakCrystal(entities, scratch, targets);
        PlaceTarget placeTarget = findBestPlaceTarget(entities, scratch, targets);
        this.bestPlaceTarget = placeTarget;
        if (placeTarget != null && placeTarget.target != null) {
            this.currentDisplayTarget = placeTarget.target;
            this.displayTargetTimer = ROTATION_HOLD_TICKS;
        }
    }

    private EndCrystalEntity findBestBreakCrystal(Iterable<Entity> entities, Scratch scratch, List<LivingEntity> targets) {
        if (!this.doBreak.get() || shouldPause(PauseMode.Break)) {
            return null;
        }
        EndCrystalEntity bestCrystal = null;
        double bestDamage = 0.0;

        for (Entity entity : entities) {
            if (!(entity instanceof EndCrystalEntity crystal) || !crystal.isAlive()) {
                continue;
            }
            if (this.inhibit.get() && this.attackedCrystals.containsKey(crystal.getId())) {
                continue;
            }
            if (isCrystalOutOfBreakRange(crystal, scratch)) {
                continue;
            }
            if (!isSafeSelfDamage(crystal)) {
                continue;
            }

            boolean lethalFound = false;
            for (LivingEntity target : targets) {
                float damage = DamageUtils.crystalDamage(target, crystal.getEntityPos(), false, crystal.getBlockPos().down());
                double totalHealth = EntityUtils.getTotalHealth(target);
                boolean isLethal = (double) damage >= totalHealth;

                if (!isLethal && (double) damage < getEffectiveMinDamage(target)) {
                    continue;
                }
                if (isLethal || (double) damage > bestDamage) {
                    bestCrystal = crystal;
                    bestDamage = damage;
                    if (isLethal) {
                        lethalFound = true;
                        break;
                    }
                }
            }
            if (lethalFound) {
                break;
            }
        }
        return bestCrystal;
    }

    private PlaceTarget findBestPlaceTarget(Iterable<Entity> entities, Scratch scratch, List<LivingEntity> targets) {
        if (!this.place.get() || shouldPause(PauseMode.Place)) {
            return null;
        }
        PlaceTarget prevTarget = this.bestPlaceTarget;
        BlockPos prevBase = prevTarget != null ? prevTarget.basePos : null;

        BlockPos bestStandardPos = null;
        LivingEntity bestStandardTarget = null;
        double bestStandardScore = 0.0;
        double bestStandardDamage = 0.0;

        List<Entity> obstructingCrystals = new ArrayList<>();
        int radius = (int) Math.ceil(Math.max(this.placeRange.get(), this.placeWallsRange.get()));
        int px = this.mc.player.getBlockX();
        int py = this.mc.player.getBlockY();
        int pz = this.mc.player.getBlockZ();
        int minY = Math.max(this.mc.world.getBottomY(), py - radius);
        int maxY = Math.min(this.mc.world.getTopYInclusive(), py + radius);
        double targetRangeSq = this.targetRange.get() * this.targetRange.get();

        BlockPos bestSupportPos = null;
        LivingEntity bestSupportTarget = null;
        double bestSupportScore = 0.0;
        double bestSupportDamage = 0.0;

        BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    mutablePos.set(x, y, z);
                    BlockState state = this.mc.world.getBlockState(mutablePos);
                    boolean isValidBase = state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);

                    if (!isValidBase) {
                        if (this.support.get() == SupportMode.Disabled || !state.isReplaceable()) {
                            continue;
                        }
                        if (this.noAirPlace.get() && !hasAdjacentSolidBlock(mutablePos)) {
                            continue;
                        }
                    }

                    if (!this.mc.world.getBlockState(mutablePos.up()).isAir()) {
                        continue;
                    }

                    ((IVec3d) scratch.crystalVec).meteor$set((double) x + 0.5, (double) (y + 1), (double) z + 0.5);
                    if (isOutOfRangeOrRaytrace(scratch.crystalVec, mutablePos, true, scratch)) {
                        continue;
                    }

                    boolean anyTargetNear = false;
                    for (LivingEntity target : targets) {
                        if (target.squaredDistanceTo((double) x + 0.5, (double) (y + 1), (double) z + 0.5) <= targetRangeSq) {
                            anyTargetNear = true;
                            break;
                        }
                    }
                    if (!anyTargetNear) {
                        continue;
                    }

                    float selfDmg = computeSelfDamage(scratch.crystalVec, mutablePos);
                    if (!isSelfDamageAcceptable(selfDmg)) {
                        continue;
                    }

                    Box crystalBox = new Box(x, y + 1, z, x + 1, y + 3, z + 1);
                    List<EndCrystalEntity> intersectingCrystals = new ArrayList<>();
                    boolean blockedByOtherEntity = false;

                    for (Entity entity : entities) {
                        if (!entity.isAlive() || entity.isSpectator() || entity instanceof AnimalEntity) {
                            continue;
                        }
                        if (!crystalBox.intersects(entity.getBoundingBox())) {
                            continue;
                        }
                        if (entity instanceof EndCrystalEntity crystal) {
                            if (wasCrystalPlacedByUs(crystal) && crystal.age < 20 - this.attackSpeed.get().intValue() + 15) {
                                continue;
                            }
                            intersectingCrystals.add(crystal);
                        } else {
                            blockedByOtherEntity = true;
                            break;
                        }
                    }
                    if (blockedByOtherEntity) {
                        continue;
                    }

                    List<LivingEntity> evalTargets = (!isValidBase && this.support.get() == SupportMode.Fast)
                        ? getClosestTargetList(targets)
                        : targets;

                    for (LivingEntity target : evalTargets) {
                        float dmg = DamageUtils.crystalDamage(target, scratch.crystalVec, false, mutablePos);
                        double health = EntityUtils.getTotalHealth(target);
                        if (dmg <= 0.0f) {
                            continue;
                        }
                        if ((double) dmg < getEffectiveMinDamage(target) && (double) dmg < health) {
                            continue;
                        }
                        if (!intersectingCrystals.isEmpty()) {
                            obstructingCrystals.add(intersectingCrystals.get(0));
                            break;
                        }

                        double score = (double) dmg + (mutablePos.equals(prevBase) ? 0.5 : 0.0);
                        if (isValidBase) {
                            if (score > bestStandardScore) {
                                bestStandardPos = mutablePos.toImmutable();
                                bestStandardTarget = target;
                                bestStandardScore = score;
                                bestStandardDamage = dmg;
                            }
                        } else if (score > bestSupportScore) {
                            bestSupportPos = mutablePos.toImmutable();
                            bestSupportTarget = target;
                            bestSupportScore = score;
                            bestSupportDamage = dmg;
                        }
                    }
                }
            }
        }

        if (bestStandardPos != null) {
            return new PlaceTarget(bestStandardPos, bestStandardTarget, obstructingCrystals, bestStandardDamage, false);
        }
        if (bestSupportPos != null) {
            return new PlaceTarget(bestSupportPos, bestSupportTarget, obstructingCrystals, bestSupportDamage, true);
        }
        return new PlaceTarget(null, null, obstructingCrystals, 0.0, false);
    }

    private boolean hasAdjacentSolidBlock(BlockPos pos) {
        return !this.mc.world.getBlockState(pos.down()).isAir()
            || !this.mc.world.getBlockState(pos.north()).isAir()
            || !this.mc.world.getBlockState(pos.south()).isAir()
            || !this.mc.world.getBlockState(pos.west()).isAir()
            || !this.mc.world.getBlockState(pos.east()).isAir();
    }

    private boolean hasLivingTarget() {
        for (LivingEntity target : this.activeTargets) {
            if (target.isAlive() && !target.isRemoved()) {
                return true;
            }
        }
        return false;
    }

    private List<LivingEntity> getClosestTargetList(List<LivingEntity> targets) {
        if (targets.size() <= 1) {
            return targets;
        }
        LivingEntity closest = targets.get(0);
        double minSq = Double.MAX_VALUE;
        for (LivingEntity target : targets) {
            double distSq = PlayerUtils.squaredDistanceTo(target);
            if (distSq < minSq) {
                minSq = distSq;
                closest = target;
            }
        }
        return List.of(closest);
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (this.mc.player == null || this.mc.world == null) {
            return;
        }
        if (!(event.entity instanceof EndCrystalEntity crystal)) {
            return;
        }
        if (!hasLivingTarget() || !this.doBreak.get() || !this.instant.get()) {
            return;
        }
        if (this.burstMode.get() && this.burstPauseRemainingTicks > 0) {
            return;
        }
        if (shouldPause(PauseMode.Break)) {
            return;
        }
        if (!isBreakDelayElapsed() || !isSwapDelayElapsed()) {
            return;
        }
        if (this.inhibit.get() && this.attackedCrystals.containsKey(crystal.getId())) {
            return;
        }
        if (!wasCrystalPlacedByUs(crystal)) {
            return;
        }
        if (isCrystalOutOfBreakRange(crystal, this.mainThreadScratch)) {
            return;
        }
        if (!isSafeSelfDamage(crystal)) {
            return;
        }

        recordBreakActionState(crystal);
        attackCrystalWithRotation(crystal);
        if (this.sequential.get() == Sequential.Strong) {
            executePlacePhase(true);
        }
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (event.entity instanceof EndCrystalEntity) {
            this.attackedCrystals.remove(event.entity.getId());
        }
        if (event.entity instanceof LivingEntity removedLiving) {
            this.cachedTargets = Collections.emptyList();
            this.lastTargetScanMs = 0L;
            List<LivingEntity> updated = new ArrayList<>(this.activeTargets);
            updated.remove(removedLiving);
            this.activeTargets = updated;

            PlaceTarget currentPlace = this.bestPlaceTarget;
            if (currentPlace != null && currentPlace.target == removedLiving) {
                this.bestPlaceTarget = null;
            }
            this.bestBreakCrystal = null;
        }
    }

    private void executeBreakPhase() {
        if (!this.doBreak.get() || shouldPause(PauseMode.Break)) {
            return;
        }
        if (this.burstMode.get() && this.burstPauseRemainingTicks > 0) {
            return;
        }
        if (!hasLivingTarget()) {
            return;
        }

        EndCrystalEntity targetCrystal = null;
        boolean bypassInhibit = false;
        PlaceTarget placeTarget = this.bestPlaceTarget;

        if (placeTarget != null && placeTarget.basePos == null && placeTarget.obstructingCrystals != null) {
            for (Entity entity : placeTarget.obstructingCrystals) {
                if (entity instanceof EndCrystalEntity crystal
                    && crystal.isAlive()
                    && !isCrystalOutOfBreakRange(crystal, this.mainThreadScratch)
                    && isSafeSelfDamage(crystal)) {
                    targetCrystal = crystal;
                    bypassInhibit = true;
                    break;
                }
            }
        }

        if (targetCrystal == null) {
            targetCrystal = this.bestBreakCrystal;
        }

        if (targetCrystal == null) {
            double bestDamage = -1.0;
            for (Entity entity : this.mc.world.getEntities()) {
                if (!(entity instanceof EndCrystalEntity crystal) || !crystal.isAlive()) {
                    continue;
                }
                if (this.inhibit.get() && this.attackedCrystals.containsKey(crystal.getId())) {
                    continue;
                }
                if (!wasCrystalPlacedByUs(crystal)) {
                    continue;
                }
                if (isCrystalOutOfBreakRange(crystal, this.mainThreadScratch) || !isSafeSelfDamage(crystal)) {
                    continue;
                }

                double maxDmg = 0.0;
                for (LivingEntity target : this.activeTargets) {
                    float dmg = DamageUtils.crystalDamage(target, crystal.getEntityPos(), false, crystal.getBlockPos().down());
                    if ((double) dmg > maxDmg) {
                        maxDmg = dmg;
                    }
                }
                if (maxDmg > bestDamage) {
                    bestDamage = maxDmg;
                    targetCrystal = crystal;
                    bypassInhibit = true;
                }
            }
        }

        if (targetCrystal == null || !targetCrystal.isAlive() || targetCrystal.isRemoved()) {
            return;
        }
        if (!isSwapDelayElapsed() || !isBreakDelayElapsed()) {
            return;
        }
        if (this.inhibit.get() && !bypassInhibit && this.attackedCrystals.containsKey(targetCrystal.getId())) {
            return;
        }
        if (isCrystalOutOfBreakRange(targetCrystal, this.mainThreadScratch) || !isSafeSelfDamage(targetCrystal)) {
            return;
        }

        recordBreakActionState(targetCrystal);
        attackCrystalWithRotation(targetCrystal);
    }

    private void attackCrystalWithRotation(EndCrystalEntity crystal) {
        if (!ensureAntiWeaknessTool(crystal)) {
            return;
        }
        if (this.rotate.get()) {
            Vec3d aimVec = getCrystalBaseAimVec(crystal.getBlockPos());
            rotateAndRun(aimVec, () -> sendCrystalAttackPacket(crystal));
        } else {
            sendCrystalAttackPacket(crystal);
        }
    }

    private boolean ensureAntiWeaknessTool(Entity crystal) {
        if (!this.antiWeakness.get()) {
            return true;
        }
        StatusEffectInstance weakness = this.mc.player.getStatusEffect(StatusEffects.WEAKNESS);
        StatusEffectInstance strength = this.mc.player.getStatusEffect(StatusEffects.STRENGTH);
        if (weakness == null || (strength != null && strength.getAmplifier() > weakness.getAmplifier())) {
            return true;
        }
        if (canItemBreakCrystalUnderWeakness(this.mc.player.getMainHandStack(), crystal)) {
            return true;
        }
        InvUtils.swap(InvUtils.findInHotbar(stack -> canItemBreakCrystalUnderWeakness(stack, crystal)).slot(), false);
        return false;
    }

    private boolean canItemBreakCrystalUnderWeakness(ItemStack stack, Entity target) {
        return DamageUtils.getAttackDamage(this.mc.player, target, stack) > 0.0f;
    }

    private void sendCrystalAttackPacket(Entity crystal) {
        if (crystal == null || !crystal.isAlive() || crystal.isRemoved()) {
            return;
        }
        if (this.burstMode.get() && crystal instanceof EndCrystalEntity) {
            if (this.burstPauseRemainingTicks > 0) {
                return;
            }
            if (!this.attackedCrystals.containsKey(crystal.getId())) {
                this.currentBurstCount++;
            }
            if (this.currentBurstCount >= this.burstExplosions.get()) {
                this.burstPauseRemainingTicks = this.burstPauseTicks.get();
                this.currentBurstCount = 0;
            }
        }

        this.mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(crystal, this.mc.player.isSneaking()));
        Hand hand = InvUtils.findInHotbar(Items.END_CRYSTAL).getHand();
        if (hand == null) {
            hand = Hand.MAIN_HAND;
        }
        this.mc.player.swingHand(hand);
        this.attackedCrystals.put(crystal.getId(), System.currentTimeMillis());
        this.lastBreakTimeMs = System.currentTimeMillis();
        this.renderBreakPos.set(crystal.getBlockPos().down());
        this.renderBreakTimer = this.breakTime.get();
    }

    private void executePlacePhase(boolean fromInstantBreak) {
        if (!this.place.get() || shouldPause(PauseMode.Place)) {
            return;
        }
        if (this.burstMode.get() && this.burstPauseRemainingTicks > 0) {
            return;
        }
        PlaceTarget targetInfo = this.bestPlaceTarget;
        if (targetInfo == null || targetInfo.basePos == null) {
            return;
        }
        if (targetInfo.target == null || !targetInfo.target.isAlive()) {
            this.bestPlaceTarget = null;
            return;
        }
        if (!InvUtils.testInHotbar(Items.END_CRYSTAL)) {
            return;
        }

        if (this.autoSwitch.get() == AutoSwitchMode.None) {
            if (this.mainHandItem != Items.END_CRYSTAL && this.offHandItem != Items.END_CRYSTAL) {
                return;
            }
        } else if (this.noGapSwitch.get()
            && this.autoSwitch.get() == AutoSwitchMode.Normal
            && this.offHandItem != Items.END_CRYSTAL
            && (this.mainHandItem == Items.ENCHANTED_GOLDEN_APPLE
                || this.offHandItem == Items.ENCHANTED_GOLDEN_APPLE
                || this.mainHandItem == Items.GOLDEN_APPLE
                || this.offHandItem == Items.GOLDEN_APPLE)) {
            return;
        }

        BlockPos basePos = targetInfo.basePos;
        BlockState baseState = this.mc.world.getBlockState(basePos);
        boolean hasSolidBase = baseState.isOf(Blocks.OBSIDIAN) || baseState.isOf(Blocks.BEDROCK);
        boolean needsSupport = targetInfo.needsSupport && !hasSolidBase;

        if (needsSupport) {
            if (!baseState.isReplaceable() || !InvUtils.findInHotbar(Items.OBSIDIAN).found()) {
                return;
            }
        } else if (!hasSolidBase) {
            return;
        }

        if (!this.mc.world.getBlockState(basePos.up()).isAir()) {
            return;
        }

        ((IVec3d) this.mainThreadScratch.crystalVec).meteor$set((double) basePos.getX() + 0.5, (double) (basePos.getY() + 1), (double) basePos.getZ() + 0.5);
        if (isOutOfRangeOrRaytrace(this.mainThreadScratch.crystalVec, basePos, true, this.mainThreadScratch)) {
            return;
        }

        Box placeBox = new Box(basePos.getX(), basePos.getY() + 1, basePos.getZ(), basePos.getX() + 1, basePos.getY() + 3, basePos.getZ() + 1);
        EndCrystalEntity blockingCrystal = null;
        for (Entity entity : this.mc.world.getEntities()) {
            if (!entity.isAlive() || entity.isSpectator() || entity instanceof AnimalEntity) {
                continue;
            }
            if (!placeBox.intersects(entity.getBoundingBox())) {
                continue;
            }
            if (entity instanceof EndCrystalEntity crystal) {
                if (this.inhibit.get() && this.attackedCrystals.containsKey(crystal.getId())) {
                    continue;
                }
                if (blockingCrystal == null) {
                    blockingCrystal = crystal;
                }
            } else {
                return;
            }
        }

        if (blockingCrystal != null) {
            if (this.doBreak.get()
                && (!this.burstMode.get() || this.burstPauseRemainingTicks <= 0)
                && isSafeSelfDamage(blockingCrystal)
                && isSwapDelayElapsed()
                && isBreakDelayElapsed()) {
                recordBreakActionState(blockingCrystal);
                attackCrystalWithRotation(blockingCrystal);
            }
            return;
        }

        for (Entity entity : this.mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal) || !crystal.isAlive()) {
                continue;
            }
            if (!wasCrystalPlacedByUs(crystal)) {
                continue;
            }
            if (this.inhibit.get() && this.attackedCrystals.containsKey(crystal.getId())) {
                continue;
            }
            if (!isCrystalOutOfBreakRange(crystal, this.mainThreadScratch)) {
                if (this.doBreak.get()
                    && (!this.burstMode.get() || this.burstPauseRemainingTicks <= 0)
                    && isSafeSelfDamage(crystal)
                    && isSwapDelayElapsed()
                    && isBreakDelayElapsed()) {
                    recordBreakActionState(crystal);
                    attackCrystalWithRotation(crystal);
                }
                return;
            }
            if (crystal.getBlockPos().down().equals(basePos)) {
                return;
            }
        }

        if (!isSwapDelayElapsed() || !isPlaceDelayElapsed()) {
            return;
        }
        if (!fromInstantBreak && this.strongPlacedThisTick) {
            this.strongPlacedThisTick = false;
            return;
        }

        recordPlaceActionState(targetInfo.damage, targetInfo.target);
        BlockHitResult hitResult = createCrystalPlaceHitResult(basePos);
        double damageVal = targetInfo.damage;
        BlockPos supportPos = needsSupport ? basePos : null;

        boolean executed;
        if (this.rotate.get()) {
            BlockPos aimBlock = needsSupport ? basePos : basePos.up();
            executed = rotateAndRun(getCrystalBaseAimVec(aimBlock), () -> interactPlaceCrystalOrSupport(hitResult, damageVal, supportPos));
        } else {
            interactPlaceCrystalOrSupport(hitResult, damageVal, supportPos);
            executed = true;
        }

        if (fromInstantBreak && executed) {
            this.strongPlacedThisTick = true;
        }
    }

    private BlockHitResult createCrystalPlaceHitResult(BlockPos basePos) {
        ((IVec3d) this.mainThreadScratch.crystalVec).meteor$set(
            this.mc.player.getX(),
            this.mc.player.getY() + (double) this.mc.player.getEyeHeight(this.mc.player.getPose()),
            this.mc.player.getZ()
        );
        Direction side = RaytraceUtils.getVisiblePlaceSide(basePos);
        if (side == null) {
            Vec3d delta = this.mainThreadScratch.crystalVec.subtract(Vec3d.ofCenter(basePos));
            side = Direction.getFacing(delta.x, delta.y, delta.z);
        }
        Vec3d hitPos = new Vec3d(
            (double) basePos.getX() + 0.5 + (double) side.getOffsetX() * 0.5,
            (double) basePos.getY() + 0.5 + (double) side.getOffsetY() * 0.5,
            (double) basePos.getZ() + 0.5 + (double) side.getOffsetZ() * 0.5
        );
        return new BlockHitResult(hitPos, side, basePos, false);
    }

    private void interactPlaceCrystalOrSupport(BlockHitResult hitResult, double damageVal, BlockPos supportPos) {
        Item requiredItem = supportPos == null ? Items.END_CRYSTAL : Items.OBSIDIAN;
        FindItemResult itemResult = InvUtils.findInHotbar(requiredItem);
        if (!itemResult.found()) {
            return;
        }

        int prevSlot = this.mc.player.getInventory().getSelectedSlot();
        if (this.autoSwitch.get() != AutoSwitchMode.None && !itemResult.isOffhand()) {
            InvUtils.swap(itemResult.slot(), false);
        }

        Hand hand = itemResult.getHand();
        if (hand == null) {
            return;
        }

        if (supportPos == null) {
            if (this.crystalPlaceMode.get() == SendMode.Packet) {
                this.mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(hand, hitResult, 0));
            } else {
                this.mc.interactionManager.interactBlock(this.mc.player, hand, hitResult);
            }
            this.mc.player.swingHand(hand);
            this.placedCrystalBases.put(hitResult.getBlockPos().toImmutable(), System.currentTimeMillis());
            this.lastPlaceTimeMs = System.currentTimeMillis();
            this.recentlyPlaced = true;
            this.recentPlaceTicks = 4;
            this.postPlaceHoldTicks = 8;
            this.renderPlacePos.set(hitResult.getBlockPos());
            this.lastPlacedDamage = damageVal;
            this.renderPlaceTimer = this.placeTime.get();
        } else {
            if (this.supportPlaceMode.get() == SendMode.Packet) {
                sendPacketSupportObsidianPlace(supportPos, hand);
            } else {
                BlockUtils.place(supportPos, itemResult, false, 0, true, true, false);
            }
            this.lastPlaceTimeMs = System.currentTimeMillis() + (long) this.supportDelay.get() * 50L;
            if (this.supportDelay.get() == 0) {
                interactPlaceCrystalOrSupport(hitResult, damageVal, null);
            }
        }

        if (this.autoSwitch.get() == AutoSwitchMode.Silent) {
            InvUtils.swap(prevSlot, false);
        }
    }

    private void sendPacketSupportObsidianPlace(BlockPos pos, Hand hand) {
        if (!BlockUtils.canPlaceBlock(pos, true, Blocks.OBSIDIAN)) {
            return;
        }
        Vec3d hitPos = Vec3d.ofCenter(pos);
        Direction side = BlockUtils.getPlaceSide(pos);
        BlockPos neighbor;
        if (side == null) {
            if (this.noAirPlace.get()) {
                return;
            }
            side = Direction.UP;
            neighbor = pos;
        } else {
            neighbor = pos.offset(side);
            hitPos = hitPos.add(
                (double) side.getOffsetX() * 0.5,
                (double) side.getOffsetY() * 0.5,
                (double) side.getOffsetZ() * 0.5
            );
        }
        BlockHitResult hitResult = new BlockHitResult(hitPos, side.getOpposite(), neighbor, false);
        this.mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(hand, hitResult, 0));
        this.mc.player.swingHand(hand);
    }

    private boolean rotateAndRun(Vec3d targetVec, Runnable action) {
        double yaw = NovaRotations.getYaw(targetVec);
        double pitch = NovaRotations.getPitch(targetVec);

        if (this.ticksSinceLastRotate < ROTATION_HOLD_TICKS && isAlreadyAimedWithinOneDegree(yaw, pitch)) {
            this.ticksSinceLastRotate = 0;
            if (this.smoothController.isYawStepEnabled()) {
                this.smoothController.recordStepTick();
            }
            action.run();
            return true;
        }

        if (this.smoothController.isYawStepEnabled()) {
            return stepSmoothRotationAndRun(yaw, pitch, action);
        }

        if (this.rotatedThisTick && !this.multiRotate.get()) {
            return false;
        }

        boolean firstRotationThisTick = !this.rotatedThisTick;
        saveCurrentRotationTarget(true, targetVec, 0.0, 0.0);
        if (firstRotationThisTick) {
            applyClientSmoothLookIfEnabled(yaw, pitch);
        }
        NovaRotations.rotate(yaw, pitch, 50, this.clientRotate.get(), ROTATION_OWNER, action);
        return true;
    }

    private boolean stepSmoothRotationAndRun(double targetYaw, double targetPitch, Runnable action) {
        if (this.rotatedThisTick) {
            return false;
        }
        Vec2f stepped = this.smoothController.stepTowards(targetYaw, targetPitch);
        boolean reached = this.smoothController.isWithinTolerance(targetYaw, targetPitch, 1.0);
        saveCurrentRotationTarget(false, null, stepped.x, stepped.y);
        applyClientSmoothLookIfEnabled(stepped.x, stepped.y);
        NovaRotations.rotate(stepped.x, stepped.y, 50, this.clientRotate.get(), ROTATION_OWNER, reached ? action : null);
        return reached;
    }

    private boolean isAlreadyAimedWithinOneDegree(double yaw, double pitch) {
        double prevYaw = this.hasSavedTargetPos ? NovaRotations.getYaw(this.savedRotationVec) : this.savedRotationYaw;
        double prevPitch = this.hasSavedTargetPos ? NovaRotations.getPitch(this.savedRotationVec) : this.savedRotationPitch;
        return (double) Math.abs(MathHelper.wrapDegrees((float) (yaw - prevYaw))) <= 1.0
            && Math.abs(pitch - prevPitch) <= 1.0;
    }

    private Vec3d getCrystalBaseAimVec(BlockPos pos) {
        return new Vec3d((double) pos.getX() + 0.5, (double) pos.getY() + 0.475, (double) pos.getZ() + 0.5);
    }

    private void applyClientSmoothLookIfEnabled(double yaw, double pitch) {
        if (this.clientRotate.get()) {
            NovaRotations.smoothLook(yaw, pitch, this.linearSpeed.get());
        }
    }

    private void saveCurrentRotationTarget(boolean useVec, Vec3d vec, double yaw, double pitch) {
        boolean firstRotation = !this.rotatedThisTick;
        this.rotatedThisTick = true;
        this.hasSavedTargetPos = useVec;
        if (useVec) {
            ((IVec3d) this.savedRotationVec).meteor$set(vec.x, vec.y, vec.z);
        } else {
            this.savedRotationYaw = yaw;
            this.savedRotationPitch = pitch;
        }
        this.ticksSinceLastRotate = 0;
        if (firstRotation) {
            this.lastRotationYaw = (float) (useVec ? NovaRotations.getYaw(vec) : yaw);
            this.lastRotationPitch = (float) (useVec ? NovaRotations.getPitch(vec) : pitch);
        }
    }

    private boolean isBreakDelayElapsed() {
        long minIntervalMs = (long) (1000.0 - this.attackSpeed.get() * 50.0);
        if (isLootProtectActive()) {
            minIntervalMs = Math.max(minIntervalMs, (long) this.lootProtectDelay.get());
        }
        return System.currentTimeMillis() - this.lastBreakTimeMs >= minIntervalMs;
    }

    private boolean isLootProtectActive() {
        if (!this.lootProtect.get()) {
            return false;
        }
        PlaceTarget placeTarget = this.bestPlaceTarget;
        for (LivingEntity entity : this.activeTargets) {
            if (!(entity instanceof PlayerEntity player)) {
                continue;
            }
            if (player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                continue;
            }
            double totalHealth = EntityUtils.getTotalHealth(player);
            if (totalHealth <= this.lootProtectHealth.get()) {
                return true;
            }
            if (placeTarget != null && placeTarget.target == player && placeTarget.damage >= totalHealth) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlaceDelayElapsed() {
        return System.currentTimeMillis() - this.lastPlaceTimeMs >= (long) (1000.0 - this.placeSpeed.get() * 50.0);
    }

    private boolean isSwapDelayElapsed() {
        if (this.swapDelay.get() <= 0) {
            return true;
        }
        return System.currentTimeMillis() - this.lastSlotSwapTimeMs >= (long) this.swapDelay.get() * 50L;
    }

    private int getPlayerPingMs() {
        if (this.mc.getNetworkHandler() == null || this.mc.player == null) {
            return 0;
        }
        try {
            PlayerListEntry entry = this.mc.getNetworkHandler().getPlayerListEntry(this.mc.player.getUuid());
            return entry != null ? entry.getLatency() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean wasCrystalPlacedByUs(EndCrystalEntity crystal) {
        if (this.placedCrystalBases.containsKey(crystal.getBlockPos().down())) {
            return true;
        }
        return this.placedCrystalBases.containsKey(BlockPos.ofFloored(crystal.getX(), crystal.getY() - 0.5, crystal.getZ()));
    }

    private boolean isCrystalOutOfBreakRange(EndCrystalEntity crystal, Scratch scratch) {
        ((IVec3d) scratch.crystalVec).meteor$set(crystal.getX(), crystal.getY() + 1.0, crystal.getZ());
        return isOutOfRangeOrRaytrace(scratch.crystalVec, crystal.getBlockPos().down(), false, scratch);
    }

    private boolean isBlockedByRaycast(Vec3d crystalVec, BlockPos basePos, Scratch scratch) {
        ((IRaycastContext) scratch.raycastContext).meteor$set(
            scratch.playerEyePos,
            crystalVec,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            this.mc.player
        );
        BlockHitResult hit = this.mc.world.raycast(scratch.raycastContext);
        return hit != null && hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(basePos);
    }

    private boolean isOutOfRangeOrRaytrace(Vec3d crystalVec, BlockPos basePos, boolean isPlace, Scratch scratch) {
        double maxRange = (isPlace ? this.placeRange : this.breakRange).get();
        if (!PlayerUtils.isWithin(crystalVec, maxRange)) {
            return true;
        }
        double wallsRange = (isPlace ? this.placeWallsRange : this.breakWallsRange).get();
        boolean requireLoS = this.raytrace.get() || !PlayerUtils.isWithin(crystalVec, wallsRange);
        return requireLoS && isBlockedByRaycast(crystalVec, basePos, scratch);
    }

    private float computeSelfDamage(Vec3d crystalVec, BlockPos basePos) {
        return DamageUtils.crystalDamage(this.mc.player, crystalVec, false, basePos);
    }

    private boolean isSafeSelfDamage(EndCrystalEntity crystal) {
        return isSelfDamageAcceptable(computeSelfDamage(crystal.getEntityPos(), crystal.getBlockPos().down()));
    }

    private boolean isSelfDamageAcceptable(float selfDamage) {
        return this.maxSelfDamage.get() >= 20.0 || (double) selfDamage <= this.maxSelfDamage.get();
    }

    private double getEffectiveMinDamage(LivingEntity target) {
        double minDmg = this.minDamage.get();
        if (!this.facePlace.get() || target == null) {
            return minDmg;
        }
        if (this.forceFacePlace.get().isPressed()) {
            return Math.min(minDmg, 2.0);
        }
        if ((double) EntityUtils.getTotalHealth(target) <= this.facePlaceHealth.get()) {
            return Math.min(minDmg, 2.0);
        }
        for (EquipmentSlot slot : AttributeModifierSlot.ARMOR) {
            ItemStack armorStack = target.getEquippedStack(slot);
            if (armorStack == null || armorStack.isEmpty()) {
                if (this.facePlaceMissingArmor.get()) {
                    return Math.min(minDmg, 2.0);
                }
                continue;
            }
            double durabilityPct = (double) (armorStack.getMaxDamage() - armorStack.getDamage()) / (double) armorStack.getMaxDamage() * 100.0;
            if (durabilityPct <= this.facePlaceDurability.get()) {
                return Math.min(minDmg, 2.0);
            }
        }
        return minDmg;
    }

    private boolean shouldYieldToTrapOrSurround() {
        if (!this.yieldToTrap.get()) {
            return false;
        }
        SelfTrapN selfTrap = Modules.get().get(SelfTrapN.class);
        if (selfTrap != null && selfTrap.isActivelyPlacing()) {
            return true;
        }
        SurroundN surround = Modules.get().get(SurroundN.class);
        return surround != null && surround.isActivelyPlacing();
    }

    private boolean shouldPause(PauseMode actionType) {
        if (shouldYieldToTrapOrSurround()) {
            return true;
        }
        if ((this.mc.player.isUsingItem() || this.mc.options.useKey.isPressed()) && this.pauseOnUse.get().matches(actionType)) {
            return true;
        }
        if (this.pauseOnLag.get() && TickRate.INSTANCE.getTimeSinceLastTick() >= 1.0f) {
            return true;
        }
        if (this.pauseOnMine.get().matches(actionType) && this.mc.interactionManager.isBreakingBlock()) {
            return true;
        }
        return (double) EntityUtils.getTotalHealth(this.mc.player) <= this.pauseHealth.get();
    }

    private void updateTargetList() {
        long now = System.currentTimeMillis();
        List<LivingEntity> candidates;
        if (now - this.lastTargetScanMs < TARGET_CACHE_TTL_MS) {
            candidates = new ArrayList<>(this.cachedTargets);
            candidates.removeIf(e -> !e.isAlive() || e.isRemoved());
        } else {
            candidates = new ArrayList<>();
            double rangeSq = this.targetRange.get() * this.targetRange.get();
            for (Entity entity : this.mc.world.getEntities()) {
                if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                    continue;
                }
                if (living instanceof PlayerEntity player) {
                    if (player.getAbilities().creativeMode || player == this.mc.player || !Friends.get().shouldAttack(player)) {
                        continue;
                    }
                }
                if (!this.entities.get().contains(living.getType())) {
                    continue;
                }
                if (living.squaredDistanceTo(this.mc.player) <= rangeSq) {
                    candidates.add(living);
                }
            }
            this.cachedTargets = candidates;
            this.lastTargetScanMs = now;
        }
        this.activeTargets = filterTargetsByMode(candidates);
    }

    private List<LivingEntity> filterTargetsByMode(List<LivingEntity> candidates) {
        if (candidates.size() <= 1 || this.targetMode.get() == TargetMode.All) {
            return candidates;
        }
        LivingEntity best = null;
        double bestMetric = 0.0;
        for (LivingEntity candidate : candidates) {
            double metric = switch (this.targetMode.get().ordinal()) {
                case 1 -> -this.mc.player.squaredDistanceTo(candidate);
                case 2 -> this.mc.player.squaredDistanceTo(candidate);
                default -> -EntityUtils.getTotalHealth(candidate);
            };
            if (best == null || metric > bestMetric) {
                best = candidate;
                bestMetric = metric;
            }
        }
        return List.of(best);
    }

    public boolean canPlaceCrystalAtPos(BlockPos crystalPos) {
        if (!this.isActive() || this.mc.player == null || this.mc.world == null) {
            return false;
        }
        BlockPos basePos = crystalPos.down();
        ((IVec3d) this.mainThreadScratch.crystalVec).meteor$set((double) basePos.getX() + 0.5, (double) (basePos.getY() + 1), (double) basePos.getZ() + 0.5);
        if (!PlayerUtils.isWithin(this.mainThreadScratch.crystalVec, this.placeRange.get())) {
            return false;
        }
        boolean requireLoS = this.raytrace.get() || !PlayerUtils.isWithin(this.mainThreadScratch.crystalVec, this.placeWallsRange.get());
        if (requireLoS && !hasClearLineOfSightToPos(this.mainThreadScratch.crystalVec, basePos, crystalPos, this.mainThreadScratch)) {
            return false;
        }
        return isSelfDamageAcceptable(computeSelfDamage(this.mainThreadScratch.crystalVec, basePos));
    }

    private boolean hasClearLineOfSightToPos(Vec3d crystalVec, BlockPos basePos, BlockPos crystalPos, Scratch scratch) {
        ((IRaycastContext) scratch.raycastContext).meteor$set(
            scratch.playerEyePos,
            crystalVec,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            this.mc.player
        );
        BlockHitResult hit = this.mc.world.raycast(scratch.raycastContext);
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        BlockPos hitPos = hit.getBlockPos();
        return hitPos.equals(basePos) || hitPos.equals(crystalPos);
    }

    public boolean isFacePlacingTarget() {
        if (!this.isActive()) {
            return false;
        }
        PlaceTarget targetInfo = this.bestPlaceTarget;
        return targetInfo != null
            && targetInfo.basePos != null
            && targetInfo.target != null
            && targetInfo.target.isAlive()
            && !isStandardMinDamageOrLethal(targetInfo.damage, targetInfo.target);
    }

    public double evaluateCrystalDamageAt(BlockPos crystalPos, LivingEntity target) {
        if (!canPlaceCrystalAtPos(crystalPos)) {
            return -1.0;
        }
        BlockPos basePos = crystalPos.down();
        Vec3d crystalVec = new Vec3d((double) crystalPos.getX() + 0.5, (double) crystalPos.getY(), (double) crystalPos.getZ() + 0.5);
        float dmg = DamageUtils.crystalDamage(target, crystalVec, false, basePos);
        double health = EntityUtils.getTotalHealth(target);
        if (dmg <= 0.0f) {
            return -1.0;
        }
        if ((double) dmg < getEffectiveMinDamage(target) && (double) dmg < health) {
            return -1.0;
        }
        return dmg;
    }

    private boolean isPausedByBurst() {
        return this.burstMode.get() && this.burstPauseRemainingTicks > 0;
    }

    public boolean isOperating() {
        if (isPausedByBurst()) {
            return this.isActive();
        }
        return this.isActive() && (this.actedThisTick || this.recentlyPlaced || this.ticksSinceLastRotate < ROTATION_HOLD_TICKS);
    }

    public boolean isFacePlacingOperating() {
        return isOperating() && !this.fullDamageAction;
    }

    private boolean isStandardMinDamageOrLethal(double damageVal, LivingEntity target) {
        if (target == null) {
            return false;
        }
        return damageVal >= this.minDamage.get() || damageVal >= (double) EntityUtils.getTotalHealth(target);
    }

    private void recordPlaceActionState(double damageVal, LivingEntity target) {
        this.actedThisTick = true;
        this.fullDamageAction = isStandardMinDamageOrLethal(damageVal, target);
    }

    private void recordBreakActionState(EndCrystalEntity crystal) {
        this.actedThisTick = true;
        boolean meetsStandard = true;
        for (LivingEntity target : this.activeTargets) {
            float dmg = DamageUtils.crystalDamage(target, crystal.getEntityPos(), false, crystal.getBlockPos().down());
            if (!isStandardMinDamageOrLethal(dmg, target)) {
                meetsStandard = false;
                break;
            }
        }
        this.fullDamageAction = meetsStandard;
    }

    @Override
    public String getInfoString() {
        if (this.currentDisplayTarget != null && this.displayTargetTimer > 0) {
            return EntityUtils.getName(this.currentDisplayTarget);
        }
        return null;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof UpdateSelectedSlotC2SPacket) {
            this.lastSlotSwapTimeMs = System.currentTimeMillis();
        }
    }

    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        if (event.packet instanceof PlayerMoveC2SPacket movePacket) {
            this.trackedServerYaw = movePacket.getYaw((float) this.trackedServerYaw);
            this.trackedServerPitch = movePacket.getPitch((float) this.trackedServerPitch);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!this.render.get()) {
            return;
        }
        if (this.renderPlace.get() && this.renderPlaceTimer > 0) {
            event.renderer.box(this.renderPlacePos, this.sideColor.get(), this.lineColor.get(), this.shapeMode.get(), 0);
        }
        if (this.renderBreak.get() && this.renderBreakTimer > 0) {
            event.renderer.box(this.renderBreakPos, this.sideColor.get(), this.lineColor.get(), this.shapeMode.get(), 0);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!this.render.get() || !this.damage.get() || this.renderPlaceTimer <= 0) {
            return;
        }
        this.nametagPos.set(
            (double) this.renderPlacePos.getX() + 0.5,
            (double) this.renderPlacePos.getY() + 0.5,
            (double) this.renderPlacePos.getZ() + 0.5
        );
        if (NametagUtils.to2D(this.nametagPos, this.damageScale.get())) {
            NametagUtils.begin(this.nametagPos);
            TextRenderer.get().begin(1.0, false, true);
            String text = String.format("%.1f", this.lastPlacedDamage);
            double halfWidth = TextRenderer.get().getWidth(text) / 2.0;
            TextRenderer.get().render(text, -halfWidth, 0.0, this.damageColor.get(), true);
            TextRenderer.get().end();
            NametagUtils.end();
        }
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

    public enum TargetMode {
        All,
        LowestDistance,
        HighestDistance,
        LowestHealth
    }

    public enum Sequential {
        None,
        Strict,
        Strong
    }

    public enum AutoSwitchMode {
        Normal,
        Silent,
        None
    }

    public enum SendMode {
        Normal,
        Packet
    }

    public enum SupportMode {
        Disabled,
        Accurate,
        Fast
    }

    public enum PauseMode {
        All,
        Place,
        Break,
        None;

        public boolean matches(PauseMode other) {
            return this == All || this == other;
        }
    }

    public enum YawStepMode {
        Break,
        All
    }

    private static final class PlaceTarget {
        final BlockPos basePos;
        final LivingEntity target;
        final List<Entity> obstructingCrystals;
        final double damage;
        final boolean needsSupport;

        PlaceTarget(BlockPos basePos, LivingEntity target, List<Entity> obstructingCrystals, double damage, boolean needsSupport) {
            this.basePos = basePos;
            this.target = target;
            this.obstructingCrystals = obstructingCrystals;
            this.damage = damage;
            this.needsSupport = needsSupport;
        }
    }

    private static final class Scratch {
        final Vec3d crystalVec = new Vec3d(0.0, 0.0, 0.0);
        final Vec3d playerEyePos = new Vec3d(0.0, 0.0, 0.0);
        RaycastContext raycastContext;
    }
}
