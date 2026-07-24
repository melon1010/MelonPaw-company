package com.melon.app.channel;

import com.melon.channels.ChannelAccessControlStore;
import com.melon.channels.ChannelAdapter;
import com.melon.channels.ChannelAdapterRegistry;
import com.melon.channels.ChannelConfigService;
import com.melon.channels.ChannelHealth;
import com.melon.channels.ChannelInboundMessage;
import com.melon.channels.ChannelManager;
import com.melon.channels.ChannelOutboundMessage;
import com.melon.channels.ChannelQueueManager;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelManagerFailureRecoveryTest {

    @Test
    void lifecycleOperationsForTheSameAgentAndChannelRunSerially() throws Exception {
        ChannelAdapterRegistry registry = mock(ChannelAdapterRegistry.class);
        ChannelConfigService configs = mock(ChannelConfigService.class);
        ChannelAccessControlStore access = mock(ChannelAccessControlStore.class);
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        CompletableFuture<ChannelHealth> pendingStart = new CompletableFuture<>();
        ChannelHealth health = ChannelHealth.of("feishu", "running", true, true, true, "running");

        when(registry.get("feishu")).thenReturn(adapter);
        when(configs.runtimeConfig("personal-agent", "feishu"))
                .thenReturn(Map.of("enabled", true));
        when(adapter.start(eq("personal-agent"), any(), any())).thenReturn(pendingStart);
        when(adapter.stop("personal-agent")).thenReturn(CompletableFuture.completedFuture(health));

        try (ChannelQueueManager queue = new ChannelQueueManager()) {
            ChannelManager manager = new ChannelManager(registry, configs, access, queue,
                    ignored -> CompletableFuture.completedFuture(new ChannelOutboundMessage()));

            CompletableFuture<ChannelHealth> starting = manager.start("personal-agent", "feishu");
            CompletableFuture<ChannelHealth> stopping = manager.stop("personal-agent", "feishu");

            verify(adapter, never()).stop("personal-agent");
            pendingStart.complete(health);

            assertEquals(health, starting.get(2, TimeUnit.SECONDS));
            assertEquals(health, stopping.get(2, TimeUnit.SECONDS));
            verify(adapter).stop("personal-agent");
        }
    }

    @Test
    void repeatedStartWithUnchangedConfigDoesNotReconnect() throws Exception {
        ChannelAdapterRegistry registry = mock(ChannelAdapterRegistry.class);
        ChannelConfigService configs = mock(ChannelConfigService.class);
        ChannelAccessControlStore access = mock(ChannelAccessControlStore.class);
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        Map<String, Object> runtime = Map.of("enabled", true, "app_id", "app-1");
        ChannelHealth health = ChannelHealth.of("feishu", "running", true, true, true, "running");

        when(registry.get("feishu")).thenReturn(adapter);
        when(configs.runtimeConfig("personal-agent", "feishu")).thenReturn(runtime);
        when(adapter.start(eq("personal-agent"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(health));
        when(adapter.health("personal-agent", runtime)).thenReturn(health);

        try (ChannelQueueManager queue = new ChannelQueueManager()) {
            ChannelManager manager = new ChannelManager(registry, configs, access, queue,
                    ignored -> CompletableFuture.completedFuture(new ChannelOutboundMessage()));

            manager.start("personal-agent", "feishu").get(2, TimeUnit.SECONDS);
            manager.start("personal-agent", "feishu").get(2, TimeUnit.SECONDS);

            verify(adapter, times(1)).start(eq("personal-agent"), any(), any());
            verify(adapter, never()).stop("personal-agent");
        }
    }

    @Test
    void processingFailureIsLoggedAndReturnedThroughTheChannel() throws Exception {
        ChannelAdapterRegistry registry = mock(ChannelAdapterRegistry.class);
        ChannelConfigService configs = mock(ChannelConfigService.class);
        ChannelAccessControlStore access = mock(ChannelAccessControlStore.class);
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        ChannelInboundMessage inbound = new ChannelInboundMessage();
        inbound.setAgentId("personal-agent");
        inbound.setChannel("wechat");
        inbound.setUserId("external-user");
        inbound.setSessionId("wechat:external-user");
        inbound.setContent("hello");

        when(registry.get("wechat")).thenReturn(adapter);
        when(configs.runtimeConfig("personal-agent", "wechat"))
                .thenReturn(Map.of("enabled", true));
        when(access.allowed(eq("personal-agent"), eq("wechat"), eq("external-user"), any(), any()))
                .thenReturn(true);
        when(adapter.send(any(), any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(invocation.getArgument(0)));

        try (ChannelQueueManager queue = new ChannelQueueManager()) {
            ChannelManager manager = new ChannelManager(registry, configs, access, queue,
                    ignored -> CompletableFuture.failedFuture(
                            new SecurityException("sensitive internal failure")));

            ChannelOutboundMessage result = manager.enqueue(inbound, 20).get(2, TimeUnit.SECONDS);

            assertEquals("消息处理失败，请稍后重试。", result.getText());
            assertEquals("processing_failed", result.getMeta().get("reason"));
            assertTrue(Boolean.TRUE.equals(result.getMeta().get("failed")));
            verify(adapter).send(eq(result), any());
        }
    }
}
