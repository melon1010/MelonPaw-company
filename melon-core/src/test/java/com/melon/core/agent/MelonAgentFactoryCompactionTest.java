package com.melon.core.agent;

import com.melon.core.config.AgentConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MelonAgentFactoryCompactionTest {

    @Test
    void disablesEarlyMessageCountCompactionByDefault() {
        AgentConfig agent = new AgentConfig();

        CompactionConfig config = new MelonAgentFactory()
                .buildCompactionConfig("agent", agent, null);

        assertEquals(0, config.getTriggerMessages());
    }

    @Test
    void respectsExplicitMessageCountCompaction() {
        AgentConfig agent = new AgentConfig();
        agent.getContextCompact().setTriggerMessagesEnabled(true);
        agent.getContextCompact().setTriggerMessages(75);

        CompactionConfig config = new MelonAgentFactory()
                .buildCompactionConfig("agent", agent, null);

        assertEquals(75, config.getTriggerMessages());
    }
}
