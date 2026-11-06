package com.nova.addon.utils.inventory;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;

public record SlotSwapResult(int slot, boolean found) {
    public static SlotSwapResult notFound() {
        return new SlotSwapResult(-1, false);
    }

    public void swapHotbar(boolean sendPacket) {
        if (MeteorClient.mc.player == null || this.slot > 8 || this.slot < 0) {
            return;
        }
        MeteorClient.mc.player.getInventory().setSelectedSlot(this.slot);
        if (sendPacket && MeteorClient.mc.getNetworkHandler() != null) {
            MeteorClient.mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(this.slot));
        }
    }

    public void swapHotbar() {
        swapHotbar(false);
    }

    public void swapInventory(int slotId, int button, SlotActionType actionType) {
        if (MeteorClient.mc.player == null || MeteorClient.mc.interactionManager == null || this.slot == -1) {
            return;
        }
        MeteorClient.mc.interactionManager.clickSlot(
            MeteorClient.mc.player.currentScreenHandler.syncId,
            slotId,
            button,
            actionType,
            MeteorClient.mc.player
        );
    }

    public boolean isHotbar() {
        return this.slot < 9 && this.slot != -1;
    }
}
