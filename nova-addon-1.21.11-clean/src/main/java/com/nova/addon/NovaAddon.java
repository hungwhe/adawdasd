package com.nova.addon;

import com.mojang.logging.LogUtils;
import com.nova.addon.modules.misc.DiagonalInput;
import com.nova.addon.modules.pvp.AutoTotemN;
import com.nova.addon.modules.pvp.CrystalAuraN;
import com.nova.addon.modules.pvp.KillAuraN;
import com.nova.addon.modules.pvp.SelfTrapN;
import com.nova.addon.modules.pvp.SurroundN;
import com.nova.addon.utils.rotation.NovaRotations;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class NovaAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOG.info("Initializing Nova Addon");
        NovaRotations.init();

        Modules modules = Modules.get();
        modules.add(new AutoTotemN());
        modules.add(new CrystalAuraN());
        modules.add(new KillAuraN());
        modules.add(new SelfTrapN());
        modules.add(new SurroundN());
        modules.add(new DiagonalInput());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(NovaCategories.NOVA_PVP);
        Modules.registerCategory(NovaCategories.NOVA_ESP);
        Modules.registerCategory(NovaCategories.NOVA_FARM);
        Modules.registerCategory(NovaCategories.NOVA_MISC);
    }

    @Override
    public String getPackage() {
        return "com.nova.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
    }
}
