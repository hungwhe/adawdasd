package com.nova.addon.modules.pvp;

import com.nova.addon.NovaCategories;
import com.nova.addon.events.PreTickMovementEvent;
import com.nova.addon.events.SendMovementPacketsEvent;
import com.nova.addon.utils.input.InputOverrideModule;
import com.nova.addon.utils.input.NeutralInputManager;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.ResourcePackStatusC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

public class AutoTotemN extends Module implements InputOverrideModule {
    private static final int OFFHAND_BUTTON = 40;

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    public final Setting<Integer> delay = this.sgGeneral.add(new IntSetting.Builder()
        .name("Delay")
        .description("Ticks between each swap.")
        .defaultValue(0)
        .min(0)
        .max(20)
        .sliderMin(0)
        .sliderMax(20)
        .build()
    );

    public final Setting<Boolean> useMainhand = this.sgGeneral.add(new BoolSetting.Builder()
        .name("Use Mainhand")
        .description("Also keeps a totem in a hotbar slot.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> mainhandSlot = this.sgGeneral.add(new IntSetting.Builder()
        .name("Mainhand Slot")
        .description("Hotbar slot that keeps a totem.")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderMin(1)
        .sliderMax(9)
        .visible(this.useMainhand::get)
        .build()
    );

    public final Setting<Integer> mainhandDelay = this.sgGeneral.add(new IntSetting.Builder()
        .name("Mainhand Delay")
        .description("Delay in ticks before swapping a totem into the mainhand slot.")
        .defaultValue(0)
        .min(0)
        .max(100)
        .sliderMin(0)
        .sliderMax(100)
        .visible(this.useMainhand::get)
        .build()
    );

    private int swapCooldownTicks;
    private boolean holdingNeutralInput;
    private boolean inputPacketSent;
    private boolean totemPopDetected;
    private boolean urgentRefillPending;
    private boolean refillingMainhand;
    private int mainhandCooldownTicks = -1;
    public boolean hasAnyTotem = false;
    private int hasReserveTotem = 0;

    public AutoTotemN() {
        super(NovaCategories.NOVA_PVP, "AutoTotemN", "Automatically equips totem to offhand using silent inventory.");
    }

    @Override
    public boolean shouldUnsprint() {
        return this.isActive() && this.holdingNeutralInput && NeutralInputManager.isEngaged();
    }

    public boolean isLockingMovement() {
        return shouldUnsprint();
    }

    public boolean hasAvailableTotems() {
        return this.isActive() && this.hasAnyTotem;
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    private void resetState() {
        this.swapCooldownTicks = 0;
        this.totemPopDetected = false;
        this.urgentRefillPending = false;
        this.refillingMainhand = false;
        this.mainhandCooldownTicks = -1;
        this.inputPacketSent = false;
        this.hasAnyTotem = false;
        releaseNeutralInput();
    }

    private void engageNeutralInput() {
        if (isRidingOrGliding()) {
            NeutralInputManager.activateImmediate();
        } else {
            NeutralInputManager.requestNeutral(true, true);
        }
        this.holdingNeutralInput = true;
    }

    private void releaseNeutralInput() {
        NeutralInputManager.reset();
        this.holdingNeutralInput = false;
    }

    private boolean isRidingOrGliding() {
        return this.mc.player != null && (this.mc.player.hasVehicle() || this.mc.player.isGliding());
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet) || packet.getStatus() != 35) {
            return;
        }
        if (this.mc.player == null || this.mc.world == null) {
            return;
        }
        if (packet.getEntity(this.mc.world) != this.mc.player) {
            return;
        }
        if (!this.mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return;
        }
        if (this.mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return;
        }
        if (!this.isActive()) {
            return;
        }

        this.totemPopDetected = true;
        this.urgentRefillPending = true;
        if (!NeutralInputManager.isEngaged()) {
            engageNeutralInput();
        }
        tryUrgentOffhandSwap();
    }

    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        if (event.packet instanceof PlayerInputC2SPacket) {
            this.inputPacketSent = true;
            tryUrgentOffhandSwap();
            return;
        }
        if (event.packet instanceof PlayerMoveC2SPacket || isActionPacket(event.packet)) {
            this.inputPacketSent = false;
        }
    }

    @EventHandler(priority = 200)
    private void onPreTickMovement(PreTickMovementEvent event) {
        NeutralInputManager.onPreTickMovement();
    }

    @EventHandler(priority = 200)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        NeutralInputManager.onPostSendMovementPackets();
    }

    @EventHandler(priority = 200)
    private void onTickPre(TickEvent.Pre event) {
        if (this.mc.player == null || this.mc.world == null || this.mc.interactionManager == null
            || this.mc.player.isCreative() || this.mc.player.isSpectator()) {
            this.swapCooldownTicks = 0;
            this.totemPopDetected = false;
            this.urgentRefillPending = false;
            this.refillingMainhand = false;
            this.mainhandCooldownTicks = -1;
            releaseNeutralInput();
            return;
        }

        updateMainhandDelayTimer();

        boolean offhandHasTotem = this.mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        int candidateSlot = findTotemSlot();
        this.hasReserveTotem = candidateSlot != -1 ? 1 : 0;
        this.hasAnyTotem = candidateSlot != -1 || offhandHasTotem || (this.useMainhand.get() && isMainhandSlotHoldingTotem());

        boolean popped = this.totemPopDetected;
        if (this.swapCooldownTicks > 0 && !popped) {
            this.swapCooldownTicks--;
            return;
        }
        if (popped) {
            this.swapCooldownTicks = 0;
        }

        if (!offhandHasTotem) {
            this.refillingMainhand = false;
            if (candidateSlot < 0) {
                this.totemPopDetected = false;
                this.urgentRefillPending = false;
                releaseNeutralInput();
                return;
            }
            if (NeutralInputManager.isPending()) {
                return;
            }
            if (NeutralInputManager.isActive()) {
                releaseNeutralInput();
                this.urgentRefillPending = false;
                if (swapSlotToOffhand(candidateSlot)) {
                    this.swapCooldownTicks = this.delay.get();
                    this.totemPopDetected = false;
                }
                return;
            }
            engageNeutralInput();
            return;
        }

        if (this.totemPopDetected) {
            this.totemPopDetected = false;
            this.urgentRefillPending = false;
        }

        boolean needsMainhandRefill = this.useMainhand.get()
            && candidateSlot != -1
            && countTotalInventoryTotems() >= 2
            && isMainhandReadyForRefill();

        if (needsMainhandRefill) {
            this.refillingMainhand = true;
            if (NeutralInputManager.isPending()) {
                return;
            }
            if (NeutralInputManager.isActive()) {
                releaseNeutralInput();
                if (swapSlotToMainhand(candidateSlot)) {
                    this.refillingMainhand = false;
                }
                return;
            }
            engageNeutralInput();
        } else {
            this.refillingMainhand = false;
            releaseNeutralInput();
        }
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (this.holdingNeutralInput && NeutralInputManager.hasTimedOut()) {
            releaseNeutralInput();
            this.totemPopDetected = false;
            this.urgentRefillPending = false;
            this.swapCooldownTicks = Math.max(this.delay.get(), 20);
        }
    }

    private void tryUrgentOffhandSwap() {
        if (!this.totemPopDetected || !this.urgentRefillPending) {
            return;
        }
        if (this.mc.player == null || this.mc.world == null || this.mc.interactionManager == null) {
            return;
        }
        if (this.mc.player.isCreative() || this.mc.player.isSpectator()) {
            return;
        }
        if (!NeutralInputManager.isActive()) {
            return;
        }
        int slot = findTotemSlot();
        if (slot < 0) {
            return;
        }

        this.urgentRefillPending = false;
        releaseNeutralInput();
        if (swapSlotToOffhand(slot)) {
            this.swapCooldownTicks = this.delay.get();
            this.totemPopDetected = false;
        }
        this.inputPacketSent = false;
    }

    private boolean swapSlotToOffhand(int invSlot) {
        if (this.mc.player == null || this.mc.interactionManager == null || invSlot < 0 || invSlot >= 36) {
            return false;
        }
        if (!this.mc.player.getInventory().getStack(invSlot).isOf(Items.TOTEM_OF_UNDYING)) {
            return false;
        }
        if (this.totemPopDetected && this.mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            this.mc.player.getOffHandStack().decrement(1);
        }

        ScreenHandler handler = this.mc.player.currentScreenHandler;
        if (handler == null) {
            return false;
        }
        int screenSlotId = findScreenSlotId(handler, invSlot);
        if (screenSlotId < 0 || screenSlotId >= handler.slots.size()) {
            return false;
        }
        Slot slot = handler.getSlot(screenSlotId);
        if (slot.inventory != this.mc.player.getInventory()
            || slot.getIndex() != invSlot
            || !slot.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return false;
        }

        this.mc.interactionManager.clickSlot(handler.syncId, screenSlotId, OFFHAND_BUTTON, SlotActionType.SWAP, this.mc.player);
        return true;
    }

    private boolean swapSlotToMainhand(int invSlot) {
        if (this.mc.player == null || this.mc.interactionManager == null || invSlot < 0 || invSlot >= 36) {
            return false;
        }
        if (!this.mc.player.getInventory().getStack(invSlot).isOf(Items.TOTEM_OF_UNDYING)) {
            return false;
        }

        int targetHotbarSlot = this.mainhandSlot.get() - 1;
        if (invSlot == targetHotbarSlot || !isMainhandReadyForRefill()) {
            return false;
        }

        ScreenHandler handler = this.mc.player.currentScreenHandler;
        if (handler == null) {
            return false;
        }
        int screenSlotId = findScreenSlotId(handler, invSlot);
        if (screenSlotId < 0 || screenSlotId >= handler.slots.size()) {
            return false;
        }
        Slot slot = handler.getSlot(screenSlotId);
        if (slot.inventory != this.mc.player.getInventory()
            || slot.getIndex() != invSlot
            || !slot.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return false;
        }

        this.mc.interactionManager.clickSlot(handler.syncId, screenSlotId, targetHotbarSlot, SlotActionType.SWAP, this.mc.player);
        this.mainhandCooldownTicks = -1;
        return true;
    }

    private int countTotalInventoryTotems() {
        if (this.mc.player == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (this.mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                count++;
            }
        }
        return count;
    }

    private int findTotemSlot() {
        if (this.mc.player == null) {
            return -1;
        }
        for (int i = 9; i < 36; i++) {
            if (this.mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (this.useMainhand.get() && i == this.mainhandSlot.get() - 1) {
                continue;
            }
            if (this.mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isMainhandSlotHoldingTotem() {
        if (this.mc.player == null) {
            return false;
        }
        return this.mc.player.getInventory().getStack(this.mainhandSlot.get() - 1).isOf(Items.TOTEM_OF_UNDYING);
    }

    private boolean isMainhandReadyForRefill() {
        return !isMainhandSlotHoldingTotem() && this.mainhandCooldownTicks == 0;
    }

    private void updateMainhandDelayTimer() {
        if (!this.useMainhand.get() || isMainhandSlotHoldingTotem()) {
            this.mainhandCooldownTicks = -1;
        } else if (this.mainhandCooldownTicks < 0) {
            this.mainhandCooldownTicks = this.mainhandDelay.get();
        } else if (this.mainhandCooldownTicks > 0) {
            this.mainhandCooldownTicks--;
        }
    }

    private static int findScreenSlotId(ScreenHandler handler, int invSlotIndex) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == invSlotIndex) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isActionPacket(Object packet) {
        return packet instanceof PlayerInteractItemC2SPacket
            || packet instanceof PlayerInteractBlockC2SPacket
            || packet instanceof PlayerInteractEntityC2SPacket
            || packet instanceof PlayerActionC2SPacket
            || packet instanceof ClickSlotC2SPacket
            || packet instanceof PickItemFromBlockC2SPacket
            || packet instanceof PickItemFromEntityC2SPacket
            || packet instanceof ClientCommandC2SPacket
            || packet instanceof ResourcePackStatusC2SPacket;
    }

    @Override
    public String getInfoString() {
        return this.hasReserveTotem > 0 ? "✓" : "✗";
    }
}
