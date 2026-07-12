package com.minescript.addons.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class ImageButton extends AbstractWidget {
    private final Identifier texture;
    private final Runnable onClick;
    private final int textureWidth;
    private final int textureHeight;

    public ImageButton(int x, int y, int width, int height, Identifier texture, Runnable onClick) {
        this(x, y, width, height, texture, 128, 128, onClick);
    }

    public ImageButton(int x, int y, int width, int height, Identifier texture, int textureWidth, int textureHeight, Runnable onClick) {
        super(x, y, width, height, Component.empty());
        this.texture = texture;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.onClick = onClick;
    }

    @Override
    protected void renderWidget(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        int size = Math.min(width, height) - 4;
        int ox = getX() + (width - size) / 2;
        int oy = getY() + (height - size) / 2;
        gui.fill(getX(), getY(), getX() + width, getY() + height, 0xFF333333);
        gui.blit(RenderPipelines.GUI_TEXTURED, texture, ox, oy, 0.0f, 0.0f, size, size, textureWidth, textureHeight, textureWidth, textureHeight);
        if (isHovered) {
            gui.fill(ox, oy, ox + size, oy + size, 0x55FFFFFF);
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean something) {
        if (isValidClickButton(event.buttonInfo())) {
            onClick.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
