package com.minescript.addons.manager;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the best Python interpreter on the PC and points Minescript's
 * {@code minescript/config.txt} ({@code python=...}) at it.
 *
 * <p>Rules (agreed spec):
 * <ul>
 *   <li>Runs once at launch, never overwrites a non-default (custom) path.</li>
 *   <li>Windows: any path containing {@code WindowsApps} counts as the
 *       Microsoft Store default. Any real install beats any Store install,
 *       then highest version wins.</li>
 *   <li>macOS/Linux: default is {@code /usr/bin/python3}. Only replaces it
 *       when a strictly higher version is found; otherwise highest version wins.</li>
 * </ul>
 */
public final class PythonDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger("minescript-addons");

    static final String AUTO_EDIT_MARKER = "#Automatically Edited By Minescript Addons";
    private static final String ALTERNATIVES_HEADER = "# Other Python installations found (in order):";

    private static final Pattern VERSION_OUTPUT = Pattern.compile("Python\\s+(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern VERSION_OUTPUT_SHORT = Pattern.compile("Python\\s+(\\d+)\\.(\\d+)");
    private static final Pattern WINDOWS_EXE_IN_LINE = Pattern.compile("[A-Za-z]:\\\\[^\"\\n]*?python[^\"\\n\\\\]*\\.exe", Pattern.CASE_INSENSITIVE);
    private static final Pattern PY_PYTHON311 = Pattern.compile("Python(\\d)(\\d{1,2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PY_DOT_VERSION = Pattern.compile("python3?\\.?(\\d+)\\.?(\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENV_VAR = Pattern.compile("%([^%]+)%");

    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    private PythonDetector() {
    }

    public enum Status {
        /** config.txt was updated to the detected interpreter. */
        UPDATED,
        /** Already pointing at the best interpreter, nothing to do. */
        UNCHANGED_ALREADY_BEST,
        /** Current python= is a custom non-default path; left alone. */
        SKIPPED_CUSTOM,
        /** minescript/config.txt does not exist yet; retry next launch. */
        SKIPPED_NO_CONFIG,
        /** No usable Python found on this machine. */
        SKIPPED_NO_PYTHON,
        /** Unexpected failure (I/O, etc.). */
        FAILED
    }

    public record Result(Status status, Path selected, String previous, String message) {
    }

    public record Candidate(Path path, Version version, boolean storeInstall) {
    }

    public record Version(int major, int minor, int micro) implements Comparable<Version> {
        public static final Version UNKNOWN = new Version(-1, -1, -1);

        public boolean isKnown() {
            return major >= 0;
        }

        @Override
        public int compareTo(Version o) {
            if (major != o.major) return Integer.compare(major, o.major);
            if (minor != o.minor) return Integer.compare(minor, o.minor);
            return Integer.compare(micro, o.micro);
        }

        @Override
        public String toString() {
            return isKnown() ? major + "." + minor + "." + micro : "unknown";
        }
    }

    // ---- entry points ----

    /** Launch-once async entry point. Never throws. */
    public static void fixIfNeededAsync(Consumer<Result> onDone) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return fixIfNeeded();
            } catch (Exception e) {
                LOGGER.warn("Python auto-detect failed: {}", e.getMessage());
                return new Result(Status.FAILED, null, null, e.getMessage());
            }
        }).thenAccept(result -> {
            try {
                if (onDone != null) {
                    onDone.accept(result);
                }
            } catch (Exception e) {
                LOGGER.warn("Python auto-detect callback failed: {}", e.getMessage());
            }
        });
    }

    /** Synchronous detect-and-fix. Safe to call off-thread only (runs subprocesses). */
    public static Result fixIfNeeded() {
        Path config = resolveConfigTxt();
        if (!Files.isRegularFile(config)) {
            LOGGER.info("Python auto-detect: {} not present yet, leaving alone until next launch", config);
            return new Result(Status.SKIPPED_NO_CONFIG, null, null, "config.txt not present");
        }

        Optional<String> currentOpt;
        try {
            currentOpt = readCurrentPython(config);
        } catch (IOException e) {
            LOGGER.warn("Python auto-detect: failed to read {}: {}", config, e.getMessage());
            return new Result(Status.FAILED, null, null, e.getMessage());
        }
        String current = currentOpt.map(String::trim).orElse("");

        if (isWindows()) {
            if (!current.isEmpty() && !isWindowsStorePath(current)) {
                LOGGER.info("Python auto-detect: custom python path '{}' kept (not a default)", current);
                return new Result(Status.SKIPPED_CUSTOM, null, current, "custom path kept");
            }
            List<Candidate> candidates = discoverWindows();
            if (candidates.isEmpty()) {
                LOGGER.warn("Python auto-detect: no Python installation found on Windows");
                return new Result(Status.SKIPPED_NO_PYTHON, null, current.isEmpty() ? null : current, "no python found");
            }
            List<Candidate> ranked = rankedWindows(candidates);
            Candidate best = ranked.isEmpty() ? null : ranked.get(0);
            if (best == null) {
                return new Result(Status.SKIPPED_NO_PYTHON, null, current.isEmpty() ? null : current, "no python found");
            }
            if (!current.isEmpty() && pathsEqual(current, best.path().toString())) {
                LOGGER.info("Python auto-detect: already pointing at best interpreter {}", best.path());
                return new Result(Status.UNCHANGED_ALREADY_BEST, best.path(), current, "already best");
            }
            try {
                writePython(config, best, ranked);
            } catch (IOException e) {
                LOGGER.warn("Python auto-detect: failed to write {}: {}", config, e.getMessage());
                return new Result(Status.FAILED, best.path(), current.isEmpty() ? null : current, e.getMessage());
            }
            LOGGER.info("Python auto-detect: python path '{}' -> '{}' (version {})",
                current.isEmpty() ? "<missing>" : current, best.path(), best.version());
            return new Result(Status.UPDATED, best.path(), current.isEmpty() ? null : current, best.path().toString());
        }

        // macOS / Linux
        if (!current.isEmpty() && !isUnixDefault(current)) {
            LOGGER.info("Python auto-detect: custom python path '{}' kept (not a default)", current);
            return new Result(Status.SKIPPED_CUSTOM, null, current, "custom path kept");
        }
        Version currentVersion = current.isEmpty() ? null : probeVersionSafe(toPathLenient(current));
        List<Candidate> candidates = discoverUnix();
        if (candidates.isEmpty()) {
            LOGGER.warn("Python auto-detect: no Python installation found");
            return new Result(Status.SKIPPED_NO_PYTHON, null, current.isEmpty() ? null : current, "no python found");
        }
        List<Candidate> ranked = rankedUnix(candidates);
        Candidate best = ranked.isEmpty() ? null : ranked.get(0);
        if (best == null) {
            return new Result(Status.SKIPPED_NO_PYTHON, null, current.isEmpty() ? null : current, "no python found");
        }
        if (!current.isEmpty()) {
            if (pathsEqual(current, best.path().toString())) {
                return new Result(Status.UNCHANGED_ALREADY_BEST, best.path(), current, "already best");
            }
            if (currentVersion != null && currentVersion.isKnown() && best.version().isKnown()
                && best.version().compareTo(currentVersion) <= 0) {
                LOGGER.info("Python auto-detect: default '{}' (version {}) already best, keeping", current, currentVersion);
                return new Result(Status.UNCHANGED_ALREADY_BEST, best.path(), current, "already best");
            }
        }
        try {
            writePython(config, best, ranked);
        } catch (IOException e) {
            LOGGER.warn("Python auto-detect: failed to write {}: {}", config, e.getMessage());
            return new Result(Status.FAILED, best.path(), current.isEmpty() ? null : current, e.getMessage());
        }
        LOGGER.info("Python auto-detect: python path '{}' -> '{}' (version {})",
            current.isEmpty() ? "<missing>" : current, best.path(), best.version());
        return new Result(Status.UPDATED, best.path(), current.isEmpty() ? null : current, best.path().toString());
    }

    // ---- config.txt ----

    static Path resolveConfigTxt() {
        return FabricLoader.getInstance().getGameDir().resolve("minescript").resolve("config.txt");
    }

    /** Finds the first non-comment {@code python=...} value. */
    static Optional<String> readCurrentPython(Path config) throws IOException {
        List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            if (key.equalsIgnoreCase("python")) {
                return Optional.of(unquote(trimmed.substring(eq + 1).trim()));
            }
        }
        return Optional.empty();
    }

    /**
     * Replaces (or appends) the {@code python=} line, preserving other settings,
     * and records every other interpreter found as ranked {@code #python=} comments.
     *
     * <p>Resulting block looks like:
     * <pre>
     * #Automatically Edited By Minescript Addons
     * python=&lt;best&gt;
     * # Other Python installations found (in order):
     * #python=&lt;runner-up&gt; # version 3.11.9
     * </pre>
     * Previous auto-generated blocks are removed first so re-runs stay idempotent.
     */
    static void writePython(Path config, Candidate best, List<Candidate> rankedInOrder) throws IOException {
        List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
        lines.removeIf(l -> isAutoGeneratedLine(l.trim()));

        String replacement = "python=" + best.path().toString();
        int activeIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (trimmed.substring(0, eq).trim().equalsIgnoreCase("python")) {
                lines.set(i, replacement);
                activeIndex = i;
                break;
            }
        }
        if (activeIndex < 0) {
            lines.add(AUTO_EDIT_MARKER);
            lines.add(replacement);
            activeIndex = lines.size() - 1;
        } else {
            lines.add(activeIndex, AUTO_EDIT_MARKER);
            activeIndex += 1;
        }

        List<String> alternatives = new ArrayList<>();
        if (rankedInOrder != null) {
            boolean first = true;
            for (Candidate c : rankedInOrder) {
                if (first) {
                    first = false; // index 0 is the active best
                    continue;
                }
                alternatives.add("#python=" + c.path().toString() + " # version " + c.version());
            }
        }
        int insertAt = activeIndex + 1;
        if (!alternatives.isEmpty()) {
            lines.add(insertAt, ALTERNATIVES_HEADER);
            insertAt += 1;
            for (String alt : alternatives) {
                lines.add(insertAt, alt);
                insertAt += 1;
            }
        }
        Files.createDirectories(config.getParent());
        Files.write(config, lines, StandardCharsets.UTF_8);
    }

    /** Lines our writer owns: the marker, the alternatives header, and commented alternatives. */
    static boolean isAutoGeneratedLine(String trimmed) {
        if (trimmed.equalsIgnoreCase(AUTO_EDIT_MARKER)) {
            return true;
        }
        if (trimmed.equalsIgnoreCase(ALTERNATIVES_HEADER)) {
            return true;
        }
        if (trimmed.length() > 1 && trimmed.charAt(0) == '#') {
            String rest = trimmed.substring(1).trim();
            int eq = rest.indexOf('=');
            if (eq > 0 && rest.substring(0, eq).trim().equalsIgnoreCase("python")) {
                return true;
            }
        }
        return false;
    }

    // ---- classification helpers ----

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static boolean isWindowsStorePath(String value) {
        return value.toLowerCase(Locale.ROOT).contains("windowsapps");
    }

    static boolean isUnixDefault(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("/usr/bin/python3") || v.equals("/usr/bin/python")
            || v.equals("/usr/bin/python3.exe") || v.equals("/usr/bin/python.exe");
    }

    static boolean pathsEqual(String a, String b) {
        try {
            Path pa = toPathLenient(a).toAbsolutePath().normalize();
            Path pb = toPathLenient(b).toAbsolutePath().normalize();
            if (isWindows()) {
                return pa.toString().equalsIgnoreCase(pb.toString());
            }
            return pa.equals(pb);
        } catch (Exception e) {
            if (isWindows()) {
                return a.equalsIgnoreCase(b);
            }
            return a.equals(b);
        }
    }

    static Path toPathLenient(String value) {
        return Path.of(expandEnv(unquote(value.trim())));
    }

    static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Expands {@code %NAME%} Windows env vars (e.g. %USERPROFILE% in Minescript defaults). */
    static String expandEnv(String s) {
        Matcher m = ENV_VAR.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String env = System.getenv(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(env != null ? env : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---- discovery: Windows ----

    static List<Candidate> discoverWindows() {
        Map<String, Path> seen = new LinkedHashMap<>();
        java.util.function.Consumer<Path> add = p -> {
            if (p == null) {
                return;
            }
            try {
                if (!Files.isRegularFile(p)) {
                    return;
                }
            } catch (Exception e) {
                return;
            }
            seen.putIfAbsent(p.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT), p);
        };

        // 1. Python launcher: py -0p lists installed versions + paths.
        try {
            String out = runCommand(COMMAND_TIMEOUT_SECONDS, "py", "-0p");
            Matcher m = WINDOWS_EXE_IN_LINE.matcher(out);
            while (m.find()) {
                try {
                    add.accept(Path.of(m.group().trim()));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Python auto-detect: 'py -0p' unavailable: {}", e.getMessage());
        }

        // 2. where python / python3 / py
        for (String name : new String[]{"python", "python3", "py"}) {
            try {
                String out = runCommand(COMMAND_TIMEOUT_SECONDS, "where", name);
                for (String line : out.split("\\R")) {
                    String t = unquote(line.trim());
                    if (t.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                        try {
                            add.accept(Path.of(t));
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Python auto-detect: 'where {}' found nothing", name);
            }
        }

        // 3. Well-known install roots.
        List<Path> roots = new ArrayList<>();
        addEnvDir(roots, "LOCALAPPDATA", "Programs", "Python");
        addEnvDir(roots, "PROGRAMFILES");
        addEnvDir(roots, "PROGRAMFILES(X86)");
        roots.add(Path.of("C:\\Python"));
        List<Path> parents = new ArrayList<>(roots);
        parents.add(getEnvPath("LOCALAPPDATA"));
        for (Path parent : parents) {
            if (parent == null || !Files.isDirectory(parent)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(parent, "Python*")) {
                for (Path child : ds) {
                    add.accept(child.resolve("python.exe"));
                    // venv-style nested exe, e.g. Python311\python.exe is covered; also check one level deeper just in case
                    if (Files.isDirectory(child)) {
                        try (DirectoryStream<Path> inner = Files.newDirectoryStream(child, "python.exe")) {
                            for (Path exe : inner) {
                                add.accept(exe);
                            }
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (IOException ignored) {
            }
            // Direct C:\Python311\python.exe style roots where parent itself holds the exe
            add.accept(parent.resolve("python.exe"));
        }

        // 4. Every dir on PATH.
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(";")) {
                String t = unquote(dir.trim());
                if (t.isEmpty()) {
                    continue;
                }
                try {
                    add.accept(Path.of(t, "python.exe"));
                    add.accept(Path.of(t, "python3.exe"));
                } catch (Exception ignored) {
                }
            }
        }

        List<Candidate> out = new ArrayList<>();
        for (Path p : seen.values()) {
            Version v = probeVersionSafe(p);
            out.add(new Candidate(p, v, isWindowsStorePath(p.toString())));
        }
        return out;
    }

    static Candidate selectBestWindows(List<Candidate> candidates) {
        List<Candidate> ranked = rankedWindows(candidates);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /** Real installs first, then Store installs; highest version wins inside each bucket. */
    static List<Candidate> rankedWindows(List<Candidate> candidates) {
        List<Candidate> real = new ArrayList<>();
        List<Candidate> store = new ArrayList<>();
        for (Candidate c : candidates) {
            (c.storeInstall() ? store : real).add(c);
        }
        Comparator<Candidate> byVersion = Comparator
            .comparing((Candidate c) -> c.version().isKnown() ? 1 : 0)
            .thenComparing(Candidate::version)
            .thenComparing(c -> c.path().toString().toLowerCase(Locale.ROOT));
        real.sort(byVersion.reversed());
        store.sort(byVersion.reversed());
        List<Candidate> ranked = new ArrayList<>(real);
        ranked.addAll(store);
        return ranked;
    }

    // ---- discovery: macOS / Linux ----

    static List<Candidate> discoverUnix() {
        Map<String, Path> seen = new LinkedHashMap<>();
        java.util.function.Consumer<String> addStr = s -> {
            String t = unquote(s.trim());
            if (!t.startsWith("/")) {
                return;
            }
            try {
                Path p = Path.of(t);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    seen.putIfAbsent(p.toAbsolutePath().normalize().toString(), p);
                }
            } catch (Exception ignored) {
            }
        };

        for (String name : new String[]{"python3", "python"}) {
            try {
                String out = runCommand(COMMAND_TIMEOUT_SECONDS, "which", "-a", name);
                for (String line : out.split("\\R")) {
                    if (!line.isBlank()) {
                        addStr.accept(line);
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Python auto-detect: 'which -a {}' found nothing", name);
            }
        }

        // Common locations.
        globAdd(seen, Path.of("/usr/bin"), "python3.*");
        globAdd(seen, Path.of("/usr/local/bin"), "python3*");
        globAdd(seen, Path.of("/opt/homebrew/bin"), "python3*");
        globAdd(seen, Path.of("/opt/local/bin"), "python3*");
        // pyenv: ~/.pyenv/versions/*/bin/python3
        Path pyenv = Path.of(System.getProperty("user.home", ""), ".pyenv", "versions");
        if (Files.isDirectory(pyenv)) {
            try (DirectoryStream<Path> versions = Files.newDirectoryStream(pyenv)) {
                for (Path v : versions) {
                    Path exe = v.resolve("bin").resolve("python3");
                    try {
                        if (Files.isRegularFile(exe) && Files.isExecutable(exe)) {
                            seen.putIfAbsent(exe.toAbsolutePath().normalize().toString(), exe);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }
        // /opt/python*/bin/python3
        Path opt = Path.of("/opt");
        if (Files.isDirectory(opt)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(opt, "python*")) {
                for (Path child : ds) {
                    Path exe = child.resolve("bin").resolve("python3");
                    try {
                        if (Files.isRegularFile(exe) && Files.isExecutable(exe)) {
                            seen.putIfAbsent(exe.toAbsolutePath().normalize().toString(), exe);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }
        // Explicit fallbacks.
        for (String p : new String[]{"/usr/bin/python3", "/usr/local/bin/python3"}) {
            addStr.accept(p);
        }

        List<Candidate> out = new ArrayList<>();
        for (Path p : seen.values()) {
            out.add(new Candidate(p, probeVersionSafe(p), false));
        }
        return out;
    }

    static Candidate selectBestUnix(List<Candidate> candidates) {
        List<Candidate> ranked = rankedUnix(candidates);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    static List<Candidate> rankedUnix(List<Candidate> candidates) {
        return candidates.stream()
            .sorted(Comparator
                .comparing((Candidate c) -> c.version().isKnown() ? 1 : 0)
                .thenComparing(Candidate::version)
                .thenComparing(c -> c.path().toString())
                .reversed())
            .toList();
    }

    // ---- shared process helpers ----

    private static void globAdd(Map<String, Path> seen, Path dir, String glob) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) {
                try {
                    if (Files.isRegularFile(p)) {
                        seen.putIfAbsent(p.toAbsolutePath().normalize().toString(), p);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void addEnvDir(List<Path> out, String env, String... subs) {
        String base = System.getenv(env);
        if (base == null || base.isBlank()) {
            return;
        }
        try {
            Path p = Path.of(base);
            for (String sub : subs) {
                if (sub != null) {
                    p = p.resolve(sub);
                }
            }
            out.add(p);
        } catch (Exception ignored) {
        }
    }

    private static Path getEnvPath(String env) {
        String base = System.getenv(env);
        if (base == null || base.isBlank()) {
            return null;
        }
        try {
            return Path.of(base);
        } catch (Exception e) {
            return null;
        }
    }

    static Version probeVersionSafe(Path exe) {
        if (exe == null) {
            return Version.UNKNOWN;
        }
        try {
            String out = runCommand(COMMAND_TIMEOUT_SECONDS, exe.toString(), "--version");
            Version v = parseVersionOutput(out);
            if (v.isKnown()) {
                return v;
            }
        } catch (Exception e) {
            LOGGER.debug("Python auto-detect: --version failed for {}: {}", exe, e.getMessage());
        }
        return parseVersionFromPath(exe.toString());
    }

    static Version parseVersionOutput(String out) {
        if (out == null) {
            return Version.UNKNOWN;
        }
        Matcher m = VERSION_OUTPUT.matcher(out);
        if (m.find()) {
            try {
                return new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher s = VERSION_OUTPUT_SHORT.matcher(out);
        if (s.find()) {
            try {
                return new Version(Integer.parseInt(s.group(1)), Integer.parseInt(s.group(2)), 0);
            } catch (NumberFormatException ignored) {
            }
        }
        return Version.UNKNOWN;
    }

    static Version parseVersionFromPath(String path) {
        Matcher m = PY_PYTHON311.matcher(path);
        if (m.find()) {
            try {
                return new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 0);
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher d = PY_DOT_VERSION.matcher(path);
        if (d.find()) {
            try {
                int minor = d.group(1).isEmpty() ? 0 : Integer.parseInt(d.group(1));
                int micro = d.group(2) == null || d.group(2).isEmpty() ? 0 : Integer.parseInt(d.group(2));
                return new Version(3, minor, micro);
            } catch (NumberFormatException ignored) {
            }
        }
        return Version.UNKNOWN;
    }

    static String runCommand(long timeoutSeconds, String... command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("failed to start " + String.join(" ", command) + ": " + e.getMessage(), e);
        }
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try {
                byte[] buf = process.getInputStream().readAllBytes();
                out.append(new String(buf, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("timed out: " + String.join(" ", command));
        }
        try {
            reader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.exitValue() != 0 && out.length() == 0) {
            throw new IOException("exit code " + process.exitValue());
        }
        return out.toString();
    }
}
