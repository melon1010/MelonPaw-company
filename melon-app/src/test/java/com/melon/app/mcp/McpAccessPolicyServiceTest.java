package com.melon.app.mcp;

import com.melon.core.config.ConfigManager;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAccessPolicyServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsToAllowAndHonorsExplicitRuntimeRules() {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        McpAccessPolicyService service = new McpAccessPolicyService(configManager);
        RuntimeContext employee = RuntimeContext.builder()
                .userId("employee-1")
                .sessionId("session-1")
                .put("channel", "console")
                .build();

        assertEquals("allow", service.effect("corp-search", "search", employee));

        service.save("corp-search", Map.of(
                "default_effect", "deny",
                "tool_overrides", List.of(Map.of(
                        "tool_name", "search",
                        "source_type", "channel",
                        "source_value", "console",
                        "subject_type", "user",
                        "subject_value", "employee-1",
                        "effect", "allow"))));

        assertEquals("allow", service.effect("corp-search", "search", employee));
        assertEquals("deny", service.effect("corp-search", "delete", employee));
        RuntimeContext other = RuntimeContext.builder()
                .userId("employee-2")
                .sessionId("session-2")
                .put("channel", "console")
                .build();
        assertEquals("deny", service.effect("corp-search", "search", other));
    }

    @Test
    void supportsPackageStyleServerIdsWithoutUsingThemAsFileNames() throws Exception {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        McpAccessPolicyService service = new McpAccessPolicyService(configManager);

        service.save("@amap/amap-maps-mcp-server", Map.of("default_effect", "ask"));

        assertEquals("ask", service.load("@amap/amap-maps-mcp-server").get("default_effect"));
        Path policyDirectory = configManager.resolveStateDir().resolve("mcp-policies");
        List<String> files;
        try (var paths = Files.list(policyDirectory)) {
            files = paths.map(path -> path.getFileName().toString()).toList();
        }
        assertEquals(1, files.size());
        assertEquals(76, files.get(0).length());
        assertTrue(files.get(0).matches("server-[0-9a-f]{64}\\.json"));
    }

    @Test
    void distinctUnsafeServerIdsUseDistinctPolicyFiles() throws Exception {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        McpAccessPolicyService service = new McpAccessPolicyService(configManager);

        service.save("@scope/one", Map.of("default_effect", "allow"));
        service.save("@scope/two", Map.of("default_effect", "deny"));

        Path policyDirectory = configManager.resolveStateDir().resolve("mcp-policies");
        List<String> files;
        try (var paths = Files.list(policyDirectory)) {
            files = paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertEquals(2, files.size());
        assertNotEquals(files.get(0), files.get(1));
        assertEquals("allow", service.load("@scope/one").get("default_effect"));
        assertEquals("deny", service.load("@scope/two").get("default_effect"));
    }

    @Test
    void keepsReadingLegacyPolicyFileForPathSafeIds() {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        McpAccessPolicyService service = new McpAccessPolicyService(configManager);
        Path legacyFile = configManager.resolveStateDir()
                .resolve("mcp-policies").resolve("corp-search.json");
        com.melon.core.util.JsonUtils.save(legacyFile, Map.of("default_effect", "deny"));

        assertEquals("deny", service.load("corp-search").get("default_effect"));
    }

    @Test
    void rejectsBlankServerIds() {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        McpAccessPolicyService service = new McpAccessPolicyService(configManager);

        assertThrows(IllegalArgumentException.class, () -> service.load("  "));
    }
}
