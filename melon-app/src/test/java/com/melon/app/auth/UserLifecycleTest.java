package com.melon.app.auth;

import com.melon.app.service.SecuritySettingsService;
import com.melon.app.service.SkillService;
import com.melon.core.agent.AgentScopedStateStore;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void createsUserWithSelectedPoolSkillsAndDeletesEveryPersonalResource() throws Exception {
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(tempDir.resolve("enterprise.db"));
        AuthService auth = new AuthService(store, Duration.ofHours(1), new SecureRandom());
        EnterprisePrincipal administrator = auth.bootstrap(
                "admin", "Administrator", "StrongPassword123");
        ConfigManager configManager = configManager();
        WorkspaceManager workspaceManager = new WorkspaceManager();
        MultiAgentManager multiAgentManager = mock(MultiAgentManager.class);
        EnterpriseAgentAccessService accessService = new EnterpriseAgentAccessService(
                store, configManager, workspaceManager, null, null, multiAgentManager);
        SkillService skillService = new SkillService(
                configManager, workspaceManager, new SecuritySettingsService(configManager));
        skillService.createPoolSkill("news", "---\nname: news\ndescription: News lookup\n---\n# News\n");
        UserAdminController controller = new UserAdminController(
                auth, store, accessService, skillService);

        var createResponse = controller.create(Map.of(
                "username", "employee",
                "display_name", "Employee",
                "password", "EmployeePassword123",
                "roles", List.of("EMPLOYEE"),
                "default_skills", List.of("news")
        ), administrator).block();

        assertNotNull(createResponse);
        assertEquals(201, createResponse.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> createdBody = (Map<String, Object>) createResponse.getBody();
        String userId = String.valueOf(createdBody.get("id"));
        assertEquals(List.of("news"), createdBody.get("initialized_skills"));
        EnterprisePrincipal employee = store.findUserById(userId).orElseThrow();
        String agentId = store.findPersonalAgentBinding(userId).orElseThrow().agentId();
        Path workspace = configManager.resolveWorkspaceDir(agentId);
        assertTrue(Files.isRegularFile(workspace.resolve("skills/news/SKILL.md")));

        Path stateDirectory = configManager.resolveStateDir().resolve(
                AgentScopedStateStore.scopedUserId(agentId, userId));
        Files.createDirectories(stateDirectory);
        Files.writeString(stateDirectory.resolve("state.json"), "{}");
        var token = auth.login(employee.username(), "EmployeePassword123");
        assertTrue(token.success());
        var project = store.createProject("EMPLOYEE-PROJECT", "Employee project", "", userId);

        var deleteResponse = controller.delete(userId, administrator).block();

        assertNotNull(deleteResponse);
        assertEquals(200, deleteResponse.getStatusCode().value());
        assertTrue(store.findUserById(userId).isEmpty());
        assertTrue(store.findPersonalAgentBinding(userId).isEmpty());
        assertFalse(configManager.getConfig().getAgents().containsKey(agentId));
        assertFalse(Files.exists(workspace));
        assertFalse(Files.exists(stateDirectory));
        assertTrue(auth.authenticateToken(token.token()).isEmpty());
        assertEquals(administrator.id(), store.findProject(project.id(), administrator)
                .orElseThrow().createdBy());
        assertTrue(store.listProjectMembers(project.id()).stream().anyMatch(member ->
                administrator.id().equals(member.userId()) && "OWNER".equals(member.projectRole())));
        verify(multiAgentManager).stop(agentId);
    }

    @Test
    void userWithoutInitialSelectionCanSynchronizeFromPoolLater() throws Exception {
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(tempDir.resolve("sync.db"));
        AuthService auth = new AuthService(store, Duration.ofHours(1), new SecureRandom());
        EnterprisePrincipal administrator = auth.bootstrap(
                "admin", "Administrator", "StrongPassword123");
        ConfigManager configManager = configManager();
        WorkspaceManager workspaceManager = new WorkspaceManager();
        EnterpriseAgentAccessService accessService = new EnterpriseAgentAccessService(
                store, configManager, workspaceManager);
        SkillService skillService = new SkillService(
                configManager, workspaceManager, new SecuritySettingsService(configManager));
        skillService.createPoolSkill("documents", "---\nname: documents\ndescription: Documents\n---\n# Documents\n");
        UserAdminController controller = new UserAdminController(
                auth, store, accessService, skillService);

        var response = controller.create(Map.of(
                "username", "employee",
                "display_name", "Employee",
                "password", "EmployeePassword123",
                "roles", List.of("EMPLOYEE")
        ), administrator).block();

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        String userId = String.valueOf(body.get("id"));
        String agentId = store.findPersonalAgentBinding(userId).orElseThrow().agentId();
        assertEquals(List.of(), body.get("initialized_skills"));
        assertTrue(skillService.listSkills(agentId).isEmpty());

        assertEquals(List.of("documents"),
                skillService.syncPoolSkillsToWorkspace(agentId, List.of("documents")));
        assertEquals("documents", skillService.listSkills(agentId).get(0).get("name"));
    }

    @Test
    void rejectsUnknownInitialSkillBeforeCreatingUser() {
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(tempDir.resolve("validation.db"));
        AuthService auth = new AuthService(store, Duration.ofHours(1), new SecureRandom());
        EnterprisePrincipal administrator = auth.bootstrap(
                "admin", "Administrator", "StrongPassword123");
        ConfigManager configManager = configManager();
        WorkspaceManager workspaceManager = new WorkspaceManager();
        SkillService skillService = new SkillService(
                configManager, workspaceManager, new SecuritySettingsService(configManager));
        UserAdminController controller = new UserAdminController(
                auth, store, new EnterpriseAgentAccessService(store, configManager, workspaceManager), skillService);

        assertThrows(Exception.class, () -> controller.create(Map.of(
                "username", "employee",
                "display_name", "Employee",
                "password", "EmployeePassword123",
                "default_skills", List.of("missing")
        ), administrator).block());
        assertEquals(1, store.countUsers());
    }

    @Test
    void administratorCannotDeleteOwnAccount() {
        EnterpriseIdentityStore store = new EnterpriseIdentityStore(tempDir.resolve("self-delete.db"));
        AuthService auth = new AuthService(store, Duration.ofHours(1), new SecureRandom());
        EnterprisePrincipal administrator = auth.bootstrap(
                "admin", "Administrator", "StrongPassword123");
        UserAdminController controller = new UserAdminController(auth, store);

        assertThrows(IllegalArgumentException.class,
                () -> controller.delete(administrator.id(), administrator).block());
        assertEquals(1, store.countUsers());
    }

    private ConfigManager configManager() {
        ConfigManager manager = new ConfigManager();
        manager.setConfigPath(tempDir.resolve("config.yaml"));
        manager.setHomeDir(tempDir.resolve("home").toString());
        manager.load();
        return manager;
    }
}
