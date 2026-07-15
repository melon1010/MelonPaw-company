package com.melon.core.util;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 路径安全工具. 防目录穿越.
 */
public class SafePathUtil {

    /**
     * 安全解析相对路径, 防止目录穿越.
     */
    public static Path resolveSafe(Path base, String relative) {
        if (base == null || relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("Base path and relative path are required");
        }
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path resolved = normalizedBase.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedBase)) {
            throw new SecurityException("Path traversal detected: " + relative);
        }
        rejectSymbolicLinks(normalizedBase, resolved);
        return resolved;
    }

    /**
     * 检查路径是否在目录内.
     */
    public static boolean isWithinDir(Path path, Path dir) {
        if (path == null || dir == null) return false;
        Path normalizedDir = dir.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedDir)) return false;
        try {
            rejectSymbolicLinks(normalizedDir, normalizedPath);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Reject existing symlinks below a trusted workspace root, including the target itself. */
    public static void rejectSymbolicLinks(Path base, Path target) {
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedBase)) {
            throw new SecurityException("Path outside workspace: " + target);
        }
        if (Files.exists(normalizedBase, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(normalizedBase)) {
            throw new SecurityException("Workspace root cannot be a symbolic link");
        }
        Path current = normalizedBase;
        for (Path segment : normalizedBase.relativize(normalizedTarget)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new SecurityException("Symbolic links are not allowed in workspace paths: " + current);
            }
        }
    }
}
