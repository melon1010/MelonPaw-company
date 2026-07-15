package com.melon.app.controller;

import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.service.FileGuardService;
import com.melon.app.service.WorkspaceMemoryService;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.config.MelonConfig;
import com.melon.core.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceControllerResourceLimitTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void workspaceUploadRejectsStreamingContentBeyondLimit() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        Files.createDirectories(workspace);
        WorkspaceController controller = controller(workspace);

        FilePart part = oversizedPart("oversized.bin", WorkspaceController.MAX_WORKSPACE_UPLOAD_BYTES);
        var response = controller.uploadWorkspaceFile("personal-user", part).block();

        assertNotNull(response);
        assertEquals(413, response.getStatusCode().value());
        assertFalse(Files.exists(workspace.resolve("oversized.bin")));
    }

    @Test
    void workspaceUploadStreamsContentToTarget() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace-success");
        Files.createDirectories(workspace);
        WorkspaceController controller = controller(workspace);
        byte[] content = "bounded upload".getBytes(StandardCharsets.UTF_8);
        FilePart part = part("bounded.txt", Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(content)));

        var response = controller.uploadWorkspaceFile("personal-user", part).block();

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("bounded upload", Files.readString(workspace.resolve("bounded.txt")));
    }

    @Test
    void transcriptionRejectsStreamingContentBeyondLimit() throws Exception {
        Path workspace = temporaryDirectory.resolve("transcription-workspace");
        JsonUtils.save(workspace.resolve(".melon").resolve("transcription.json"), Map.of(
                "transcription_provider_type", "whisper_api",
                "provider_id", "external",
                "base_url", "https://8.8.8.8"
        ));
        WorkspaceController controller = controller(workspace);

        FilePart part = oversizedPart("oversized.webm", WorkspaceController.MAX_TRANSCRIPTION_UPLOAD_BYTES);
        var response = controller.transcribeDisabled("personal-user", part).block();

        assertNotNull(response);
        assertEquals(413, response.getStatusCode().value());
    }

    @Test
    void streamedDownloadDeletesTemporaryFileAfterConsumption() throws Exception {
        Path file = temporaryDirectory.resolve("workspace.zip");
        Files.writeString(file, "zip-content");
        WorkspaceController controller = controller(temporaryDirectory.resolve("unused"));

        DataBuffer joined = DataBufferUtils.join(controller.streamFileAndDelete(file)).block();
        assertNotNull(joined);
        try {
            byte[] content = new byte[joined.readableByteCount()];
            joined.read(content);
            assertEquals("zip-content", new String(content, StandardCharsets.UTF_8));
        } finally {
            DataBufferUtils.release(joined);
        }
        assertFalse(Files.exists(file));
    }

    @Test
    void workspacePathIsLogicalForRegularUsersAndAbsoluteForSystemAdministrators() throws Exception {
        Path workspace = temporaryDirectory.resolve("private-host-path");
        Files.createDirectories(workspace);
        ConfigManager configManager = mock(ConfigManager.class);
        MelonConfig melonConfig = mock(MelonConfig.class);
        when(configManager.resolveWorkspaceDir("personal-user")).thenReturn(workspace);
        when(configManager.getConfig()).thenReturn(melonConfig);
        when(melonConfig.getAgent("personal-user")).thenReturn(new AgentConfig());
        WorkspaceController controller = controller(configManager);

        var regularResponse = controller.getWorkspace(
                "personal-user", principal(Set.of("workspace:personal"))).block();
        var adminResponse = controller.getWorkspace(
                "personal-user", principal(Set.of("system:manage"))).block();
        var regularInit = controller.initWorkspace(
                "personal-user", principal(Set.of("workspace:personal"))).block();

        assertNotNull(regularResponse);
        assertNotNull(adminResponse);
        assertNotNull(regularInit);
        assertEquals("workspace://personal-user", body(regularResponse).get("path"));
        assertEquals("workspace://personal-user", body(regularInit).get("path"));
        assertEquals(workspace.toString(), body(adminResponse).get("path"));
    }

    private WorkspaceController controller(Path workspace) {
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.resolveWorkspaceDir(anyString())).thenReturn(workspace);
        return controller(configManager);
    }

    private WorkspaceController controller(ConfigManager configManager) {
        return new WorkspaceController(
                configManager,
                mock(WorkspaceManager.class),
                mock(MultiAgentManager.class),
                mock(FileGuardService.class),
                mock(EnterpriseAgentAccessService.class),
                mock(WorkspaceMemoryService.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(org.springframework.http.ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    private EnterprisePrincipal principal(Set<String> permissions) {
        return new EnterprisePrincipal(
                "user-1", "employee", "Employee", true,
                Set.of("EMPLOYEE"), permissions, "", "");
    }

    private FilePart oversizedPart(String filename, long maxBytes) {
        DataBuffer buffer = mock(DataBuffer.class);
        when(buffer.readableByteCount()).thenReturn(Math.toIntExact(maxBytes + 1));
        return part(filename, Flux.just(buffer));
    }

    private FilePart part(String filename, Flux<DataBuffer> content) {
        FilePart part = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        when(part.filename()).thenReturn(filename);
        when(part.headers()).thenReturn(headers);
        when(part.content()).thenReturn(content);
        return part;
    }
}
