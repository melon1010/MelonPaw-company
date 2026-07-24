package com.melon.app.runner;

import com.melon.core.config.ConfigManager;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolApprovalGrantServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void remembersOnlyExplicitAllowScopesAndKeepsUsersAndRiskFamiliesIsolated() {
        ConfigManager configManager = new ConfigManager();
        configManager.setConfigPath(tempDir.resolve("config.yaml"));
        configManager.setHomeDir(tempDir.resolve("home").toString());
        configManager.load();

        ToolRiskClassifier classifier = new ToolRiskClassifier();
        ToolApprovalGrantService grants = new ToolApprovalGrantService(configManager);
        ToolUseBlock firstDelete = call("trash report-a.txt");
        ToolUseBlock secondDelete = call("trash report-b.txt");
        ToolUseBlock write = call("touch report-a.txt");
        ToolRiskClassifier.Assessment deleteRisk = classifier.classify(firstDelete);
        ToolRiskClassifier.Assessment secondDeleteRisk = classifier.classify(secondDelete);
        ToolRiskClassifier.Assessment writeRisk = classifier.classify(write);
        Map<String, Object> deleteApproval = grants.approvalFields(firstDelete, deleteRisk);

        grants.remember("default", "user-a", "once", deleteApproval);
        grants.remember("default", "user-a", "unexpected", deleteApproval);
        assertFalse(grants.isAllowed("default", "user-a", firstDelete, deleteRisk));

        grants.remember("default", "user-a", "exact", deleteApproval);
        assertTrue(grants.isAllowed("default", "user-a", firstDelete, deleteRisk));
        assertFalse(grants.isAllowed("default", "user-a", secondDelete, secondDeleteRisk));
        assertFalse(grants.isAllowed("default", "user-b", firstDelete, deleteRisk));
        assertFalse(grants.isAllowed("default", "user-a", write, writeRisk));

        grants.remember("default", "user-a", "similar", deleteApproval);
        assertTrue(grants.isAllowed("default", "user-a", secondDelete, secondDeleteRisk));
        assertFalse(grants.isAllowed("default", "user-a", write, writeRisk));
    }

    private ToolUseBlock call(String command) {
        return ToolUseBlock.builder()
                .id("call-" + command)
                .name("execute_shell_command")
                .input(Map.of("command", command))
                .build();
    }
}
