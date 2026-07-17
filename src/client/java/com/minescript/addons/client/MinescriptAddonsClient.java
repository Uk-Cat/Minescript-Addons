package com.minescript.addons.client;

import com.minescript.addons.command.ModCommands;
import com.minescript.addons.config.ModConfig;
import com.minescript.addons.manager.ScriptManager;
import com.minescript.addons.mixin.ScreenAccessor;
import com.minescript.addons.screen.AddRepoPopup;
import com.minescript.addons.screen.AddonManagerScreen;
import com.minescript.addons.screen.ImageButton;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class MinescriptAddonsClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("minescript-addons");

    @Override
    public void onInitializeClient() {
        ModCommands.register();

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof PauseScreen)) return;

            ModConfig config = ModConfig.load();
            int leftX = screen.width / 2 + 104;
            int baseY = screen.height / 4 + 24 - 16;

            if (config.isShowAddonsButton()) {
                ((ScreenAccessor) screen).invokeAddRenderableWidget(new ImageButton(
                    leftX, baseY + 24, 22, 22,
                    Identifier.fromNamespaceAndPath("minescript-addons", "textures/gui/addons.png"),
                    () -> client.setScreen(new AddonManagerScreen((PauseScreen) screen))
                ));
            }

            if (config.isShowFolderButton()) {
                ((ScreenAccessor) screen).invokeAddRenderableWidget(new ImageButton(
                    leftX, baseY + 48, 22, 22,
                    Identifier.fromNamespaceAndPath("minescript-addons", "textures/gui/folder.png"),
                    () -> openScriptFolder(config)
                ));
            }

            if (config.isShowAddRepoButton()) {
                ((ScreenAccessor) screen).invokeAddRenderableWidget(new ImageButton(
                    leftX, baseY + 72, 22, 22,
                    Identifier.fromNamespaceAndPath("minescript-addons", "textures/gui/plus.png"),
                    () -> client.setScreen(new AddRepoPopup((PauseScreen) screen, ModConfig.load(), () -> {}))
                ));
            }
        });
    }

    private static void openScriptFolder(ModConfig config) {
        Path folder = ScriptManager.getScriptFolder(config.getScriptFolder());
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"explorer.exe", folder.toAbsolutePath().toString()});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", folder.toAbsolutePath().toString()});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", folder.toAbsolutePath().toString()});
            }
        } catch (Exception e) {
            LOGGER.error("Failed to open folder: {}", e.getMessage());
        }
    }
}
