package com.melon.app.channel;

import com.melon.app.runner.AgentRunner;
import com.melon.app.runner.ChatManager;
import com.melon.app.runner.ChatSpec;
import com.melon.channels.ChannelConfigService;
import com.melon.channels.ChannelInboundMessage;
import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelRuntimeIdentityIsolationTest {

    @Test
    void webhookMetadataCannotOverrideExecutionUser() {
        Fixture fixture = fixture();
        ChannelInboundMessage inbound = inbound();
        inbound.setChannelMeta(Map.of("execution_user_id", "victim-user"));

        fixture.processor().process(inbound).join();

        verify(fixture.agentRunner()).stream(
                eq("agent-a"), anyList(), eq("channel-user"), eq("channel-session"), anyMap());
    }

    @Test
    void internalExecutionUserIsHonoredWithoutTrustingMetadata() {
        Fixture fixture = fixture();
        ChannelInboundMessage inbound = inbound();
        inbound.setChannelMeta(Map.of("execution_user_id", "attacker-value"));
        inbound.setExecutionUserId("trusted-owner");

        fixture.processor().process(inbound).join();

        verify(fixture.agentRunner()).stream(
                eq("agent-a"), anyList(), eq("trusted-owner"), eq("channel-session"), anyMap());
    }

    private Fixture fixture() {
        AgentRunner agentRunner = mock(AgentRunner.class);
        ChatManager chatManager = mock(ChatManager.class);
        ConfigManager configManager = mock(ConfigManager.class);
        ChannelConfigService channelConfigService = mock(ChannelConfigService.class);
        ChatSpec chat = new ChatSpec("chat-a", "agent-a", "Channel chat");

        when(chatManager.beginTurn(
                eq("agent-a"), eq("channel-session"),
                org.mockito.ArgumentMatchers.anyString(), eq("feishu"),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(chat);
        when(chatManager.activeTurnId("agent-a", "chat-a")).thenReturn("turn-a");
        when(configManager.resolveWorkspaceDir("agent-a")).thenReturn(Path.of(".").toAbsolutePath());
        when(channelConfigService.runtimeConfig("agent-a", "feishu")).thenReturn(Map.of());
        when(agentRunner.stream(
                eq("agent-a"), anyList(),
                org.mockito.ArgumentMatchers.anyString(), eq("channel-session"), anyMap()))
                .thenReturn(Flux.empty());

        return new Fixture(
                new ChannelRuntimeProcessor(agentRunner, chatManager, configManager, channelConfigService),
                agentRunner);
    }

    private ChannelInboundMessage inbound() {
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId("agent-a");
        inbound.setChannel("feishu");
        inbound.setUserId("channel-user");
        inbound.setSessionId("channel-session");
        inbound.setContent("hello");
        return inbound;
    }

    private record Fixture(ChannelRuntimeProcessor processor, AgentRunner agentRunner) {
    }
}
