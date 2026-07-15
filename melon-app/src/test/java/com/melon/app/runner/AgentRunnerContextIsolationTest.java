package com.melon.app.runner;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentRunnerContextIsolationTest {

    @Test
    void requestMetadataCannotReplaceAuthenticatedExecutionIdentity() {
        AgentRunner runner = new AgentRunner(null, null);

        RuntimeContext context = runner.buildContext(
                "personal-owner",
                "trusted-user",
                "trusted-session",
                Map.of(
                        "agent_id", "foreign-agent",
                        "user_id", "foreign-user",
                        "session_id", "foreign-session",
                        "root_agent_id", "foreign-root-agent",
                        "root_session_id", "foreign-root-session",
                        "working_dir", "C:/sensitive",
                        "project_dir", "C:/sensitive-project",
                        "source", "console"));

        assertEquals("trusted-user", context.getUserId());
        assertEquals("trusted-session", context.getSessionId());
        assertEquals("trusted-user", context.get("user_id"));
        assertEquals("trusted-session", context.get("session_id"));
        assertEquals("trusted-session", context.get("root_session_id"));
        assertEquals("personal-owner", context.get("agent_id"));
        assertEquals("personal-owner", context.get("root_agent_id"));
        assertNull(context.get("working_dir"));
        assertNull(context.get("project_dir"));
        assertEquals("console", context.get("source"));
    }
}
