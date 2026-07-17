package com.melon.app.runner;

import io.agentscope.core.message.ToolUseBlock;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Parameter-aware risk policy used after AgentScope pauses a candidate tool call. */
@Component
public class ToolRiskClassifier {

    public enum Decision { ALLOW, ASK, DENY }

    public record Assessment(Decision decision, String severity, String code, String summary) {
        static Assessment allow(String code, String summary) {
            return new Assessment(Decision.ALLOW, "low", code, summary);
        }

        static Assessment ask(String code, String summary) {
            return new Assessment(Decision.ASK, "high", code, summary);
        }

        static Assessment deny(String code, String summary) {
            return new Assessment(Decision.DENY, "critical", code, summary);
        }
    }

    private static final Pattern DESTRUCTIVE_SHELL = Pattern.compile(
            "(?is)(^|[\\s;&|()'\"])(sudo|su|rm|rmdir|unlink|shred|dd|mkfs(?:\\.[a-z0-9]+)?|"
                    + "chmod|chown|kill|pkill|killall|shutdown|reboot|halt|poweroff)\\b"
                    + "|\\bgit\\s+(?:reset\\s+--hard|clean\\s+-[^\\s]*f|push\\b)"
                    + "|\\b(?:drop|truncate)\\s+(?:table|database)\\b"
                    + "|\\b(?:npm|pnpm|yarn|pip|brew|apt|apt-get|dnf|yum)\\s+(?:install|add|remove|uninstall)\\b"
                    + "|\\b(?:npm|pnpm|yarn)\\s+publish\\b"
                    + "|\\b(?:docker|podman)\\s+push\\b"
                    + "|\\bkubectl\\s+(?:apply|create|delete|patch|replace|scale)\\b"
                    + "|\\bterraform\\s+(?:apply|destroy|import)\\b"
                    + "|\\bgh\\s+(?:pr|issue|release)\\s+(?:create|merge|close|delete)\\b"
                    + "|\\bcurl\\b[^\\n]*(?:-X\\s*(?:POST|PUT|PATCH|DELETE)|--data|--upload-file)"
                    + "|\\b(?:scp|sftp|ssh|rsync)\\b");
    private static final Pattern CREDENTIAL_ACCESS = Pattern.compile(
            "(?is)(?:^|[/\\s'\"])(?:\\.ssh|\\.aws|\\.gnupg|\\.kube|\\.docker|\\.config/gcloud)(?:/|[\\s'\"]|$)"
                    + "|(?:^|[/\\s'\"])(?:id_rsa|id_ed25519|credentials|shadow|master\\.key|\\.env)"
                    + "(?:\\.[^/\\s'\"]+)?(?:$|[/\\s'\"])");
    private static final Pattern HIGH_RISK_MCP_OPERATION = Pattern.compile(
            "(?i)(^|[_\\s-])(delete|remove|send|publish|post|upload|deploy|execute|run|install|uninstall|"
                    + "start|stop|restart|approve|reject|grant|revoke|schedule|cancel|pay|purchase|transfer|"
                    + "overwrite|truncate|drop)"
                    + "([_\\s-]|$)");
    private static final Set<String> MCP_ACTION_KEYS = Set.of("action", "operation", "method", "mode");
    public Assessment classify(ToolUseBlock call) {
        return classify(call, false, false);
    }

    public Assessment classify(ToolUseBlock call, boolean mcpTool) {
        return classify(call, mcpTool, false);
    }

    public Assessment classify(ToolUseBlock call, boolean mcpTool, boolean readOnlyTool) {
        if (call == null) return Assessment.deny("invalid_tool_call", "Tool call is missing");
        String name = value(call.getName()).toLowerCase(Locale.ROOT);
        Map<String, Object> input = call.getInput() != null ? call.getInput() : Map.of();

        if (mcpTool) return classifyMcp(call, readOnlyTool);

        if ("execute".equals(name) || "execute_shell_command".equals(name)) {
            return classifyShell(value(input.get("command")));
        }
        if ("write_file".equals(name) || "edit_file".equals(name)) {
            return classifyWorkspacePath(value(input.get("path")));
        }
        if ("agent_spawn".equals(name)) {
            String worker = value(input.get("agent_id"));
            if ("general-purpose".equals(worker)) {
                return Assessment.allow("restricted_subagent", "Start a restricted workspace worker");
            }
            return Assessment.deny("unsupported_subagent",
                    "Only the restricted general-purpose worker may be started");
        }
        if ("create_cron_job".equals(name)) {
            return Assessment.ask("persistent_automation", "Create a persistent scheduled task");
        }
        if ("skill_manage".equals(name)) {
            String action = value(input.get("action")).toLowerCase(Locale.ROOT);
            if (Set.of("archive", "delete", "remove").contains(action)) {
                return Assessment.ask("skill_removal", "Remove or archive an installed skill");
            }
            return Assessment.allow("workspace_skill_change", "Change a skill inside the Agent workspace");
        }
        if (name.startsWith("mcp__") || name.startsWith("mcp_")) return classifyMcp(call, false);
        return Assessment.allow("safe_system_tool", "System tool has no classified external side effect");
    }

    public Assessment forceStrict(ToolUseBlock call) {
        return Assessment.ask("strict_policy", "Organization strict policy requires approval for this tool");
    }

    public Assessment forceAllow(ToolUseBlock call) {
        return Assessment.allow("approval_disabled", "Organization policy disabled interactive approval");
    }

    public Assessment forceDeny(ToolUseBlock call) {
        return Assessment.deny("mcp_policy_denied", "MCP access policy denied this tool call");
    }

    private Assessment classifyShell(String command) {
        if (command.isBlank()) return Assessment.deny("empty_command", "Shell command is empty");
        if (CREDENTIAL_ACCESS.matcher(command).find()) {
            return Assessment.deny("credential_access", "Command attempts to access credentials or protected secrets");
        }
        if (DESTRUCTIVE_SHELL.matcher(command).find()) {
            return Assessment.ask("destructive_shell", "Command may delete data, change privileges, install software, or affect an external system");
        }
        return Assessment.allow("ordinary_shell", "Ordinary command may run inside the configured Agent workspace sandbox");
    }

    private Assessment classifyWorkspacePath(String rawPath) {
        if (rawPath.isBlank()) return Assessment.deny("empty_path", "File path is empty");
        String normalizedText = rawPath.replace('\\', '/');
        if (CREDENTIAL_ACCESS.matcher(normalizedText).find()) {
            return Assessment.deny("credential_path", "Protected credential files cannot be modified");
        }
        Path path;
        try {
            path = Path.of(rawPath).normalize();
        } catch (RuntimeException e) {
            return Assessment.deny("invalid_path", "File path is invalid");
        }
        if (path.isAbsolute() || normalizedText.startsWith("~") || normalizedText.equals("..")
                || normalizedText.startsWith("../") || normalizedText.contains("/../")) {
            return Assessment.deny("workspace_escape", "File changes must stay inside the Agent workspace");
        }
        return Assessment.allow("workspace_edit", "Ordinary file change inside the Agent workspace");
    }

    private Assessment classifyMcp(ToolUseBlock call, boolean readOnlyTool) {
        if (readOnlyTool) {
            return Assessment.allow("readonly_mcp", "MCP server declares this tool read-only");
        }
        String name = value(call.getName()).toLowerCase(Locale.ROOT);
        if (HIGH_RISK_MCP_OPERATION.matcher(name).find()) {
            return Assessment.ask("external_mcp_effect", "MCP tool name indicates a high-risk external side effect");
        }
        Map<String, Object> input = call.getInput() != null ? call.getInput() : Map.of();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (MCP_ACTION_KEYS.contains(value(entry.getKey()).toLowerCase(Locale.ROOT))
                    && HIGH_RISK_MCP_OPERATION.matcher(value(entry.getValue())).find()) {
                return Assessment.ask("external_mcp_effect",
                        "MCP tool requests a high-risk external operation");
            }
        }
        return Assessment.allow("safe_mcp_operation",
                "MCP tool does not declare or request an external side effect");
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
