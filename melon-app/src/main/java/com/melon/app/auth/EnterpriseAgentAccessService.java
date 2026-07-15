package com.melon.app.auth;

import com.melon.core.agent.WorkspaceManager;
import com.melon.core.agent.AgentScopedStateStore;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.BuiltinToolConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.provider.ProviderManager;
import com.melon.app.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.melon.app.auth.EnterprisePermissions.AGENTS_MANAGE;
import static com.melon.app.auth.EnterprisePermissions.AGENTS_USE;
import static com.melon.app.auth.EnterprisePermissions.AUDIT_READ;
import static com.melon.app.auth.EnterprisePermissions.DATA_BREAKGLASS;
import static com.melon.app.auth.EnterprisePermissions.PERSONAL_AUTOMATIONS;
import static com.melon.app.auth.EnterprisePermissions.PERSONAL_CHANNELS;
import static com.melon.app.auth.EnterprisePermissions.PERSONAL_SKILLS;
import static com.melon.app.auth.EnterprisePermissions.PERSONAL_WORKSPACE;
import static com.melon.app.auth.EnterprisePermissions.SYSTEM_MANAGE;

/** Resolves authenticated users to the Agent workspaces they are allowed to use. */
@Service
public class EnterpriseAgentAccessService {

    private static final Set<String> PERSONAL_BLOCKED_TOOLS = Set.of();

    private final EnterpriseIdentityStore identityStore;
    private final ConfigManager configManager;
    private final WorkspaceManager workspaceManager;
    private final ProviderManager providerManager;
    private final AuditLogService auditLogService;
    private final MultiAgentManager multiAgentManager;

    @Autowired
    public EnterpriseAgentAccessService(
            EnterpriseIdentityStore identityStore,
            ConfigManager configManager,
            WorkspaceManager workspaceManager,
            ProviderManager providerManager,
            AuditLogService auditLogService,
            MultiAgentManager multiAgentManager) {
        this.identityStore = identityStore;
        this.configManager = configManager;
        this.workspaceManager = workspaceManager;
        this.providerManager = providerManager;
        this.auditLogService = auditLogService;
        this.multiAgentManager = multiAgentManager;
    }

    public EnterpriseAgentAccessService(
            EnterpriseIdentityStore identityStore,
            ConfigManager configManager,
            WorkspaceManager workspaceManager,
            ProviderManager providerManager,
            AuditLogService auditLogService) {
        this(identityStore, configManager, workspaceManager, providerManager, auditLogService, null);
    }

    public EnterpriseAgentAccessService(
            EnterpriseIdentityStore identityStore,
            ConfigManager configManager,
            WorkspaceManager workspaceManager,
            ProviderManager providerManager) {
        this(identityStore, configManager, workspaceManager, providerManager, null, null);
    }

    public EnterpriseAgentAccessService(
            EnterpriseIdentityStore identityStore,
            ConfigManager configManager,
            WorkspaceManager workspaceManager) {
        this(identityStore, configManager, workspaceManager, null, null, null);
    }

    public String resolve(EnterprisePrincipal principal, String requestedAgentId) {
        if (principal == null) {
            throw new SecurityException("Authentication is required");
        }
        String requested = normalize(requestedAgentId);
        if (principal.hasPermission(SYSTEM_MANAGE)) {
            requireDataBoundary(principal, requested);
            return requested;
        }
        if (principal.hasPermission(AGENTS_MANAGE)) {
            requireNotForeignPersonalAgent(principal, requested);
            return requested;
        }
        if (!canUsePersonalAgent(principal)) {
            throw new SecurityException("Agent access permission is required");
        }
        // Project Agent execution remains disabled until delegation, ownership,
        // approval and quota policies are all project-scoped.
        return ensurePersonalAgent(principal);
    }

    public boolean canAccess(EnterprisePrincipal principal, String agentId) {
        if (principal == null) return false;
        String requested = normalize(agentId);
        if (principal.hasPermission(SYSTEM_MANAGE)) {
            return !isForeignPersonalAgent(principal, requested)
                    || principal.hasPermission(DATA_BREAKGLASS);
        }
        if (principal.hasPermission(AGENTS_MANAGE)) {
            return !isForeignPersonalAgent(principal, requested);
        }
        if (!canUsePersonalAgent(principal)) return false;
        String personalAgentId = ensurePersonalAgent(principal);
        return personalAgentId.equals(requested);
    }

    public List<String> accessibleAgentIds(EnterprisePrincipal principal) {
        if (principal.hasPermission(SYSTEM_MANAGE)) {
            return configManager.getConfig().getAgents().keySet().stream()
                    .filter(agentId -> !isForeignPersonalAgent(principal, agentId)
                            || principal.hasPermission(DATA_BREAKGLASS)
                    ).toList();
        }
        if (principal.hasPermission(AGENTS_MANAGE)) {
            return configManager.getConfig().getAgents().keySet().stream()
                    .filter(agentId -> !isForeignPersonalAgent(principal, agentId))
                    .toList();
        }
        if (!canUsePersonalAgent(principal)) {
            return List.of();
        }
        return List.of(ensurePersonalAgent(principal));
    }

    /** Resolves an audit target without granting auditors access to personal content. */
    public String resolveAuditScope(EnterprisePrincipal principal, String requestedAgentId) {
        if (principal == null || !principal.hasPermission(AUDIT_READ)) {
            throw new SecurityException("Audit read permission is required");
        }
        String requested = normalize(requestedAgentId);
        if (isForeignPersonalAgent(principal, requested)) {
            requireDataBoundary(principal, requested);
        }
        if (isPersonalAgent(requested) && !canAccess(principal, requested)
                && !principal.hasPermission(DATA_BREAKGLASS)) {
            throw new SecurityException("Personal audit records require owner or break-glass access");
        }
        return requested;
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
            boolean changed = enforcePersonalPolicy(existing);
            changed |= applyGlobalModel(existing);
            if (changed) {
                configManager.save();
            }
        }
        return agentId;
    }

    /**
     * Removes a personal Agent from the live configuration before its identity is deleted. The
     * returned plan retains enough information to restore the configuration if the database
     * transaction fails. Workspace and state files are deliberately removed only after the user
     * row and its authentication sessions have been deleted.
     */
    public synchronized PersonalAgentDeletion preparePersonalAgentDeletion(String userId) {
        Path usersRoot = configManager.resolveWorkspaceRootDir()
                .resolve("users").toAbsolutePath().normalize();
        Path workspace = directChild(usersRoot, userId, "user workspace");
        String agentId = identityStore.findPersonalAgentBinding(userId)
                .map(EnterpriseIdentityStore.AgentBindingRecord::agentId)
                .orElse(null);
        AgentConfig removedConfig = agentId == null
                ? null : configManager.getConfig().getAgent(agentId);
        if (removedConfig != null) {
            Path configuredWorkspace = configManager.resolveWorkspaceDir(agentId)
                    .toAbsolutePath().normalize();
            if (!workspace.equals(configuredWorkspace)) {
                throw new SecurityException(
                        "Personal Agent workspace does not match its managed user directory");
            }
            Map<String, AgentConfig> agents = new LinkedHashMap<>(configManager.getConfig().getAgents());
            agents.remove(agentId);
            configManager.getConfig().setAgents(agents);
            try {
                configManager.save();
            } catch (RuntimeException e) {
                agents.put(agentId, removedConfig);
                configManager.getConfig().setAgents(agents);
                throw e;
            }
            if (multiAgentManager != null) {
                multiAgentManager.stop(agentId);
            }
        }
        Path stateRoot = configManager.resolveStateDir().toAbsolutePath().normalize();
        Path stateDirectory = agentId == null ? null : directChild(
                stateRoot,
                AgentScopedStateStore.scopedUserId(agentId, userId),
                "Agent state directory");
        return new PersonalAgentDeletion(agentId, userId, workspace, stateDirectory, removedConfig);
    }

    public synchronized void restorePersonalAgent(PersonalAgentDeletion deletion) {
        if (deletion == null || deletion.agentId() == null || deletion.agentConfig() == null) {
            return;
        }
        Map<String, AgentConfig> agents = new LinkedHashMap<>(configManager.getConfig().getAgents());
        agents.put(deletion.agentId(), deletion.agentConfig());
        configManager.getConfig().setAgents(agents);
        configManager.save();
    }

    public PersonalAgentCleanupResult deletePersonalAgentFiles(PersonalAgentDeletion deletion) {
        if (deletion == null) {
            return new PersonalAgentCleanupResult(false, false);
        }
        try {
            Path usersRoot = configManager.resolveWorkspaceRootDir()
                    .resolve("users").toAbsolutePath().normalize();
            boolean workspaceRemoved = workspaceManager.deleteWorkspaceIfUnderRoot(
                    deletion.workspaceDirectory(), usersRoot);
            boolean stateRemoved = false;
            if (deletion.stateDirectory() != null) {
                stateRemoved = workspaceManager.deleteWorkspaceIfUnderRoot(
                        deletion.stateDirectory(), configManager.resolveStateDir());
            }
            return new PersonalAgentCleanupResult(workspaceRemoved, stateRemoved);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to remove personal Agent files", e);
        }
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
        applyGlobalModel(personal);
        personal.setSystemPromptFiles(List.of("AGENTS.md", "PROFILE.md", "MEMORY.md"));
        personal.getApproval().setLevel("SMART");
        enforcePersonalPolicy(personal);
        return personal;
    }

    private boolean enforcePersonalPolicy(AgentConfig personal) {
        boolean changed = false;
        Map<String, BuiltinToolConfig> tools = personal.getTools().getBuiltinTools();
        if (tools != null) {
            for (String name : PERSONAL_BLOCKED_TOOLS) {
                BuiltinToolConfig tool = tools.get(name);
                if (tool != null && tool.isEnabled()) {
                    tool.setEnabled(false);
                    changed = true;
                }
            }
        }
        Map<String, Boolean> asyncTools = new LinkedHashMap<>(personal.getTools().getAsyncExecutionTools());
        for (String name : PERSONAL_BLOCKED_TOOLS) {
            if (!Boolean.FALSE.equals(asyncTools.get(name))) {
                asyncTools.put(name, false);
                changed = true;
            }
        }
        if (changed) {
            personal.getTools().setAsyncExecutionTools(asyncTools);
        }
        return changed;
    }

    private boolean canUsePersonalAgent(EnterprisePrincipal principal) {
        return principal.hasAnyPermission(
                AGENTS_USE,
                PERSONAL_WORKSPACE,
                PERSONAL_SKILLS,
                PERSONAL_CHANNELS,
                PERSONAL_AUTOMATIONS
        );
    }

    private void requireNotForeignPersonalAgent(EnterprisePrincipal principal, String agentId) {
        if (isForeignPersonalAgent(principal, agentId)) {
            throw new SecurityException("Another user's personal Agent is not an operator-managed resource");
        }
    }

    private void requireDataBoundary(EnterprisePrincipal principal, String agentId) {
        if (!isForeignPersonalAgent(principal, agentId)) {
            return;
        }
        if (!principal.hasPermission(DATA_BREAKGLASS)) {
            throw new SecurityException(
                    "Administrative access does not include user content; request audited break-glass access");
        }
        if (auditLogService != null) {
            auditLogService.recordApproval(
                    agentId,
                    "breakglass-" + System.currentTimeMillis(),
                    "data_breakglass",
                    Map.of("agent_id", agentId),
                    true,
                    "explicit break-glass access",
                    Map.of(
                            "decision_actor_user_id", principal.id(),
                            "decision_actor_username", principal.username(),
                            "scope", "PERSONAL_AGENT"));
        }
    }

    private boolean applyGlobalModel(AgentConfig agent) {
        if (agent == null || providerManager == null) {
            return false;
        }
        Map<String, String> active = providerManager.getActiveModel();
        String provider = active.get("provider_id");
        String model = active.get("model");
        if (provider != null && !provider.isBlank() && model != null && !model.isBlank()) {
            String activeModel = provider + ":" + model;
            if (!activeModel.equals(agent.getActiveModel())) {
                agent.setActiveModel(activeModel);
                return true;
            }
        }
        return false;
    }

    private boolean isForeignPersonalAgent(EnterprisePrincipal principal, String agentId) {
        return identityStore.findAgentBinding(agentId)
                .filter(binding -> "PERSONAL".equals(binding.scopeType()))
                .map(binding -> !principal.id().equals(binding.ownerUserId()))
                .orElse(false);
    }

    private String normalize(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId.trim();
    }

    private Path directChild(Path root, String child, String description) {
        if (child == null || child.isBlank()) {
            throw new IllegalArgumentException(description + " id is required");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(child).normalize();
        if (!normalizedRoot.equals(target.getParent())) {
            throw new SecurityException("Invalid " + description);
        }
        return target;
    }

    public record PersonalAgentDeletion(
            String agentId,
            String userId,
            Path workspaceDirectory,
            Path stateDirectory,
            AgentConfig agentConfig) {
    }

    public record PersonalAgentCleanupResult(boolean workspaceRemoved, boolean stateRemoved) {
    }
}
