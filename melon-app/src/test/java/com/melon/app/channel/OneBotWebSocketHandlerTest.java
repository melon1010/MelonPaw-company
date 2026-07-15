package com.melon.app.channel;

import com.melon.channels.ChannelAdapterRegistry;
import com.melon.channels.ChannelConfigService;
import com.melon.channels.ChannelManager;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OneBotWebSocketHandlerTest {

    @Test
    void acceptsOnlyTheConfiguredAgentToken() {
        ChannelConfigService configs = mock(ChannelConfigService.class);
        when(configs.runtimeConfig("agent-a", "onebot"))
                .thenReturn(Map.of("enabled", true, "access_token", "agent-a-secret"));
        when(configs.runtimeConfig("agent-b", "onebot"))
                .thenReturn(Map.of("enabled", true, "access_token", "agent-b-secret"));
        OneBotWebSocketHandler handler = handler(configs);

        assertTrue(handler.authorized("agent-a", "agent-a-secret"));
        assertFalse(handler.authorized("agent-a", "agent-b-secret"));
        assertFalse(handler.authorized("agent-b", "agent-a-secret"));
    }

    @Test
    void rejectsDisabledOrUnconfiguredChannels() {
        ChannelConfigService configs = mock(ChannelConfigService.class);
        when(configs.runtimeConfig("disabled", "onebot"))
                .thenReturn(Map.of("enabled", false, "access_token", "secret"));
        when(configs.runtimeConfig("blank", "onebot"))
                .thenReturn(Map.of("enabled", true, "access_token", ""));
        OneBotWebSocketHandler handler = handler(configs);

        assertFalse(handler.authorized("disabled", "secret"));
        assertFalse(handler.authorized("blank", "secret"));
        assertFalse(handler.authorized("blank", ""));
    }

    private OneBotWebSocketHandler handler(ChannelConfigService configs) {
        return new OneBotWebSocketHandler(
                mock(ChannelAdapterRegistry.class),
                mock(ChannelManager.class),
                configs);
    }
}
