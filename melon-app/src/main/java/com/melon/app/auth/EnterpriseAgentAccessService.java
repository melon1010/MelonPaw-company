package com.melon.app.auth;

import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.BuiltinToolConfig;
import com.melon.core.config.ConfigManager;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves authenticated users to the Agent workspaces they are allowed to use. */
@Service
public class EnterpriseAgentAccessService {

    private static final Set<String> PERSONAL_BLOCKED_TOOLS = Set.of("execute_shell_command");

    private final EnterpriseIdentityStore identityStore;
    private final ConfigManager configManager;
    private final WorkspaceManager workspaceManager;

    public EnterpriseAgentAccessService(
            EnterpriseIdentityStore identityStore,
            ConfigManager configManager,
            WorkspaceManager workspaceManager) {
        this.identityStore = identityStore;
        this.configManager = configManager;
        this.workspaceManager = workspaceManager;
    }

    public String resolve(EnterprisePrincipal principal, String requestedAgentId) {
        if (principal == null) {
            throw new SecurityException("Authentication is required");
        }
        String requested = normalize(requestedAgentId);
        if (principal.hasPermission("agents:manage")) {
            return requested;
        }
        // Project Agent execution remains disabled until delegation, task ownership,
        // approval and quota policies are all project-scoped.
        return ensurePersonalAgent(principal);
    }

    public boolean canAccess(EnterprisePrincipal principal, String agentId) {
        if (principal == null) return false;
        if (principal.hasPermission("agents:manage")) return true;
        String personalAgentId = ensurePersonalAgent(principal);
        return personalAgentId.equals(normalize(agentId));
    }

    public List<String> accessibleAgentIds(EnterprisePrincipal principal) {
        if (principal.hasPermission("agents:manage")) {
            return List.copyOf(configManager.getConfig().getAgents().keySet());
        }
        return List.of(ensurePersonalAgent(principal));
    }

    public synchronized String ensurePersonalAgent(EnterprisePrincipal principal) {
        String agentId = identityStore.findPersonalAgentBinding(principal.id())
                .map(EnterpriseIdentityStore.AgentBindingRecord::agentId)
                .orElseGet(() -> {
                    String generated = "personal-" + principal.id().replaceAll("[^A-Za-z0-9_.-]", "_");
                    identityStore.bindPersonalAgent(generated, principal.id());
                    return generated;
                });

        AgentConfig existing = configManager.getConfig().getAgent(agentId);
        if (existing == null) {
            AgentConfig personal = personalAgent(principal);
            Map<String, AgentConfig> agents = new LinkedHashMap<>(configManager.getConfig().getAgents());
            agents.put(agentId, personal);
            configManager.getConfig().setAgents(agents);

            Path workspace = Path.of(personal.getWorkspaceDir()).toAbsolutePath().normalize();
            workspaceManager.initWorkspace(workspace);
            workspaceManager.writeAgentJson(workspace, agentId, personal);
            configManager.save();
        } else {
            enforcePersonalPolicy(existing);
        }
        return agentId;
    }

    public boolean isPersonalAgent(String agentId) {
        return identityStore.findAgentBinding(normalize(agentId))
                .map(binding -> "PERSONAL".equals(binding.scopeType()))
                .orElse(false);
    }

    private AgentConfig personalAgent(EnterprisePrincipal principal) {
        AgentConfig personal = new AgentConfig();
        String displayName = principal.displayName() == null || principal.displayName().isBlank()
                ? principal.username()
                : principal.displayName();
        personal.setName(displayName + " 的工作空间");
        personal.setDescription("个人技能、文件与渠道的隔离运行空间");
        personal.setWorkspaceDir(configManager.resolveWorkspaceRootDir()
                .resolve("users")
                .resolve(principal.id())
                .toAbsolutePath()
                .normalize()
                .toString());

        AgentConfig defaultAgent = configManager.getConfig().getAgent("default");
        if (defaultAgent != null) {
            personal.setActiveModel(defaultAgent.getActiveModel());
            personal.setLanguage(defaultAgent.getLanguage());
            personal.setTimezone(defaultAgent.getTimezone());
        }
        personal.setSystemPromptFiles(List.of("AGENTS.md", "PROFILE.md", "MEMORY.md"));
        personal.getApproval().setLevel("SMART");
        enforcePersonalPolicy(personal);
        return personal;
    }

    private void enforcePersonalPolicy(AgentConfig personal) {
        Map<String, BuiltinToolConfig> tools = personal.getTools().getBuiltinTools();
        if (tools != null) {
            PERSONAL_BLOCKED_TOOLS.forEach(name -> {
                BuiltinToolConfig tool = tools.get(name);
                if (tool != null) tool.setEnabled(false);
            });
        }
        Map<String, Boolean> asyncTools = new LinkedHashMap<>(personal.getTools().getAsyncExecutionTools());
        PERSONAL_BLOCKED_TOOLS.forEach(name -> asyncTools.put(name, false));
        personal.getTools().setAsyncExecutionTools(asyncTools);
    }

    private String normalize(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId.trim();
    }
}
