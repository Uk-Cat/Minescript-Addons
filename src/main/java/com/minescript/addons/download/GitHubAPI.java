package com.minescript.addons.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minescript.addons.data.RepoEntry;
import com.minescript.addons.data.RepoEntry.ScriptFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GitHubAPI {
    private static final Logger LOGGER = LoggerFactory.getLogger("minescript-addons");
    private static final String API_BASE = "https://api.github.com";
    private static final String RAW_BASE = "https://raw.githubusercontent.com";
    private static final String USER_AGENT = "MinescriptAddons/1.0";
    private static final List<String> SCRIPT_EXTENSIONS = List.of(".py", ".pyj");

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);

    public static CompletableFuture<List<ScriptFile>> listScriptFiles(RepoEntry repo) {
        return CompletableFuture.supplyAsync(() -> {
            String owner = repo.getOwner();
            String repoName = repo.getRepo();
            String ref = repo.getApiRef();
            String path = repo.getApiPath();

            if (owner == null || repoName == null) {
                throw new IllegalArgumentException("Invalid GitHub URL: " + repo.getUrl());
            }

            List<ScriptFile> result = new ArrayList<>();

            try {
                if (repo.getDescription().isEmpty()) {
                    fetchRepoInfo(owner, repoName, repo);
                }

                String apiUrl = API_BASE + "/repos/" + owner + "/" + repoName
                    + "/contents/" + encodePath(path)
                    + "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8);

                LOGGER.info("Fetching file list from: {}", apiUrl);

                HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    String errorBody = readStream(conn.getErrorStream());
                    throw new IOException("GitHub API returned " + responseCode + ": " + errorBody);
                }

                String body = readStream(conn.getInputStream());
                JsonElement json = JsonParser.parseString(body);

                if (json.isJsonArray()) {
                    JsonArray arr = json.getAsJsonArray();
                    for (JsonElement el : arr) {
                        JsonObject obj = el.getAsJsonObject();
                        String itemName = obj.get("name").getAsString();
                        String type = obj.get("type").getAsString();

                        if (type.equals("file") && isScriptFile(itemName)) {
                            String downloadUrl = obj.get("download_url").getAsString();
                            long size = obj.get("size").getAsLong();
                            result.add(new ScriptFile(itemName, downloadUrl, size));
                        } else if (type.equals("dir")) {
                            String dirPath = obj.get("path").getAsString();
                            result.addAll(listFilesRecursive(owner, repoName, ref, dirPath));
                        }
                    }
                } else if (json.isJsonObject()) {
                    JsonObject obj = json.getAsJsonObject();
                    String itemName = obj.get("name").getAsString();
                    String type = obj.get("type").getAsString();
                    if (type.equals("file") && isScriptFile(itemName)) {
                        String downloadUrl = obj.get("download_url").getAsString();
                        long size = obj.get("size").getAsLong();
                        result.add(new ScriptFile(itemName, downloadUrl, size));
                    }
                }

                LOGGER.info("Found {} script files in {}", result.size(), repo.getName());
            } catch (Exception e) {
                LOGGER.error("Failed to list files for {}: {}", repo.getName(), e.getMessage());
                throw new RuntimeException("Failed to fetch file list: " + e.getMessage(), e);
            }

            return result;
        }, EXECUTOR);
    }

    public static CompletableFuture<RepoEntry> fetchAndPopulateRepo(RepoEntry repo) {
        return CompletableFuture.supplyAsync(() -> {
            String owner = repo.getOwner();
            String repoName = repo.getRepo();
            if (owner == null || repoName == null) {
                throw new IllegalArgumentException("Invalid GitHub URL: " + repo.getUrl());
            }
            try {
                fetchRepoInfo(owner, repoName, repo);
                validateRefPath(owner, repoName, repo.getApiRef(), repo.getApiPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return repo;
        }, EXECUTOR);
    }

    static void fetchRepoInfo(String owner, String repoName, RepoEntry repo) throws IOException {
        String infoUrl = API_BASE + "/repos/" + owner + "/" + repoName;
        HttpURLConnection conn = (HttpURLConnection) URI.create(infoUrl).toURL().openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        int responseCode = conn.getResponseCode();
        if (responseCode == 404) {
            throw new IOException("Repository not found (404)");
        }
        if (responseCode != 200) {
            String errorBody = readStream(conn.getErrorStream());
            throw new IOException("GitHub API returned " + responseCode + ": " + errorBody);
        }

        String body = readStream(conn.getInputStream());
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        if (json.has("description") && !json.get("description").isJsonNull()) {
            repo.setDescription(json.get("description").getAsString());
        }
        if (json.has("owner") && json.getAsJsonObject("owner").has("login")) {
            repo.setAuthor(json.getAsJsonObject("owner").get("login").getAsString());
        }
        LOGGER.info("Fetched repo info for {}: desc='{}' author='{}'",
            repo.getName(), repo.getDescription(), repo.getAuthor());
    }

    static void validateRefPath(String owner, String repoName, String ref, String path) throws IOException {
        String apiUrl = API_BASE + "/repos/" + owner + "/" + repoName
            + "/contents/" + encodePath(path)
            + "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        int responseCode = conn.getResponseCode();
        if (responseCode == 404) {
            throw new IOException("Branch or path not found. Check that '" + ref + "' exists and the path is correct (404)");
        }
        if (responseCode != 200) {
            String errorBody = readStream(conn.getErrorStream());
            throw new IOException("GitHub API returned " + responseCode + ": " + errorBody);
        }
    }

    private static List<ScriptFile> listFilesRecursive(String owner, String repo, String ref, String path) {
        List<ScriptFile> result = new ArrayList<>();
        try {
            String apiUrl = API_BASE + "/repos/" + owner + "/" + repo
                + "/contents/" + encodePath(path)
                + "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) return result;

            String body = readStream(conn.getInputStream());
            JsonElement json = JsonParser.parseString(body);

            if (json.isJsonArray()) {
                JsonArray arr = json.getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    String itemName = obj.get("name").getAsString();
                    String type = obj.get("type").getAsString();
                    if (type.equals("file") && isScriptFile(itemName)) {
                        String downloadUrl = obj.get("download_url").getAsString();
                        long size = obj.get("size").getAsLong();
                        result.add(new ScriptFile(itemName, downloadUrl, size));
                    } else if (type.equals("dir")) {
                        String dirPath = obj.get("path").getAsString();
                        result.addAll(listFilesRecursive(owner, repo, ref, dirPath));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error listing subdirectory {}: {}", path, e.getMessage());
        }
        return result;
    }

    public static CompletableFuture<DownloadResult> downloadFile(String fileName, String downloadUrl, Path targetFolder) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.info("Downloading {} from {}", fileName, downloadUrl);

                HttpURLConnection conn = (HttpURLConnection) URI.create(downloadUrl).toURL().openConnection();
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    return new DownloadResult(fileName, false, "HTTP " + responseCode);
                }

                Path targetFile = targetFolder.resolve(fileName);
                int counter = 1;
                while (Files.exists(targetFile)) {
                    String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
                    String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
                    targetFile = targetFolder.resolve(baseName + "_" + counter + ext);
                    counter++;
                }

                Files.createDirectories(targetFolder);

                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(targetFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }

                LOGGER.info("Successfully downloaded {} to {}", fileName, targetFile);
                return new DownloadResult(targetFile.getFileName().toString(), true, null);
            } catch (Exception e) {
                LOGGER.error("Failed to download {}: {}", fileName, e.getMessage());
                return new DownloadResult(fileName, false, e.getMessage());
            }
        }, EXECUTOR);
    }

    private static boolean isScriptFile(String name) {
        String lower = name.toLowerCase();
        for (String ext : SCRIPT_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static String encodePath(String path) {
        if (path == null || path.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (char c : path.toCharArray()) {
            if (c == '/') result.append('/');
            else result.append(URLEncoder.encode(String.valueOf(c), StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private static String readStream(InputStream stream) throws IOException {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public record DownloadResult(String fileName, boolean success, String errorMessage) {}
}
