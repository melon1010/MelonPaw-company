package com.melon.app.controller;

import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.service.BuiltinSkillService;
import com.melon.app.service.SecuritySettingsService;
import com.melon.app.service.SkillService;
import com.melon.core.agent.MultiAgentManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillControllerIsolationTest {

    @Test
    void operatorPreviewChecksSelectedPersonalTargetsWithoutWriting() throws Exception {
        SkillService skillService = mock(SkillService.class);
        EnterpriseAgentAccessService accessService = mock(EnterpriseAgentAccessService.class);
        EnterprisePrincipal operator = new EnterprisePrincipal(
                "operator-1", "operator", "Operator", true,
                Set.of("SERVICE_OPERATOR"), Set.of("agents:manage"), "", "");
        when(accessService.resolveConfiguration(operator, "personal-operator-1"))
                .thenReturn("personal-operator-1");
        when(accessService.resolveSkillDistributionTargets(
                operator, List.of("personal-user-2"), false))
                .thenReturn(List.of("personal-user-2"));
        when(skillService.workspaceHasSkill("personal-user-2", "reporting"))
                .thenReturn(true);

        SkillController controller = controller(skillService, accessService);
        var response = controller.downloadSkillPoolSkill(
                "personal-operator-1", operator,
                Map.of(
                        "skill_name", "reporting",
                        "preview_only", true,
                        "targets", List.of(Map.of("workspace_id", "personal-user-2"))))
                .block();

        assertEquals(409, response.getStatusCode().value());
        verify(accessService).resolveSkillDistributionTargets(
                operator, List.of("personal-user-2"), false);
        verify(skillService).workspaceHasSkill("personal-user-2", "reporting");
        verify(skillService, never()).downloadPoolSkillToWorkspace(
                "personal-user-2", "reporting", false);
    }

    @Test
    void poolUploadCannotUseBodyWorkspaceIdWithoutAccessResolution() throws Exception {
        SkillService skillService = mock(SkillService.class);
        EnterpriseAgentAccessService accessService = mock(EnterpriseAgentAccessService.class);
        EnterprisePrincipal principal = principal();
        when(accessService.resolveConfiguration(principal, "personal-user-1"))
                .thenReturn("personal-user-1");
        when(skillService.uploadWorkspaceSkillToPool("personal-user-1", "reporting", false))
                .thenReturn(Map.of("success", true));

        SkillController controller = controller(skillService, accessService);
        var response = controller.uploadWorkspaceSkillToPool(
                "personal-user-1", principal,
                Map.of("workspace_id", "foreign-agent", "skill_name", "reporting"))
                .block();

        assertEquals(200, response.getStatusCode().value());
        verify(accessService).resolveConfiguration(principal, "personal-user-1");
        verify(skillService).uploadWorkspaceSkillToPool("personal-user-1", "reporting", false);
    }

    @Test
    void poolDownloadCannotUseBodyTargetWithoutAccessResolution() throws Exception {
        SkillService skillService = mock(SkillService.class);
        EnterpriseAgentAccessService accessService = mock(EnterpriseAgentAccessService.class);
        EnterprisePrincipal principal = principal();
        when(accessService.resolveConfiguration(principal, "personal-user-1"))
                .thenReturn("personal-user-1");
        when(skillService.downloadPoolSkillToWorkspace("personal-user-1", "reporting", true))
                .thenReturn(Map.of("downloaded", true));

        SkillController controller = controller(skillService, accessService);
        var response = controller.downloadSkillPoolSkill(
                "personal-user-1", principal,
                Map.of(
                        "skill_name", "reporting",
                        "overwrite", true,
                        "targets", List.of(Map.of("workspace_id", "foreign-agent"))))
                .block();

        assertEquals(200, response.getStatusCode().value());
        verify(accessService).resolveConfiguration(principal, "personal-user-1");
        verify(skillService).downloadPoolSkillToWorkspace("personal-user-1", "reporting", true);
    }

    @Test
    void operatorCanImportPoolSkillIntoSelectedProjectAgent() throws Exception {
        SkillService skillService = mock(SkillService.class);
        EnterpriseAgentAccessService accessService = mock(EnterpriseAgentAccessService.class);
        EnterprisePrincipal operator = new EnterprisePrincipal(
                "operator-1", "operator", "Operator", true,
                Set.of("SERVICE_OPERATOR"), Set.of("agents:manage"), "", "");
        when(accessService.resolveConfiguration(operator, "project-agent"))
                .thenReturn("project-agent");
        when(skillService.downloadPoolSkillToWorkspace("project-agent", "reporting", false))
                .thenReturn(Map.of("downloaded", true));

        SkillController controller = controller(skillService, accessService);
        var response = controller.downloadSkillPoolSkill(
                "project-agent", operator,
                Map.of(
                        "skill_name", "reporting",
                        "targets", List.of(Map.of("workspace_id", "project-agent"))))
                .block();

        assertEquals(200, response.getStatusCode().value());
        verify(accessService).resolveConfiguration(operator, "project-agent");
        verify(accessService, never()).resolveSkillDistributionTargets(
                operator, List.of("project-agent"), false);
        verify(skillService).downloadPoolSkillToWorkspace("project-agent", "reporting", false);
    }

    @Test
    void projectManagerCanImportPoolSkillIntoAuthorizedProjectAgent() throws Exception {
        SkillService skillService = mock(SkillService.class);
        EnterpriseAgentAccessService accessService = mock(EnterpriseAgentAccessService.class);
        EnterprisePrincipal manager = new EnterprisePrincipal(
                "manager-1", "manager", "Manager", true,
                Set.of("EMPLOYEE"), Set.of("skills:personal"), "", "");
        when(accessService.resolveConfiguration(manager, "project-agent"))
                .thenReturn("project-agent");
        when(skillService.downloadPoolSkillToWorkspace("project-agent", "reporting", false))
                .thenReturn(Map.of("downloaded", true));

        SkillController controller = controller(skillService, accessService);
        var response = controller.downloadSkillPoolSkill(
                "project-agent", manager,
                Map.of(
                        "skill_name", "reporting",
                        "targets", List.of(Map.of("workspace_id", "project-agent"))))
                .block();

        assertEquals(200, response.getStatusCode().value());
        verify(accessService).resolveConfiguration(manager, "project-agent");
        verify(skillService).downloadPoolSkillToWorkspace("project-agent", "reporting", false);
    }

    @Test
    void employeePoolCatalogDoesNotExposeHostPathsOrSharedConfiguration() {
        SkillService skillService = mock(SkillService.class);
        EnterpriseAgentAccessService accessService = mock(EnterpriseAgentAccessService.class);
        BuiltinSkillService builtinSkillService = mock(BuiltinSkillService.class);
        when(skillService.listPoolSkills()).thenReturn(List.of(Map.of(
                "name", "reporting",
                "description", "Create reports",
                "path", "C:/host/skill_pool/reporting",
                "external_path", "C:/host/source/reporting",
                "config", Map.of("secret", "value")
        )));
        when(builtinSkillService.syncInfo(org.mockito.ArgumentMatchers.eq("reporting"),
                org.mockito.ArgumentMatchers.any())).thenReturn(Map.of());
        SkillController controller = new SkillController(
                skillService,
                builtinSkillService,
                mock(SecuritySettingsService.class),
                mock(MultiAgentManager.class),
                accessService);

        var response = controller.listSkillPool("personal-user-1", principal()).block();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        Map<String, Object> skill = body.get(0);
        assertEquals("reporting", skill.get("name"));
        assertFalse(skill.containsKey("path"));
        assertFalse(skill.containsKey("external_path"));
        assertFalse(skill.containsKey("config"));
    }

    private SkillController controller(SkillService skillService,
                                       EnterpriseAgentAccessService accessService) {
        return new SkillController(
                skillService,
                mock(BuiltinSkillService.class),
                mock(SecuritySettingsService.class),
                mock(MultiAgentManager.class),
                accessService);
    }

    private EnterprisePrincipal principal() {
        return new EnterprisePrincipal(
                "user-1", "employee", "Employee", true,
                Set.of("EMPLOYEE"), Set.of("skills:personal"), "", "");
    }
}
