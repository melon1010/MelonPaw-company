package com.melon.core.agent;

import com.melon.core.config.AgentConfig;
import org.junit.jupiter.api.Test;

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

        assertNotNull(MelonAgentFactory.memoryConfig(config));
    }
}
