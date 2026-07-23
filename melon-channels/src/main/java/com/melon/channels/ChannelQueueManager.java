package com.melon.channels;

import com.melon.core.util.ExecutorUtils;
import com.melon.core.util.NamedThreadFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class ChannelQueueManager implements AutoCloseable {

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final int maxPendingPerQueue;
    private final Map<QueueKey, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private final Map<QueueKey, AtomicInteger> pendingByQueue = new ConcurrentHashMap<>();

    public ChannelQueueManager() {
        this(new ThreadPoolExecutor(
                2, Math.max(4, Runtime.getRuntime().availableProcessors()),
                60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
                new NamedThreadFactory("melon-channel-queue"), new ThreadPoolExecutor.AbortPolicy()), 32, true);
    }

    public ChannelQueueManager(ExecutorService executor) {
        this(executor, 32);
    }

    public ChannelQueueManager(ExecutorService executor, int maxPendingPerQueue) {
        this(executor, maxPendingPerQueue, false);
    }

    private ChannelQueueManager(
            ExecutorService executor, int maxPendingPerQueue, boolean ownsExecutor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        if (maxPendingPerQueue < 1) {
            throw new IllegalArgumentException("maxPendingPerQueue must be positive");
        }
        this.maxPendingPerQueue = maxPendingPerQueue;
        this.ownsExecutor = ownsExecutor;
    }

    public CompletableFuture<ChannelOutboundMessage> enqueue(ChannelInboundMessage message,
                                                             int priority,
                                                             ChannelProcessor processor) {
        QueueKey key = new QueueKey(message.getAgentId(), message.getChannel(), message.getSessionId());
        CompletableFuture<ChannelOutboundMessage> result = new CompletableFuture<>();
        AtomicInteger pending = pendingByQueue.computeIfAbsent(key, ignored -> new AtomicInteger());
        if (pending.incrementAndGet() > maxPendingPerQueue) {
            decrementPending(key, pending);
            result.completeExceptionally(new RejectedExecutionException(
                    "Channel session queue capacity exceeded: " + maxPendingPerQueue));
            return result;
        }
        CompletableFuture<Void> next;
        try {
            next = tails.compute(key, (ignored, tail) -> {
                CompletableFuture<Void> base = tail != null
                        ? tail.exceptionally(e -> null)
                        : CompletableFuture.completedFuture(null);
                return base.thenComposeAsync(unused -> {
                    try {
                        return processor.process(message);
                    } catch (Throwable error) {
                        return CompletableFuture.failedFuture(error);
                    }
                }, executor).handle((outbound, error) -> {
                    if (error == null) result.complete(outbound);
                    else result.completeExceptionally(unwrap(error));
                    return null;
                });
            });
        } catch (RuntimeException error) {
            decrementPending(key, pending);
            result.completeExceptionally(error);
            return result;
        }
        next.whenComplete((unused, error) -> {
            tails.remove(key, next);
            decrementPending(key, pending);
        });
        return result;
    }

    public void clear(String agentId, String channel, String sessionId) {
        tails.keySet().removeIf(key -> key.agentId.equals(agentId)
                && key.channel.equals(channel)
                && key.sessionId.equals(sessionId));
        pendingByQueue.keySet().removeIf(key -> key.agentId.equals(agentId)
                && key.channel.equals(channel)
                && key.sessionId.equals(sessionId));
    }

    @Override
    public void close() {
        tails.clear();
        pendingByQueue.clear();
        if (ownsExecutor) ExecutorUtils.shutdownGracefully(executor, Duration.ofSeconds(5));
    }

    int activeQueueCount() {
        return tails.size();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void decrementPending(QueueKey key, AtomicInteger pending) {
        if (pending.decrementAndGet() <= 0) pendingByQueue.remove(key, pending);
    }

    private record QueueKey(String agentId, String channel, String sessionId) {
        private QueueKey {
            agentId = value(agentId, "default");
            channel = value(channel, "console");
            sessionId = value(sessionId, "default");
            Objects.requireNonNull(agentId);
            Objects.requireNonNull(channel);
            Objects.requireNonNull(sessionId);
        }

        private static String value(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
