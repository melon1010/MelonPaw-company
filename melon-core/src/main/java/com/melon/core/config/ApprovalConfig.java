package com.melon.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Approval level configuration for tool execution.
 *
 * <p>Defines how the agent handles tool execution approval:
 * <ul>
 *   <li>{@code RISK_BASED} - Default: classify each tool invocation by its parameters.</li>
 *   <li>{@code OFF} - No approval required, all tools auto-approved.</li>
 *   <li>{@code AUTO} - Automatic approval based on tool safety classification.</li>
 *   <li>{@code SMART} - Smart approval: auto-approve safe tools, require approval for risky ones.</li>
 *   <li>{@code STRICT} - Require explicit approval for every tool call.</li>
 * </ul>
 *
 * <p>This config is mapped to AgentScope {@code PermissionContextState}.
 */
public class ApprovalConfig {

    /**
     * Default approval level.
     */
    public static final String LEVEL_OFF = "OFF";
    public static final String LEVEL_AUTO = "AUTO";
    public static final String LEVEL_SMART = "SMART";
    public static final String LEVEL_STRICT = "STRICT";
    public static final String LEVEL_RISK_BASED = "RISK_BASED";

    @JsonProperty("level")
    private String level = LEVEL_RISK_BASED;

    // ---- Getters / Setters ----

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }
}
