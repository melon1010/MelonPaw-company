package com.melon.app.service;

import com.melon.core.config.ConfigManager;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalServiceIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void approvalsAndPlansAreScopedByAgentAndSession() {
        ConfigManager configManager = new ConfigManager();
        configManager.setConfigPath(tempDir.resolve("config.yaml"));
        configManager.setHomeDir(tempDir.resolve("home").toString());
        configManager.load();
        ApprovalService service = new ApprovalService(configManager);

        ToolUseBlock toolCall = ToolUseBlock.builder()
                .id("call-1")
                .name("write_file")
                .build();
        Map<String, Object> request = Map.of(
                "request_id", "call-1",
                "agent_id", "personal-a",
                "session_id", "shared-session"
        );
        var decision = service.openPendingApproval(
                "personal-a", "shared-session", List.of(toolCall), List.of(request));

        assertEquals(request, service.getPendingApproval("personal-a", "shared-session"));
        assertNull(service.getPendingApproval("personal-b", "shared-session"));
        assertTrue(service.getPendingApprovals("personal-b").isEmpty());
        assertFalse(service.decidePendingApproval(
                "personal-b", "shared-session", "call-1", true));
        assertTrue(service.decidePendingApproval(
                "personal-a", "shared-session", "call-1", true));
        assertEquals(1, decision.block().size());

        service.setPlan("personal-a", "shared-session", Map.of("title", "Plan A"));
        assertNull(service.getPlan("personal-b", "shared-session"));
        service.confirmPlan("personal-b", "shared-session");
        assertEquals("Plan A", service.getPlan("personal-a", "shared-session").get("title"));
    }
}
