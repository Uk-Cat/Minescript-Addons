package com.minescript.addons.manager;

import com.minescript.addons.config.ModConfig;
import com.minescript.addons.data.RepoEntry;
import com.minescript.addons.data.RepoEntry.ScriptFile;
import com.minescript.addons.download.GitHubAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Compares the local install hashmap against the remote GitHub sha
 * and overwrites outdated files in place.
 */
public class UpdateManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("minescript-addons");

    public record UpdateInfo(ScriptFile remoteFile, String storedSha, String localHash,
                             boolean installed, boolean updateAvailable, boolean locallyModified) {}

    /** Fast check: stored git sha vs remote git sha, plus local edit detection via SHA-256. */
    public static List<UpdateInfo> checkForUpdates(List<ScriptFile> remoteFiles, ModConfig config, Path scriptFolder) {
        List<UpdateInfo> result = new ArrayList<>();
        for (ScriptFile remote : remoteFiles) {
            String name = remote.getName();
            ModConfig.InstalledScript stored = config.getInstalledScripts().get(name);
            boolean installed = stored != null || Files.exists(scriptFolder.resolve(name));
            String storedSha = stored != null ? stored.getSha() : "";
            String localHash = "";
            boolean locallyModified = false;
            if (installed) {
                localHash = FileHasher.sha256(scriptFolder.resolve(name));
                String baseline = stored != null ? stored.getLocalHash() : "";
                locallyModified = !baseline.isEmpty() && !localHash.isEmpty() && !baseline.equals(localHash)
                    && (storedSha.isEmpty() || storedSha.equals(remote.getSha()));
            }
            boolean updateAvailable;
            if (!installed) {
                updateAvailable = false;
            } else if (storedSha.isEmpty()) {
                // No baseline (installed before tracking): needs re-sync to establish one.
                updateAvailable = true;
            } else {
                updateAvailable = !storedSha.equals(remote.getSha());
            }
            result.add(new UpdateInfo(remote, storedSha, localHash, installed, updateAvailable, locallyModified));
        }
        return result;
    }

    /** Re-list remote files for a repo, then diff against the hashmap. */
    public static CompletableFuture<List<UpdateInfo>> checkRepoForUpdates(RepoEntry repo, ModConfig config, Path scriptFolder) {
        return GitHubAPI.listScriptFiles(repo)
            .thenApply(remoteFiles -> {
                repo.setFiles(remoteFiles);
                return checkForUpdates(remoteFiles, config, scriptFolder);
            });
    }

    /** Overwrite one file in place and refresh its hashmap entry. */
    public static CompletableFuture<GitHubAPI.DownloadResult> updateFile(RepoEntry repo, ScriptFile file,
                                                                          ModConfig config, Path scriptFolder) {
        return GitHubAPI.downloadFile(file.getName(), file.getDownloadUrl(), scriptFolder, true)
            .thenApply(result -> {
                if (result.success()) {
                    String localHash = FileHasher.sha256(scriptFolder.resolve(result.fileName()));
                    config.recordInstall(result.fileName(), file.getSha(), localHash,
                        repo.getUrl(), file.getDownloadUrl());
                    LOGGER.info("Updated {} (sha={})", result.fileName(), file.getSha());
                }
                return result;
            });
    }

    /** Fresh install that also seeds the hashmap entry. */
    public static CompletableFuture<GitHubAPI.DownloadResult> installFile(RepoEntry repo, ScriptFile file,
                                                                           ModConfig config, Path scriptFolder) {
        return GitHubAPI.downloadFile(file.getName(), file.getDownloadUrl(), scriptFolder, false)
            .thenApply(result -> {
                if (result.success()) {
                    String localHash = FileHasher.sha256(scriptFolder.resolve(result.fileName()));
                    config.recordInstall(result.fileName(), file.getSha(), localHash,
                        repo.getUrl(), file.getDownloadUrl());
                }
                return result;
            });
    }
}
