package com.melon.app.runner;

import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolRiskClassifierCronTest {

    @Test
    void workspaceCronIsAllowedByRiskPolicyButStillAskedUnderStrictPolicy() {
        ToolUseBlock call = ToolUseBlock.builder()
                .id("cron-1")
                .name("create_cron_job")
                .input(Map.of("cron", "0 9 * * *", "prompt", "daily summary"))
                .build();
        ToolRiskClassifier classifier = new ToolRiskClassifier();

        assertEquals(ToolRiskClassifier.Decision.ALLOW, classifier.classify(call).decision());
        assertEquals(ToolRiskClassifier.Decision.ASK, classifier.forceStrict(call).decision());
    }
}
