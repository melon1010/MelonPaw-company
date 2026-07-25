package com.melon.core.agent;

import com.melon.core.config.AgentConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MelonAgentFactoryToolResultEvictionTest {

    @Test
    void storesDockerEvictionsInsideMountedAgentWorkspace() {
        MelonAgentFactory factory = new MelonAgentFactory();

        ToolResultEvictionConfig config = factory.buildToolResultEvictionConfig(
                new AgentConfig(), "docker");

        assertEquals("/workspace/large_tool_results", config.getEvictionPath());
    }

    @Test
    void keepsLocalEvictionsInsideSandboxedWorkspaceNamespace() {
        MelonAgentFactory factory = new MelonAgentFactory();

        ToolResultEvictionConfig config = factory.buildToolResultEvictionConfig(
                new AgentConfig(), "local");

        assertEquals("/large_tool_results", config.getEvictionPath());
    }
}
