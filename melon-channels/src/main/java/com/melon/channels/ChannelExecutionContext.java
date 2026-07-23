package com.melon.channels;

import com.melon.core.util.ExecutorUtils;
import com.melon.core.util.NamedThreadFactory;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Shared worker and scheduler resources for all channel adapters. */
final class ChannelExecutionContext implements AutoCloseable {

    private final ExecutorService worker;
    private final ScheduledExecutorService scheduler;
    private final boolean owned;

    private ChannelExecutionContext(
            ExecutorService worker, ScheduledExecutorService scheduler, boolean owned) {
        this.worker = worker;
        this.scheduler = scheduler;
        this.owned = owned;
    }

    static ChannelExecutionContext managed(
            ExecutorService worker, ScheduledExecutorService scheduler) {
        if (worker == null || scheduler == null) {
            throw new IllegalArgumentException("Channel executors are required");
        }
        return new ChannelExecutionContext(worker, scheduler, false);
    }

    static ChannelExecutionContext standalone() {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(
                4, 16, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
                new NamedThreadFactory("melon-channel"), new ThreadPoolExecutor.AbortPolicy());
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
                4, new NamedThreadFactory("melon-channel-scheduler"));
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return new ChannelExecutionContext(worker, scheduler, true);
    }

    ExecutorService worker() {
        return worker;
    }

    ScheduledExecutorService scheduler() {
        return scheduler;
    }

    @Override
    public void close() {
        if (!owned) return;
        ExecutorUtils.shutdownGracefully(worker, Duration.ofSeconds(5));
        ExecutorUtils.shutdownGracefully(scheduler, Duration.ofSeconds(5));
    }
}
