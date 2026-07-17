package com.melon.app.auth;

import com.melon.app.service.SkillService;
import com.melon.app.task.EnterpriseTaskService;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.BuiltinToolConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.config.ToolsConfig;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.melon.core.util.ValueUtils.booleanValue;
import static com.melon.core.util.ValueUtils.stringList;
import static com.melon.core.util.ValueUtils.trimmedStringValue;

/** Provisions and publishes the single shared Agent owned by a project space. */
@Service
public class ProjectAgentService {

    private final EnterpriseIdentityStore identityStore;
    private final EnterpriseAgentDefinitionService definitions;
    private final ProjectWorkspaceService projectWorkspaceService;
    private final ConfigManager configManager;
    private final WorkspaceManager workspaceManager;
    private final MultiAgentManager multiAgentManager;
    private final SkillService skillService;
    private final EnterpriseTaskService taskService;

    public ProjectAgentService(EnterpriseIdentityStore identityStore,
                               EnterpriseAgentDefinitionService definitions,
                               ProjectWorkspaceService projectWorkspaceService,
                               ConfigManager configManager,
                               WorkspaceManager workspaceManager,
                               MultiAgentManager multiAgentManager,
                               SkillService skillService,
                               EnterpriseTaskService taskService) {
        this.identityStore = identityStore;
        this.definitions = definitions;
        this.projectWorkspaceService = projectWorkspaceService;
        this.configManager = configManager;
        this.workspaceManager = workspaceManager;
        this.multiAgentManager = multiAgentManager;
        this.skillService = skillService;
        this.taskService = taskService;
    }

    public synchronized Map<String, Object> get(String projectId) {
        EnterpriseIdentityStore.AgentBindingRecord binding = identityStore.findProjectAgentBinding(projectId)
                .orElse(null);
        if (binding == null) return Map.of();
        AgentConfig config = configManager.getConfig().getAgent(binding.agentId());
        if (config == null) throw new IllegalStateException("Project Agent configuration is missing");
        EnterpriseAgentDefinitionService.AgentDefinition definition = definitions.findByAgent(binding.agentId())
                .orElse(null);
        return payload(binding.agentId(), projectId, config, definition);
    }

    public synchronized Map<String, Object> create(String projectId, Map<String, Object> body,
                                                    EnterprisePrincipal actor) throws IOException {
        if (identityStore.findProjectAgentBinding(projectId).isPresent()) {
            throw new IllegalStateException("This project already has an Agent");
        }
        String agentId = "project-" + projectId.replaceAll("[^A-Za-z0-9_.-]", "_");
        Map<String, AgentConfig> agents = new LinkedHashMap<>(configManager.getConfig().getAgents());
        if (agents.containsKey(agentId)) throw new IllegalStateException("Project Agent id already exists");

        AgentConfig config = new AgentConfig();
        config.setName(trimmedStringValue(body != null ? body.get("name") : null, "Project Agent"));
        config.setDescription(trimmedStringValue(body != null ? body.get("description") : null,
                "Shared Agent for the project space"));
        config.setEnabled(booleanValue(body != null ? body.get("enabled") : null, true));
        config.setWorkspaceDir(projectWorkspaceService.workspaceDir(projectId).toString());
        config.setSystemPromptFiles(List.of("PROJECT.md", "POLICY.md", "KNOWLEDGE.md", "AGENTS.md"));
        config.setSkills(stringList(body != null ? body.get("skill_names") : null));
        applyModel(config, body != null ? body.get("active_model") : null);
        inheritDefaults(config);

        Path workspace = projectWorkspaceService.workspaceDir(projectId);
        projectWorkspaceService.list(projectId);
        workspaceManager.initWorkspace(workspace);
        try {
            config.setSkills(materializeSkills(agentId, config.getSkills(), agents, config));
        } catch (IOException | RuntimeException e) {
            agents.remove(agentId);
            configManager.getConfig().setAgents(agents);
            throw e;
        }
        // ponytail: TaskService already serializes starts; use the same monitor for publication.
        synchronized (taskService) {
            try {
                configManager.save();
                workspaceManager.writeAgentJson(workspace, agentId, config);
                identityStore.bindProjectAgent(agentId, projectId);
                EnterpriseAgentDefinitionService.AgentDefinition definition = definitions.publish(
                        agentId, "PROJECT", projectId, config, actor.id(), "Initial project Agent release");
                return payload(agentId, projectId, config, definition);
            } catch (RuntimeException e) {
                rollbackCreate(agentId, agents, e);
                throw e;
            }
        }
    }

    public synchronized Map<String, Object> update(String projectId, Map<String, Object> body,
                                                    EnterprisePrincipal actor) throws IOException {
        EnterpriseIdentityStore.AgentBindingRecord binding = requireBinding(projectId);
        String agentId = binding.agentId();
        AgentConfig before = copy(requireConfig(agentId));
        AgentConfig updated = copy(before);
        List<String> requestedSkills = null;
        if (body != null) {
            if (body.get("name") != null) updated.setName(trimmedStringValue(body.get("name"), updated.getName()));
            if (body.get("description") != null) {
                updated.setDescription(trimmedStringValue(body.get("description"), ""));
            }
            if (body.get("enabled") != null) updated.setEnabled(booleanValue(body.get("enabled"), true));
            if (body.get("active_model") != null) applyModel(updated, body.get("active_model"));
            if (body.containsKey("skill_names")) {
                requestedSkills = stringList(body.get("skill_names"));
            }
        }
        synchronized (taskService) {
            requireIdle(agentId);
            if (requestedSkills != null) {
                updated.setSkills(materializeSkills(agentId, requestedSkills, null, null));
            }
            try {
                replaceConfig(agentId, updated);
                EnterpriseAgentDefinitionService.AgentDefinition definition = definitions.publish(
                        agentId, "PROJECT", projectId, updated, actor.id(),
                        trimmedStringValue(body != null ? body.get("change_summary") : null,
                                "Project Agent configuration updated"));
                return payload(agentId, projectId, updated, definition);
            } catch (RuntimeException e) {
                restoreConfig(agentId, before, e);
                throw e;
            }
        }
    }

    public synchronized Map<String, Object> rollback(String projectId, String versionId,
                                                      EnterprisePrincipal actor) {
        EnterpriseIdentityStore.AgentBindingRecord binding = requireBinding(projectId);
        String agentId = binding.agentId();
        AgentConfig current = requireConfig(agentId);
        AgentConfig before = copy(current);
        AgentConfig restored = definitions.versionConfig(agentId, versionId);
        restored.setWorkspaceDir(current.getWorkspaceDir());
        restored.setChannels(current.getChannels());
        restored.setTools(restoreToolPolicy(restored.getTools(), current.getTools()));
        synchronized (taskService) {
            requireIdle(agentId);
            try {
                replaceConfig(agentId, restored);
                EnterpriseAgentDefinitionService.AgentDefinition definition = definitions.publish(
                        agentId, "PROJECT", projectId, restored, actor.id(),
                        "Rollback from version " + versionId);
                return payload(agentId, projectId, restored, definition);
            } catch (RuntimeException e) {
                restoreConfig(agentId, before, e);
                throw e;
            }
        }
    }

    public synchronized List<Map<String, Object>> versions(String projectId) {
        String agentId = requireBinding(projectId).agentId();
        return definitions.versions(agentId).stream()
                .map(EnterpriseAgentDefinitionService.AgentVersion::toMap)
                .toList();
    }

    public String beginResourceMutation(String projectId) {
        EnterpriseIdentityStore.AgentBindingRecord binding = identityStore.findProjectAgentBinding(projectId)
                .orElse(null);
        if (binding == null) return null;
        if (!taskService.beginAgentMutation(binding.agentId())) {
            throw new EnterpriseTaskService.AgentMutationInProgressException(
                    "Project Agent resources cannot change while a task is active");
        }
        return binding.agentId();
    }

    public void endResourceMutation(String agentId) {
        taskService.endAgentMutation(agentId);
    }

    private void inheritDefaults(AgentConfig config) {
        AgentConfig defaults = configManager.getConfig().getAgent("default");
        if (defaults == null) return;
        if (config.getActiveModel() == null || config.getActiveModel().isBlank()) {
            config.setActiveModel(defaults.getActiveModel());
        }
        config.setLanguage(defaults.getLanguage());
        config.setTimezone(defaults.getTimezone());
    }

    private void applyModel(AgentConfig config, Object value) {
        if (value instanceof Map<?, ?> map) {
            String provider = trimmedStringValue(map.get("provider_id"), "");
            String model = trimmedStringValue(map.get("model"), "");
            if (!provider.isBlank() && !model.isBlank()) config.setActiveModel(provider + ":" + model);
            return;
        }
        String model = trimmedStringValue(value, "");
        if (!model.isBlank()) config.setActiveModel(model);
    }

    private List<String> materializeSkills(String agentId, List<String> skills,
                                           Map<String, AgentConfig> pendingAgents,
                                           AgentConfig pendingConfig) throws IOException {
        if (pendingAgents != null && pendingConfig != null) {
            pendingAgents.put(agentId, pendingConfig);
            configManager.getConfig().setAgents(pendingAgents);
        }
        return skillService.syncPoolSkillsToWorkspace(agentId, skills);
    }

    private void rollbackCreate(String agentId, Map<String, AgentConfig> agents, RuntimeException failure) {
        try {
            identityStore.unbindAgent(agentId);
        } catch (RuntimeException cleanupError) {
            failure.addSuppressed(cleanupError);
        }
        agents.remove(agentId);
        configManager.getConfig().setAgents(agents);
        try {
            configManager.save();
        } catch (RuntimeException cleanupError) {
            failure.addSuppressed(cleanupError);
        }
    }

    private void restoreConfig(String agentId, AgentConfig config, RuntimeException failure) {
        try {
            replaceConfig(agentId, config);
        } catch (RuntimeException cleanupError) {
            failure.addSuppressed(cleanupError);
        }
    }

    private void replaceConfig(String agentId, AgentConfig config) {
        Map<String, AgentConfig> agents = new LinkedHashMap<>(configManager.getConfig().getAgents());
        agents.put(agentId, config);
        configManager.getConfig().setAgents(agents);
        configManager.save();
        workspaceManager.writeAgentJson(configManager.resolveWorkspaceDir(agentId), agentId, config);
        multiAgentManager.reload(agentId);
    }

    private AgentConfig copy(AgentConfig config) {
        return com.melon.core.util.JsonUtils.getMapper().convertValue(config, AgentConfig.class);
    }

    private ToolsConfig restoreToolPolicy(ToolsConfig restored, ToolsConfig current) {
        if (restored == null) return current;
        if (current == null) return restored;
        Map<String, BuiltinToolConfig> restoredTools = new LinkedHashMap<>(
                restored.getBuiltinTools() != null ? restored.getBuiltinTools() : Map.of());
        for (Map.Entry<String, BuiltinToolConfig> entry : (current.getBuiltinTools() != null
                ? current.getBuiltinTools() : Map.<String, BuiltinToolConfig>of()).entrySet()) {
            BuiltinToolConfig versioned = restoredTools.get(entry.getKey());
            if (versioned == null) {
                restoredTools.put(entry.getKey(), entry.getValue());
            } else {
                versioned.setConfig(new LinkedHashMap<>(entry.getValue().getConfig() != null
                        ? entry.getValue().getConfig() : Map.of()));
            }
        }
        restored.setBuiltinTools(restoredTools);
        return restored;
    }

    private EnterpriseIdentityStore.AgentBindingRecord requireBinding(String projectId) {
        return identityStore.findProjectAgentBinding(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project Agent not found"));
    }

    private AgentConfig requireConfig(String agentId) {
        AgentConfig config = configManager.getConfig().getAgent(agentId);
        if (config == null) throw new IllegalStateException("Project Agent configuration is missing");
        return config;
    }

    private void requireIdle(String agentId) {
        if (taskService.hasActiveAgent(agentId)) {
            throw new IllegalStateException("Project Agent configuration cannot change while a task is active");
        }
    }

    private Map<String, Object> payload(String agentId, String projectId, AgentConfig config,
                                        EnterpriseAgentDefinitionService.AgentDefinition definition) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (definition != null) result.putAll(definition.toMap());
        result.put("agent_id", agentId);
        result.put("project_id", projectId);
        result.put("scope_type", "PROJECT");
        result.put("name", config.getName());
        result.put("description", config.getDescription());
        result.put("enabled", config.isEnabled());
        result.put("active_model", config.getActiveModel());
        result.put("skill_names", config.getSkills());
        return result;
    }
}
