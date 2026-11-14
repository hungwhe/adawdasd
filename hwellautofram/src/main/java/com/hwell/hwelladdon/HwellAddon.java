package com.hwell.hwelladdon;

import com.hwell.hwelladdon.hud.ArmorHud;
import com.hwell.hwelladdon.hud.InventoryHud;
import com.hwell.hwelladdon.modules.*;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class HwellAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    // 🌟 MỤC DUY NHẤT: HWELL FRAM VIP+
    public static final Category HWELL_FRAM_VIP = new Category("Hwell Fram VIP+",
        new ItemStack(Items.ENCHANTED_GOLDEN_APPLE)); // Bạn có thể đổi icon thành Items.NETHERITE_INGOT, Items.DIAMOND...

    public static final HudGroup HUD_GROUP = new HudGroup("HWELL");

    @Override
    public void onInitialize() {
        LOG.info("Initializing HwellAddon (VIP+ Version)");

        // =========================================================
        // ĐÃ XÓA TOÀN BỘ MODULE CŨ ĐỂ LÀM LẠI TỪ ĐẦU
        // (Các file code vẫn còn trong thư mục modules, chỉ không hiển thị lên GUI)
        // =========================================================

        // CÁC MODULE MỚI CỦA BẠN SẼ ĐƯỢC THÊM VÀO ĐÂY
        // Ví dụ:
        // Modules.get().add(new TenModuleMoiCuaBan());
	Modules.get().add(new HwellSpruceFarm());  
        // HUD (Giữ nguyên nếu bạn vẫn muốn dùng HUD)
        Hud.get().register(InventoryHud.INFO);
        Hud.get().register(ArmorHud.INFO);
        LOG.info("Registered HUD elements");

        MeteorClient.EVENT_BUS.subscribe(new AutoEnableListener());
        LOG.info("AutoEnableListener registered!");
    }

    @Override
    public void onRegisterCategories() {
        // Chỉ đăng ký duy nhất mục VIP+
        Modules.registerCategory(HWELL_FRAM_VIP); 
    }

    @Override
    public String getPackage() {
        return "com.hwell.hwelladdon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("hungwhe", "hwelladdon-1.21.11");
    }
}