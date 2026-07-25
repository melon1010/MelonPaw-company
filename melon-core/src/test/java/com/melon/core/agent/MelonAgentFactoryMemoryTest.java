package com.melon.core.agent;

import com.melon.core.config.AgentConfig;
import io.agentscope.harness.agent.memory.MemoryConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MelonAgentFactoryMemoryTest {

    @Test
    void disablesHarnessMemoryWhenConfiguredAsNone() {
        AgentConfig config = new AgentConfig();
        config.setMemoryManagerBackend("none");

        assertNull(MelonAgentFactory.memoryConfig(config));
    }

    @Test
    void keepsFileMemoryEnabledByDefault() {
        AgentConfig config = new AgentConfig();

        MemoryConfig memory = MelonAgentFactory.memoryConfig(config);

        assertNotNull(memory);
        assertEquals(MemoryConfig.FlushMode.THROTTLED, memory.flushTrigger().mode());
        assertEquals(MelonAgentFactory.MEMORY_FLUSH_MIN_GAP, memory.flushTrigger().minGap());
    }
}
