package com.melon.app.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.melon.app.config.McpExecutionProperties;
import com.melon.core.util.JsonUtils;
import com.melon.core.util.SafePathUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Projects trusted local MCP artifacts into an Agent workspace before sandbox execution.
 * The source directory is never mounted into an MCP container.
 */
@Component
public class McpArtifactImporter {

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", "node_modules", ".venv", "venv", "__pycache__");
    private static final Set<String> PROJECT_MARKERS = Set.of(
            "package.json", "pyproject.toml", "requirements.txt", "uv.lock",
            "Cargo.toml", "go.mod", "pom.xml", "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts");
    private static final List<Path> CONTAINER_PATH_PREFIXES = List.of(
            Path.of("/workspace"), Path.of("/usr"), Path.of("/opt"), Path.of("/bin"),
            Path.of("/sbin"), Path.of("/lib"), Path.of("/app"), Path.of("/tmp"),
            Path.of("/home/melon"));
    private static final ConcurrentHashMap<Path, Object> IMPORT_LOCKS = new ConcurrentHashMap<>();
    private final String configuredImportRoots;
    private final String configuredPathMappings;
    private final long configuredImportMaxBytes;
    private final int configuredImportMaxFiles;

    public McpArtifactImporter() {
        this(null, null, 256L * 1024L * 1024L, 20_000);
    }

    @Autowired
    McpArtifactImporter(
            McpExecutionProperties properties,
            @Value("${melon.execution.mcp-import-roots:}") String legacyImportRoots,
            @Value("${melon.execution.mcp-path-mappings:}") String legacyPathMappings) {
        this(prefer(properties.getImport().getRoots(), legacyImportRoots),
                prefer(properties.getImport().getPathMappings(), legacyPathMappings),
                properties.getImport().getMaxBytes(), properties.getImport().getMaxFiles());
    }

    McpArtifactImporter(String configuredImportRoots, String configuredPathMappings) {
        this(configuredImportRoots, configuredPathMappings, 256L * 1024L * 1024L, 20_000);
    }

    McpArtifactImporter(String configuredImportRoots, String configuredPathMappings,
                        long configuredImportMaxBytes, int configuredImportMaxFiles) {
        this.configuredImportRoots = configuredImportRoots;
        this.configuredPathMappings = configuredPathMappings;
        this.configuredImportMaxBytes = configuredImportMaxBytes;
        this.configuredImportMaxFiles = configuredImportMaxFiles;
    }

    private static String prefer(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    public PreparedMcp resolve(McpClientManager.McpServerConfig original, Path workspace)
            throws IOException {
        McpClientManager.McpServerConfig runtime = copyOf(original);
        if (workspace == null) {
            for (PathReference reference : references(runtime)) {
                Path value = absolutePath(reference.value());
                if (value != null && !isContainerPath(value)) {
                    throw new IllegalArgumentException(
                            "Host paths require an Agent workspace before stdio MCP can run in Docker: "
                                    + reference.value());
                }
            }
            return PreparedMcp.direct(runtime);
        }

        Path workspaceRoot = workspace.toAbsolutePath().normalize();
        Files.createDirectories(workspaceRoot);
        List<Path> trustedRoots = trustedRoots();
        applyPathMappings(runtime, trustedRoots);
        List<PathReference> references = references(runtime);
        List<PathReference> external = new ArrayList<>();
        for (PathReference reference : references) {
            Path value = absolutePath(reference.value());
            if (value == null) continue;
            if (SafePathUtil.isWithinDir(value, workspaceRoot)) {
                reference.write(runtime, containerPath(workspaceRoot, value));
                continue;
            }
            if (Files.exists(value, LinkOption.NOFOLLOW_LINKS)
                    && isWithinTrustedRoot(value, trustedRoots)) {
                external.add(reference);
                continue;
            }
            if (isContainerPath(value)) continue;
            if (!Files.exists(value, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "stdio MCP contains an absolute path that is unavailable to the Java service: "
                                + reference.value() + ". Mount its trusted parent read-only into the Java "
                                + "container and add that parent to MELON_MCP_IMPORT_ROOTS.");
            }
            external.add(reference);
        }
        if (external.isEmpty()) return PreparedMcp.direct(runtime);

        if (trustedRoots.isEmpty()) {
            throw new IllegalArgumentException(
                    "Local stdio MCP artifacts are disabled. Configure "
                            + "melon.execution.mcp.import.roots or MELON_MCP_IMPORT_ROOTS with one or "
                            + "more trusted source directories, then restart the Java service.");
        }

        SourceSelection selection = selectSource(original.getCommand(), external, trustedRoots);
        ImportedArtifact imported = importArtifact(original.getId(), selection, workspaceRoot);
        for (PathReference reference : external) {
            Path sourcePath = secureRealPath(absolutePath(reference.value()), selection.trustedRoot());
            if (!selection.contains(sourcePath)) {
                throw new IllegalArgumentException(
                        "A stdio MCP configuration may import only one local project. Move additional "
                                + "inputs into the Agent workspace: " + reference.value());
            }
            reference.write(runtime, imported.containerPathFor(sourcePath));
        }
        if (runtime.getCwd() == null || runtime.getCwd().isBlank()) {
            runtime.setCwd(imported.containerWorkingDirectory());
        }

        PreparationKind preparationKind = preparationKind(original.getCommand(), selection.sourceRoot());
        if (preparationKind.isPython()
                && executableName(original.getCommand()).matches("python(?:3(?:\\.\\d+)?)?")) {
            runtime.setCommand(imported.containerWorkingDirectory() + "/.venv/bin/python");
        }
        return new PreparedMcp(runtime, preparationKind, imported.destinationRoot(),
                imported.containerWorkingDirectory(), imported.contentHash());
    }

    private SourceSelection selectSource(String command, List<PathReference> references,
                                         List<Path> trustedRoots) throws IOException {
        List<Path> paths = new ArrayList<>(references.size());
        for (PathReference reference : references) {
            paths.add(secureRealPath(absolutePath(reference.value()), trustedRoots));
        }
        Path first = paths.get(0);
        Path trustedRoot = containingRoot(first, trustedRoots);
        for (Path path : paths) {
            if (!path.startsWith(trustedRoot)) {
                throw new IllegalArgumentException("stdio MCP host paths must use one trusted import root");
            }
        }
        String executable = executableName(command);
        Set<String> preferredMarkers = markerPreference(executable);
        Path sourceRoot = findProjectRoot(first, trustedRoot, preferredMarkers);
        if (sourceRoot == null && !preferredMarkers.isEmpty()) {
            sourceRoot = findProjectRoot(first, trustedRoot, PROJECT_MARKERS);
        }
        if (sourceRoot == null) {
            sourceRoot = paths.stream()
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(directory -> paths.stream().allMatch(path -> path.startsWith(directory)))
                    .max(Comparator.comparingInt(Path::getNameCount))
                    .orElse(first);
        }
        SourceSelection selection = new SourceSelection(sourceRoot, trustedRoot);
        for (int index = 0; index < references.size(); index++) {
            Path path = paths.get(index);
            if (!selection.contains(path)) {
                throw new IllegalArgumentException(
                        "stdio MCP host paths must belong to one project: " + references.get(index).value()
                                + ". Set cwd to the project directory when no project manifest exists.");
            }
        }
        return selection;
    }

    private Path findProjectRoot(Path source, Path trustedRoot, Set<String> markers) {
        if (markers.isEmpty()) return null;
        Path current = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) ? source : source.getParent();
        while (current != null && current.startsWith(trustedRoot)) {
            for (String marker : markers) {
                if (Files.isRegularFile(current.resolve(marker), LinkOption.NOFOLLOW_LINKS)) return current;
            }
            if (current.equals(trustedRoot)) break;
            current = current.getParent();
        }
        return null;
    }

    private Set<String> markerPreference(String executable) {
        if (Set.of("node", "npm", "npx", "bun", "bunx").contains(executable)) {
            return Set.of("package.json");
        }
        if (executable.matches("python(?:3(?:\\.\\d+)?)?")
                || Set.of("uv", "uvx", "pip", "pip3").contains(executable)) {
            return Set.of("pyproject.toml", "requirements.txt", "uv.lock");
        }
        return Set.of();
    }

    private PreparationKind preparationKind(String command, Path sourceRoot) {
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) return PreparationKind.NONE;
        String executable = executableName(command);
        if (Set.of("node", "npm", "npx", "bun", "bunx").contains(executable)
                && Files.isRegularFile(sourceRoot.resolve("package.json"))) {
            return PreparationKind.NODE;
        }
        if (executable.matches("python(?:3(?:\\.\\d+)?)?")
                || Set.of("uv", "uvx").contains(executable)) {
            if (Files.isRegularFile(sourceRoot.resolve("pyproject.toml"))) {
                return PreparationKind.PYTHON_UV;
            }
            if (Files.isRegularFile(sourceRoot.resolve("requirements.txt"))) {
                return PreparationKind.PYTHON_REQUIREMENTS;
            }
        }
        return PreparationKind.NONE;
    }

    private ImportedArtifact importArtifact(String serverId, SourceSelection selection, Path workspaceRoot)
            throws IOException {
        ScannedArtifact source = scan(selection.sourceRoot());
        String hash = hex(source.digest()).substring(0, 24);
        Path serverDirectory = workspaceRoot.resolve(".melon").resolve("mcp-sources")
                .resolve(safeServerId(serverId));
        Path destination = serverDirectory.resolve(hash);
        Object lock = IMPORT_LOCKS.computeIfAbsent(destination, ignored -> new Object());
        try {
            synchronized (lock) {
                Path ready = destination.resolve(".melon-import-complete");
                if (!Files.isRegularFile(ready)) {
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        deleteRecursively(destination);
                    }
                    Files.createDirectories(serverDirectory);
                    Path temporary = serverDirectory.resolve(".import-" + UUID.randomUUID());
                    try {
                        Files.createDirectories(temporary);
                        copyScanned(source, temporary);
                        ScannedArtifact copied = scanCopied(temporary);
                        if (!MessageDigest.isEqual(source.digest(), copied.digest())) {
                            throw new IOException("Local MCP artifact changed while it was being imported");
                        }
                        Files.writeString(temporary.resolve(".melon-import-complete"), hash,
                                StandardCharsets.UTF_8);
                        try {
                            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                        } catch (java.nio.file.FileAlreadyExistsException ignored) {
                            deleteRecursively(temporary);
                            if (!Files.isRegularFile(ready)) {
                                throw new IOException("Concurrent MCP artifact import did not complete");
                            }
                        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                            Files.move(temporary, destination);
                        }
                    } catch (IOException | RuntimeException e) {
                        deleteRecursively(temporary);
                        throw e;
                    }
                }
            }
        } finally {
            IMPORT_LOCKS.remove(destination, lock);
        }
        Path mappedRoot = Files.isDirectory(selection.sourceRoot(), LinkOption.NOFOLLOW_LINKS)
                ? destination : destination.resolve(selection.sourceRoot().getFileName().toString());
        Path workingRoot = Files.isDirectory(selection.sourceRoot(), LinkOption.NOFOLLOW_LINKS)
                ? mappedRoot : destination;
        return new ImportedArtifact(selection.sourceRoot(), destination, mappedRoot,
                containerPath(workspaceRoot, workingRoot), hash, workspaceRoot);
    }

    private ScannedArtifact scan(Path sourceRoot) throws IOException {
        long maxBytes = positiveLongSetting("MELON_MCP_IMPORT_MAX_BYTES", configuredImportMaxBytes);
        int maxFiles = (int) Math.min(Integer.MAX_VALUE,
                positiveLongSetting("MELON_MCP_IMPORT_MAX_FILES", configuredImportMaxFiles));
        Path base = Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)
                ? sourceRoot : sourceRoot.getParent();
        List<ArtifactEntry> entries = new ArrayList<>();
        long[] bytes = {0L};
        int[] files = {0};
        Files.walkFileTree(sourceRoot, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(sourceRoot) && excludedDirectory(dir.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (attrs.isSymbolicLink()) {
                            throw new IllegalArgumentException("Symbolic links are not allowed in MCP artifacts: " + dir);
                        }
                        if (!dir.equals(sourceRoot)) entries.add(new ArtifactEntry(dir, base.relativize(dir), true));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
                            throw new IllegalArgumentException("Symbolic links are not allowed in MCP artifacts: " + file);
                        }
                        if (!attrs.isRegularFile()) {
                            throw new IllegalArgumentException("Only regular files are allowed in MCP artifacts: " + file);
                        }
                        if (excludedFile(file.getFileName().toString())) {
                            if (file.equals(sourceRoot)) {
                                throw new IllegalArgumentException(
                                        "Sensitive environment files cannot be imported as MCP artifacts");
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        if (++files[0] > maxFiles) {
                            throw new IllegalArgumentException("MCP artifact exceeds MELON_MCP_IMPORT_MAX_FILES");
                        }
                        bytes[0] = Math.addExact(bytes[0], attrs.size());
                        if (bytes[0] > maxBytes) {
                            throw new IllegalArgumentException("MCP artifact exceeds MELON_MCP_IMPORT_MAX_BYTES");
                        }
                        entries.add(new ArtifactEntry(file, base.relativize(file), false));
                        return FileVisitResult.CONTINUE;
                    }
                });
        entries.sort(Comparator.comparing(entry -> portable(entry.relative())));
        return new ScannedArtifact(entries, digest(entries, maxBytes), maxBytes);
    }

    private ScannedArtifact scanCopied(Path destination) throws IOException {
        return scan(destination);
    }

    private byte[] digest(List<ArtifactEntry> entries, long maxBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] buffer = new byte[64 * 1024];
        long totalBytes = 0L;
        for (ArtifactEntry entry : entries) {
            digest.update((entry.directory() ? "D\0" : "F\0").getBytes(StandardCharsets.UTF_8));
            digest.update(portable(entry.relative()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (entry.directory()) continue;
            try (InputStream input = Files.newInputStream(entry.source(), LinkOption.NOFOLLOW_LINKS)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        totalBytes = Math.addExact(totalBytes, read);
                        if (totalBytes > maxBytes) {
                            throw new IllegalArgumentException("MCP artifact exceeds MELON_MCP_IMPORT_MAX_BYTES");
                        }
                        digest.update(buffer, 0, read);
                    }
                }
            }
        }
        return digest.digest();
    }

    private void copyScanned(ScannedArtifact artifact, Path destination) throws IOException {
        long totalBytes = 0L;
        byte[] buffer = new byte[64 * 1024];
        for (ArtifactEntry entry : artifact.entries()) {
            Path target = destination.resolve(entry.relative()).normalize();
            if (!target.startsWith(destination)) throw new SecurityException("MCP artifact path traversal detected");
            if (entry.directory()) {
                Files.createDirectories(target);
                setDirectoryPermissions(target);
            } else {
                Files.createDirectories(target.getParent());
                try (InputStream input = Files.newInputStream(
                        entry.source(), LinkOption.NOFOLLOW_LINKS);
                     OutputStream output = Files.newOutputStream(
                             target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        totalBytes = Math.addExact(totalBytes, read);
                        if (totalBytes > artifact.maxBytes()) {
                            throw new IllegalArgumentException(
                                    "MCP artifact exceeds MELON_MCP_IMPORT_MAX_BYTES during import");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                setFilePermissions(entry.source(), target);
            }
        }
    }

    private void setDirectoryPermissions(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }

    private void setFilePermissions(Path source, Path target) {
        try {
            Set<PosixFilePermission> sourcePermissions = Files.getPosixFilePermissions(source);
            Set<PosixFilePermission> permissions = new HashSet<>(
                    PosixFilePermissions.fromString("rw-r--r--"));
            if (sourcePermissions.contains(PosixFilePermission.OWNER_EXECUTE)
                    || sourcePermissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || sourcePermissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
                permissions.add(PosixFilePermission.GROUP_EXECUTE);
                permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            }
            Files.setPosixFilePermissions(target, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private List<PathReference> references(McpClientManager.McpServerConfig config) {
        List<PathReference> references = new ArrayList<>();
        references.add(PathReference.command(config.getCommand()));
        List<String> args = config.getArgs() == null ? List.of() : config.getArgs();
        for (int index = 0; index < args.size(); index++) {
            references.add(PathReference.argument(index, args.get(index)));
        }
        if (config.getCwd() != null && !config.getCwd().isBlank()) {
            references.add(PathReference.cwd(config.getCwd()));
        }
        return references;
    }

    private List<Path> trustedRoots() throws IOException {
        String configured = setting("MELON_MCP_IMPORT_ROOTS");
        if (configured == null || configured.isBlank()) return List.of();
        List<Path> roots = new ArrayList<>();
        for (String item : configured.split(Pattern.quote(File.pathSeparator))) {
            if (item == null || item.isBlank()) continue;
            Path root = expandHome(item.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                throw new IllegalArgumentException("MELON_MCP_IMPORT_ROOTS contains an invalid directory: " + item);
            }
            if (root.getParent() == null) {
                throw new IllegalArgumentException("MELON_MCP_IMPORT_ROOTS cannot contain a filesystem root");
            }
            roots.add(root.toRealPath());
        }
        return roots.stream().distinct().toList();
    }

    private void applyPathMappings(McpClientManager.McpServerConfig runtime,
                                   List<Path> trustedRoots) throws IOException {
        List<PathMapping> mappings = pathMappings(trustedRoots);
        if (mappings.isEmpty()) return;
        for (PathReference reference : references(runtime)) {
            Path sourcePath = absolutePath(reference.value());
            if (sourcePath == null) continue;
            PathMapping mapping = mappings.stream()
                    .filter(candidate -> sourcePath.startsWith(candidate.sourceRoot()))
                    .findFirst()
                    .orElse(null);
            if (mapping == null) continue;
            Path mapped = mapping.targetRoot()
                    .resolve(mapping.sourceRoot().relativize(sourcePath)).normalize();
            if (!mapped.startsWith(mapping.targetRoot())) {
                throw new SecurityException("MCP path mapping escaped its target root: " + sourcePath);
            }
            if (!Files.exists(mapped, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "stdio MCP path mapping resolved to an unavailable path: "
                                + sourcePath + " -> " + mapped);
            }
            Path real = secureRealPath(mapped, trustedRoots);
            reference.write(runtime, real.toString());
        }
    }

    private List<PathMapping> pathMappings(List<Path> trustedRoots) throws IOException {
        String configured = setting("MELON_MCP_PATH_MAPPINGS");
        if (configured == null || configured.isBlank()) return List.of();
        if (trustedRoots.isEmpty()) {
            throw new IllegalArgumentException(
                    "MELON_MCP_PATH_MAPPINGS requires MELON_MCP_IMPORT_ROOTS");
        }

        Map<String, String> configuredMappings;
        try {
            configuredMappings = JsonUtils.getMapper().readValue(configured,
                    new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException(
                    "MELON_MCP_PATH_MAPPINGS must be a JSON object of source and target path prefixes", e);
        }
        if (configuredMappings.isEmpty()) return List.of();

        List<PathMapping> mappings = new ArrayList<>(configuredMappings.size());
        for (Map.Entry<String, String> entry : configuredMappings.entrySet()) {
            Path source = configuredPath(entry.getKey(), "source");
            Path target = configuredPath(entry.getValue(), "target");
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                throw new IllegalArgumentException(
                        "MELON_MCP_PATH_MAPPINGS contains an invalid target directory: " + entry.getValue());
            }
            mappings.add(new PathMapping(source, secureRealPath(target, trustedRoots)));
        }
        mappings.sort(Comparator.comparingInt((PathMapping mapping) ->
                mapping.sourceRoot().getNameCount()).reversed());
        return List.copyOf(mappings);
    }

    private Path configuredPath(String value, String role) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "MELON_MCP_PATH_MAPPINGS contains a blank " + role + " path");
        }
        Path path;
        try {
            Path configured = expandHome(value.trim());
            if (!configured.isAbsolute()) {
                throw new IllegalArgumentException("path must be absolute");
            }
            path = configured.normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "MELON_MCP_PATH_MAPPINGS contains an invalid " + role + " path: " + value, e);
        }
        if (path.getParent() == null) {
            throw new IllegalArgumentException(
                    "MELON_MCP_PATH_MAPPINGS cannot use a filesystem root as a " + role);
        }
        return path;
    }

    private Path secureRealPath(Path path, List<Path> trustedRoots) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path real = normalized.toRealPath();
        Path root = containingRoot(real, trustedRoots);
        SafePathUtil.rejectSymbolicLinks(lexicalRoot(normalized, root), normalized);
        if (!real.startsWith(root)) {
            throw new SecurityException("MCP artifact escapes its trusted import root: " + path);
        }
        return real;
    }

    private Path lexicalRoot(Path path, Path realRoot) throws IOException {
        Path current = path;
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(current)
                    && current.toRealPath().equals(realRoot)) {
                return current;
            }
            current = current.getParent();
        }
        throw new SecurityException("Unable to resolve the configured MCP import root for: " + path);
    }

    private boolean isWithinTrustedRoot(Path path, List<Path> roots) {
        try {
            secureRealPath(path, roots);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private Path secureRealPath(Path path, Path trustedRoot) throws IOException {
        return secureRealPath(path, List.of(trustedRoot));
    }

    private Path containingRoot(Path path, List<Path> roots) {
        return roots.stream()
                .filter(path::startsWith)
                .max(Comparator.comparingInt(Path::getNameCount))
                .orElseThrow(() -> new SecurityException(
                        "stdio MCP host path is outside MELON_MCP_IMPORT_ROOTS: " + path));
    }

    private boolean isContainerPath(Path value) {
        Path normalized = value.normalize();
        return CONTAINER_PATH_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    private boolean excludedDirectory(String name) {
        return EXCLUDED_DIRECTORIES.contains(name);
    }

    private boolean excludedFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return ".ds_store".equals(lower)
                || ".env".equals(lower)
                || (lower.startsWith(".env.") && !lower.endsWith(".example") && !lower.endsWith(".sample"));
    }

    private String safeServerId(String id) {
        String value = id == null || id.isBlank() ? "server" : id;
        String safe = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.equals(value) && safe.length() <= 80) return safe;
        String prefix = safe.length() <= 60 ? safe : safe.substring(0, 60);
        return prefix + "-" + shortHash(value);
    }

    private String shortHash(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private Path absolutePath(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? path.toAbsolutePath().normalize() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String executableName(String command) {
        if (command == null || command.isBlank()) return "";
        try {
            Path name = Path.of(command).getFileName();
            return (name == null ? command : name.toString()).toLowerCase(Locale.ROOT);
        } catch (RuntimeException ignored) {
            return command.toLowerCase(Locale.ROOT);
        }
    }

    private String containerPath(Path workspace, Path hostPath) {
        if (!SafePathUtil.isWithinDir(hostPath, workspace)) {
            throw new SecurityException("Imported MCP artifact escaped the Agent workspace");
        }
        String suffix = portable(workspace.toAbsolutePath().normalize().relativize(
                hostPath.toAbsolutePath().normalize()));
        return suffix.isBlank() ? "/workspace" : "/workspace/" + suffix;
    }

    private String portable(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private long positiveLongSetting(String name, long fallback) {
        String value = setting(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
    }

    private String setting(String name) {
        String value = System.getProperty(name);
        if (value != null && !value.isBlank()) return value;
        value = System.getenv(name);
        if (value != null && !value.isBlank()) return value;
        return switch (name) {
            case "MELON_MCP_IMPORT_ROOTS" -> configuredImportRoots;
            case "MELON_MCP_PATH_MAPPINGS" -> configuredPathMappings;
            default -> null;
        };
    }

    private Path expandHome(String value) {
        if ("~".equals(value)) return Path.of(System.getProperty("user.home"));
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home")).resolve(value.substring(2));
        }
        return Path.of(value);
    }

    private McpClientManager.McpServerConfig copyOf(McpClientManager.McpServerConfig config) {
        return McpClientManager.McpServerConfig.fromMap(config.toMap());
    }

    enum PreparationKind {
        NONE("none"), NODE("node"), PYTHON_UV("python-uv"),
        PYTHON_REQUIREMENTS("python-requirements");

        private final String id;

        PreparationKind(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        boolean isPython() {
            return this == PYTHON_UV || this == PYTHON_REQUIREMENTS;
        }
    }

    record PreparedMcp(McpClientManager.McpServerConfig runtimeConfig,
                       PreparationKind preparationKind,
                       Path artifactDirectory,
                       String containerWorkingDirectory,
                       String contentHash) {
        static PreparedMcp direct(McpClientManager.McpServerConfig config) {
            return new PreparedMcp(config, PreparationKind.NONE, null, null, null);
        }

        boolean requiresPreparation() {
            return preparationKind != PreparationKind.NONE;
        }

        Path preparationMarker() {
            return artifactDirectory.resolve(".melon-runtime-" + preparationKind.id() + "-v1.ready");
        }
    }

    private record PathReference(ReferenceType type, int index, String value, String prefix) {
        static PathReference command(String value) {
            return new PathReference(ReferenceType.COMMAND, -1, value, "");
        }

        static PathReference argument(int index, String value) {
            if (value != null && value.startsWith("--")) {
                int separator = value.indexOf('=');
                if (separator > 2) {
                    String path = value.substring(separator + 1);
                    try {
                        if (Path.of(path).isAbsolute()) {
                            return new PathReference(ReferenceType.ARGUMENT, index, path,
                                    value.substring(0, separator + 1));
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            return new PathReference(ReferenceType.ARGUMENT, index, value, "");
        }

        static PathReference cwd(String value) {
            return new PathReference(ReferenceType.CWD, -1, value, "");
        }

        void write(McpClientManager.McpServerConfig config, String replacement) {
            String value = prefix + replacement;
            switch (type) {
                case COMMAND -> config.setCommand(value);
                case CWD -> config.setCwd(value);
                case ARGUMENT -> {
                    List<String> arguments = new ArrayList<>(config.getArgs());
                    arguments.set(index, value);
                    config.setArgs(arguments);
                }
            }
        }
    }

    private enum ReferenceType { COMMAND, ARGUMENT, CWD }

    private record PathMapping(Path sourceRoot, Path targetRoot) {}

    private record SourceSelection(Path sourceRoot, Path trustedRoot) {
        boolean contains(Path path) {
            return Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)
                    ? path.startsWith(sourceRoot) : path.equals(sourceRoot);
        }
    }

    private record ArtifactEntry(Path source, Path relative, boolean directory) {}

    private record ScannedArtifact(List<ArtifactEntry> entries, byte[] digest, long maxBytes) {}

    private record ImportedArtifact(Path sourceRoot, Path destinationRoot, Path mappedRoot,
                                    String containerWorkingDirectory, String contentHash,
                                    Path workspace) {
        String containerPathFor(Path sourcePath) {
            Path mapped = Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)
                    ? mappedRoot.resolve(sourceRoot.relativize(sourcePath)) : mappedRoot;
            String suffix = mapped.toAbsolutePath().normalize().startsWith(workspace)
                    ? workspace.relativize(mapped.toAbsolutePath().normalize()).toString()
                    : "";
            if (suffix.isBlank()) throw new SecurityException("Imported MCP path escaped its workspace");
            return "/workspace/" + suffix.replace(File.separatorChar, '/');
        }
    }
}
