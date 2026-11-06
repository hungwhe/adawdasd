package com.nova.addon.utils.inventory;

import java.util.function.Predicate;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;

public final class InventorySwapUtils {
    private InventorySwapUtils() {
    }

    public static SlotSwapResult findAxe() {
        return findItem(stack -> stack.getItem() instanceof AxeItem);
    }

    public static SlotSwapResult findWeaponInHotbar() {
        return findInHotbar(stack ->
            stack.isIn(ItemTags.SWORDS)
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof MaceItem
        );
    }

    public static SlotSwapResult findWeapon() {
        return findItem(stack ->
            stack.isIn(ItemTags.SWORDS)
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof MaceItem
        );
    }

    public static void selectSlot(int slot) {
        if (slot < 0 || slot > 8 || MeteorClient.mc.player == null) {
            return;
        }
        MeteorClient.mc.player.getInventory().setSelectedSlot(slot);
        if (MeteorClient.mc.getNetworkHandler() != null) {
            MeteorClient.mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    public static void swapSlotWithHotbar(int slot) {
        if (slot == -1 || MeteorClient.mc.player == null || MeteorClient.mc.getNetworkHandler() == null || MeteorClient.mc.interactionManager == null) {
            return;
        }
        if (slot >= 9) {
            MeteorClient.mc.interactionManager.clickSlot(
                MeteorClient.mc.player.currentScreenHandler.syncId,
                slot,
                MeteorClient.mc.player.getInventory().getSelectedSlot(),
                SlotActionType.SWAP,
                MeteorClient.mc.player
            );
            MeteorClient.mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(MeteorClient.mc.player.currentScreenHandler.syncId));
        } else {
            MeteorClient.mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    public static SlotSwapResult findInHotbar(Predicate<ItemStack> predicate) {
        return findInRange(predicate, 0, 8);
    }

    public static SlotSwapResult findItem(Predicate<ItemStack> predicate) {
        if (MeteorClient.mc.player == null) {
            return SlotSwapResult.notFound();
        }
        return findInRange(predicate, 0, MeteorClient.mc.player.getInventory().size());
    }

    public static SlotSwapResult findInRange(Predicate<ItemStack> predicate, int start, int end) {
        if (MeteorClient.mc.player == null) {
            return SlotSwapResult.notFound();
        }
        for (int i = start; i <= end; i++) {
            if (predicate.test(MeteorClient.mc.player.getInventory().getStack(i))) {
                return new SlotSwapResult(i, true);
            }
        }
        return SlotSwapResult.notFound();
    }
}
