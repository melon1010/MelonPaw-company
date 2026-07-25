package com.melon.app.mcp;

import com.melon.app.config.McpExecutionProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpArtifactImporterTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearSettings() {
        for (String name : List.of(
                "MELON_MCP_IMPORT_ROOTS", "MELON_MCP_IMPORT_MAX_BYTES",
                "MELON_MCP_IMPORT_MAX_FILES", "MELON_MCP_PATH_MAPPINGS")) {
            System.clearProperty(name);
        }
    }

    @Test
    void importsNodeProjectIntoAgentWorkspaceWithoutMutatingPersistedConfig() throws Exception {
        Path source = nodeProject();
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        System.setProperty("MELON_MCP_IMPORT_ROOTS", source.getParent().toString());
        Path script = source.resolve("dist/index.js");
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "local-node",
                "command", "node",
                "args", List.of(script.toString()),
                "cwd", source.toString()));

        McpArtifactImporter.PreparedMcp prepared = new McpArtifactImporter().resolve(config, workspace);

        assertEquals(script.toString(), config.getArgs().get(0));
        assertEquals(McpArtifactImporter.PreparationKind.NODE, prepared.preparationKind());
        assertTrue(prepared.runtimeConfig().getArgs().get(0).startsWith(
                "/workspace/.melon/mcp-sources/local-node/"));
        assertTrue(Files.isRegularFile(prepared.artifactDirectory().resolve("dist/index.js")));
        assertFalse(Files.exists(prepared.artifactDirectory().resolve("node_modules")));
        assertFalse(Files.exists(prepared.artifactDirectory().resolve(".env")));
    }

    @Test
    void readsTrustedRootsFromSpringConfiguration() throws Exception {
        Path source = nodeProject();
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        McpExecutionProperties properties = new McpExecutionProperties();
        properties.getImport().setRoots(source.getParent().toString());

        McpArtifactImporter.PreparedMcp prepared = new McpArtifactImporter(
                properties, "", "").resolve(localNodeConfig(source), workspace);

        assertTrue(Files.isRegularFile(prepared.artifactDirectory().resolve("dist/index.js")));
    }

    @Test
    void reusesContentAddressedImportAndChangesHashWhenSourceChanges() throws Exception {
        Path source = nodeProject();
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        System.setProperty("MELON_MCP_IMPORT_ROOTS", source.getParent().toString());
        McpClientManager.McpServerConfig config = localNodeConfig(source);
        McpArtifactImporter importer = new McpArtifactImporter();

        Path first = importer.resolve(config, workspace).artifactDirectory();
        Path cached = importer.resolve(config, workspace).artifactDirectory();
        Files.writeString(source.resolve("dist/index.js"), "console.error('changed');");
        Path changed = importer.resolve(config, workspace).artifactDirectory();

        assertEquals(first, cached);
        assertNotEquals(first, changed);
    }

    @Test
    void rewritesLongOptionContainingAnAbsolutePath() throws Exception {
        Path source = nodeProject();
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        System.setProperty("MELON_MCP_IMPORT_ROOTS", source.getParent().toString());
        Path script = source.resolve("dist/index.js");
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "option-path",
                "command", "node",
                "args", List.of("--config=" + script)));

        McpArtifactImporter.PreparedMcp prepared = new McpArtifactImporter().resolve(config, workspace);

        assertTrue(prepared.runtimeConfig().getArgs().get(0).startsWith(
                "--config=/workspace/.melon/mcp-sources/option-path/"));
    }

    @Test
    void rejectsHostPathOutsideTrustedRoots() throws Exception {
        Path source = nodeProject();
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path otherRoot = Files.createDirectories(tempDir.resolve("trusted-other"));
        System.setProperty("MELON_MCP_IMPORT_ROOTS", otherRoot.toString());

        assertThrows(SecurityException.class,
                () -> new McpArtifactImporter().resolve(localNodeConfig(source), workspace));
    }

    @Test
    void rejectsSymbolicLinksInsideImportedProject() throws Exception {
        Path source = nodeProject();
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        System.setProperty("MELON_MCP_IMPORT_ROOTS", source.getParent().toString());
        try {
            Files.createSymbolicLink(source.resolve("dist/escape.js"), tempDir.resolve("outside.js"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "Symbolic links are not supported by this filesystem");
        }

        assertThrows(IllegalArgumentException.class,
                () -> new McpArtifactImporter().resolve(localNodeConfig(source), workspace));
    }

    @Test
    void rejectsDirectSymlinkEvenWhenItPointsInsideTrustedRoot() throws Exception {
        Path source = nodeProject();
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        System.setProperty("MELON_MCP_IMPORT_ROOTS", source.getParent().toString());
        Path linkedScript = source.resolve("linked-index.js");
        try {
            Files.createSymbolicLink(linkedScript, source.resolve("dist/index.js"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "Symbolic links are not supported by this filesystem");
        }
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "linked-node",
                "command", "node",
                "args", List.of(linkedScript.toString())));

        assertThrows(SecurityException.class,
                () -> new McpArtifactImporter().resolve(config, workspace));
    }

    @Test
    void enforcesArtifactSizeLimit() throws Exception {
        Path source = nodeProject();
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        System.setProperty("MELON_MCP_IMPORT_ROOTS", source.getParent().toString());
        System.setProperty("MELON_MCP_IMPORT_MAX_BYTES", "8");

        assertThrows(IllegalArgumentException.class,
                () -> new McpArtifactImporter().resolve(localNodeConfig(source), workspace));
    }

    @Test
    void rejectsHostPathWhenNoAgentWorkspaceExists() throws Exception {
        Path source = nodeProject();

        assertThrows(IllegalArgumentException.class,
                () -> new McpArtifactImporter().resolve(localNodeConfig(source), null));
    }

    @Test
    void mapsLinuxPathToJavaVisiblePathWithoutMutatingPersistedConfig() throws Exception {
        Path source = nodeProject();
        Path trusted = source.getParent();
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path foreignRoot = Path.of("/home/mineru/Github");
        Path foreignScript = foreignRoot.resolve("local-mcp/dist/index.js");
        System.setProperty("MELON_MCP_IMPORT_ROOTS", trusted.toString());
        System.setProperty("MELON_MCP_PATH_MAPPINGS",
                "{\"/home/mineru/Github\":\"" + jsonPath(trusted) + "\"}");
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "mapped-node",
                "command", "node",
                "args", List.of(foreignScript.toString())));

        McpArtifactImporter.PreparedMcp prepared = new McpArtifactImporter().resolve(config, workspace);

        assertEquals(foreignScript.toString(), config.getArgs().get(0));
        assertTrue(prepared.runtimeConfig().getArgs().get(0).startsWith(
                "/workspace/.melon/mcp-sources/mapped-node/"));
    }

    @Test
    void usesLongestConfiguredPathPrefix() throws Exception {
        Path source = nodeProject();
        Path trusted = source.getParent();
        Path wrong = Files.createDirectories(trusted.resolve("wrong"));
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        System.setProperty("MELON_MCP_IMPORT_ROOTS", trusted.toString());
        System.setProperty("MELON_MCP_PATH_MAPPINGS",
                "{\"/home/mineru\":\"" + jsonPath(wrong)
                        + "\",\"/home/mineru/Github\":\"" + jsonPath(trusted) + "\"}");
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "longest-prefix",
                "command", "node",
                "args", List.of("/home/mineru/Github/local-mcp/dist/index.js")));

        McpArtifactImporter.PreparedMcp prepared = new McpArtifactImporter().resolve(config, workspace);

        assertTrue(Files.isRegularFile(prepared.artifactDirectory().resolve("dist/index.js")));
    }

    @Test
    void mapsCommandArgumentsOptionsAndWorkingDirectory() throws Exception {
        Path source = nodeProject();
        Files.writeString(source.resolve("dist/config.json"), "{}");
        Path trusted = source.getParent();
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        String foreignProject = "/home/mineru/Github/local-mcp";
        System.setProperty("MELON_MCP_IMPORT_ROOTS", trusted.toString());
        System.setProperty("MELON_MCP_PATH_MAPPINGS",
                "{\"/home/mineru/Github\":\"" + jsonPath(trusted) + "\"}");
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "mapped-references",
                "command", foreignProject + "/dist/index.js",
                "args", List.of("--config=" + foreignProject + "/dist/config.json"),
                "cwd", foreignProject));

        McpArtifactImporter.PreparedMcp prepared = new McpArtifactImporter().resolve(config, workspace);

        assertTrue(prepared.runtimeConfig().getCommand().startsWith("/workspace/.melon/mcp-sources/"));
        assertTrue(prepared.runtimeConfig().getArgs().get(0).startsWith(
                "--config=/workspace/.melon/mcp-sources/"));
        assertTrue(prepared.runtimeConfig().getCwd().startsWith("/workspace/.melon/mcp-sources/"));
        assertEquals(foreignProject + "/dist/index.js", config.getCommand());
        assertEquals(foreignProject, config.getCwd());
    }

    @Test
    void rejectsPathMappingOutsideTrustedRoots() throws Exception {
        Path source = nodeProject();
        Path otherRoot = Files.createDirectories(tempDir.resolve("trusted-other"));
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        System.setProperty("MELON_MCP_IMPORT_ROOTS", otherRoot.toString());
        System.setProperty("MELON_MCP_PATH_MAPPINGS",
                "{\"/home/mineru/Github\":\"" + jsonPath(source.getParent()) + "\"}");

        assertThrows(SecurityException.class,
                () -> new McpArtifactImporter().resolve(localNodeConfig(source), workspace));
    }

    @Test
    void rejectsPathMappingToUnavailableFile() throws Exception {
        Path source = nodeProject();
        Path trusted = source.getParent();
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        System.setProperty("MELON_MCP_IMPORT_ROOTS", trusted.toString());
        System.setProperty("MELON_MCP_PATH_MAPPINGS",
                "{\"/home/mineru/Github\":\"" + jsonPath(trusted) + "\"}");
        McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "missing-mapped-file",
                "command", "node",
                "args", List.of("/home/mineru/Github/missing/dist/index.js")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new McpArtifactImporter().resolve(config, workspace));
        assertTrue(error.getMessage().contains("resolved to an unavailable path"));
    }

    private Path nodeProject() throws IOException {
        Path source = Files.createDirectories(tempDir.resolve("trusted/local-mcp"));
        Files.createDirectories(source.resolve("dist"));
        Files.createDirectories(source.resolve("node_modules/dependency"));
        Files.writeString(source.resolve("package.json"), "{\"name\":\"local-mcp\"}");
        Files.writeString(source.resolve("package-lock.json"), "{\"lockfileVersion\":3}");
        Files.writeString(source.resolve("dist/index.js"), "console.error('ready');");
        Files.writeString(source.resolve("node_modules/dependency/index.js"), "ignored");
        Files.writeString(source.resolve(".env"), "SECRET=ignored");
        return source;
    }

    private McpClientManager.McpServerConfig localNodeConfig(Path source) {
        return McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", "local-node",
                "command", "node",
                "args", List.of(source.resolve("dist/index.js").toString())));
    }

    private String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
