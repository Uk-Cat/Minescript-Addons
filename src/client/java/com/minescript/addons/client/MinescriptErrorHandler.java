package com.minescript.addons.client;

import com.minescript.addons.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class MinescriptErrorHandler {
    public static String lastError = null;
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private static final StringBuilder errorBuffer = new StringBuilder();
    private static ScheduledFuture<?> pendingSend;
    private static boolean handlingMessage = false;

    public static void register() {
    }

    public static void onChatMessage(Component message) {
        if (handlingMessage) return;
        handlingMessage = true;
        try {
            String text = message.getString();

            if (!isMinescriptRelated(text)) {
                flushError();
                return;
            }

            if (isErrorText(text)) {
                if (errorBuffer.isEmpty()) {
                    errorBuffer.append(text);
                } else {
                    errorBuffer.append("\n").append(text);
                }

                if (pendingSend != null) {
                    pendingSend.cancel(false);
                }
                pendingSend = SCHEDULER.schedule(MinescriptErrorHandler::flushError, 600, TimeUnit.MILLISECONDS);
            } else {
                flushError();
            }
        } finally {
            handlingMessage = false;
        }
    }

    private static boolean isMinescriptRelated(String text) {
        return text.contains("Minescript")
            || text.contains("minescript")
            || text.contains("Traceback")
            || text.contains("File \"")
            || text.contains(".py\", line")
            || text.contains(".pyj\", line")
            || text.contains("Error:")
            || text.contains("Exception:")
            || text.contains("Exception in job")
            || text.contains("Exited with error code")
            || text.contains("SyntaxError")
            || text.contains("NameError")
            || text.contains("TypeError")
            || text.contains("ValueError")
            || text.contains("KeyError")
            || text.contains("IndexError")
            || text.contains("AttributeError")
            || text.contains("ImportError")
            || text.contains("ModuleNotFoundError")
            || text.contains("RuntimeError")
            || text.contains("ZeroDivisionError");
    }

    private static boolean isErrorText(String text) {
        if (text.contains("Traceback (most recent call last)")) return true;
        if (text.contains("File \"")) return true;
        if (text.contains(".py\", line")) return true;
        if (text.contains(".pyj\", line")) return true;
        if (text.contains("Error:") && !text.contains("Minescript built-in")) return true;
        if (text.contains("Exception:")) return true;
        if (text.contains("Exception in job")) return true;
        if (text.contains("Exited with error code")) return true;
        if (text.matches("^\\w+Error:.*")) return true;
        if (text.matches("^\\w+Warning:.*")) return true;
        if (text.startsWith("  ") && !errorBuffer.isEmpty()) return true;
        if (text.contains("No Minescript command named")) return true;
        return false;
    }

    private static void flushError() {
        if (pendingSend != null) {
            pendingSend.cancel(false);
            pendingSend = null;
        }

        if (errorBuffer.isEmpty()) return;

        String fullError = errorBuffer.toString();
        errorBuffer.setLength(0);

        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            boolean autoCopy = ModConfig.load().isAutoCopyToClipboard();
            client.execute(() -> {
                lastError = fullError;
                if (autoCopy) {
                    client.keyboardHandler.setClipboard(fullError);
                }
                client.gui.getChat().addMessage(
                    Component.literal("")
                        .append(Component.literal("Click ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal("here")
                            .withStyle(Style.EMPTY
                                .withColor(TextColor.fromRgb(0x5555FF))
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand("minescriptcopy"))
                            )
                        )
                        .append(Component.literal(" to copy error to clipboard").withStyle(ChatFormatting.GOLD))
                );
            });
        }
    }

    public static void shutdown() {
        SCHEDULER.shutdown();
    }
}
