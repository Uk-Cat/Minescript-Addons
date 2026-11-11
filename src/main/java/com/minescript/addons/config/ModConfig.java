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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("minescript-addons");
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("minescript-addons.json");
    /** Legacy rich list, only read once to seed URLS_PATH on first run. */
    private static final Path REPOS_PATH = FabricLoader.getInstance().getGameDir().resolve("minescript_addons_repos.json");
    /**
     * Single working copy of URLS.json in the game directory. Same shape as URLS.json
     * at the repo root: {"repos": ["<github-url>", ...]}. Seeded from the jar on first
     * run, appended to on add, pruned on delete, merged with the GitHub copy on refresh.
     * Offline the mod just keeps using this file, so no separate offline list is needed.
     */
    private static final Path URLS_PATH = FabricLoader.getInstance().getGameDir().resolve("URLS.json");

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

    /** @deprecated Repos now live in the single URLS.json store; use {@link #loadCuratedRepos()}. */
    @Deprecated
    public List<RepoEntry> getUserRepos() { return new ArrayList<>(); }
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
    /** @deprecated No longer used: repos live in the single URLS.json store. Kept for config compat. */
    @Deprecated
    public boolean isCuratedRepoHidden(String url) { return false; }
    /** @deprecated Now just removes the URL from URLS.json; refresh re-pulls it from GitHub. */
    @Deprecated
    public void hideCuratedRepo(String url) {
        removeRepoUrl(url);
    }
    /** @deprecated Now just re-adds the URL to URLS.json. Prefer {@link #refreshCuratedRepos()}. */
    @Deprecated
    public void restoreCuratedRepo(String url) {
        addRepoUrl(url);
    }
    public static void clearCuratedCache() { curatedReposCache = null; }
    public void setScriptFolder(String value) {
        scriptFolder = value != null ? value : "";
        save();
    }

    /** Adds the repo URL to the local URLS.json working copy. */
    public void addUserRepo(RepoEntry repo) {
        if (repo != null) addRepoUrl(repo.getUrl());
    }

    /** Removes the repo URL from the local URLS.json working copy. */
    public void removeUserRepo(String url) {
        removeRepoUrl(url);
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

    /** Reads the local URLS.json working copy. Offline-safe: no network involved. */
    public static List<RepoEntry> loadCuratedRepos() {
        if (curatedReposCache != null) return curatedReposCache;
        synchronized (ModConfig.class) {
            if (curatedReposCache == null) {
                curatedReposCache = readUrlsFile();
                LOGGER.info("Loaded {} repos from local URLS.json", curatedReposCache.size());
            }
            return curatedReposCache;
        }
    }

    /**
     * Refresh = refetch the remote URLS.json from GitHub and merge any missing URLs
     * into the local working copy. Local-only entries are kept; deleted curated repos
     * come back (restore). Offline the fetch fails and the local file is used as-is.
     */
    public static CompletableFuture<List<RepoEntry>> refreshCuratedRepos() {
        loadCuratedRepos();
        return GitHubAPI.fetchCuratedRepos().thenApply(fetched -> {
            if (fetched == null || fetched.isEmpty()) {
                throw new IllegalStateException("Remote URLS.json unreachable or empty (offline?)");
            }
            synchronized (ModConfig.class) {
                Set<String> urls = new LinkedHashSet<>();
                for (RepoEntry r : curatedReposCache) urls.add(r.getUrl());
                for (RepoEntry r : fetched) {
                    if (!urls.contains(r.getUrl())) {
                        urls.add(r.getUrl());
                        curatedReposCache.add(r);
                    }
                }
                saveUrlsFile(urls);
                LOGGER.info("Refreshed URLS.json from GitHub ({} repos)", curatedReposCache.size());
                return curatedReposCache;
            }
        });
    }

    /** Appends a repo URL to the local URLS.json working copy. */
    public static void addRepoUrl(String url) {
        if (url == null || url.isEmpty()) return;
        synchronized (ModConfig.class) {
            loadCuratedRepos();
            for (RepoEntry r : curatedReposCache) {
                if (r.getUrl().equals(url)) return;
            }
            curatedReposCache.add(RepoEntry.fromUrl(url, null));
            saveUrlsFile(collectUrls(curatedReposCache));
        }
    }

    /** Removes a repo URL from the local URLS.json working copy. */
    public static void removeRepoUrl(String url) {
        if (url == null) return;
        synchronized (ModConfig.class) {
            loadCuratedRepos();
            boolean changed = curatedReposCache.removeIf(r -> r.getUrl().equals(url));
            if (changed) saveUrlsFile(collectUrls(curatedReposCache));
        }
    }

    private static List<RepoEntry> readUrlsFile() {
        try {
            if (!Files.exists(URLS_PATH)) {
                Set<String> seed = collectSeedUrls();
                saveUrlsFile(seed);
                return entriesFromUrls(seed);
            }
            String content = Files.readString(URLS_PATH, StandardCharsets.UTF_8);
            return entriesFromUrls(parseUrlsJson(content));
        } catch (Exception e) {
            LOGGER.error("Failed to read local URLS.json: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Accepts both the plain ["url", ...] shape and legacy [{"url": ...}] objects. */
    private static Set<String> parseUrlsJson(String content) {
        Set<String> urls = new LinkedHashSet<>();
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();
        JsonArray arr = json.getAsJsonArray("repos");
        if (arr == null) return urls;
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive()) {
                urls.add(el.getAsString());
            } else if (el.isJsonObject() && el.getAsJsonObject().has("url")) {
                urls.add(el.getAsJsonObject().get("url").getAsString());
            }
        }
        return urls;
    }

    /** First-run seed: jar URLS.json, then legacy rich lists, then legacy config entries. */
    private static Set<String> collectSeedUrls() {
        Set<String> urls = new LinkedHashSet<>();
        try {
            Optional<Path> jarUrls = FabricLoader.getInstance()
                .getModContainer(MinescriptAddonsMod.MOD_ID)
                .flatMap(container -> container.findPath("URLS.json"));
            if (jarUrls.isPresent()) {
                try (InputStream in = Files.newInputStream(jarUrls.get())) {
                    urls.addAll(parseUrlsJson(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                }
            } else {
                LOGGER.warn("URLS.json not found via mod container");
            }
        } catch (Exception e) {
            LOGGER.warn("Bundled URLS.json seed skipped: {}", e.getMessage());
        }
        addLegacyRichUrls(REPOS_PATH, urls);
        try {
            Optional<Path> jarLegacy = FabricLoader.getInstance()
                .getModContainer(MinescriptAddonsMod.MOD_ID)
                .flatMap(container -> container.findPath("minescript_addons_repos.json"));
            jarLegacy.ifPresent(p -> addLegacyRichUrls(p, urls));
        } catch (Exception e) {
            LOGGER.warn("Legacy bundled repos seed skipped: {}", e.getMessage());
        }
        try {
            ModConfig legacy = ModConfig.load();
            for (RepoEntry r : legacy.userRepos) urls.add(r.getUrl());
            urls.removeAll(legacy.hiddenCuratedRepos);
        } catch (Exception e) {
            LOGGER.warn("Legacy config migration skipped: {}", e.getMessage());
        }
        return urls;
    }

    private static void addLegacyRichUrls(Path file, Set<String> urls) {
        try {
            if (file == null || !Files.exists(file)) return;
            try (InputStream in = Files.newInputStream(file)) {
                List<RepoEntry> tmp = new ArrayList<>();
                loadReposFromStream(in, tmp);
                for (RepoEntry r : tmp) urls.add(r.getUrl());
            }
        } catch (Exception e) {
            LOGGER.warn("Skipping legacy repos file {}: {}", file, e.getMessage());
        }
    }

    private static void saveUrlsFile(java.util.Collection<String> urls) {
        try {
            JsonObject json = new JsonObject();
            JsonArray arr = new JsonArray();
            for (String u : urls) arr.add(u);
            json.add("repos", arr);
            Files.createDirectories(URLS_PATH.getParent());
            Files.writeString(URLS_PATH, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("Failed to save local URLS.json: {}", e.getMessage());
        }
    }

    private static Set<String> collectUrls(List<RepoEntry> repos) {
        Set<String> urls = new LinkedHashSet<>();
        for (RepoEntry r : repos) urls.add(r.getUrl());
        return urls;
    }

    private static List<RepoEntry> entriesFromUrls(java.util.Collection<String> urls) {
        List<RepoEntry> repos = new ArrayList<>();
        for (String url : urls) repos.add(RepoEntry.fromUrl(url, null));
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
