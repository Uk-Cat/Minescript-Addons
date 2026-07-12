package com.minescript.addons.screen;

import com.minescript.addons.config.ModConfig;
import com.minescript.addons.data.RepoEntry;
import com.minescript.addons.download.GitHubAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class AddRepoPopup extends Screen {
    private static final Pattern GITHUB_PATTERN = Pattern.compile(
        "https?://github\\.com/[^/]+/[^/]+"
    );

    private final Screen parent;
    private final ModConfig config;
    private final Runnable onAdded;

    private EditBox urlField;
    private Button confirmButton;
    private Button cancelButton;
    private Component errorMessage;
    private boolean loading;

    protected AddRepoPopup(Screen parent, ModConfig config, Runnable onAdded) {
        super(Component.translatable("text.minescript-addons.add_repo_title"));
        this.parent = parent;
        this.config = config;
        this.onAdded = onAdded;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        int centerX = width / 2;
        int centerY = height / 2;

        urlField = new EditBox(font, centerX - 150, centerY - 15, 300, 20,
            Component.translatable("text.minescript-addons.add_repo_placeholder"));
        urlField.setMaxLength(200);
        urlField.setResponder(this::onUrlChanged);
        addRenderableWidget(urlField);

        confirmButton = addRenderableWidget(Button.builder(
            Component.translatable("text.minescript-addons.confirm"),
            btn -> confirm()
        ).bounds(centerX - 80, centerY + 30, 70, 20).build());
        confirmButton.active = false;

        cancelButton = addRenderableWidget(Button.builder(
            CommonComponents.GUI_CANCEL,
            btn -> {
                if (!loading) onClose();
            }
        ).bounds(centerX + 10, centerY + 30, 70, 20).build());
    }

    private void onUrlChanged(String text) {
        confirmButton.active = GITHUB_PATTERN.matcher(text.trim()).find();
        errorMessage = null;
    }

    private void confirm() {
        String url = urlField.getValue().trim();
        if (!GITHUB_PATTERN.matcher(url).find()) {
            errorMessage = Component.translatable("text.minescript-addons.invalid_url");
            return;
        }

        boolean duplicate = config.getUserRepos().stream()
            .anyMatch(r -> r.getUrl().equals(url));
        if (duplicate) {
            errorMessage = Component.translatable("text.minescript-addons.repo_already_added");
            return;
        }

        loading = true;
        confirmButton.active = false;
        urlField.active = false;
        cancelButton.active = false;
        errorMessage = null;

        RepoEntry repo = RepoEntry.fromUrl(url, null);
        GitHubAPI.fetchAndPopulateRepo(repo).whenComplete((r, error) -> {
            minecraft.execute(() -> {
                if (error != null) {
                    String msg = error.getMessage();
                    if (error instanceof RuntimeException && error.getCause() != null) {
                        msg = error.getCause().getMessage();
                    }
                    loading = false;
                    urlField.active = true;
                    confirmButton.active = GITHUB_PATTERN.matcher(urlField.getValue().trim()).find();
                    cancelButton.active = true;
                    errorMessage = Component.literal("§c" + (msg != null ? msg : "Unknown error"));
                    return;
                }
                config.addUserRepo(r);
                if (onAdded != null) onAdded.run();
                onClose();
            });
        });
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        gui.fill(0, 0, width, height, 0xAA1A1A2E);

        int centerX = width / 2;
        int centerY = height / 2;

        gui.fill(centerX - 170, centerY - 50, centerX + 170, centerY + 65, 0xCC1A1A2E);

        Component headerText = loading
            ? Component.translatable("text.minescript-addons.loading")
            : title;
        gui.drawString(font, headerText, centerX - font.width(headerText) / 2, centerY - 40, 0xFFFFFFFF, false);

        if (loading) {
            gui.drawString(font, Component.translatable("text.minescript-addons.fetching_info"),
                centerX - 150, centerY + 10, 0xFF808080, false);
        }

        if (errorMessage != null) {
            int maxErrorWidth = 300;
            renderWrappedText(gui, errorMessage, centerX - maxErrorWidth / 2, centerY + 50, maxErrorWidth, 0xFFFF5555);
        }

        super.render(gui, mouseX, mouseY, delta);
    }

    private void renderWrappedText(GuiGraphics gui, Component text, int x, int y, int maxWidth, int color) {
        String raw = text.getString();
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : raw.split(" ")) {
            String test = current.isEmpty() ? word : current + " " + word;
            if (font.width(test) > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(test);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());

        int lineY = y;
        for (String line : lines) {
            gui.drawString(font, Component.literal(line), x + (maxWidth - font.width(line)) / 2, lineY, color, false);
            lineY += 10;
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
