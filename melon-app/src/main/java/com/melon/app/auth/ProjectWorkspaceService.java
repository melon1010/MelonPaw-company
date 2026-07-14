package com.melon.app.auth;

import com.melon.core.config.ConfigManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** File-backed, project-scoped documents stored outside every personal Agent workspace. */
@Service
public class ProjectWorkspaceService {

    static final int MAX_DOCUMENT_BYTES = 1024 * 1024;

    private static final List<String> DOCUMENTS = List.of(
            "PROJECT.md", "KNOWLEDGE.md", "POLICY.md"
    );

    private final ConfigManager configManager;

    public ProjectWorkspaceService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public List<Map<String, Object>> list(String projectId) throws IOException {
        Path workspace = initialize(projectId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : DOCUMENTS) {
            Path file = requireRegularDocument(workspace.resolve(name));
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("name", name);
            document.put("content", Files.readString(file, StandardCharsets.UTF_8));
            document.put("updated_at", Files.getLastModifiedTime(file).toInstant().toString());
            result.add(document);
        }
        return result;
    }

    public Map<String, Object> write(String projectId, String name, String content) throws IOException {
        String safeName = safeDocumentName(name);
        Path workspace = initialize(projectId);
        Path file = workspace.resolve(safeName).normalize();
        if (!file.startsWith(workspace)) {
            throw new IllegalArgumentException("Invalid project document path");
        }
        if (Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("Project documents cannot be symbolic links");
        }
        String value = content == null ? "" : content;
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Project document exceeds the 1 MiB limit");
        }
        writeAtomically(workspace, file, value);
        return Map.of(
                "name", safeName,
                "content", Files.readString(file, StandardCharsets.UTF_8),
                "updated_at", Files.getLastModifiedTime(file).toInstant().toString()
        );
    }

    public Path workspaceDir(String projectId) {
        String safeProjectId = safeProjectId(projectId);
        Path root = configManager.resolveHomeDir().toAbsolutePath().normalize();
        Path projectsRoot = root.resolve("project-workspaces").normalize();
        Path workspace = projectsRoot.resolve(safeProjectId).normalize();
        if (!workspace.startsWith(projectsRoot) || workspace.equals(projectsRoot)) {
            throw new IllegalArgumentException("Invalid project workspace");
        }
        return workspace;
    }

    private Path initialize(String projectId) throws IOException {
        Path workspace = workspaceDir(projectId);
        Path projectsRoot = workspace.getParent();
        Files.createDirectories(projectsRoot);
        requireDirectory(projectsRoot, "Project workspace root");
        if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(workspace)) {
            throw new IllegalArgumentException("Project workspace cannot be a symbolic link");
        }
        Files.createDirectories(workspace);
        requireDirectory(workspace, "Project workspace");
        createIfMissing(workspace.resolve("PROJECT.md"), "# Project\n");
        createIfMissing(workspace.resolve("KNOWLEDGE.md"), "# Knowledge\n");
        createIfMissing(workspace.resolve("POLICY.md"), "# Policy\n");
        return workspace;
    }

    private void createIfMissing(Path file, String content) throws IOException {
        if (Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("Project documents cannot be symbolic links");
        }
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
            // Another request initialized the same project first.
        }
        requireRegularDocument(file);
    }

    private String safeDocumentName(String name) {
        String value = name == null ? "" : name.trim();
        return DOCUMENTS.stream()
                .filter(document -> document.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported project document"));
    }

    private String safeProjectId(String projectId) {
        String value = projectId == null ? "" : projectId.trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("Invalid project id");
        }
        return value;
    }

    private Path requireRegularDocument(Path file) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Invalid project document file");
        }
        if (Files.size(file) > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Project document exceeds the 1 MiB limit");
        }
        return file;
    }

    private void requireDirectory(Path directory, String label) {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " must be a regular directory");
        }
    }

    private void writeAtomically(Path workspace, Path file, String content) throws IOException {
        Path temporary = Files.createTempFile(workspace, ".project-document-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
