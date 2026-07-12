package com.minescript.addons;

import com.minescript.addons.config.ModConfig;
import com.minescript.addons.screen.AddonManagerScreen;
import com.minescript.addons.screen.DisclaimerScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class MinescriptAddonsMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ModConfig config = MinescriptAddonsMod.getConfig();
            if (!config.isDisclaimerAccepted()) {
                return new DisclaimerScreen(new AddonManagerScreen(parent), config);
            }
            return new AddonManagerScreen(parent);
        };
    }
}
