package com.nova.addon.utils.world;

import com.nova.addon.utils.rotation.NovaRotations;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class BlockPlaceUtils {
    private BlockPlaceUtils() {
    }

    public static boolean placeBlock(BlockPos pos, FindItemResult item, boolean rotate, int rotationPriority, boolean checkEntities) {
        return placeBlock(pos, item, rotate, rotationPriority, true, checkEntities, true, false, false);
    }

    public static boolean placeBlock(
        BlockPos pos,
        FindItemResult item,
        boolean rotate,
        int rotationPriority,
        boolean swingHand,
        boolean checkEntities,
        boolean swapBack,
        boolean packetPlace,
        boolean airPlace
    ) {
        if (!item.found() || MeteorClient.mc.player == null || MeteorClient.mc.world == null) {
            return false;
        }
        if (item.isOffhand()) {
            return placeWithHand(pos, Hand.OFF_HAND, item.slot(), rotate, rotationPriority, swingHand, checkEntities, false, swapBack, packetPlace, airPlace);
        }
        if (item.isHotbar()) {
            return placeWithHand(pos, Hand.MAIN_HAND, item.slot(), rotate, rotationPriority, swingHand, checkEntities, true, swapBack, packetPlace, airPlace);
        }
        return false;
    }

    public static Vec3d getTargetHitVec(BlockPos pos) {
        Vec3d hitPos = Vec3d.ofCenter(pos);
        Direction side = BlockUtils.getPlaceSide(pos);
        if (side != null) {
            hitPos = hitPos.add(
                side.getOffsetX() * 0.5,
                side.getOffsetY() * 0.5,
                side.getOffsetZ() * 0.5
            );
        }
        return hitPos;
    }

    public static boolean place(
        BlockPos pos,
        FindItemResult item,
        boolean rotate,
        int rotationPriority,
        boolean swingHand,
        boolean checkEntities,
        boolean packetPlace,
        boolean airPlace
    ) {
        return placeBlock(pos, item, rotate, rotationPriority, swingHand, checkEntities, true, packetPlace, airPlace);
    }

    public static boolean placeWithHand(
        BlockPos pos,
        Hand hand,
        int slot,
        boolean rotate,
        int rotationPriority,
        boolean swingHand,
        boolean checkEntities,
        boolean swapSlot,
        boolean swapBack,
        boolean packetPlace,
        boolean airPlace
    ) {
        if (MeteorClient.mc.player == null || MeteorClient.mc.world == null) {
            return false;
        }
        if (!BlockUtils.canPlace(pos, checkEntities)) {
            return false;
        }

        Vec3d hitPos = Vec3d.ofCenter(pos);
        Direction side = BlockUtils.getPlaceSide(pos);
        BlockPos neighbor;

        if (side == null) {
            if (!airPlace) {
                return false;
            }
            side = Direction.UP;
            neighbor = pos;
        } else {
            neighbor = pos.offset(side);
            hitPos = hitPos.add(
                side.getOffsetX() * 0.5,
                side.getOffsetY() * 0.5,
                side.getOffsetZ() * 0.5
            );
        }

        BlockHitResult hitResult = new BlockHitResult(hitPos, side.getOpposite(), neighbor, false);
        if (rotate) {
            NovaRotations.rotate(
                NovaRotations.getYaw(hitPos),
                NovaRotations.getPitch(hitPos),
                rotationPriority,
                () -> executeInteract(hitResult, hand, slot, swingHand, swapSlot, swapBack, packetPlace)
            );
        } else {
            executeInteract(hitResult, hand, slot, swingHand, swapSlot, swapBack, packetPlace);
        }
        return true;
    }

    private static void executeInteract(
        BlockHitResult hitResult,
        Hand hand,
        int slot,
        boolean swingHand,
        boolean swapSlot,
        boolean swapBack,
        boolean packetPlace
    ) {
        if (MeteorClient.mc.player == null || MeteorClient.mc.interactionManager == null || MeteorClient.mc.getNetworkHandler() == null) {
            return;
        }

        int prevSlot = MeteorClient.mc.player.getInventory().getSelectedSlot();
        if (swapSlot) {
            InvUtils.swap(slot, false);
        }

        boolean wasSneaking = MeteorClient.mc.player.isSneaking();
        BlockPos targetPos = hitResult.getBlockPos();
        if (!wasSneaking && isClickableBlock(MeteorClient.mc.world.getBlockState(targetPos))) {
            MeteorClient.mc.player.setSneaking(true);
        }

        if (packetPlace) {
            MeteorClient.mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(hand, hitResult, 0));
            if (swingHand) {
                MeteorClient.mc.player.swingHand(hand);
            } else {
                MeteorClient.mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
            }
        } else {
            ActionResult result = MeteorClient.mc.interactionManager.interactBlock(MeteorClient.mc.player, hand, hitResult);
            if (result.isAccepted()) {
                if (swingHand) {
                    MeteorClient.mc.player.swingHand(hand);
                } else {
                    MeteorClient.mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
                }
            }
        }

        if (!wasSneaking) {
            MeteorClient.mc.player.setSneaking(false);
        }
        if (swapSlot && swapBack) {
            InvUtils.swap(prevSlot, false);
        }
    }

    private static boolean isClickableBlock(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.CRAFTING_TABLE
            || block == Blocks.FURNACE
            || block == Blocks.BLAST_FURNACE
            || block == Blocks.SMOKER
            || block == Blocks.ANVIL
            || block == Blocks.CHIPPED_ANVIL
            || block == Blocks.DAMAGED_ANVIL
            || block == Blocks.CHEST
            || block == Blocks.TRAPPED_CHEST
            || block == Blocks.ENDER_CHEST
            || block == Blocks.SHULKER_BOX
            || block == Blocks.HOPPER
            || block == Blocks.DISPENSER
            || block == Blocks.DROPPER
            || block == Blocks.BREWING_STAND
            || block == Blocks.BEACON
            || block == Blocks.ENCHANTING_TABLE
            || block == Blocks.GRINDSTONE
            || block == Blocks.STONECUTTER
            || block == Blocks.LOOM
            || block == Blocks.CARTOGRAPHY_TABLE
            || block == Blocks.SMITHING_TABLE
            || block == Blocks.BARREL;
    }
}
