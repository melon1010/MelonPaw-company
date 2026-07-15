package com.melon.app.controller;

import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.service.FileGuardService;
import com.melon.app.service.SecuritySettingsService;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilesPreviewIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void previewCannotReadAnotherAgentWorkspace() throws Exception {
        ConfigManager configManager = new ConfigManager();
        configManager.setConfigPath(tempDir.resolve("config.yaml"));
        configManager.setHomeDir(tempDir.resolve("home").toString());
        configManager.load();

        var agents = new LinkedHashMap<>(configManager.getConfig().getAgents());
        AgentConfig other = new AgentConfig();
        other.setName("Other");
        agents.put("other", other);
        configManager.getConfig().setAgents(agents);

        WorkspaceManager workspaceManager = new WorkspaceManager();
        Path defaultWorkspace = configManager.resolveWorkspaceDir("default");
        Path otherWorkspace = configManager.resolveWorkspaceDir("other");
        workspaceManager.initWorkspace(defaultWorkspace);
        workspaceManager.initWorkspace(otherWorkspace);
        Path secret = otherWorkspace.resolve("secret.txt");
        Files.writeString(secret, "not-visible");

        SecuritySettingsService settings = new SecuritySettingsService(configManager);
        FilesPreviewController controller = new FilesPreviewController(
                configManager, new FileGuardService(settings));

        var response = controller.preview(employee(), "default", secret.toString()).block();
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void employeeCannotPreviewRuntimeCredentialsButCanPreviewTaskOutput() throws Exception {
        ConfigManager configManager = new ConfigManager();
        configManager.setConfigPath(tempDir.resolve("config.yaml"));
        configManager.setHomeDir(tempDir.resolve("home").toString());
        configManager.load();

        Path workspace = configManager.resolveWorkspaceDir("default");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("credentials.yaml"), "secret: value");
        Files.writeString(workspace.resolve("result.txt"), "task result");

        SecuritySettingsService settings = new SecuritySettingsService(configManager);
        FilesPreviewController controller = new FilesPreviewController(
                configManager, new FileGuardService(settings));

        assertEquals(HttpStatus.FORBIDDEN,
                controller.preview(employee(), "default", "credentials.yaml").block().getStatusCode());
        assertEquals(HttpStatus.OK,
                controller.preview(employee(), "default", "result.txt").block().getStatusCode());
    }

    @Test
    void outsideWorkspacePreviewIsDisabledEvenWithBreakGlass() throws Exception {
        ConfigManager configManager = new ConfigManager();
        configManager.setConfigPath(tempDir.resolve("breakglass-config.yaml"));
        configManager.setHomeDir(tempDir.resolve("breakglass-home").toString());
        configManager.load();
        Path workspace = configManager.resolveWorkspaceDir("default");
        Files.createDirectories(workspace);
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "outside");

        SecuritySettingsService settings = new SecuritySettingsService(configManager);
        FilesPreviewController controller = new FilesPreviewController(
                configManager, new FileGuardService(settings));

        EnterprisePrincipal admin = new EnterprisePrincipal(
                "admin-id", "admin", "Admin", true,
                Set.of("SYSTEM_ADMIN"), Set.of(), "", "");
        EnterprisePrincipal breakGlass = new EnterprisePrincipal(
                "admin-id", "admin", "Admin", true,
                Set.of("SYSTEM_ADMIN"), Set.of("data:breakglass"), "", "");
        assertEquals(HttpStatus.FORBIDDEN,
                controller.preview(admin, "default", outside.toString()).block().getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller.preview(breakGlass, "default", outside.toString()).block().getStatusCode());
    }

    private EnterprisePrincipal employee() {
        return new EnterprisePrincipal(
                "employee-id", "employee", "Employee", true,
                Set.of("EMPLOYEE"), Set.of("agents:use"), "", "");
    }
}
