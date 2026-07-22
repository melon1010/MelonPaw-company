package com.melon.app.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentConfigPersistenceSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsFullAgentConfigOutsideTheModelReadableWorkspace() throws Exception {
        Path configPath = tempDir.resolve("config.yaml");
        Path home = tempDir.resolve("home");
        ConfigManager manager = manager(configPath, home);
        AgentConfig agent = new AgentConfig();
        agent.setName("Managed Agent");
        agent.setActiveModel("dashscope:qwen-plus");
        agent.getApproval().setLevel("STRICT");
        agent.setChannels(Map.of(
                "dingtalk", Map.of("enabled", true, "client_secret", "channel-secret-value")
        ));
        Map<String, AgentConfig> agents = new LinkedHashMap<>(manager.getConfig().getAgents());
        agents.put("managed-agent", agent);
        manager.getConfig().setAgents(agents);
        manager.save();

        assertFalse(Files.exists(manager.resolveWorkspaceDir("default")));
        Path workspaceAgentJson = manager.resolveWorkspaceDir("managed-agent").resolve("agent.json");
        String workspaceMetadata = Files.readString(workspaceAgentJson);
        assertFalse(workspaceMetadata.contains("channel-secret-value"));
        assertFalse(workspaceMetadata.contains("\"config\""));

        Files.writeString(workspaceAgentJson, """
                {"version":1,"active_model":"attacker:model","approval_level":"OFF"}
                """);

        ConfigManager reloaded = manager(configPath, home);
        assertEquals("channel-secret-value", reloaded.getConfig().getAgent("managed-agent")
                .getChannels().get("dingtalk").get("client_secret"));
        assertEquals("dashscope:qwen-plus", reloaded.getConfig().getAgent("managed-agent").getActiveModel());
        assertEquals("STRICT", reloaded.getConfig().getAgent("managed-agent").getApproval().getLevel());
    }

    @Test
    void ignoresLegacyWorkspaceRuntimeConfigAndSanitizesMetadataOnSave() throws Exception {
        Path configPath = tempDir.resolve("legacy-config.yaml");
        Path home = tempDir.resolve("legacy-home");
        Path workspace = home.resolve("workspaces").resolve("legacy-agent");
        Files.createDirectories(workspace);

        AgentConfig legacy = new AgentConfig();
        legacy.setName("Legacy Agent");
        legacy.setWorkspaceDir(workspace.toString());
        legacy.getApproval().setLevel("STRICT");
        legacy.setChannels(Map.of(
                "dingtalk", Map.of("enabled", false, "client_secret", "legacy-secret")
        ));
        new ObjectMapper().writeValue(workspace.resolve("agent.json").toFile(),
                Map.of("version", 1, "config", legacy));

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("name", "Legacy Agent");
        index.put("description", "");
        index.put("enabled", true);
        index.put("workspace_dir", workspace.toString());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("home_dir", home.toString());
        root.put("agents", Map.of("legacy-agent", index));
        new ObjectMapper(new YAMLFactory()).writeValue(configPath.toFile(), root);

        ConfigManager migrated = manager(configPath, home);
        assertFalse(migrated.getConfig().getAgent("legacy-agent").getChannels().containsKey("dingtalk"));
        assertEquals("RISK_BASED", migrated.getConfig().getAgent("legacy-agent").getApproval().getLevel());
        migrated.save();
        String metadata = Files.readString(workspace.resolve("agent.json"));
        assertFalse(metadata.contains("legacy-secret"));
        assertFalse(metadata.contains("\"config\""));

        ConfigManager reloaded = manager(configPath, home);
        assertFalse(reloaded.getConfig().getAgent("legacy-agent").getChannels().containsKey("dingtalk"));
        assertEquals("RISK_BASED", reloaded.getConfig().getAgent("legacy-agent").getApproval().getLevel());
    }

    private ConfigManager manager(Path configPath, Path home) {
        ConfigManager manager = new ConfigManager();
        manager.setConfigPath(configPath);
        manager.setHomeDir(home.toString());
        manager.load();
        return manager;
    }
}
