package com.minescript.addons.manager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Hashing helper for update detection.
 * Local files are hashed with SHA-256 (hex).
 * Remote files are tracked by the Git blob sha from the GitHub contents API
 * (fast path, no download needed) with SHA-256 fallback when sha is missing.
 */
public class FileHasher {
    private static final Logger LOGGER = LoggerFactory.getLogger("minescript-addons");

    public static String sha256(Path file) {
        if (file == null || !Files.isRegularFile(file)) return "";
        try (InputStream in = Files.newInputStream(file)) {
            return sha256(in);
        } catch (IOException e) {
            LOGGER.warn("Failed to hash {}: {}", file, e.getMessage());
            return "";
        }
    }

    public static String sha256(byte[] bytes) {
        if (bytes == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(bytes));
        } catch (Exception e) {
            LOGGER.warn("Failed to hash bytes: {}", e.getMessage());
            return "";
        }
    }

    public static String sha256(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                digest.update(buffer, 0, len);
            }
            return toHex(digest.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("Failed to hash stream: {}", e.getMessage());
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
