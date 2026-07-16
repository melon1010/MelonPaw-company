package com.melon.app.controller;

import com.melon.app.auth.EnterprisePrincipal;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.provider.ProviderManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ModelsCompatControllerIsolationTest {

    @TempDir
    Path home;

    private ConfigManager configManager;
    private ModelsCompatController controller;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager();
        configManager.setConfigPath(home.resolve("config.yaml"));
        configManager.setHomeDir(home.toString());
        configManager.load();
        ProviderManager providerManager = new ProviderManager();
        providerManager.init(configManager.getConfig());
        controller = new ModelsCompatController(
                providerManager, configManager, mock(MultiAgentManager.class));
    }

    @Test
    void employeeCatalogDoesNotExposeProviderConnectionDetails() {
        controller.updateProviderConfig("openai", Map.of(
                "api_key", "private-api-key",
                "base_url", "https://internal-model.example/v1",
                "custom_headers", Map.of("X-Private-Tenant", "enterprise-a")
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) controller
                .listModels(employee())
                .block()
                .getBody();

        Map<String, Object> openai = providers.stream()
                .filter(provider -> "openai".equals(provider.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals("", openai.get("api_key"));
        assertEquals("", openai.get("base_url"));
        assertEquals(Map.of(), openai.get("custom_headers"));
        assertEquals(true, openai.get("api_key_configured"));
    }

    @Test
    void queryAndBodyAgentIdsCannotReplaceResolvedPersonalAgent() {
        AgentConfig defaultAgent = configManager.getConfig().getAgent("default");
        defaultAgent.setActiveModel("openai:default-model");
        AgentConfig personalAgent = new AgentConfig();
        personalAgent.setName("Personal");
        personalAgent.setWorkspaceDir(home.resolve("workspaces/personal-user-1").toString());
        personalAgent.setActiveModel("openai:personal-model");
        Map<String, AgentConfig> agents = new LinkedHashMap<>(configManager.getConfig().getAgents());
        agents.put("personal-user-1", personalAgent);
        configManager.getConfig().setAgents(agents);

        Map<?, ?> fromQuery = (Map<?, ?>) controller.getActiveModels(
                "personal-user-1", "default", "effective", employee()).block().getBody();
        Map<?, ?> fromBody = (Map<?, ?>) controller.updateActiveModels(
                "personal-user-1",
                Map.of("agent_id", "default", "scope", "agent"),
                employee()).block().getBody();

        assertEquals("personal-model", ((Map<?, ?>) fromQuery.get("active_llm")).get("model"));
        assertEquals("personal-model", ((Map<?, ?>) fromBody.get("active_llm")).get("model"));
        assertTrue(configManager.getConfig().getAgent("default").getActiveModel().contains("default-model"));
    }

    private EnterprisePrincipal employee() {
        return new EnterprisePrincipal(
                "user-1", "employee", "Employee", true,
                Set.of("EMPLOYEE"), Set.of("agents:use"), "", "");
    }
}
