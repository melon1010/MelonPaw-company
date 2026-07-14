package com.melon.core.config;

public final class ToolsConfigSelfCheck {
    private static final java.util.List<String> WEB_AGENT_TOOLS = java.util.List.of(
            "execute_shell_command",
            "read_file",
            "write_file",
            "edit_file",
            "grep_search",
            "glob_search",
            "browser_use",
            "view_image",
            "view_video",
            "send_file_to_user",
            "get_current_time",
            "set_user_timezone",
            "get_token_usage",
            "create_cron_job",
            "recall_history"
    );

    private ToolsConfigSelfCheck() {
    }

    public static void main(String[] args) {
        ConfigManager configManager = new ConfigManager();
        ToolsConfig tools = configManager.getConfig().getAgents().get("default").getTools();

        assertVisible(tools, "execute_shell_command");
        assertVisible(tools, "grep_search");
        assertVisible(tools, "glob_search");
        assertHidden(tools, "view_image");
        assertHidden(tools, "view_video");
        assertRemoved(tools, "desktop_screenshot");
        assertRemoved(tools, "delegate_external_agent");
        assertRemoved(tools, "list_agents");
        assertRemoved(tools, "chat_with_agent");
        assertRemoved(tools, "submit_to_agent");
        assertRemoved(tools, "check_agent_task");
        assertRemoved(tools, "spawn_subagent");
        assertRemoved(tools, "materialize_skill");
        assertHiddenOrMissing(tools, "execute");
        assertHiddenOrMissing(tools, "grep_files");
        assertHiddenOrMissing(tools, "glob_files");
        assertWebAgentToolNames();
    }

    private static void assertVisible(ToolsConfig tools, String name) {
        BuiltinToolConfig tool = tools.getBuiltinTools().get(name);
        if (tool == null || !tool.isDisplayToUser()) {
            throw new AssertionError("expected visible Python tool name: " + name);
        }
    }

    private static void assertHiddenOrMissing(ToolsConfig tools, String name) {
        BuiltinToolConfig tool = tools.getBuiltinTools().get(name);
        if (tool != null && tool.isDisplayToUser()) {
            throw new AssertionError("Java internal tool name should not be visible: " + name);
        }
    }

    private static void assertHidden(ToolsConfig tools, String name) {
        BuiltinToolConfig tool = tools.getBuiltinTools().get(name);
        if (tool == null || tool.isDisplayToUser()) {
            throw new AssertionError("expected hidden Python tool name: " + name);
        }
    }

    private static void assertRemoved(ToolsConfig tools, String name) {
        if (tools.getBuiltinTools().containsKey(name)) {
            throw new AssertionError("retired tool should not be configurable: " + name);
        }
    }

    private static void assertWebAgentToolNames() {
        java.util.Set<String> actual = new java.util.LinkedHashSet<>(new ToolsConfig().getBuiltinTools().keySet());
        java.util.Set<String> expected = new java.util.LinkedHashSet<>(WEB_AGENT_TOOLS);
        if (!actual.equals(expected)) {
            throw new AssertionError("tool names differ from the Web agent surface. expected="
                    + expected + ", actual=" + actual);
        }
    }
}
