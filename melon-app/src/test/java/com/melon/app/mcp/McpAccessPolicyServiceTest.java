package com.melon.app.mcp;

import com.melon.core.config.ConfigManager;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void rejectsUnsafeServerIds() {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        McpAccessPolicyService service = new McpAccessPolicyService(configManager);

        assertThrows(IllegalArgumentException.class, () -> service.load("../escape"));
    }
}
