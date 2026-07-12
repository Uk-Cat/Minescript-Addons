package com.minescript.addons;

import com.minescript.addons.config.ModConfig;
import com.minescript.addons.manager.ScriptManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MinescriptAddonsMod implements ModInitializer {
    public static final String MOD_ID = "minescript-addons";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ModConfig config;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Minescript Addons");

        ScriptManager.getMinescriptFolder();

        config = ModConfig.load();

        LOGGER.info("Minescript Addons initialized");
    }

    public static ModConfig getConfig() {
        if (config == null) {
            config = ModConfig.load();
        }
        return config;
    }
}
