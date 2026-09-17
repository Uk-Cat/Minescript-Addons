package com.minescript.addons.client;

import com.minescript.addons.command.ModCommands;
import com.minescript.addons.config.ModConfig;
import com.minescript.addons.manager.PythonDetector;
import com.minescript.addons.manager.ScriptManager;
import com.minescript.addons.mixin.ScreenAccessor;
import com.minescript.addons.screen.AddRepoPopup;
import com.minescript.addons.screen.AddonManagerScreen;
import com.minescript.addons.screen.ImageButton;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class MinescriptAddonsClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("minescript-addons");

    @Override
    public void onInitializeClient() {
        ModCommands.register();
        MinescriptErrorHandler.register();
        runPythonAutoDetectOnce();

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

    private static void runPythonAutoDetectOnce() {
        ModConfig config;
        try {
            config = ModConfig.load();
        } catch (Exception e) {
            LOGGER.warn("Python auto-detect skipped: failed to load config: {}", e.getMessage());
            return;
        }
        if (!config.isAutoDetectPython()) {
            LOGGER.info("Python auto-detect disabled in settings, skipping");
            return;
        }
        PythonDetector.fixIfNeededAsync(result -> {
            if (result.status() == PythonDetector.Status.UPDATED && result.selected() != null) {
                LOGGER.info("Minescript python path set to: {}", result.selected());
                notifyPythonUpdated(result.selected().toString());
            } else if (result.status() == PythonDetector.Status.SKIPPED_NO_PYTHON) {
                LOGGER.warn("No Python installation found — Minescript python path left unchanged");
                notifyPythonNotFound();
            }
        });
    }

    private static void notifyPythonUpdated(String path) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            if (client.gui != null) {
                client.gui.getChat().addMessage(
                    Component.translatable("text.minescript-addons.python_updated", path));
            }
        });
    }

    private static void notifyPythonNotFound() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            if (client.gui != null) {
                client.gui.getChat().addMessage(
                    Component.translatable("text.minescript-addons.python_not_found"));
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
