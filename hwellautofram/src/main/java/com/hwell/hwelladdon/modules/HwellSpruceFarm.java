package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class HwellSpruceFarm extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Bán kính quét (block).")
        .defaultValue(10)
        .range(1, 20)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay giữa các hành động (ms).")
        .defaultValue(100)
        .range(0, 1000)
        .build()
    );

    private final Setting<Boolean> instantBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("instant-break")
        .description("Phá block ngay lập tức bằng packet (Siêu nhanh, có thể bị kick nếu server có anti-cheat).")
        .defaultValue(false)
        .build()
    );

    private enum State {
        SCAN_EMPTY_PODZOL,
        PLANT_SAPLING,
        SCAN_GROW_TREE,
        USE_BONE_MEAL,
        SCAN_LOGS,
        CHOP_TREE
    }

    private State state = State.SCAN_EMPTY_PODZOL;
    private BlockPos targetPos = null;
    private int timer = 0;

    public HwellSpruceFarm() {
        super(HwellAddon.HWELL_FRAM_VIP, "hwell-spruce-farm", "Tự động trồng, bón phân và chặt cây Spruce (Silent Rotate).");
    }

    @Override
    public void onActivate() {
        state = State.SCAN_EMPTY_PODZOL;
        targetPos = null;
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        switch (state) {
            case SCAN_EMPTY_PODZOL -> {
                targetPos = findBlock(Blocks.PODZOL, false);
                if (targetPos != null) {
                    state = State.PLANT_SAPLING;
                } else {
                    state = State.SCAN_GROW_TREE;
                }
                timer = 1;
            }

            case PLANT_SAPLING -> {
                if (targetPos != null) {
                    int slot = findItemInHotbar(Items.SPRUCE_SAPLING);
                    if (slot != -1) {
                        doSwap(slot);
                        doSilentRotate(targetPos, () -> interactBlock(targetPos, Direction.UP));
                    }
                }
                state = State.SCAN_EMPTY_PODZOL;
                timer = getTicks();
            }

            case SCAN_GROW_TREE -> {
                targetPos = findBlock(Blocks.PODZOL, true);
                if (targetPos != null) {
                    state = State.USE_BONE_MEAL;
                } else {
                    state = State.SCAN_LOGS;
                }
                timer = 1;
            }

            case USE_BONE_MEAL -> {
                if (targetPos != null) {
                    int slot = findItemInHotbar(Items.BONE_MEAL);
                    if (slot != -1) {
                        doSwap(slot);
                        BlockPos saplingPos = targetPos.up();
                        doSilentRotate(saplingPos, () -> interactBlock(saplingPos, Direction.UP));
                    }
                }
                state = State.SCAN_GROW_TREE; 
                timer = getTicks() + 2; 
            }

            case SCAN_LOGS -> {
                targetPos = findSpruceLogs();
                if (targetPos != null) {
                    state = State.CHOP_TREE;
                } else {
                    state = State.SCAN_EMPTY_PODZOL;
                }
                timer = 1;
            }

            case CHOP_TREE -> {
                if (targetPos != null && !mc.world.getBlockState(targetPos).isAir()) {
                    int slot = findItemInHotbar(Items.NETHERITE_AXE);
                    if (slot == -1) {
                        toggle(); 
                        return;
                    }
                    doSwap(slot);
                    
                    doSilentRotate(targetPos, () -> {
                        if (instantBreak.get()) {
                            // Siêu nhanh: Gửi packet START và STOP trực tiếp
                            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, targetPos, Direction.UP, 0));
                            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, targetPos, Direction.UP, 0));
                            // Cập nhật client-side để tránh ghost block
                            mc.world.setBlockState(targetPos, Blocks.AIR.getDefaultState());
                        } else {
                            // Tốc độ chuẩn (không bị kick)
                            mc.interactionManager.updateBlockBreakingProgress(targetPos, Direction.UP);
                        }
                        mc.player.swingHand(Hand.MAIN_HAND);
                    });
                    
                    if (instantBreak.get()) {
                        targetPos = null; // Phá xong, scan cái mới
                    }
                    timer = 0;
                } else {
                    // Nếu block đã vỡ, quét ngay block tiếp theo
                    targetPos = findSpruceLogs();
                    if (targetPos == null) {
                        state = State.SCAN_EMPTY_PODZOL;
                    }
                    timer = 0;
                }
            }
        }
    }

    private BlockPos findBlock(Block blockType, boolean requireSapling) {
        BlockPos playerPos = mc.player.getBlockPos();
        int r = range.get();
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    
                    if (mc.world.getBlockState(pos).isOf(blockType)) {
                        boolean hasSapling = mc.world.getBlockState(pos.up()).isOf(Blocks.SPRUCE_SAPLING);
                        if (requireSapling == hasSapling) {
                            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
                            if (dist <= 4.5 && dist < closestDist) {
                                closestDist = dist;
                                closest = pos;
                            }
                        }
                    }
                }
            }
        }
        return closest;
    }

    private BlockPos findSpruceLogs() {
        BlockPos playerPos = mc.player.getBlockPos();
        int r = range.get();
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);

                    if (state.isOf(Blocks.SPRUCE_LOG) || state.isOf(Blocks.SPRUCE_WOOD)) {
                        double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
                        if (dist <= 4.5 && dist < closestDist) {
                            closestDist = dist;
                            closest = pos;
                        }
                    }
                }
            }
        }
        return closest;
    }

    private int findItemInHotbar(Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private void doSwap(int slot) {
        if (mc.player.getInventory().getSelectedSlot() != slot) {
            InvUtils.swap(slot, false);
        }
    }

    private void doSilentRotate(BlockPos pos, Runnable action) {
        float yaw = (float) Rotations.getYaw(pos.toCenterPos());
        float pitch = (float) Rotations.getPitch(pos.toCenterPos());
        Rotations.rotate(yaw, pitch, 50, action);
    }

    private void interactBlock(BlockPos pos, Direction side) {
        Vec3d hitVec = pos.toCenterPos().add(Vec3d.of(side.getVector()).multiply(0.5));
        BlockHitResult hitResult = new BlockHitResult(hitVec, side, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private int getTicks() {
        if (delay.get() <= 0) return 0;
        return Math.max(1, delay.get() / 50);
    }

    @Override
    public String getInfoString() {
        return state.name();
    }
}