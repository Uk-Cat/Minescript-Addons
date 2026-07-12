package com.minescript.addons.manager;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ScriptManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("minescript-addons");

    public static Path getScriptFolder(String customPath) {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path folder = (customPath != null && !customPath.isEmpty())
            ? Path.of(customPath)
            : gameDir.resolve("minescript");
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            LOGGER.error("Failed to create folder: {}", e.getMessage());
        }
        return folder;
    }

    public static Path getScriptFolder() {
        return getScriptFolder("");
    }

    public static Path getMinescriptFolder() {
        return getScriptFolder();
    }

    public static boolean isScriptInstalled(String fileName, Path folder) {
        return Files.exists(folder.resolve(fileName));
    }

    public static boolean isScriptInstalled(String fileName) {
        return isScriptInstalled(fileName, getMinescriptFolder());
    }
}
