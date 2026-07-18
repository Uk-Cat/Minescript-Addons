package com.minescript.addons.command;

import com.minescript.addons.client.MinescriptErrorHandler;
import com.minescript.addons.MinescriptAddonsMod;
import com.minescript.addons.config.ModConfig;
import com.minescript.addons.data.RepoEntry;
import com.minescript.addons.download.GitHubAPI;
import com.minescript.addons.manager.ScriptManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ModCommands {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("minescriptcopy")
                .executes(context -> {
                    String error = MinescriptErrorHandler.lastError;
                    if (error != null) {
                        Minecraft.getInstance().keyboardHandler.setClipboard(error);
                        context.getSource().sendFeedback(
                            Component.literal("Copied to clipboard").withStyle(ChatFormatting.GREEN)
                        );
                    } else {
                        context.getSource().sendFeedback(
                            Component.literal("No error to copy").withStyle(ChatFormatting.RED)
                        );
                    }
                    return 1;
                })
            );

            dispatcher.register(ClientCommandManager.literal("install")
                .then(ClientCommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .executes(context -> {
                        String name = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "name");
                        executeInstall(context.getSource(), name);
                        return 1;
                    })
                )
            );

            dispatcher.register(ClientCommandManager.literal("setpath")
                .then(ClientCommandManager.argument("path", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .executes(context -> {
                        String path = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "path");
                        executeSetPath(context.getSource(), path);
                        return 1;
                    })
                )
            );
        });
    }

    private static void executeSetPath(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String path) {
        ModConfig config = MinescriptAddonsMod.getConfig();
        config.setScriptFolder(path);
        source.sendFeedback(Component.literal("Script folder set to: " + path));
    }

    private static void executeInstall(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String input) {
        ModConfig config = MinescriptAddonsMod.getConfig();

        List<RepoEntry> allRepos = new ArrayList<>();
        allRepos.addAll(ModConfig.loadCuratedRepos());
        allRepos.addAll(config.getUserRepos());

        RepoEntry found = null;
        for (RepoEntry repo : allRepos) {
            if (repo.getName().equalsIgnoreCase(input) || repo.getUrl().equalsIgnoreCase(input)) {
                found = repo;
                break;
            }
        }
        if (found == null) {
            for (RepoEntry repo : allRepos) {
                if (repo.getName().toLowerCase().contains(input.toLowerCase())
                    || repo.getUrl().toLowerCase().contains(input.toLowerCase())) {
                    found = repo;
                    break;
                }
            }
        }

        if (found != null) {
            installRepo(source, config, found);
            return;
        }

        if (!input.contains("github.com")) {
            source.sendError(Component.literal("Repo not found: " + input));
            return;
        }

        if (input.contains("/blob/")) {
            downloadBlob(source, config, input);
        } else {
            RepoEntry temp = RepoEntry.fromUrl(input, null);
            if (temp.getOwner() == null) {
                source.sendError(Component.literal("Invalid GitHub URL: " + input));
                return;
            }
            installRepo(source, config, temp);
        }
    }

    private static void downloadBlob(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, ModConfig config, String url) {
        String rawUrl = url.replace("github.com", "raw.githubusercontent.com")
            .replace("/blob/", "/");
        String fileName = url.substring(url.lastIndexOf('/') + 1);

        source.sendFeedback(Component.literal("Downloading: " + fileName));
        Path folder = ScriptManager.getScriptFolder(config.getScriptFolder());

        GitHubAPI.downloadFile(fileName, rawUrl, folder).thenAccept(result -> {
            if (result.success()) {
                config.markInstalled(result.fileName());
                source.sendFeedback(Component.literal("Downloaded: " + result.fileName()));
            } else {
                source.sendError(Component.literal("Failed: " + result.errorMessage()));
            }
        });
    }

    private static void installRepo(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, ModConfig config, RepoEntry target) {
        source.sendFeedback(Component.literal("Installing: " + target.getName()));

        GitHubAPI.listScriptFiles(target).thenAccept(files -> {
            if (files.isEmpty()) {
                source.sendError(Component.literal("No script files found in " + target.getName()));
                return;
            }
            source.sendFeedback(Component.literal("Found " + files.size() + " files"));
            Path folder = ScriptManager.getScriptFolder(config.getScriptFolder());

            List<CompletableFuture<GitHubAPI.DownloadResult>> futures = new ArrayList<>();
            for (RepoEntry.ScriptFile file : files) {
                futures.add(GitHubAPI.downloadFile(file.getName(), file.getDownloadUrl(), folder));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> {
                int success = 0;
                int fail = 0;
                StringBuilder details = new StringBuilder();
                for (CompletableFuture<GitHubAPI.DownloadResult> f : futures) {
                    GitHubAPI.DownloadResult result = f.join();
                    if (result.success()) {
                        success++;
                        config.markInstalled(result.fileName());
                    } else {
                        fail++;
                        details.append("\n  ").append(result.fileName()).append(": ").append(result.errorMessage());
                    }
                }
                String msg = "Installed " + success + "/" + (success + fail) + " files";
                if (fail > 0) {
                    source.sendError(Component.literal(msg + details.toString()));
                } else {
                    source.sendFeedback(Component.literal(msg));
                }
            });
        }).exceptionally(e -> {
            source.sendError(Component.literal("Failed: " + e.getMessage()));
            return null;
        });
    }
}
