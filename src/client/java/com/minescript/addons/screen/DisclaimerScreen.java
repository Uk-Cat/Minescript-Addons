package com.minescript.addons.screen;

import com.minescript.addons.config.ModConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class DisclaimerScreen extends Screen {
    private final Screen parent;
    private final ModConfig config;
    private int scrollOffset;

    private static final String[] LINES = {
        "Minescript Addons - Disclaimer & Terms of Service",
        "",
        "By using this mod to download script files, you acknowledge",
        "and agree to the following terms:",
        "",
        "1. Scope of Operation & Extension Filtering",
        "   This mod acts purely as a file downloader. It strictly",
        "   enforces download restrictions to .py (Python) and .pyj",
        "   (Pyjinn/Minescript) extensions to prevent malicious",
        "   executables from being downloaded.",
        "",
        "   No Verification or Execution: This mod does not read,",
        "   scan, or execute the downloaded code. It simply places",
        "   files into your local Minescript directory.",
        "",
        "2. Curated Lists and Third-Party Links",
        "   Community & Curated Scripts: For your convenience, this",
        "   mod features a curated list of popular or useful scripts.",
        "   These scripts are hosted on external GitHub repositories",
        "   managed by third-party creators.",
        "",
        "   No Ongoing Vetting: While these repositories are checked",
        "   for safety at the time of their inclusion in the mod,",
        "   the mod author does not control them, cannot monitor",
        "   ongoing code changes, and cannot guarantee that a",
        "   repository will not be modified or compromised.",
        "",
        "   Custom Repositories: Users downloading from custom,",
        "   non-curated GitHub links do so under their own full",
        "   responsibility.",
        "",
        "3. User Responsibility & Limitation of Liability",
        "   Review Before Running: Because Minescript runs Python",
        "   and Pyjinn scripts on your local machine, you are highly",
        "   encouraged to inspect any script's code before executing",
        "   it in-game.",
        "",
        "   Provided As-Is: This mod is provided 'as-is.' In no",
        "   event shall the mod author(s) be held liable for any",
        "   data loss, system instability, security breaches, or",
        "   harm resulting from scripts downloaded via the curated",
        "   list or custom URLs.",
        "",
    };

    public DisclaimerScreen(Screen parent, ModConfig config) {
        super(Component.literal("Disclaimer & Terms of Service"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        super.init();

        addRenderableWidget(Button.builder(
            Component.literal("I Understand & Accept"),
            btn -> {
                config.setDisclaimerAccepted(true);
                minecraft.setScreen(parent);
            }
        ).bounds(width / 2 - 100, height - 30, 120, 20).build());

        addRenderableWidget(Button.builder(
            Component.literal("Decline"),
            btn -> minecraft.setScreen(parent)
        ).bounds(width / 2 + 30, height - 30, 60, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        gui.fill(0, 0, width, height, 0xFF1A1A2E);

        int y = 12 - scrollOffset;
        int lineHeight = 10;
        int contentHeight = LINES.length * lineHeight + 20;

        gui.fill(0, height - 50, width, height, 0xCC000000);
        gui.fill(0, 0, width, 1, 0xFF555555);

        for (String line : LINES) {
            int ly = y;
            y += lineHeight;
            if (ly + lineHeight < 0 || ly > height - 50) continue;

            int color = line.startsWith("1.") || line.startsWith("2.") || line.startsWith("3.")
                ? 0xFFFFCC44 : line.isEmpty() ? 0x00000000 : 0xFFC0C0C0;
            if (color != 0x00000000) {
                int textColor = line.startsWith("Minescript Addons") ? 0xFFFFFFFF : color;
                gui.drawString(font, line, (width - font.width(line)) / 2, ly, textColor, false);
            }
        }

        if (contentHeight > height - 50) {
            int maxScroll = contentHeight - (height - 50);
            int scrollBarHeight = Math.max(20, (height - 50) * (height - 50) / contentHeight);
            int scrollBarY = (scrollOffset * (height - 50 - scrollBarHeight) / maxScroll);
            gui.fill(width - 6, scrollBarY, width - 2, scrollBarY + scrollBarHeight, 0xFFAAAAAA);
        }

        super.render(gui, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int contentHeight = LINES.length * 10 + 20;
        int maxScroll = Math.max(0, contentHeight - (height - 50));
        scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - deltaY * 12));
        return true;
    }
}
