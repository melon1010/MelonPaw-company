package com.melon.app.runner;

import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRiskClassifierGeneralTest {

    @TempDir
    Path tempDir;

    @Test
    void allowsCanonicalWorkspacePathsAndRejectsEscapes() {
        ToolRiskClassifier classifier = new ToolRiskClassifier();
        Path workspace = tempDir.resolve("workspace");

        ToolRiskClassifier.Assessment inside = classifier.classify(
                call("write_file", Map.of("path", workspace.resolve("report.docx").toString())),
                workspace, false, false);
        ToolRiskClassifier.Assessment outside = classifier.classify(
                call("write_file", Map.of("path", tempDir.resolve("outside.txt").toString())),
                workspace, false, false);

        assertEquals(ToolRiskClassifier.Decision.ALLOW, inside.decision());
        assertEquals(ToolRiskClassifier.Decision.DENY, outside.decision());
    }

    @Test
    void routesShellWritesThroughWriteApprovalWithoutFlaggingSafeStderrSink() {
        ToolRiskClassifier classifier = new ToolRiskClassifier();

        ToolRiskClassifier.Assessment write = classifier.classify(
                call("execute_shell_command", Map.of("command", "cat > report.txt <<'EOF'\nhello\nEOF")));
        ToolRiskClassifier.Assessment read = classifier.classify(
                call("execute_shell_command", Map.of("command", "cat README.md 2>/dev/null")));

        assertEquals(ToolRiskClassifier.Decision.ASK, write.decision());
        assertTrue(write.effects().contains(ToolRiskClassifier.Effect.WRITE_FILE));
        assertEquals(ToolRiskClassifier.Decision.ALLOW, read.decision());
    }

    private ToolUseBlock call(String name, Map<String, Object> input) {
        return ToolUseBlock.builder().id("call-1").name(name).input(input).build();
    }
}
