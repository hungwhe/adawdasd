package com.nova.addon;

import meteordevelopment.meteorclient.systems.modules.Category;
import net.minecraft.item.Items;

public final class NovaCategories {
    public static final Category NOVA_PVP = new Category("Nova PvP", Items.NETHERITE_SWORD.getDefaultStack());
    public static final Category NOVA_ESP = new Category("Nova ESP", Items.ENDER_EYE.getDefaultStack());
    public static final Category NOVA_FARM = new Category("Nova Farm", Items.WHEAT.getDefaultStack());
    public static final Category NOVA_MISC = new Category("Nova Misc", Items.COMPASS.getDefaultStack());

    private NovaCategories() {
    }
}
