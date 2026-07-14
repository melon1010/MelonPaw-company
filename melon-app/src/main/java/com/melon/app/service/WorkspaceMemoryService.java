package com.melon.app.service;

import com.melon.core.config.ConfigManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Safe access to the Harness daily-memory directory of a managed agent. */
@Service
public class WorkspaceMemoryService {

    private static final int MAX_MEMORY_BYTES = 1024 * 1024;
    private static final int MAX_SCAN_DEPTH = 8;

    private final ConfigManager configManager;
    private final FileGuardService fileGuardService;

    public WorkspaceMemoryService(ConfigManager configManager, FileGuardService fileGuardService) {
        this.configManager = configManager;
        this.fileGuardService = fileGuardService;
    }

    public List<Map<String, Object>> list(String agentId) throws IOException {
        Path root = memoryRoot(agentId);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        try (var stream = Files.walk(root, MAX_SCAN_DEPTH)) {
            for (Path file : stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .toList()) {
                fileGuardService.assertAllowed(file);
                String relative = root.relativize(file).toString().replace('\\', '/');
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("filename", relative);
                info.put("path", relative);
                info.put("size", Files.size(file));
                info.put("created_time", Files.getLastModifiedTime(file).toInstant().toString());
                info.put("modified_time", Files.getLastModifiedTime(file).toInstant().toString());
                result.add(info);
            }
        }
        return result;
    }

    public Map<String, Object> read(String agentId, String memoryPath) throws IOException {
        Path root = memoryRoot(agentId);
        Path file = resolveMemoryFile(root, memoryPath);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new java.nio.file.NoSuchFileException(memoryPath);
        }
        return Map.of("content", Files.readString(file, StandardCharsets.UTF_8));
    }

    public Map<String, Object> write(String agentId, String memoryPath, String content) throws IOException {
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MEMORY_BYTES) {
            throw new IllegalArgumentException("Memory document exceeds the 1 MiB limit");
        }
        Path root = memoryRoot(agentId);
        Files.createDirectories(root);
        Path file = resolveMemoryFile(root, memoryPath);
        Files.createDirectories(file.getParent());
        assertNoSymlink(root, file);
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
        String relative = root.relativize(file).toString().replace('\\', '/');
        return Map.of("path", relative, "saved", true, "enabled", true);
    }

    private Path memoryRoot(String agentId) {
        return configManager.resolveWorkspaceDir(agentId).toAbsolutePath().normalize().resolve("memory");
    }

    private Path resolveMemoryFile(Path root, String memoryPath) {
        String clean = memoryPath == null ? "" : memoryPath.replace('\\', '/').trim();
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.isBlank()) throw new IllegalArgumentException("Memory path is required");
        Path relative = Path.of(clean).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || !clean.endsWith(".md")) {
            throw new IllegalArgumentException("Invalid memory path");
        }
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("Invalid memory path");
        fileGuardService.assertAllowed(file);
        assertNoSymlink(root, file);
        return file;
    }

    private void assertNoSymlink(Path root, Path file) {
        Path current = root;
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Memory path cannot contain symbolic links");
            }
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
