package com.minescript.addons.config;

import com.google.gson.*;
import com.minescript.addons.MinescriptAddonsMod;
import com.minescript.addons.data.RepoEntry;
import com.minescript.addons.download.GitHubAPI;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("minescript-addons");
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("minescript-addons.json");
    private static final Path REPOS_PATH = FabricLoader.getInstance().getGameDir().resolve("minescript_addons_repos.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private List<RepoEntry> userRepos = new ArrayList<>();
    private List<String> installedFiles = new ArrayList<>();
    /**
     * Hashmap of downloaded scripts: file name -> install metadata.
     * Used to detect updates by comparing the stored remote sha against
     * the current sha reported by GitHub.
     */
    private java.util.Map<String, InstalledScript> installedScripts = new java.util.LinkedHashMap<>();
    private String scriptFolder = "";
    private boolean disclaimerAccepted = false;
    private boolean showAddonsButton = true;
    private boolean showFolderButton = true;
    private boolean showAddRepoButton = true;
    private boolean autoCopyToClipboard = false;
    private boolean autoDetectPython = true;

    private List<String> hiddenCuratedRepos = new ArrayList<>();
    private static List<RepoEntry> curatedReposCache = null;

    public List<RepoEntry> getUserRepos() { return userRepos; }
    public List<String> getInstalledFiles() { return installedFiles; }
    public java.util.Map<String, InstalledScript> getInstalledScripts() { return installedScripts; }
    public String getScriptFolder() { return scriptFolder; }
    public boolean isDisclaimerAccepted() { return disclaimerAccepted; }
    public void setDisclaimerAccepted(boolean value) {
        disclaimerAccepted = value;
        save();
    }
    public boolean isShowAddonsButton() { return showAddonsButton; }
    public boolean isShowFolderButton() { return showFolderButton; }
    public boolean isShowAddRepoButton() { return showAddRepoButton; }
    public void setShowAddonsButton(boolean value) {
        showAddonsButton = value;
        save();
    }
    public void setShowFolderButton(boolean value) {
        showFolderButton = value;
        save();
    }
    public void setShowAddRepoButton(boolean value) {
        showAddRepoButton = value;
        save();
    }
    public boolean isAutoCopyToClipboard() { return autoCopyToClipboard; }
    public void setAutoCopyToClipboard(boolean value) {
        autoCopyToClipboard = value;
        save();
    }
    public boolean isAutoDetectPython() { return autoDetectPython; }
    public void setAutoDetectPython(boolean value) {
        autoDetectPython = value;
        save();
    }
    public List<String> getHiddenCuratedRepos() { return hiddenCuratedRepos; }
    public boolean isCuratedRepoHidden(String url) { return hiddenCuratedRepos.contains(url); }
    public void hideCuratedRepo(String url) {
        if (!hiddenCuratedRepos.contains(url)) {
            hiddenCuratedRepos.add(url);
            save();
        }
    }
    public void restoreCuratedRepo(String url) {
        hiddenCuratedRepos.remove(url);
        save();
    }
    public static void clearCuratedCache() { curatedReposCache = null; }
    public void setScriptFolder(String value) {
        scriptFolder = value != null ? value : "";
        save();
    }

    public void addUserRepo(RepoEntry repo) {
        userRepos.removeIf(r -> r.getUrl().equals(repo.getUrl()));
        userRepos.add(repo);
        save();
    }

    public void removeUserRepo(String url) {
        userRepos.removeIf(r -> r.getUrl().equals(url));
        save();
    }

    public void markInstalled(String fileName) {
        if (!installedFiles.contains(fileName)) {
            installedFiles.add(fileName);
            save();
        }
        installedScripts.putIfAbsent(fileName, new InstalledScript("", "", "", ""));
        save();
    }

    /** Record a download with its hashes so future update checks can compare. */
    public void recordInstall(String fileName, String remoteSha, String localHash, String repoUrl, String downloadUrl) {
        if (fileName == null) return;
        if (!installedFiles.contains(fileName)) {
            installedFiles.add(fileName);
        }
        installedScripts.put(fileName, new InstalledScript(
            remoteSha != null ? remoteSha : "",
            localHash != null ? localHash : "",
            repoUrl != null ? repoUrl : "",
            downloadUrl != null ? downloadUrl : ""));
        save();
    }

    public String getStoredSha(String fileName) {
        InstalledScript s = installedScripts.get(fileName);
        return s != null ? s.getSha() : "";
    }

    public boolean isUpdateAvailable(String fileName, String remoteSha) {
        if (remoteSha == null || remoteSha.isEmpty()) return false;
        InstalledScript s = installedScripts.get(fileName);
        if (s == null) return false;
        String stored = s.getSha();
        // Files installed before hash tracking have no baseline: offer update to re-sync.
        if (stored == null || stored.isEmpty()) return true;
        return !stored.equals(remoteSha);
    }

    public static ModConfig load() {
        ModConfig config = new ModConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String content = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();

                if (json.has("userRepos")) {
                    JsonArray arr = json.getAsJsonArray("userRepos");
                    for (JsonElement el : arr) {
                        JsonObject obj = el.getAsJsonObject();
                        RepoEntry repo = RepoEntry.fromUrl(
                            obj.get("url").getAsString(),
                            obj.has("name") ? obj.get("name").getAsString() : null
                        );
                        if (obj.has("description")) repo.setDescription(obj.get("description").getAsString());
                        if (obj.has("author")) repo.setAuthor(obj.get("author").getAsString());
                        config.userRepos.add(repo);
                    }
                }

                if (json.has("installedFiles")) {
                    JsonArray arr = json.getAsJsonArray("installedFiles");
                    for (JsonElement el : arr) {
                        config.installedFiles.add(el.getAsString());
                    }
                }

                if (json.has("installedScripts")) {
                    JsonObject map = json.getAsJsonObject("installedScripts");
                    for (String key : map.keySet()) {
                        JsonObject obj = map.getAsJsonObject(key);
                        InstalledScript s = new InstalledScript(
                            obj.has("sha") ? obj.get("sha").getAsString() : "",
                            obj.has("localHash") ? obj.get("localHash").getAsString() : "",
                            obj.has("repoUrl") ? obj.get("repoUrl").getAsString() : "",
                            obj.has("downloadUrl") ? obj.get("downloadUrl").getAsString() : "");
                        config.installedScripts.put(key, s);
                    }
                }
                // Migrate legacy installs (pre-hashmap) so every installed file has a map entry.
                for (String f : config.installedFiles) {
                    config.installedScripts.putIfAbsent(f, new InstalledScript("", "", "", ""));
                }

                if (json.has("scriptFolder")) {
                    config.scriptFolder = json.get("scriptFolder").getAsString();
                }

                if (json.has("disclaimerAccepted")) {
                    config.disclaimerAccepted = json.get("disclaimerAccepted").getAsBoolean();
                }
                if (json.has("showAddonsButton")) {
                    config.showAddonsButton = json.get("showAddonsButton").getAsBoolean();
                }
                if (json.has("showFolderButton")) {
                    config.showFolderButton = json.get("showFolderButton").getAsBoolean();
                }
                if (json.has("showAddRepoButton")) {
                    config.showAddRepoButton = json.get("showAddRepoButton").getAsBoolean();
                }
                if (json.has("autoCopyToClipboard")) {
                    config.autoCopyToClipboard = json.get("autoCopyToClipboard").getAsBoolean();
                }
                if (json.has("autoDetectPython")) {
                    config.autoDetectPython = json.get("autoDetectPython").getAsBoolean();
                }
                if (json.has("hiddenCuratedRepos")) {
                    JsonArray arr = json.getAsJsonArray("hiddenCuratedRepos");
                    for (JsonElement el : arr) {
                        config.hiddenCuratedRepos.add(el.getAsString());
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load config: {}", e.getMessage());
            }
        }
        return config;
    }

    public void save() {
        try {
            JsonObject json = new JsonObject();

            JsonArray userArr = new JsonArray();
            for (RepoEntry repo : userRepos) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", repo.getName());
                obj.addProperty("url", repo.getUrl());
                obj.addProperty("description", repo.getDescription());
                obj.addProperty("author", repo.getAuthor());
                userArr.add(obj);
            }
            json.add("userRepos", userArr);

            JsonArray installedArr = new JsonArray();
            for (String f : installedFiles) {
                installedArr.add(f);
            }
            json.add("installedFiles", installedArr);

            JsonObject scriptsObj = new JsonObject();
            for (java.util.Map.Entry<String, InstalledScript> e : installedScripts.entrySet()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("sha", e.getValue().getSha());
                obj.addProperty("localHash", e.getValue().getLocalHash());
                obj.addProperty("repoUrl", e.getValue().getRepoUrl());
                obj.addProperty("downloadUrl", e.getValue().getDownloadUrl());
                scriptsObj.add(e.getKey(), obj);
            }
            json.add("installedScripts", scriptsObj);
            json.addProperty("scriptFolder", scriptFolder);
            json.addProperty("disclaimerAccepted", disclaimerAccepted);
            json.addProperty("showAddonsButton", showAddonsButton);
            json.addProperty("showFolderButton", showFolderButton);
            json.addProperty("showAddRepoButton", showAddRepoButton);
            json.addProperty("autoCopyToClipboard", autoCopyToClipboard);
            json.addProperty("autoDetectPython", autoDetectPython);

            JsonArray hiddenArr = new JsonArray();
            for (String url : hiddenCuratedRepos) {
                hiddenArr.add(url);
            }
            json.add("hiddenCuratedRepos", hiddenArr);

            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }

    public static List<RepoEntry> loadCuratedRepos() {
        if (curatedReposCache != null) return curatedReposCache;

        List<RepoEntry> repos = loadBundledRepos();
        curatedReposCache = repos;

        GitHubAPI.fetchCuratedRepos().whenComplete((fetched, error) -> {
            if (error == null && !fetched.isEmpty()) {
                curatedReposCache = fetched;
                LOGGER.info("Updated curated repos from GitHub ({} repos)", fetched.size());
            } else if (error != null) {
                LOGGER.warn("GitHub curated fetch failed, using bundled: {}", error.getMessage());
            }
        });

        LOGGER.info("Loaded {} curated repos (async GitHub fetch pending)", repos.size());
        return repos;
    }

    private static List<RepoEntry> loadBundledRepos() {
        List<RepoEntry> repos = new ArrayList<>();
        try {
            if (Files.exists(REPOS_PATH)) {
                try (InputStream in = Files.newInputStream(REPOS_PATH)) {
                    loadReposFromStream(in, repos);
                }
            } else {
                Optional<Path> path = FabricLoader.getInstance()
                    .getModContainer(MinescriptAddonsMod.MOD_ID)
                    .flatMap(container -> container.findPath("minescript_addons_repos.json"));

                if (path.isPresent()) {
                    try (InputStream in = Files.newInputStream(path.get())) {
                        loadReposFromStream(in, repos);
                    }
                } else {
                    LOGGER.warn("minescript_addons_repos.json not found via mod container");
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load bundled curated repos: {}", e.getMessage());
        }
        return repos;
    }

    private static void loadReposFromStream(InputStream in, List<RepoEntry> repos) throws IOException {
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();
        JsonArray arr = json.getAsJsonArray("repos");

        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String name = obj.get("name").getAsString();
            String url = obj.get("url").getAsString();
            String description = obj.has("description") ? obj.get("description").getAsString() : "";
            String author = obj.has("author") ? obj.get("author").getAsString() : "";
            String ref = obj.has("ref") ? obj.get("ref").getAsString() : "";

            RepoEntry repo = RepoEntry.fromUrl(url, name);
            repo.setDescription(description);
            repo.setAuthor(author);
            repo.setRef(ref);
            repos.add(repo);
        }
    }

    /** Metadata stored per downloaded script for update detection. */
    public static class InstalledScript {
        private String sha;
        private String localHash;
        private String repoUrl;
        private String downloadUrl;

        public InstalledScript() {
            this("", "", "", "");
        }

        public InstalledScript(String sha, String localHash, String repoUrl, String downloadUrl) {
            this.sha = sha != null ? sha : "";
            this.localHash = localHash != null ? localHash : "";
            this.repoUrl = repoUrl != null ? repoUrl : "";
            this.downloadUrl = downloadUrl != null ? downloadUrl : "";
        }

        public String getSha() { return sha != null ? sha : ""; }
        public String getLocalHash() { return localHash != null ? localHash : ""; }
        public String getRepoUrl() { return repoUrl != null ? repoUrl : ""; }
        public String getDownloadUrl() { return downloadUrl != null ? downloadUrl : ""; }
    }
}
