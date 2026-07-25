package com.melon.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class McpExecutionPropertiesTest {

    @Test
    void bindsDocumentedMcpExecutionSettings() {
        var source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("melon.execution.mcp.stdio-mode", "docker"),
                Map.entry("melon.execution.mcp.docker-command", "/usr/local/bin/docker"),
                Map.entry("melon.execution.mcp.sandbox-image", "mcp:test"),
                Map.entry("melon.execution.mcp.sandbox-network", "restricted"),
                Map.entry("melon.execution.mcp.sandbox-runtime", "runsc"),
                Map.entry("melon.execution.mcp.sandbox-memory", "768m"),
                Map.entry("melon.execution.mcp.sandbox-cpus", "3"),
                Map.entry("melon.execution.mcp.sandbox-pids", "128"),
                Map.entry("melon.execution.mcp.sandbox-user", "10002:10003"),
                Map.entry("melon.execution.mcp.prepare-timeout-seconds", "120"),
                Map.entry("melon.execution.mcp.import.roots", "/srv/mcp"),
                Map.entry("melon.execution.mcp.import.path-mappings", "{\"/host\":\"/srv/mcp\"}"),
                Map.entry("melon.execution.mcp.import.max-files", "5000"),
                Map.entry("melon.execution.mcp.import.max-bytes", "1048576")));

        McpExecutionProperties properties = new Binder(source)
                .bind("melon.execution.mcp", Bindable.of(McpExecutionProperties.class))
                .orElseThrow(() -> new AssertionError("MCP execution properties were not bound"));

        assertEquals("docker", properties.getStdioMode());
        assertEquals("/usr/local/bin/docker", properties.getDockerCommand());
        assertEquals("mcp:test", properties.getSandboxImage());
        assertEquals("restricted", properties.getSandboxNetwork());
        assertEquals("runsc", properties.getSandboxRuntime());
        assertEquals("768m", properties.getSandboxMemory());
        assertEquals("3", properties.getSandboxCpus());
        assertEquals("128", properties.getSandboxPids());
        assertEquals("10002:10003", properties.getSandboxUser());
        assertEquals(120L, properties.getPrepareTimeoutSeconds());
        assertEquals("/srv/mcp", properties.getImport().getRoots());
        assertEquals("{\"/host\":\"/srv/mcp\"}", properties.getImport().getPathMappings());
        assertEquals(5000, properties.getImport().getMaxFiles());
        assertEquals(1_048_576L, properties.getImport().getMaxBytes());
    }

    @Test
    void applicationYamlRemainsValidAfterDocumentingExecutionSettings() throws Exception {
        var sources = new YamlPropertySourceLoader().load(
                "application", new ClassPathResource("application.yaml"));

        assertFalse(sources.isEmpty());
    }
}
