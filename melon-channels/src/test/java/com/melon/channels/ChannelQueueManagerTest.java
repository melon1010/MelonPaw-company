package com.melon.channels;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ChannelQueueManagerTest {

    @Test
    void removesCompletedSessionQueues() throws Exception {
        try (ChannelQueueManager queue = new ChannelQueueManager()) {
            ChannelInboundMessage inbound = message("session-1");
            var result = queue.enqueue(inbound, 10,
                    message -> CompletableFuture.completedFuture(outbound(message)));

            assertEquals("session-1", result.get(2, TimeUnit.SECONDS).getSessionId());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (queue.activeQueueCount() != 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(0, queue.activeQueueCount());
        }
    }

    @Test
    void completesRejectedWorkExceptionally() {
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdownNow();
        try (ChannelQueueManager queue = new ChannelQueueManager(executor)) {
            var result = queue.enqueue(message("session-1"), 10,
                    message -> CompletableFuture.completedFuture(outbound(message)));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void boundsPendingMessagesPerSession() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CompletableFuture<ChannelOutboundMessage> blocker = new CompletableFuture<>();
        try (ChannelQueueManager queue = new ChannelQueueManager(executor, 1)) {
            var first = queue.enqueue(message("session-1"), 10, ignored -> blocker);
            var rejected = queue.enqueue(message("session-1"), 10,
                    message -> CompletableFuture.completedFuture(outbound(message)));

            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> rejected.get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, error.getCause());
            blocker.complete(outbound(message("session-1")));
            assertEquals("session-1", first.get(2, TimeUnit.SECONDS).getSessionId());
        } finally {
            executor.shutdownNow();
        }
    }

    private static ChannelInboundMessage message(String sessionId) {
        ChannelInboundMessage message = new ChannelInboundMessage();
        message.setAgentId("agent-1");
        message.setChannel("console");
        message.setSessionId(sessionId);
        return message;
    }

    private static ChannelOutboundMessage outbound(ChannelInboundMessage inbound) {
        ChannelOutboundMessage result = new ChannelOutboundMessage();
        result.setSessionId(inbound.getSessionId());
        return result;
    }
}
