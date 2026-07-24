package com.melon.app.runner;

import com.melon.core.util.WorkspacePathResolver;
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

    public enum Effect {
        READ_FILE,
        WRITE_FILE,
        DELETE_FILE,
        INSTALL_SOFTWARE,
        EXECUTE_PROCESS,
        EXTERNAL_SIDE_EFFECT
    }

    public record Assessment(Decision decision, String severity, String code, String summary,
                             Set<Effect> effects, String resource) {
        public Assessment {
            effects = effects == null ? Set.of() : Set.copyOf(effects);
            resource = resource == null ? "" : resource;
        }

        static Assessment allow(String code, String summary) {
            return allow(code, summary, Set.of(), "");
        }

        static Assessment allow(String code, String summary, Set<Effect> effects, String resource) {
            return new Assessment(Decision.ALLOW, "low", code, summary, effects, resource);
        }

        static Assessment ask(String code, String summary) {
            return ask(code, summary, Set.of(), "");
        }

        static Assessment ask(String code, String summary, Set<Effect> effects, String resource) {
            return new Assessment(Decision.ASK, "high", code, summary, effects, resource);
        }

        static Assessment deny(String code, String summary) {
            return deny(code, summary, Set.of(), "");
        }

        static Assessment deny(String code, String summary, Set<Effect> effects, String resource) {
            return new Assessment(Decision.DENY, "critical", code, summary, effects, resource);
        }
    }

    private static final Pattern DESTRUCTIVE_SHELL = Pattern.compile(
            "(?is)(^|[\\s;&|()'\"])(sudo|su|dd|mkfs(?:\\.[a-z0-9]+)?|"
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
        return classify(call, null, mcpTool, readOnlyTool);
    }

    public Assessment classify(ToolUseBlock call, Path workspaceRoot,
                               boolean mcpTool, boolean readOnlyTool) {
        if (call == null) return Assessment.deny("invalid_tool_call", "Tool call is missing");
        String name = value(call.getName()).toLowerCase(Locale.ROOT);
        Map<String, Object> input = call.getInput() != null ? call.getInput() : Map.of();

        if (mcpTool) return classifyMcp(call, readOnlyTool);

        if ("execute".equals(name) || "execute_shell_command".equals(name)) {
            return classifyShell(value(input.get("command")));
        }
        if ("write_file".equals(name) || "edit_file".equals(name)) {
            String rawPath = firstNonBlank(input.get("path"), input.get("file_path"));
            return classifyWorkspacePath(rawPath, workspaceRoot);
        }
        if ("agent_spawn".equals(name)) {
            String worker = value(input.get("agent_id")).toLowerCase(Locale.ROOT);
            if (worker.isBlank()) worker = value(input.get("role")).toLowerCase(Locale.ROOT);
            if (Set.of("general-purpose", "researcher", "executor", "reviewer").contains(worker)) {
                String role = value(input.get("role")).toLowerCase(Locale.ROOT);
                if (!role.isBlank() && !Set.of("researcher", "executor", "reviewer").contains(role)) {
                    return Assessment.deny("unsupported_delegation_role",
                            "Only researcher, executor, or reviewer delegation roles are supported");
                }
                return Assessment.allow("restricted_subagent", "Start a restricted workspace worker");
            }
            return Assessment.deny("unsupported_subagent",
                    "Only the restricted general-purpose worker may be started");
        }
        if ("create_cron_job".equals(name)) {
            return Assessment.allow("workspace_automation",
                    "Create a workspace-local scheduled task; risky actions remain governed when it runs");
        }
        if ("skill_manage".equals(name)) {
            String action = value(input.get("action")).toLowerCase(Locale.ROOT);
            if (Set.of("archive", "delete", "remove").contains(action)) {
                return Assessment.ask("skill_removal", "Remove or archive an installed skill",
                        Set.of(Effect.DELETE_FILE), "skill");
            }
            return Assessment.allow("workspace_skill_change", "Change a skill inside the Agent workspace",
                    Set.of(Effect.WRITE_FILE), "skill");
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
        return Assessment.deny("mcp_policy_denied", "MCP access policy denied this tool call",
                Set.of(Effect.EXTERNAL_SIDE_EFFECT), value(call != null ? call.getName() : null));
    }

    private Assessment classifyShell(String command) {
        if (command.isBlank()) return Assessment.deny("empty_command", "Shell command is empty");
        if (CREDENTIAL_ACCESS.matcher(command).find()) {
            return Assessment.deny("credential_access", "Command attempts to access credentials or protected secrets",
                    Set.of(Effect.READ_FILE, Effect.EXECUTE_PROCESS), "protected_credentials");
        }
        ShellCommandEffects.Analysis shell = ShellCommandEffects.analyze(command);
        if (shell.deletesFiles()) {
            String resource = shell.targets().isEmpty() ? "shell_delete" : String.join(",", shell.targets());
            return Assessment.ask("destructive_shell",
                    "Command removes files or moves them out of the workspace",
                    Set.of(Effect.EXECUTE_PROCESS, Effect.DELETE_FILE), resource);
        }
        if (DESTRUCTIVE_SHELL.matcher(command).find()) {
            Set<Effect> effects = command.matches("(?is).*\\b(?:npm|pnpm|yarn|pip|brew|apt|apt-get|dnf|yum)\\s+(?:install|add|remove|uninstall)\\b.*")
                    ? Set.of(Effect.EXECUTE_PROCESS, Effect.INSTALL_SOFTWARE)
                    : Set.of(Effect.EXECUTE_PROCESS, Effect.DELETE_FILE, Effect.EXTERNAL_SIDE_EFFECT);
            return Assessment.ask("destructive_shell",
                    "Command may delete data, change privileges, install software, or affect an external system",
                    effects, "shell");
        }
        if (shell.writesFiles()) {
            String resource = shell.targets().isEmpty() ? "shell_write" : String.join(",", shell.targets());
            return Assessment.ask("shell_file_write",
                    "Shell command writes files; workspace edits require the same approval as file tools",
                    Set.of(Effect.EXECUTE_PROCESS, Effect.WRITE_FILE), resource);
        }
        return Assessment.allow("ordinary_shell", "Ordinary command may run inside the configured Agent workspace sandbox",
                Set.of(Effect.EXECUTE_PROCESS), "shell");
    }

    private Assessment classifyWorkspacePath(String rawPath, Path workspaceRoot) {
        if (rawPath.isBlank()) return Assessment.deny("empty_path", "File path is empty");
        String normalizedText = rawPath.replace('\\', '/');
        if (CREDENTIAL_ACCESS.matcher(normalizedText).find()) {
            return Assessment.deny("credential_path", "Protected credential files cannot be modified",
                    Set.of(Effect.WRITE_FILE), "protected_credentials");
        }
        try {
            Path path;
            if (workspaceRoot != null) {
                path = new WorkspacePathResolver(workspaceRoot.toString()).resolve(rawPath);
            } else {
                path = Path.of(rawPath).normalize();
                if (path.isAbsolute() || normalizedText.startsWith("~") || normalizedText.equals("..")
                        || normalizedText.startsWith("../") || normalizedText.contains("/../")) {
                    return Assessment.deny("workspace_escape", "File changes must stay inside the Agent workspace",
                            Set.of(Effect.WRITE_FILE), normalizedText);
                }
            }
            return Assessment.allow("workspace_edit", "Ordinary file change inside the Agent workspace",
                    Set.of(Effect.WRITE_FILE), path.toString());
        } catch (SecurityException e) {
            return Assessment.deny("workspace_escape", "File changes must stay inside the Agent workspace",
                    Set.of(Effect.WRITE_FILE), normalizedText);
        } catch (RuntimeException e) {
            return Assessment.deny("invalid_path", "File path is invalid",
                    Set.of(Effect.WRITE_FILE), normalizedText);
        }
    }

    private Assessment classifyMcp(ToolUseBlock call, boolean readOnlyTool) {
        if (readOnlyTool) {
            return Assessment.allow("readonly_mcp", "MCP server declares this tool read-only",
                    Set.of(Effect.READ_FILE), value(call.getName()));
        }
        String name = value(call.getName()).toLowerCase(Locale.ROOT);
        if (HIGH_RISK_MCP_OPERATION.matcher(name).find()) {
            return Assessment.ask("external_mcp_effect", "MCP tool name indicates a high-risk external side effect",
                    Set.of(Effect.EXTERNAL_SIDE_EFFECT), name);
        }
        Map<String, Object> input = call.getInput() != null ? call.getInput() : Map.of();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (MCP_ACTION_KEYS.contains(value(entry.getKey()).toLowerCase(Locale.ROOT))
                    && HIGH_RISK_MCP_OPERATION.matcher(value(entry.getValue())).find()) {
                return Assessment.ask("external_mcp_effect",
                        "MCP tool requests a high-risk external operation",
                        Set.of(Effect.EXTERNAL_SIDE_EFFECT), name);
            }
        }
        return Assessment.allow("safe_mcp_operation",
                "MCP tool does not declare or request an external side effect",
                Set.of(Effect.EXTERNAL_SIDE_EFFECT), name);
    }

    private static String firstNonBlank(Object first, Object second) {
        String value = value(first);
        return value.isBlank() ? value(second) : value;
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
