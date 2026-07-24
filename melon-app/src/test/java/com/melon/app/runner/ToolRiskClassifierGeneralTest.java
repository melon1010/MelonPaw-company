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
        ToolRiskClassifier.Assessment mergedStderr = classifier.classify(
                call("execute_shell_command", Map.of("command", "ls -l report.txt 2>&1")));

        assertEquals(ToolRiskClassifier.Decision.ASK, write.decision());
        assertTrue(write.effects().contains(ToolRiskClassifier.Effect.WRITE_FILE));
        assertEquals(ToolRiskClassifier.Decision.ALLOW, read.decision());
        assertEquals(ToolRiskClassifier.Decision.ALLOW, mergedStderr.decision());
    }

    @Test
    void routesRecoverableAndPlatformDeleteCommandsThroughDestructiveApproval() {
        ToolRiskClassifier classifier = new ToolRiskClassifier();

        ToolRiskClassifier.Assessment trash = classifier.classify(
                call("execute_shell_command", Map.of("command", "trash report.txt")));
        ToolRiskClassifier.Assessment gioTrash = classifier.classify(
                call("execute_shell_command", Map.of("command", "gio trash report.txt")));
        ToolRiskClassifier.Assessment chainedTrash = classifier.classify(
                call("execute_shell_command", Map.of("command", "test -f report.txt && trash report.txt")));
        ToolRiskClassifier.Assessment wrappedTrash = classifier.classify(
                call("execute_shell_command", Map.of("command", "env LC_ALL=C sh -c 'trash report.txt'")));
        ToolRiskClassifier.Assessment powershellDelete = classifier.classify(
                call("execute_shell_command", Map.of(
                        "command", "pwsh -Command \"Remove-Item report.txt -Force\"")));
        ToolRiskClassifier.Assessment mention = classifier.classify(
                call("execute_shell_command", Map.of("command", "printf '%s\\n' trash")));

        assertEquals(ToolRiskClassifier.Decision.ASK, trash.decision());
        assertTrue(trash.effects().contains(ToolRiskClassifier.Effect.DELETE_FILE));
        assertEquals(ToolRiskClassifier.Decision.ASK, gioTrash.decision());
        assertTrue(gioTrash.effects().contains(ToolRiskClassifier.Effect.DELETE_FILE));
        assertEquals(ToolRiskClassifier.Decision.ASK, chainedTrash.decision());
        assertEquals(ToolRiskClassifier.Decision.ASK, wrappedTrash.decision());
        assertEquals(ToolRiskClassifier.Decision.ASK, powershellDelete.decision());
        assertEquals(ToolRiskClassifier.Decision.ALLOW, mention.decision());
    }

    private ToolUseBlock call(String name, Map<String, Object> input) {
        return ToolUseBlock.builder().id("call-1").name(name).input(input).build();
    }
}
