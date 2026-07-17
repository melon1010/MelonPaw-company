package com.melon.app.auth;

import com.melon.app.mcp.McpClientRegistry;
import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Captures and atomically restores project documents, skill files and MCP configuration. */
@Service
public class ProjectResourceVersionService {

    private static final long MAX_RESOURCE_BYTES = 32L * 1024L * 1024L;
    private static final int SNAPSHOT_VERSION = 1;

    private final ConfigManager configManager;
    private final ProjectWorkspaceService workspaceService;
    private final McpClientRegistry mcpClientRegistry;

    public ProjectResourceVersionService(ConfigManager configManager,
                                         ProjectWorkspaceService workspaceService,
                                         McpClientRegistry mcpClientRegistry) {
        this.configManager = configManager;
        this.workspaceService = workspaceService;
        this.mcpClientRegistry = mcpClientRegistry;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> capture(String projectId, String agentId) throws IOException {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshot_version", SNAPSHOT_VERSION);
        Map<String, Object> documents = new LinkedHashMap<>();
        for (Map<String, Object> document : workspaceService.list(projectId)) {
            documents.put(String.valueOf(document.get("name")), document.getOrDefault("content", ""));
        }
        snapshot.put("project_documents", documents);
        snapshot.put("skill_files", captureFiles(
                configManager.resolveWorkspaceDir(agentId).resolve("skills")));
        snapshot.put("mcp_policy_files", captureFiles(
                configManager.resolveWorkspaceDir(agentId).resolve(".melon").resolve("mcp-policies")));
        Path mcpFile = mcpFile(agentId);
        Map<String, Object> mcp = Files.isRegularFile(mcpFile, LinkOption.NOFOLLOW_LINKS)
                ? JsonUtils.loadAsMap(mcpFile) : Map.of();
        snapshot.put("mcp", configManager.unprotectSecrets(mcp));
        snapshot.put("content_sha256", sha256(JsonUtils.toJson(snapshot)));
        Object protectedSnapshot = configManager.protectSecrets(snapshot);
        return protectedSnapshot instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    @SuppressWarnings("unchecked")
    public boolean restore(String projectId, String agentId, Map<String, Object> protectedSnapshot) throws IOException {
        if (protectedSnapshot == null || protectedSnapshot.isEmpty()) return false;
        Object decoded = configManager.unprotectSecrets(protectedSnapshot);
        if (!(decoded instanceof Map<?, ?> raw)) return false;
        Map<String, Object> snapshot = new LinkedHashMap<>((Map<String, Object>) raw);
        if (number(snapshot.get("snapshot_version")) != SNAPSHOT_VERSION) return false;
        String expectedHash = snapshot.get("content_sha256") == null
                ? "" : String.valueOf(snapshot.get("content_sha256"));
        if (!expectedHash.isBlank()) {
            Map<String, Object> integrity = new LinkedHashMap<>(snapshot);
            integrity.remove("content_sha256");
            if (!expectedHash.equals(sha256(JsonUtils.toJson(integrity)))) {
                throw new IllegalArgumentException("Project resource snapshot integrity check failed");
            }
        }

        Object documentsRaw = snapshot.get("project_documents");
        if (documentsRaw instanceof Map<?, ?> documents) {
            for (Map.Entry<?, ?> document : documents.entrySet()) {
                workspaceService.write(projectId, String.valueOf(document.getKey()),
                        document.getValue() == null ? "" : String.valueOf(document.getValue()));
            }
        }
        Map<String, Object> files = snapshot.get("skill_files") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
        replaceFiles(configManager.resolveWorkspaceDir(agentId).resolve("skills"), files);

        Map<String, Object> policyFiles = snapshot.get("mcp_policy_files") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
        replaceFiles(configManager.resolveWorkspaceDir(agentId)
                .resolve(".melon").resolve("mcp-policies"), policyFiles);

        Object mcpRaw = snapshot.get("mcp");
        Map<String, Object> mcp = mcpRaw instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of("servers", List.of());
        JsonUtils.save(mcpFile(agentId), configManager.protectSecrets(mcp));
        mcpClientRegistry.reloadAgent(agentId);
        return true;
    }

    private Map<String, Object> captureFiles(Path root) throws IOException {
        Map<String, Object> files = new LinkedHashMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return files;
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Project resource directory must be a regular directory");
        }
        long total = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.toList()) {
                if (Files.isSymbolicLink(file)) {
                    throw new IllegalArgumentException("Project resources cannot contain symbolic links");
                }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                byte[] content = Files.readAllBytes(file);
                total += content.length;
                if (total > MAX_RESOURCE_BYTES) {
                    throw new IllegalArgumentException("Project Agent resource snapshot exceeds 32 MiB");
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                files.put(relative, Base64.getEncoder().encodeToString(content));
            }
        }
        return files;
    }

    private void replaceFiles(Path target, Map<String, Object> encodedFiles) throws IOException {
        Path workspace = configManager.resolveWorkspaceRootDir().toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(workspace)) {
            throw new IllegalArgumentException("Invalid project resource directory");
        }
        Path parent = normalizedTarget.getParent();
        if (parent == null) throw new IllegalArgumentException("Invalid project resource directory");
        Path control = parent.resolve(".resource-versions");
        Files.createDirectories(control);
        Path stage = control.resolve("resource-stage-" + UUID.randomUUID());
        Path stagedFiles = stage.resolve("skills");
        Path backup = control.resolve("resource-backup-" + UUID.randomUUID());
        Files.createDirectories(stagedFiles);
        long total = 0;
        try {
            for (Map.Entry<String, Object> entry : encodedFiles.entrySet()) {
                Path file = safeRelative(stagedFiles, entry.getKey());
                byte[] content;
                try {
                    content = Base64.getDecoder().decode(String.valueOf(entry.getValue()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid project resource snapshot encoding", e);
                }
                total += content.length;
                if (total > MAX_RESOURCE_BYTES) {
                    throw new IllegalArgumentException("Project Agent resource snapshot exceeds 32 MiB");
                }
                Files.createDirectories(file.getParent());
                Files.write(file, content);
            }
            boolean hadTarget = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (hadTarget) move(target, backup);
            try {
                move(stagedFiles, target);
                if (hadTarget) deleteTree(backup);
            } catch (IOException | RuntimeException e) {
                deleteTree(target);
                if (hadTarget && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) move(backup, target);
                throw e;
            }
        } finally {
            deleteTree(stage);
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) deleteTree(backup);
            deleteIfEmpty(control);
        }
    }

    private Path safeRelative(Path root, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Invalid project resource snapshot path");
        Path relative = Path.of(value).normalize();
        Path resolved = root.resolve(relative).normalize();
        if (relative.isAbsolute() || resolved.equals(root) || !resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid project resource snapshot path");
        }
        return resolved;
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private void deleteIfEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> entries = Files.list(directory)) {
            if (entries.findAny().isEmpty()) Files.deleteIfExists(directory);
        }
    }

    private Path mcpFile(String agentId) {
        return configManager.resolveWorkspaceDir(agentId).resolve(".melon").resolve("mcp-clients.json");
    }

    private int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
