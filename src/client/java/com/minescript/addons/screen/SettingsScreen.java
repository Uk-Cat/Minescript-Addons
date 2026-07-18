package com.minescript.addons.screen;

import com.minescript.addons.config.ModConfig;
import com.minescript.addons.data.RepoEntry;
import com.minescript.addons.manager.ScriptManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

public class SettingsScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("minescript-addons");
    private final Screen parent;
    private final ModConfig config;
    private final Runnable onRefresh;
    private String customPath;

    protected SettingsScreen(Screen parent, ModConfig config, Runnable onRefresh) {
        super(Component.translatable("text.minescript-addons.settings_title"));
        this.parent = parent;
        this.config = config;
        this.onRefresh = onRefresh;
        this.customPath = config.getScriptFolder();
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        int centerX = width / 2;
        int baseY = height / 2 - 40;

        String displayPath = customPath.isEmpty() ? "minescript" : customPath;
        addRenderableWidget(Button.builder(
            Component.literal(displayPath),
            btn -> pickFolder()
        ).bounds(centerX - 150, baseY - 10, 220, 20).build());

        addRenderableWidget(Button.builder(
            Component.literal("Open"),
            btn -> openFolder(ScriptManager.getScriptFolder(customPath))
        ).bounds(centerX + 80, baseY - 10, 70, 20).build());

        addRenderableWidget(Button.builder(
            Component.translatable("text.minescript-addons.back"),
            btn -> onClose()
        ).bounds(width - 70, 8, 60, 20).build());

        int toggleY = baseY + 40;
        addRenderableWidget(Button.builder(
            Component.translatable("text.minescript-addons.show_addons_button",
                config.isShowAddonsButton()
                    ? Component.translatable("text.minescript-addons.toggle_on")
                    : Component.translatable("text.minescript-addons.toggle_off")),
            btn -> {
                config.setShowAddonsButton(!config.isShowAddonsButton());
                init();
            }
        ).bounds(centerX - 150, toggleY, 300, 20).build());

        addRenderableWidget(Button.builder(
            Component.translatable("text.minescript-addons.show_folder_button",
                config.isShowFolderButton()
                    ? Component.translatable("text.minescript-addons.toggle_on")
                    : Component.translatable("text.minescript-addons.toggle_off")),
            btn -> {
                config.setShowFolderButton(!config.isShowFolderButton());
                init();
            }
        ).bounds(centerX - 150, toggleY + 24, 300, 20).build());

        addRenderableWidget(Button.builder(
            Component.translatable("text.minescript-addons.show_add_repo_button",
                config.isShowAddRepoButton()
                    ? Component.translatable("text.minescript-addons.toggle_on")
                    : Component.translatable("text.minescript-addons.toggle_off")),
            btn -> {
                config.setShowAddRepoButton(!config.isShowAddRepoButton());
                init();
            }
        ).bounds(centerX - 150, toggleY + 48, 300, 20).build());

        addRenderableWidget(Button.builder(
            Component.translatable("text.minescript-addons.auto_copy_to_clipboard",
                config.isAutoCopyToClipboard()
                    ? Component.translatable("text.minescript-addons.toggle_on")
                    : Component.translatable("text.minescript-addons.toggle_off")),
            btn -> {
                config.setAutoCopyToClipboard(!config.isAutoCopyToClipboard());
                init();
            }
        ).bounds(centerX - 150, toggleY + 72, 300, 20).build());

        int restoreY = toggleY + 104;
        List<RepoEntry> hiddenRepos = new java.util.ArrayList<>();
        List<RepoEntry> allCurated = ModConfig.loadCuratedRepos();
        for (RepoEntry r : allCurated) {
            if (config.isCuratedRepoHidden(r.getUrl())) {
                hiddenRepos.add(r);
            }
        }

        if (!hiddenRepos.isEmpty()) {
            restoreLabelY = restoreY;
            restoreY += 18;

            addRenderableWidget(Button.builder(
                Component.translatable("text.minescript-addons.restore"),
                btn -> {
                    for (RepoEntry r : hiddenRepos) {
                        config.restoreCuratedRepo(r.getUrl());
                    }
                    init();
                }
            ).bounds(centerX - 150, restoreY, 300, 20).build());
        }
    }

    private int restoreLabelY = -1;

    private void pickFolder() {
        String initialPath = customPath.isEmpty()
            ? ScriptManager.getScriptFolder("").toAbsolutePath().toString()
            : customPath;
        String result = TinyFileDialogs.tinyfd_selectFolderDialog("Select script install folder", initialPath);
        if (result != null) {
            customPath = result;
            config.setScriptFolder(customPath);
            init();
        }
    }

    private static void openFolder(Path folder) {
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

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        gui.fill(0, 0, width, height, 0xFF1A1A2E);

        gui.drawString(font, title, (width - font.width(title)) / 2, 12, 0xFFE0E0E0, false);

        int centerX = width / 2;
        int baseY = height / 2 - 40;

        gui.drawString(font, Component.translatable("text.minescript-addons.choose_location"),
            centerX - 150, baseY - 28, 0xFFA0A0A0, false);

        if (restoreLabelY >= 0) {
            gui.drawString(font, Component.translatable("text.minescript-addons.hidden_curated_repos"),
                centerX - 150, restoreLabelY, 0xFFA0A0A0, false);
        }

        super.render(gui, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        onRefresh.run();
        minecraft.setScreen(parent);
    }
}
