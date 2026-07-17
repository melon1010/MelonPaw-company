package com.melon.app.controller;

import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.auth.EnterpriseIdentityStore;
import com.melon.app.auth.EnterprisePrincipal;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.provider.ProviderManager;
import com.melon.app.service.SkillService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.melon.core.util.ValueUtils.booleanValue;
import static com.melon.core.util.ValueUtils.stringList;
import static com.melon.core.util.ValueUtils.trimmedStringValue;
import static com.melon.app.auth.EnterprisePermissions.SYSTEM_MANAGE;

/**
 * Agent 管理 CRUD. 对应 Python /api/agents.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentManagementController {

    private final ConfigManager configManager;
    private final WorkspaceManager workspaceManager;
    private final MultiAgentManager multiAgentManager;
    private final SkillService skillService;
    private final EnterpriseAgentAccessService agentAccessService;
    private final ProviderManager providerManager;

    public AgentManagementController(ConfigManager configManager,
                                     WorkspaceManager workspaceManager,
                                     MultiAgentManager multiAgentManager,
                                     SkillService skillService,
                                     EnterpriseAgentAccessService agentAccessService,
                                     ProviderManager providerManager) {
        this.configManager = configManager;
        this.workspaceManager = workspaceManager;
        this.multiAgentManager = multiAgentManager;
        this.skillService = skillService;
        this.agentAccessService = agentAccessService;
        this.providerManager = providerManager;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> list(@AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.fromCallable(() -> ResponseEntity.ok(Map.of(
                "agents", agentAccessService.accessibleAgentIds(principal).stream()
                        .map(id -> Map.entry(id, configManager.getConfig().getAgent(id)))
                        .filter(entry -> entry.getValue() != null)
                        .map(entry -> agentPayload(entry.getKey(), entry.getValue(),
                                principal.hasPermission("agents:manage")))
                        .toList()
        )));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<?>> get(
            @PathVariable String id,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.fromCallable(() -> {
            if (!agentAccessService.canAccess(principal, id)) {
                return ResponseEntity.status(403).body(Map.of("detail", "Agent access denied"));
            }
            AgentConfig config = configManager.getConfig().getAgent(id);
            return config == null
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.ok(agentPayload(id, config,
                    principal.hasPermission("agents:manage")));
        });
    }

    @PostMapping
    public Mono<ResponseEntity<?>> create(
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.fromCallable(() -> {
            String id = sanitizeId(trimmedStringValue(value(body, "id", "agent_id", "name"), "agent"));
            Map<String, AgentConfig> agents = mutableAgents();
            if (agents.containsKey(id)) {
                return ResponseEntity.status(409).body(Map.of("detail", "Agent already exists: " + id));
            }
            AgentConfig agent = new AgentConfig();
            agent.setName(trimmedStringValue(body != null ? body.get("name") : null, id));
            agent.setDescription(trimmedStringValue(body != null ? body.get("description") : null, ""));
            agent.setEnabled(booleanValue(body != null ? body.get("enabled") : null, true));
            String workspaceDirValue = trimmedStringValue(body != null ? body.get("workspace_dir") : null, "");
            if (!workspaceDirValue.isBlank()) {
                try {
                    agent.setWorkspaceDir(validateManagedWorkspacePath(workspaceDirValue));
                } catch (SecurityException e) {
                    return ResponseEntity.status(403).body(Map.of("detail", e.getMessage()));
                }
            }
            applyActiveModel(agent, body != null ? body.get("active_model") : null);
            if (body != null && body.get("system_prompt_files") instanceof List<?> files) {
                agent.setSystemPromptFiles(files.stream().map(String::valueOf).toList());
            }
            List<String> skillNames = stringList(body != null ? body.get("skill_names") : null);
            agent.setSkills(skillNames);
            agents.put(id, agent);
            configManager.getConfig().setAgents(agents);
            Path workspaceDir = configManager.resolveWorkspaceDir(id);
            workspaceManager.initWorkspace(workspaceDir);
            try {
                materializeInitialSkills(id, skillNames);
            } catch (NoSuchFileException e) {
                agents.remove(id);
                configManager.getConfig().setAgents(agents);
                return ResponseEntity.status(404).body(Map.of("detail", e.getMessage()));
            }
            workspaceManager.writeAgentJson(workspaceDir, id, agent);
            configManager.save();
            return ResponseEntity.ok(agentPayload(id, agent, true));
        });
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<?>> update(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.fromCallable(() -> {
            if (!agentAccessService.canAccess(principal, id)) {
                return ResponseEntity.status(403).body(Map.of("detail", "Agent access denied"));
            }
            AgentConfig agent = configManager.getConfig().getAgent(id);
            if (agent == null) {
                return ResponseEntity.notFound().build();
            }
            if (body != null) {
                if (body.get("name") != null) agent.setName(String.valueOf(body.get("name")));
                if (body.get("description") != null) agent.setDescription(String.valueOf(body.get("description")));
                if (body.get("enabled") != null) agent.setEnabled(booleanValue(body.get("enabled"), true));
                if (body.get("workspace_dir") != null) {
                    String workspaceDir = trimmedStringValue(body.get("workspace_dir"), "");
                    agent.setWorkspaceDir(workspaceDir.isBlank() ? null : validateManagedWorkspacePath(workspaceDir));
                }
                if (body.get("active_model") != null) applyActiveModel(agent, body.get("active_model"));
                if (body.get("system_prompt_files") instanceof List<?> files) {
                    agent.setSystemPromptFiles(files.stream().map(String::valueOf).toList());
                }
                boolean hasSkillField = body.containsKey("skill_names") || body.containsKey("skills");
                List<String> skills = stringList(value(body, "skill_names", "skills"));
                if (hasSkillField) {
                    agent.setSkills(skills);
                    materializeInitialSkills(id, skills);
                }
            }
            configManager.save();
            workspaceManager.writeAgentJson(configManager.resolveWorkspaceDir(id), id, agent);
            multiAgentManager.reload(id);
            return ResponseEntity.ok(agentPayload(id, agent, true));
        });
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<?>> delete(
            @PathVariable String id,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.fromCallable(() -> {
            if (!agentAccessService.canAccess(principal, id)) {
                return ResponseEntity.status(403).body(Map.of("detail", "Agent access denied"));
            }
            if ("default".equals(id)) {
                return ResponseEntity.status(409).body(Map.of("detail", "Default agent cannot be deleted"));
            }
            Map<String, AgentConfig> agents = mutableAgents();
            boolean removed = agents.remove(id) != null;
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            Path workspaceDir = configManager.resolveWorkspaceDir(id);
            configManager.getConfig().setAgents(agents);
            configManager.save();
            multiAgentManager.stop(id);
            boolean workspaceRemoved = deleteWorkspaceIfSafe(workspaceDir);
            return ResponseEntity.ok(Map.of(
                    "success", removed,
                    "agent_id", id,
                    "workspace_dir", workspaceDir.toString(),
                    "workspace_removed", workspaceRemoved
            ));
        });
    }

    @PutMapping("/order")
    public Mono<ResponseEntity<?>> reorder(
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.fromCallable(() -> {
            if (!principal.hasPermission(SYSTEM_MANAGE)) {
                return ResponseEntity.status(403).body(Map.of(
                        "detail", "Only a system administrator can change the global Agent order"));
            }
            List<String> requested = stringList(body != null ? body.get("agent_ids") : null);
            Map<String, AgentConfig> current = mutableAgents();
            LinkedHashMap<String, AgentConfig> ordered = new LinkedHashMap<>();
            for (String id : requested) {
                AgentConfig config = current.remove(id);
                if (config != null) {
                    ordered.put(id, config);
                }
            }
            ordered.putAll(current);
            configManager.getConfig().setAgents(ordered);
            configManager.save();
            return ResponseEntity.ok(Map.of("success", true, "agent_ids", new ArrayList<>(ordered.keySet())));
        });
    }

    @PatchMapping("/{id}/toggle")
    public Mono<ResponseEntity<?>> toggle(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return Mono.fromCallable(() -> {
            if (!agentAccessService.canAccess(principal, id)) {
                return ResponseEntity.status(403).body(Map.of("detail", "Agent access denied"));
            }
            if ("default".equals(id)) {
                return ResponseEntity.status(409).body(Map.of("detail", "Default agent cannot be disabled"));
            }
            AgentConfig agent = configManager.getConfig().getAgent(id);
            if (agent == null) {
                return ResponseEntity.notFound().build();
            }
            boolean enabled = booleanValue(body == null ? null : body.get("enabled"), true);
            agent.setEnabled(enabled);
            configManager.save();
            if (enabled) {
                multiAgentManager.reload(id);
            } else {
                multiAgentManager.stop(id);
            }
            workspaceManager.writeAgentJson(configManager.resolveWorkspaceDir(id), id, agent);
            return ResponseEntity.ok(Map.of("success", true, "agent_id", id, "enabled", enabled));
        });
    }

    private Map<String, AgentConfig> mutableAgents() {
        return new LinkedHashMap<>(configManager.getConfig().getAgents());
    }

    private Map<String, Object> agentPayload(String id, AgentConfig config, boolean includeInternalDetails) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("agent_id", id);
        result.put("name", config.getName() != null ? config.getName() : id);
        result.put("description", config.getDescription() != null ? config.getDescription() : "");
        if (includeInternalDetails) {
            result.put("workspace_dir", configManager.resolveWorkspaceDir(id).toString());
        }
        EnterpriseIdentityStore.AgentBindingRecord binding = agentAccessService.binding(id);
        result.put("scope", binding != null ? binding.scopeType() : "SERVICE");
        if (binding != null && binding.projectId() != null) result.put("project_id", binding.projectId());
        result.put("enabled", config.isEnabled());
        result.put("active_model", activeModel(config.getActiveModel()));
        if (!includeInternalDetails) {
            return result;
        }
        result.put("approval_level", config.getApproval() != null ? config.getApproval().getLevel() : "RISK_BASED");
        result.put("system_prompt_files", config.getSystemPromptFiles());
        result.put("skills", config.getSkills());
        result.put("tools", config.getTools());
        result.put("running", config.getRunning());
        result.put("channels", Map.of());
        result.put("mcp", Map.of());
        result.put("heartbeat", Map.of("enabled", config.getHeartbeat() != null && config.getHeartbeat().isEnabled()));
        return result;
    }

    private Map<String, Object> activeModel(String active) {
        String effective = active;
        String source = "AGENT_OVERRIDE";
        if (effective == null || effective.isBlank()) {
            Map<String, String> global = providerManager.getActiveModel();
            String provider = global.get("provider_id");
            String model = global.get("model");
            if (provider != null && !provider.isBlank() && model != null && !model.isBlank()) {
                effective = provider + ":" + model;
                source = "GLOBAL_DEFAULT";
            }
        }
        if (effective == null || effective.isBlank()) {
            return Map.of("configured", false, "source", "UNCONFIGURED");
        }
        int idx = effective.indexOf(':');
        return idx > 0 && idx < effective.length() - 1
                ? Map.of("provider_id", effective.substring(0, idx),
                        "model", effective.substring(idx + 1), "configured", true, "source", source)
                : Map.of("provider_id", "dashscope", "model", effective,
                        "configured", true, "source", source);
    }

    @SuppressWarnings("unchecked")
    private void applyActiveModel(AgentConfig agent, Object value) {
        if (value instanceof Map<?, ?> map) {
            String provider = trimmedStringValue(map.get("provider_id"), "");
            String model = trimmedStringValue(map.get("model"), "");
            if (!provider.isBlank() && !model.isBlank()) {
                agent.setActiveModel(provider + ":" + model);
            }
            return;
        }
        String text = trimmedStringValue(value, "");
        if (!text.isBlank()) {
            agent.setActiveModel(text);
        }
    }

    private Object value(Map<String, Object> body, String... keys) {
        if (body == null) return null;
        for (String key : keys) {
            Object value = body.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    private void materializeInitialSkills(String agentId, List<String> skillNames) throws IOException {
        for (String skill : skillNames) {
            skillService.downloadPoolSkillToWorkspace(agentId, skill, true);
        }
    }

    private boolean deleteWorkspaceIfSafe(Path workspaceDir) throws IOException {
        return workspaceManager.deleteWorkspaceIfUnderRoot(workspaceDir, configManager.resolveWorkspaceRootDir());
    }

    private String validateManagedWorkspacePath(String value) {
        try {
            return configManager.validateManagedWorkspacePath(value).toString();
        } catch (IllegalArgumentException e) {
            throw new SecurityException(e.getMessage(), e);
        }
    }

    private String sanitizeId(String raw) {
        String id = raw.replaceAll("[^A-Za-z0-9_.-]", "_");
        return id.isBlank() ? "agent" : id;
    }
}
