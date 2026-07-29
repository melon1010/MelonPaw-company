package com.melon.app.auth;

import com.melon.app.service.SecuritySettingsService;
import com.melon.app.service.SkillService;
import com.melon.app.service.AuditLogService;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.ConfigManager;
import com.melon.core.config.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EnterpriseAgentAccessServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void projectMembersCanExecuteOnlyTheirPublishedProjectAgent() {
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(tempDir.resolve("project-agent-access.db"));
        AuthService auth = new AuthService(store, Duration.ofHours(1), new SecureRandom());
        EnterprisePrincipal admin = auth.bootstrap("admin", "Administrator", "StrongPassword123");
        EnterprisePrincipal member = auth.createUser(
                "member", "Member", "MemberPassword123", true, Set.of("USER"));
        EnterprisePrincipal manager = auth.createUser(
                "manager", "Manager", "ManagerPassword123", true, Set.of("USER"));
        EnterprisePrincipal viewer = auth.createUser(
                "viewer", "Viewer", "ViewerPassword123", true, Set.of("USER"));
        EnterpriseIdentityStore.ProjectSpaceRecord project = store.createProject(
                "project-one", "Project One", "", admin.id());
        store.putProjectMember(project.id(), member.id(), "MEMBER");
        store.putProjectMember(project.id(), manager.id(), "MANAGER");
        store.putProjectMember(project.id(), viewer.id(), "VIEWER");
        store.bindProjectAgent("project-agent", project.id());

        ConfigManager config = configManager();
        AgentConfig projectAgent = new AgentConfig();
        projectAgent.setName("Project Agent");
        var agents = new LinkedHashMap<>(config.getConfig().getAgents());
        agents.put("project-agent", projectAgent);
        config.getConfig().setAgents(agents);
        EnterpriseAgentAccessService access = new EnterpriseAgentAccessService(
                store, config, new WorkspaceManager());

        assertEquals("project-agent", access.resolveExecution(member, "project-agent"));
        assertTrue(access.accessibleAgentIds(member).contains("project-agent"));
        assertFalse(access.canManageConfiguration(member, "project-agent"));
        assertTrue(access.canManageConfiguration(manager, "project-agent"));
        assertTrue(access.canManageConfiguration(admin, "project-agent"));
        assertThrows(SecurityException.class,
                () -> access.resolveExecution(viewer, "project-agent"));
    }

    @Test
    void administratorsCanResolveSkillDistributionTargetsWithoutWorkspaceAccess() throws Exception {
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(tempDir.resolve("distribution.db"));
        AuthService auth = new AuthService(store, Duration.ofHours(1), new SecureRandom());
        EnterprisePrincipal administrator = auth.bootstrap(
                "admin", "Administrator", "StrongPassword123");
        EnterprisePrincipal employee = auth.createUser(
                "employee", "Employee", "EmployeePassword123", true, Set.of("USER"));

        EnterpriseAgentAccessService access = new EnterpriseAgentAccessService(
                store, configManager(), new WorkspaceManager());
        String employeeAgent = access.ensurePersonalAgent(employee);

        List<EnterpriseAgentAccessService.PersonalAgentTarget> targets =
                access.distributablePersonalAgents(administrator);
        assertTrue(targets.stream().anyMatch(target -> target.agentId().equals(employeeAgent)));
        assertEquals(List.of(employeeAgent), access.resolveSkillDistributionTargets(
                administrator, List.of(employeeAgent), false));
        assertThrows(SecurityException.class, () -> access.resolveSkillDistributionTargets(
                administrator, List.of("personal-forged"), false));
    }

    @Test
    void provisionsIsolatedPersonalAgentsAndRejectsForgedScopes() throws Exception {
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(tempDir.resolve("enterprise.db"));
        AuthService auth = new AuthService(store, Duration.ofHours(1), new SecureRandom());
        auth.bootstrap("admin", "Administrator", "StrongPassword123");
        EnterprisePrincipal first = auth.createUser(
                "employee-one", "Employee One", "EmployeePassword123", true, Set.of("USER"));
        EnterprisePrincipal second = auth.createUser(
                "employee-two", "Employee Two", "EmployeePassword123", true, Set.of("USER"));

        ConfigManager configManager = configManager();
        WorkspaceManager workspaceManager = new WorkspaceManager();
        EnterpriseAgentAccessService access = new EnterpriseAgentAccessService(
                store, configManager, workspaceManager);

        String firstAgent = access.ensurePersonalAgent(first);
        String secondAgent = access.ensurePersonalAgent(second);

        assertNotEquals(firstAgent, secondAgent);
        assertEquals(firstAgent, access.resolve(first, "default"));
        assertEquals(firstAgent, access.resolve(first, secondAgent));
        assertEquals(Set.of(firstAgent), Set.copyOf(access.accessibleAgentIds(first)));

        Path firstWorkspace = configManager.resolveWorkspaceDir(firstAgent);
        Path secondWorkspace = configManager.resolveWorkspaceDir(secondAgent);
        assertNotEquals(firstWorkspace, secondWorkspace);
        assertTrue(firstWorkspace.startsWith(configManager.resolveWorkspaceRootDir()));
        assertTrue(Files.isRegularFile(firstWorkspace.resolve("AGENTS.md")));
        var personalTools = configManager.getConfig().getAgent(firstAgent).getTools().getBuiltinTools();
        for (String retired : Set.of(
                "desktop_screenshot", "delegate_external_agent", "list_agents",
                "chat_with_agent", "submit_to_agent", "check_agent_task", "spawn_subagent")) {
            assertFalse(personalTools.containsKey(retired), "retired tool remains configurable: " + retired);
        }
        assertTrue(configManager.getConfig().getAgent(firstAgent)
                .getTools().getBuiltinTools().get("execute_shell_command").isEnabled());
    }

    @Test
    void existingPersonalAgentDoesNotRewriteGlobalConfigOnEveryRequest() throws Exception {
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(tempDir.resolve("stable-config.db"));
        AuthService auth = new AuthService(store, Duration.ofHours(1), new SecureRandom());
        auth.bootstrap("admin", "Administrator", "StrongPassword123");
        EnterprisePrincipal employee = auth.createUser(
                "stable-user", "Stable User", "EmployeePassword123", true, Set.of("USER"));

        CountingConfigManager configManager = new CountingConfigManager();
        configManager.setConfigPath(tempDir.resolve("stable-config.yaml"));
        configManager.setHomeDir(tempDir.resolve("stable-home").toString());
        configManager.load();
        EnterpriseAgentAccessService access = new EnterpriseAgentAccessService(
                store, configManager, new WorkspaceManager());

        access.ensurePersonalAgent(employee);
        int writesAfterProvisioning = configManager.saveCount;
        access.ensurePersonalAgent(employee);
        access.ensurePersonalAgent(employee);

        assertEquals(writesAfterProvisioning, configManager.saveCount);
    }

    @Test
    void rejectsUnknownAgentsAndUnsafeSkillNames() throws Exception {
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(tempDir.resolve("skills.db"));
        AuthService auth = new AuthService(store, Duration.ofHours(1), new SecureRandom());
        auth.bootstrap("admin", "Administrator", "StrongPassword123");
        EnterprisePrincipal employee = auth.createUser(
                "employee", "Employee", "EmployeePassword123", true, Set.of("USER"));

        ConfigManager configManager = configManager();
        WorkspaceManager workspaceManager = new WorkspaceManager();
        EnterpriseAgentAccessService access = new EnterpriseAgentAccessService(
                store, configManager, workspaceManager);
        String personalAgent = access.ensurePersonalAgent(employee);

        assertThrows(IllegalArgumentException.class,
                () -> configManager.resolveWorkspaceDir("missing-agent"));
        assertThrows(IllegalArgumentException.class,
                () -> configManager.resolveWorkspaceDir("../../outside"));

        SkillService skillService = new SkillService(
                configManager, workspaceManager, new SecuritySettingsService(configManager));
        assertThrows(IOException.class,
                () -> skillService.createSkill(personalAgent, "../outside", "# unsafe"));
        assertFalse(Files.exists(configManager.resolveWorkspaceDir(personalAgent)
                .resolve("outside").resolve("SKILL.md")));
    }

    @Test
    void usersCannotEnterAnotherUsersPersonalAgent() throws Exception {
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(tempDir.resolve("operator-scope.db"));
        AuthService auth = new AuthService(store, Duration.ofHours(1), new SecureRandom());
        EnterprisePrincipal systemAdministrator = auth.bootstrap(
                "admin", "Administrator", "StrongPassword123");
        EnterprisePrincipal operator = auth.createUser(
                "operator", "Operator", "OperatorPassword123", true, Set.of("USER"));
        EnterprisePrincipal employee = auth.createUser(
                "employee", "Employee", "EmployeePassword123", true, Set.of("USER"));

        ConfigManager configManager = configManager();
        AuditLogService auditLogService = mock(AuditLogService.class);
        EnterpriseAgentAccessService access = new EnterpriseAgentAccessService(
                store, configManager, new WorkspaceManager(), null, auditLogService);
        String employeeAgent = access.ensurePersonalAgent(employee);
        String operatorAgent = access.ensurePersonalAgent(operator);

        assertThrows(SecurityException.class, () -> access.resolve(operator, employeeAgent));
        assertFalse(access.canAccess(operator, employeeAgent));
        assertTrue(access.canAccess(operator, operatorAgent));
        assertFalse(access.canAccess(operator, "default"));
        assertEquals(operatorAgent, access.resolve(operator, "default"));
        assertFalse(access.accessibleAgentIds(operator).contains(employeeAgent));
        assertTrue(access.accessibleAgentIds(operator).contains(operatorAgent));
        assertFalse(access.accessibleAgentIds(operator).contains("default"));
        assertThrows(SecurityException.class,
                () -> access.resolve(systemAdministrator, employeeAgent));
        assertFalse(access.canAccess(systemAdministrator, employeeAgent));
        assertFalse(access.canAccess(systemAdministrator, "default"));
        assertFalse(access.accessibleAgentIds(systemAdministrator).contains("default"));
        assertEquals(access.ensurePersonalAgent(systemAdministrator),
                access.resolve(systemAdministrator, "default"));

        EnterprisePrincipal breakGlassAdministrator = new EnterprisePrincipal(
                systemAdministrator.id(),
                systemAdministrator.username(),
                systemAdministrator.displayName(),
                systemAdministrator.enabled(),
                systemAdministrator.roles(),
                Set.of(EnterprisePermissions.DATA_BREAKGLASS),
                systemAdministrator.createdAt(),
                systemAdministrator.updatedAt());
        assertEquals(employeeAgent, access.resolve(breakGlassAdministrator, employeeAgent));
        assertTrue(access.canAccess(breakGlassAdministrator, employeeAgent));
        verify(auditLogService).recordApproval(
                anyString(), anyString(), anyString(), any(), anyBoolean(), anyString(), any());
    }

    private ConfigManager configManager() {
        ConfigManager manager = new ConfigManager();
        manager.setConfigPath(tempDir.resolve("config.yaml"));
        manager.setHomeDir(tempDir.resolve("home").toString());
        manager.load();
        return manager;
    }

    private static final class CountingConfigManager extends ConfigManager {
        private int saveCount;

        @Override
        public synchronized void save() {
            saveCount++;
            super.save();
        }
    }
}
