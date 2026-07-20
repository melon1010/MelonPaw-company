package com.melon.app.runner;

import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolRiskClassifierSubagentTest {

    @Test
    void acceptsRestrictedRoleAliasAndRejectsUnknownRole() {
        ToolRiskClassifier classifier = new ToolRiskClassifier();

        ToolRiskClassifier.Assessment allowed = classifier.classify(
                new ToolUseBlock("call-1", "agent_spawn", Map.of("role", "researcher")));
        ToolRiskClassifier.Assessment denied = classifier.classify(
                new ToolUseBlock("call-2", "agent_spawn", Map.of("role", "unrestricted")));

        assertEquals(ToolRiskClassifier.Decision.ALLOW, allowed.decision());
        assertEquals(ToolRiskClassifier.Decision.DENY, denied.decision());
    }
}
