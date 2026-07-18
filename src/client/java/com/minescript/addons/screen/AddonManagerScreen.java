package com.minescript.addons.screen;

import com.minescript.addons.config.ModConfig;
import com.minescript.addons.data.RepoEntry;
import com.minescript.addons.data.RepoEntry.ScriptFile;
import com.minescript.addons.download.GitHubAPI;
import com.minescript.addons.manager.ScriptManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import com.minescript.addons.screen.ImageButton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.io.File;

public class AddonManagerScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("minescript-addons");

    private int contentLeft;
    private int contentWidth;
    private int cardLeft;
    private int cardWidth;
    private static final int CONTENT_TOP = 32;
    private static final int SECTION_TOP = 35;
    private static final int BOTTOM_Y_OFFSET = 45;

    private final Screen parent;
    private final ModConfig config;
    private List<RepoEntry> allRepos;
    private int scrollOffset;
    private int totalContentHeight;

    private final Map<String, List<ScriptFile>> fileCache = new HashMap<>();
    private final Set<RepoEntry> expandedRepos = new HashSet<>();
    private final Set<String> downloadingUrls = new HashSet<>();
    private final Set<String> userRepoUrls = new HashSet<>();

    private Component statusMessage;
    private int statusTimer;

    private final List<AbstractWidget> cardButtons = new ArrayList<>();
    private ImageButton githubBtn, discordBtn, settingsBtn;

    public AddonManagerScreen(Screen parent) {
        super(Component.translatable("text.minescript-addons.title"));
        this.parent = parent;
        this.config = ModConfig.load();
        reloadRepos();
    }

    private void reloadRepos() {
        allRepos = new ArrayList<>();
        List<RepoEntry> curated = ModConfig.loadCuratedRepos();
        for (RepoEntry r : curated) {
            if (!config.isCuratedRepoHidden(r.getUrl())) {
                allRepos.add(r);
            }
        }
        List<RepoEntry> user = config.getUserRepos();
        allRepos.addAll(user);
        userRepoUrls.clear();
        for (RepoEntry r : user) {
            userRepoUrls.add(r.getUrl());
        }
        LOGGER.info("reloadRepos: {} total repos ({} curated visible, {} user)",
            allRepos.size(), allRepos.size() - user.size(), user.size());
        for (RepoEntry r : allRepos) {
            LOGGER.info("  Repo: name='{}' url='{}' author='{}'",
                r.getName(), r.getUrl(), r.getAuthor());
        }
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        computeLayout();
        int bottomY = height - BOTTOM_Y_OFFSET;
        int btnW = 110, btnR = 70, btnC = 60;
        int totalW = btnW + 5 + btnR + 5 + btnC;
        int startX = (width - totalW) / 2;
        addRenderableWidget(Button.builder(
            Component.translatable("text.minescript-addons.add_custom_repo"),
            btn -> openAddRepoPopup()
        ).bounds(startX, bottomY, btnW, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("text.minescript-addons.refresh"),
            btn -> refreshRepos()
        ).bounds(startX + btnW + 5, bottomY, btnR, 20).build());
        addRenderableWidget(Button.builder(
            CommonComponents.GUI_CANCEL, btn -> onClose()
        ).bounds(startX + btnW + 5 + btnR + 5, bottomY, btnC, 20).build());
        githubBtn = addRenderableWidget(new ImageButton(
            width - 82, 6, 22, 22,
            Identifier.fromNamespaceAndPath("minescript-addons", "textures/gui/github_logo.png"),
            () -> openLink("https://github.com/Uk-Cat/Minescript-Addons")
        ));
        discordBtn = addRenderableWidget(new ImageButton(
            width - 56, 6, 22, 22,
            Identifier.fromNamespaceAndPath("minescript-addons", "textures/gui/discord_logo.png"),
            () -> openLink("https://discord.gg/85QzqzuzBw")
        ));
        settingsBtn = addRenderableWidget(new ImageButton(
            width - 30, 6, 22, 22,
            Identifier.fromNamespaceAndPath("minescript-addons", "textures/gui/setting.png"),
            () -> openSettings()
        ));
        rebuildCardButtons();
    }

    private void computeLayout() {
        contentLeft = width / 14;
        contentWidth = width - contentLeft * 2;
        cardLeft = contentLeft + 4;
        cardWidth = contentWidth - 8;
    }

    private void rebuildCardButtons() {
        for (AbstractWidget b : cardButtons) {
            removeWidget(b);
        }
        cardButtons.clear();

        computeLayout();
        int scissorBottom = height - BOTTOM_Y_OFFSET - 3;
        int yPos = SECTION_TOP - scrollOffset;

        for (RepoEntry repo : allRepos) {
            int cardHeight = getCardHeight(repo);
            int cardEndY = yPos + cardHeight;

            if (cardEndY >= CONTENT_TOP && yPos <= scissorBottom) {
                int rightEdge = contentLeft + contentWidth + 2;

                RepoEntry captured = repo;
                int btnY = yPos + 1;
                if (btnY + 18 >= CONTENT_TOP && btnY <= scissorBottom) {
                    Button viewBtn = Button.builder(
                        Component.translatable("text.minescript-addons.view_on_github"),
                        b -> openUrl(captured.getDisplayUrl())
                    ).bounds(rightEdge - 130, btnY, 80, 18).build();
                    cardButtons.add(viewBtn);
                    addRenderableWidget(viewBtn);

                    {
                        RepoEntry capturedForAction = repo;
                        Button deleteBtn = Button.builder(
                            Component.literal("X"),
                            b -> {
                                if (userRepoUrls.contains(capturedForAction.getUrl())) {
                                    deleteRepo(capturedForAction);
                                } else {
                                    hideCuratedRepo(capturedForAction);
                                }
                            }
                        ).bounds(rightEdge - 45, btnY, 20, 18).build();
                        cardButtons.add(deleteBtn);
                        addRenderableWidget(deleteBtn);
                    }

                    Button expandBtn = Button.builder(
                        Component.literal(expandedRepos.contains(repo) ? "▲" : "▼"),
                        b -> toggleExpand(captured)
                    ).bounds(rightEdge - 20, btnY, 20, 18).build();
                    cardButtons.add(expandBtn);
                    addRenderableWidget(expandBtn);
                }

                int authY = yPos + 16;
                if (!repo.getAuthor().isEmpty() && authY + 9 >= CONTENT_TOP && authY <= scissorBottom) {
                    Component authorLabel = Component.literal("By " + repo.getAuthor());
                    int aw = font.width(authorLabel);
                    AuthorLinkWidget authorW = new AuthorLinkWidget(
                        cardLeft + 4, authY, aw, 9,
                        authorLabel, "https://github.com/" + repo.getAuthor()
                    );
                    cardButtons.add(authorW);
                    addRenderableWidget(authorW);
                }

                if (expandedRepos.contains(repo)) {
                    List<ScriptFile> files = fileCache.get(repo.getUrl());
                    if (files != null) {
                        int fy = yPos + 46 + 12;
                        for (ScriptFile file : files) {
                            if (fy + 18 > scissorBottom) break;
                            if (fy < CONTENT_TOP) { fy += 22; continue; }

                            RepoEntry repoRef = repo;
                            ScriptFile fileRef = file;
                            boolean dling = downloadingUrls.contains(file.getDownloadUrl());
                            Path scriptFolder = ScriptManager.getScriptFolder(config.getScriptFolder());
                            boolean installed = ScriptManager.isScriptInstalled(file.getName(), scriptFolder);
                            String dlLabel = dling ? "..." : Component.translatable("text.minescript-addons.download").getString();

                            if (installed) {
                                Button openBtn = Button.builder(
                                    Component.literal("Open"),
                                    b -> openFile(scriptFolder.resolve(file.getName()))
                                ).bounds(rightEdge - 130, fy, 60, 18).build();
                                cardButtons.add(openBtn);
                                addRenderableWidget(openBtn);
                            }

                            Button dlBtn = Button.builder(
                                Component.literal(dlLabel),
                                b -> downloadFile(repoRef, fileRef)
                            ).bounds(rightEdge - 65, fy, 60, 18).build();
                            dlBtn.active = !dling;
                            cardButtons.add(dlBtn);
                            addRenderableWidget(dlBtn);

                            fy += 22;
                        }
                    }
                }
            }

            yPos = cardEndY + 6;
        }

        totalContentHeight = yPos + scrollOffset - SECTION_TOP;
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        computeLayout();
        gui.fill(0, 0, width, height, 0xFF1A1A2E);

        gui.fill(contentLeft - 2, CONTENT_TOP, contentLeft + contentWidth + 2, height - BOTTOM_Y_OFFSET - 3, 0x44000000);

        int tw = font.width(title);
        gui.drawString(font, title, (width - tw) / 2, 8, 0xFFE0E0E0, false);

        int scissorLeft = contentLeft - 2;
        int scissorTop = CONTENT_TOP;
        int scissorRight = contentLeft + contentWidth + 2;
        int scissorBottom = height - BOTTOM_Y_OFFSET - 3;
        gui.enableScissor(scissorLeft, scissorTop, scissorRight, scissorBottom);

        int yPos = SECTION_TOP - scrollOffset;
        int textMaxWidth = cardWidth - 16;

        if (allRepos.isEmpty()) {
            gui.drawString(font, Component.translatable("text.minescript-addons.no_repos"),
                contentLeft + 4, SECTION_TOP + 10, 0xFF808080, false);
        }

        for (RepoEntry repo : allRepos) {
            int cardHeight = getCardHeight(repo);
            int cardEndY = yPos + cardHeight;
            boolean visible = cardEndY >= scissorTop && yPos <= scissorBottom;

            if (visible) {
                int bgColor = expandedRepos.contains(repo) ? 0x66000000 : 0x44000000;
                gui.fill(cardLeft, yPos, cardLeft + cardWidth, cardEndY, bgColor);

                gui.drawString(font, Component.literal(repo.getName()),
                    cardLeft + 4, yPos + 4, 0xFFFFFFFF, false);

                String author = repo.getAuthor().isEmpty() ? "Unknown" : repo.getAuthor();
                String authorText = "By " + author;
                gui.drawString(font, Component.literal(authorText),
                    cardLeft + 4, yPos + 16, 0xFFA0A0A0, false);

                if (!repo.getDescription().isEmpty()) {
                    String desc = repo.getDescription();
                    if (font.width(desc) > textMaxWidth) {
                        desc = font.plainSubstrByWidth(desc, textMaxWidth - 6) + "...";
                    }
                    gui.drawString(font, Component.literal(desc),
                        cardLeft + 4, yPos + 28, 0xFF808080, false);
                }

                int contextTop = yPos + 46;

                if (expandedRepos.contains(repo)) {
                    List<ScriptFile> files = fileCache.get(repo.getUrl());

                    if (repo.isLoading()) {
                        gui.drawString(font, Component.translatable("text.minescript-addons.loading"),
                            cardLeft + 4, contextTop, 0xFF808080, false);
                    } else if (repo.getLoadError() != null) {
                        gui.drawString(font, Component.literal(repo.getLoadError()),
                            cardLeft + 4, contextTop, 0xFFFF5555, false);
                    } else if (files == null || files.isEmpty()) {
                        if (!repo.isFilesLoaded()) {
                            loadFiles(repo);
                        } else {
                            gui.drawString(font, Component.translatable("text.minescript-addons.no_files"),
                                cardLeft + 4, contextTop, 0xFF808080, false);
                        }
                    } else {
                        gui.drawString(font, Component.translatable("text.minescript-addons.files"),
                            cardLeft + 4, contextTop, 0xFFA0A0A0, false);
                        int fy = contextTop + 12;

                        for (ScriptFile file : files) {
                            if (fy > scissorBottom) break;
                            if (fy < scissorTop) { fy += 22; continue; }

                            Path scriptFolder = ScriptManager.getScriptFolder(config.getScriptFolder());
                            boolean installed = ScriptManager.isScriptInstalled(file.getName(), scriptFolder);
                            String mark = installed ? " [Installed]" : "";
                            int fileColor = installed ? 0xFF55FF55 : 0xFFC0C0C0;
                            gui.drawString(font, Component.literal("  " + file.getName() + mark),
                                cardLeft + 4, fy + 2, fileColor, false);

                            fy += 22;
                        }
                    }
                }
            }

            yPos = cardEndY + 6;
        }

        gui.disableScissor();

        super.render(gui, mouseX, mouseY, delta);

        gui.fill(0, 0, width, CONTENT_TOP, 0xFF1A1A2E);

        gui.drawString(font, title, (width - font.width(title)) / 2, 8, 0xFFE0E0E0, false);

        if (githubBtn != null) githubBtn.render(gui, mouseX, mouseY, delta);
        if (discordBtn != null) discordBtn.render(gui, mouseX, mouseY, delta);
        if (settingsBtn != null) settingsBtn.render(gui, mouseX, mouseY, delta);

        if (statusMessage != null && statusTimer > 0) {
            int sw = font.width(statusMessage);
            gui.drawString(font, statusMessage, (width - sw) / 2, height - BOTTOM_Y_OFFSET - 18, 0xFFFFFFFF, true);
            statusTimer--;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= contentLeft - 2 && mouseX <= contentLeft + contentWidth + 2
            && mouseY >= CONTENT_TOP && mouseY <= height - BOTTOM_Y_OFFSET - 3) {
            int maxScroll = Math.max(0, totalContentHeight - (height - BOTTOM_Y_OFFSET - SECTION_TOP - 10));
            scrollOffset = (int) Math.max(0, Math.min(scrollOffset - scrollY * 15, maxScroll));
            rebuildCardButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int getCardHeight(RepoEntry repo) {
        int base = 46;
        if (expandedRepos.contains(repo)) {
            List<ScriptFile> files = fileCache.get(repo.getUrl());
            if (files != null && !files.isEmpty()) {
                base += 12 + files.size() * 22;
            } else {
                base += 16;
            }
        }
        return base;
    }

    private void toggleExpand(RepoEntry repo) {
        if (expandedRepos.contains(repo)) {
            expandedRepos.remove(repo);
        } else {
            expandedRepos.add(repo);
            if (!repo.isFilesLoaded() && !repo.isLoading()) {
                loadFiles(repo);
            }
        }
        rebuildCardButtons();
    }

    private void loadFiles(RepoEntry repo) {
        repo.setLoading(true);
        rebuildCardButtons();
        GitHubAPI.listScriptFiles(repo).whenComplete((files, error) -> {
            if (error != null) {
                repo.setLoadError(error.getCause() != null
                    ? error.getCause().getMessage()
                    : error.getMessage());
            } else {
                fileCache.put(repo.getUrl(), files);
                repo.setFiles(files);
            }
            rebuildCardButtons();
        });
    }

    private void downloadFile(RepoEntry repo, ScriptFile file) {
        String url = file.getDownloadUrl();
        if (downloadingUrls.contains(url)) return;
        downloadingUrls.add(url);
        rebuildCardButtons();

        GitHubAPI.downloadFile(file.getName(), file.getDownloadUrl(), ScriptManager.getScriptFolder(config.getScriptFolder()))
            .whenComplete((result, error) -> {
                downloadingUrls.remove(url);
                if (error != null) {
                    setStatus(Component.literal("§c" + error.getMessage()));
                } else if (result.success()) {
                    config.markInstalled(result.fileName());
                    setStatus(Component.translatable(
                        "text.minescript-addons.download_success", result.fileName()));
                } else {
                    setStatus(Component.translatable(
                        "text.minescript-addons.download_failed",
                        result.fileName(), result.errorMessage()));
                }
                rebuildCardButtons();
            });
    }

    private void setStatus(Component msg) {
        statusMessage = msg;
        statusTimer = 100;
    }

    private void openUrl(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            Runtime rt = Runtime.getRuntime();
            if (os.contains("win")) {
                rt.exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", url});
            } else if (os.contains("mac")) {
                rt.exec(new String[]{"open", url});
            } else {
                rt.exec(new String[]{"xdg-open", url});
            }
        } catch (Exception e) {
            LOGGER.error("Failed to open URL: {}", e.getMessage());
            setStatus(Component.literal("§c" + e.getMessage()));
        }
    }

    private void openFile(Path filePath) {
        try {
            File f = filePath.toFile();
            if (!f.exists()) return;
            String os = System.getProperty("os.name").toLowerCase();
            Runtime rt = Runtime.getRuntime();
            if (os.contains("win")) {
                rt.exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", f.getAbsolutePath()});
            } else if (os.contains("mac")) {
                rt.exec(new String[]{"open", f.getAbsolutePath()});
            } else {
                rt.exec(new String[]{"xdg-open", f.getAbsolutePath()});
            }
        } catch (Exception e) {
            LOGGER.error("Failed to open file: {}", e.getMessage());
            setStatus(Component.literal("§c" + e.getMessage()));
        }
    }

    private void openSettings() {
        Minecraft.getInstance().setScreen(new SettingsScreen(this, config, this::refreshRepos));
    }

    private void openLink(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            Runtime rt = Runtime.getRuntime();
            if (os.contains("win")) {
                rt.exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", url});
            } else if (os.contains("mac")) {
                rt.exec(new String[]{"open", url});
            } else {
                rt.exec(new String[]{"xdg-open", url});
            }
        } catch (Exception e) {
            LOGGER.error("Failed to open URL: {}", e.getMessage());
        }
    }

    private void deleteRepo(RepoEntry repo) {
        config.removeUserRepo(repo.getUrl());
        fileCache.remove(repo.getUrl());
        expandedRepos.remove(repo);
        reloadRepos();
        rebuildCardButtons();
        setStatus(Component.translatable("text.minescript-addons.repo_deleted", repo.getName()));
    }

    private void hideCuratedRepo(RepoEntry repo) {
        config.hideCuratedRepo(repo.getUrl());
        fileCache.remove(repo.getUrl());
        expandedRepos.remove(repo);
        reloadRepos();
        rebuildCardButtons();
        setStatus(Component.translatable("text.minescript-addons.curated_hidden", repo.getName()));
    }

    private void openAddRepoPopup() {
        Minecraft.getInstance().setScreen(new AddRepoPopup(this, config, this::onRepoAdded));
    }

    private void onRepoAdded() {
        reloadRepos();
        rebuildCardButtons();
    }

    private void refreshRepos() {
        setStatus(Component.translatable("text.minescript-addons.refreshing"));
        fileCache.clear();
        expandedRepos.clear();
        scrollOffset = 0;
        ModConfig.clearCuratedCache();
        reloadRepos();
        rebuildCardButtons();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private static class AuthorLinkWidget extends AbstractWidget {
        private final String url;

        public AuthorLinkWidget(int x, int y, int width, int height, Component message, String url) {
            super(x, y, width, height, message);
            this.url = url;
        }

        @Override
        protected void renderWidget(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean something) {
            if (isValidClickButton(event.buttonInfo()) && isMouseOver(event.x(), event.y())) {
                playDownSound(Minecraft.getInstance().getSoundManager());
                try {
                    String os = System.getProperty("os.name").toLowerCase();
                    Runtime rt = Runtime.getRuntime();
                    if (os.contains("win")) {
                        rt.exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", url});
                    } else if (os.contains("mac")) {
                        rt.exec(new String[]{"open", url});
                    } else {
                        rt.exec(new String[]{"xdg-open", url});
                    }
                } catch (Exception e) {
                    AddonManagerScreen.LOGGER.error("Failed to open URL: {}", e.getMessage());
                }
                return true;
            }
            return false;
        }
    }
}
