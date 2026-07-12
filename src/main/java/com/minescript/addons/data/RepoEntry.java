package com.minescript.addons.data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RepoEntry {
    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
        "github\\.com/([^/]+)/([^/]+?)(?:/tree/([^/]+)(?:/(.*))?)?(?:/blob/([^/]+)/(.*))?$"
    );

    private String name;
    private String url;
    private String description;
    private String author;
    private String ref;
    private String path;

    private List<ScriptFile> files;
    private boolean filesLoaded;
    private boolean loading;
    private String loadError;

    public RepoEntry() {
        this.files = new ArrayList<>();
    }

    public RepoEntry(String name, String url, String description, String author, String ref) {
        this.name = name;
        this.url = url;
        this.description = description;
        this.author = author;
        this.ref = ref;
        this.files = new ArrayList<>();
    }

    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getDescription() { return description; }
    public String getAuthor() { return author; }
    public String getRef() { return ref; }
    public String getPath() { return path; }

    public void setDescription(String description) { this.description = description; }
    public void setAuthor(String author) { this.author = author; }
    public void setRef(String ref) { this.ref = ref; }
    public void setName(String name) { this.name = name; }
    public List<ScriptFile> getFiles() { return files; }
    public boolean isFilesLoaded() { return filesLoaded; }
    public boolean isLoading() { return loading; }
    public String getLoadError() { return loadError; }

    public void setFiles(List<ScriptFile> files) {
        this.files = files;
        this.filesLoaded = true;
        this.loading = false;
        this.loadError = null;
    }

    public void setLoading(boolean loading) { this.loading = loading; }
    public void setLoadError(String error) {
        this.loadError = error;
        this.loading = false;
    }

    public String getOwner() {
        Matcher m = GITHUB_URL_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public String getRepo() {
        Matcher m = GITHUB_URL_PATTERN.matcher(url);
        return m.find() ? m.group(2).replace(".git", "") : null;
    }

    public String getApiRef() {
        if (ref != null && !ref.isEmpty()) return ref;
        Matcher m = GITHUB_URL_PATTERN.matcher(url);
        if (m.find() && m.group(3) != null) return m.group(3);
        return "main";
    }

    public String getApiPath() {
        if (path != null && !path.isEmpty()) return path;
        Matcher m = GITHUB_URL_PATTERN.matcher(url);
        if (m.find()) {
            if (m.group(4) != null) return m.group(4);
            if (m.group(6) != null) {
                String p = m.group(6);
                int lastSlash = p.lastIndexOf('/');
                return lastSlash > 0 ? p.substring(0, lastSlash) : "";
            }
        }
        return "";
    }

    public String getDisplayUrl() {
        String owner = getOwner();
        String repo = getRepo();
        if (owner == null || repo == null) return url;
        String base = "https://github.com/" + owner + "/" + repo;
        String apiRef = getApiRef();
        String apiPath = getApiPath();
        if (!apiPath.isEmpty()) {
            return base + "/tree/" + apiRef + "/" + apiPath;
        }
        return base;
    }

    public static RepoEntry fromUrl(String url, String name) {
        RepoEntry entry = new RepoEntry();
        entry.url = url;
        entry.name = name != null && !name.isEmpty() ? name : parseRepoName(url);
        entry.description = "";
        entry.author = "";
        entry.files = new ArrayList<>();
        return entry;
    }

    private static String parseRepoName(String url) {
        Matcher m = GITHUB_URL_PATTERN.matcher(url);
        if (m.find()) {
            String repo = m.group(2).replace(".git", "");
            return repo.substring(0, 1).toUpperCase() + repo.substring(1);
        }
        return "Unknown Repo";
    }

    public static class ScriptFile {
        private String name;
        private String downloadUrl;
        private long size;

        public ScriptFile() {}

        public ScriptFile(String name, String downloadUrl, long size) {
            this.name = name;
            this.downloadUrl = downloadUrl;
            this.size = size;
        }

        public String getName() { return name; }
        public String getDownloadUrl() { return downloadUrl; }
        public long getSize() { return size; }
    }
}
