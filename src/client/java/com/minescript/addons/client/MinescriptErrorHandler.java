package com.minescript.addons.client;

import com.minescript.addons.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MinescriptErrorHandler {
    public static String lastError = null;
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private static final StringBuilder errorBuffer = new StringBuilder();
    private static ScheduledFuture<?> pendingSend;
    private static boolean handlingMessage = false;
    // Coalescing: flush only via the scheduler (never on intermediate lines).
    // A flush prints the prompt only when the burst looks finished (job-end
    // marker, closed tail, or max hold); unfinished bursts accumulate silently
    // and re-arm, so clumped pipe output can't split one traceback into many
    // prompts. Rate-limiting is per job: separate jobs always get their own
    // prompt and copy buffer.
    private static final long FLUSH_DELAY_MILLIS = 1500;
    private static final long MAX_HOLD_MILLIS = 8000;
    private static final long PER_JOB_COOLDOWN_MILLIS = 2000;
    private static final int MAX_COPIED_CHARS = 8000;
    private static final ConcurrentHashMap<String, Long> lastPromptByJob = new ConcurrentHashMap<>();
    private static volatile String currentJob = "";
    private static volatile long burstStartTime = 0;
    private static volatile String heldError = null;
    private static final Pattern JOB_STATUS_RE =
        Pattern.compile("(?:\\[(\\d+)\\]\\s*(?:Running|Exited))|(?:[Ii]n\\s+job\\s+(\\d+))");
    // Traceback frames name the script: File "...\minescript\exchange.py", line N.
    // Job IDs recycle (usually back to 1), so the script name is the reliable
    // separator between different errors.
    private static final Pattern SCRIPT_FILE_RE =
        Pattern.compile("minescript[\\\\/]([\\w\\- ]+)\\.pyj?");

    public static void register() {
    }

    public static void onChatMessage(Component message) {
        if (handlingMessage) return;
        handlingMessage = true;
        try {
            String text = message.getString();

            // Note: deliberately no flush on unrelated lines. Tracebacks are
            // often interleaved with non-error lines (job status, blanks);
            // flushing on those is what printed a copy prompt every few lines.
            // The scheduler below flushes once the burst goes quiet.
            trackJobContext(text);
            if (!isMinescriptRelated(text)) {
                return;
            }

            if (isErrorText(text) || isContinuationLine(text)) {
                if (isErrorText(text) && !errorBuffer.isEmpty() && bufferHasJobEnd()) {
                    // The buffered burst already closed with a job-end marker,
                    // so this line starts a new error. Flush first so the two
                    // are never combined into one prompt/copy, even when both
                    // arrive inside a single flush window.
                    flushError();
                }
                if (errorBuffer.isEmpty()) {
                    errorBuffer.append(text);
                    if (heldError == null) {
                        burstStartTime = System.currentTimeMillis();
                    }
                } else {
                    errorBuffer.append("\n").append(text);
                }

                if (pendingSend != null) {
                    pendingSend.cancel(false);
                }
                pendingSend = SCHEDULER.schedule(MinescriptErrorHandler::flushError, FLUSH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            }
        } finally {
            handlingMessage = false;
        }
    }

    /**
     * Remembers which job the latest status line belongs to (e.g. "[1] Running",
     * "[1] Exited...", "Exception in job 1"), so traceback bodies carrying no
     * job ID can still be attributed to their job.
     */
    private static void trackJobContext(String text) {
        Matcher m = JOB_STATUS_RE.matcher(text);
        if (m.find()) {
            currentJob = m.group(1) != null ? m.group(1) : m.group(2);
        }
    }

    /**
     * Resolves the grouping key for a flushed burst. Script name first (job IDs
     * recycle, so "[1] test" and "[1] exchange" would otherwise collide), then
     * job ID, else the latest job seen.
     */
    private static String resolveJobKey(String fullError) {
        Matcher s = SCRIPT_FILE_RE.matcher(fullError);
        if (s.find()) {
            return "script:" + s.group(1);
        }
        Matcher m = JOB_STATUS_RE.matcher(fullError);
        if (m.find()) {
            return "job:" + (m.group(1) != null ? m.group(1) : m.group(2));
        }
        Matcher b = Pattern.compile("\\[(\\d+)\\]").matcher(fullError);
        if (b.find()) {
            return "job:" + b.group(1);
        }
        return currentJob.isEmpty() ? "" : "job:" + currentJob;
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

    /**
     * Continuation lines inside a single traceback that carry no error keyword
     * themselves (carets, truncated frames, chained-exception headers). Only
     * recognized while a buffer is open, so normal chat is never swallowed.
     * Joining them keeps one traceback in one buffer (one prompt) and keeps
     * the copied text complete.
     */
    private static boolean isContinuationLine(String text) {
        if (errorBuffer.isEmpty()) return false;
        String stripped = text.stripLeading();
        if (stripped.startsWith("^^^")) return true;
        if (stripped.startsWith("...")) return true;
        if (text.contains("another exception occurred")) return true;
        if (text.contains("direct cause of the following exception")) return true;
        return false;
    }

    /** Whether the open buffer already contains a job-end marker. */
    private static boolean bufferHasJobEnd() {
        return errorBuffer.indexOf("Exited with error code") >= 0
            || errorBuffer.indexOf("Exception in job") >= 0;
    }

    /**
     * Whether a drained burst looks finished. Job-end markers always close a
     * burst. Otherwise a tail that looks like mid-traceback (indented frame /
     * source / caret line, or a chained-exception header) means more lines may
     * still arrive. Anything else (lone warning, "no command", ...) prompts
     * right away instead of hanging the prompt on the max hold.
     */
    private static boolean isBurstComplete(String text) {
        if (text.contains("Exited with error code")) return true;
        if (text.contains("Exception in job")) return true;
        String tail = text.substring(text.lastIndexOf('\n') + 1);
        if (tail.matches("\\s+.*")) return false;
        String stripped = tail.stripLeading();
        return !(stripped.startsWith("File \"")
            || stripped.startsWith("Traceback")
            || stripped.startsWith("^^^")
            || stripped.startsWith("...")
            || stripped.contains("another exception occurred")
            || stripped.contains("direct cause of the following exception"));
    }

    private static void flushError() {
        if (pendingSend != null) {
            pendingSend.cancel(false);
            pendingSend = null;
        }

        if (errorBuffer.isEmpty()) return;

        String fullError = errorBuffer.toString();
        errorBuffer.setLength(0);

        long now = System.currentTimeMillis();
        String combined = heldError == null ? fullError : heldError + "\n" + fullError;
        String jobKey = resolveJobKey(combined);
        boolean recent =
            now - lastPromptByJob.getOrDefault(jobKey, 0L) < PER_JOB_COOLDOWN_MILLIS;
        boolean complete = isBurstComplete(combined);
        boolean holdExpired = now - burstStartTime >= MAX_HOLD_MILLIS;

        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            boolean autoCopy = ModConfig.load().isAutoCopyToClipboard();
            String jobIdSuffix = extractJobId(combined);
            client.execute(() -> {
                if (recent) {
                    // Same job re-flushed inside the window: one traceback split
                    // across flushes (held text is already published, so append
                    // only the new piece). No new prompt.
                    if (lastError == null || lastError.isEmpty()) {
                        lastError = fullError;
                    } else {
                        lastError = lastError + "\n" + fullError;
                        if (lastError.length() > MAX_COPIED_CHARS) {
                            lastError = lastError.substring(lastError.length() - MAX_COPIED_CHARS);
                        }
                    }
                    heldError = null;
                    return;
                }
                if (!complete && !holdExpired) {
                    // Burst still open: hold the prompt, publish the partial
                    // text silently so copy/auto-copy track the burst live,
                    // and re-arm to re-check when more lines arrive (or hold
                    // expires).
                    heldError = combined;
                    lastError = combined;
                    if (lastError.length() > MAX_COPIED_CHARS) {
                        lastError = lastError.substring(lastError.length() - MAX_COPIED_CHARS);
                    }
                    if (autoCopy) {
                        client.keyboardHandler.setClipboard(lastError);
                    }
                    pendingSend = SCHEDULER.schedule(MinescriptErrorHandler::flushError, FLUSH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
                    return;
                }
                // Finished burst (or hold expired): fresh prompt, fresh buffer.
                // Never merged across jobs.
                heldError = null;
                burstStartTime = 0;
                lastError = combined;
                if (autoCopy) {
                    client.keyboardHandler.setClipboard(lastError);
                }
                lastPromptByJob.put(jobKey, now);
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
                        .append(Component.literal(jobIdSuffix + " to copy error to clipboard").withStyle(ChatFormatting.GOLD))
                );
            });
        }
    }

    public static void shutdown() {
        SCHEDULER.shutdown();
    }

    private static String extractJobId(String error) {
        Pattern pattern = Pattern.compile("Exception in job\\s+(\\d+)");
        Matcher matcher = pattern.matcher(error);
        if (matcher.find()) {
            return " [Job " + matcher.group(1) + "]";
        }
        pattern = Pattern.compile("Exited with error code\\s+\\d+\\s+in job\\s+(\\d+)");
        matcher = pattern.matcher(error);
        if (matcher.find()) {
            return " [Job " + matcher.group(1) + "]";
        }
        return "";
    }
}
