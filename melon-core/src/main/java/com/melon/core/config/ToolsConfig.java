package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具开关配置. 对应 Python config.py 的 ToolsConfig.
 */
public class ToolsConfig {

    @JsonProperty("builtin_tools")
    private Map<String, BuiltinToolConfig> builtinTools = defaultBuiltinTools();

    @JsonProperty("async_execution_tools")
    private Map<String, Boolean> asyncExecutionTools = Map.of("execute_shell_command", true);

    public Map<String, BuiltinToolConfig> getBuiltinTools() { return builtinTools; }
    public void setBuiltinTools(Map<String, BuiltinToolConfig> builtinTools) { this.builtinTools = builtinTools; }

    public Map<String, Boolean> getAsyncExecutionTools() { return asyncExecutionTools; }
    public void setAsyncExecutionTools(Map<String, Boolean> asyncExecutionTools) { this.asyncExecutionTools = asyncExecutionTools; }

    /**
     * 默认工具开关. 对应 Python _default_builtin_tools().
     */
    private static Map<String, BuiltinToolConfig> defaultBuiltinTools() {
        Map<String, BuiltinToolConfig> m = new LinkedHashMap<>();
        put(m, "execute_shell_command", true, "Execute commands in the AgentScope sandbox", "shell");
        put(m, "read_file", true, "Read file contents", "📄");
        put(m, "write_file", true, "Write content to file", "✍️");
        put(m, "edit_file", true, "Edit file using find-and-replace", "🖊️");
        put(m, "grep_search", true, "Search file contents by pattern", "🔍");
        put(m, "glob_search", true, "Find files matching a glob pattern", "📁");
        put(m, "web_search", true, "Search current public web pages without using shell commands", "🌐");
        put(m, "web_fetch", true, "Read a public web page with private-network and content-size protection", "🔗");
        put(m, "view_image", true, "Load an image into LLM context for visual analysis", false, "🖼️");
        put(m, "view_video", true, "Load a video into LLM context for visual analysis", false, "🎥");
        put(m, "send_file_to_user", true, "Send files to user", "📤");
        put(m, "get_current_time", true, "Get current date and time", "🕐");
        put(m, "set_user_timezone", true, "Set user timezone", "🌍");
        put(m, "get_token_usage", true, "Get llm token usage", "📊");
        put(m, "create_cron_job", true, "Create a scheduled task in the current workspace", "⏰");
        put(m, "recall_history", true, "Search and read durable conversation history", "🧠");
        return m;
    }

    private static void put(Map<String, BuiltinToolConfig> tools, String name, boolean enabled,
                            String description, String icon) {
        put(tools, name, enabled, description, true, icon);
    }

    private static void put(Map<String, BuiltinToolConfig> tools, String name, boolean enabled,
                            String description, boolean displayToUser, String icon) {
        BuiltinToolConfig tool = new BuiltinToolConfig(name, enabled, description);
        tool.setDisplayToUser(displayToUser);
        tool.setIcon(icon);
        tools.put(name, tool);
    }
}
