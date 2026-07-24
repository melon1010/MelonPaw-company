package com.melon.channels;

import com.melon.core.util.ExecutorUtils;
import com.melon.core.util.NamedThreadFactory;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Shared worker and scheduler resources for all channel adapters. */
final class ChannelExecutionContext implements AutoCloseable {

    private final ExecutorService worker;
    private final ExecutorService longPoll;
    private final ScheduledExecutorService scheduler;
    private final boolean owned;

    private ChannelExecutionContext(
            ExecutorService worker, ScheduledExecutorService scheduler,
            ExecutorService longPoll, boolean owned) {
        this.worker = worker;
        this.scheduler = scheduler;
        this.longPoll = longPoll;
        this.owned = owned;
    }

    static ChannelExecutionContext managed(
            ExecutorService worker, ScheduledExecutorService scheduler,
            ExecutorService longPoll) {
        if (worker == null || scheduler == null || longPoll == null) {
            throw new IllegalArgumentException("Channel executors are required");
        }
        return new ChannelExecutionContext(worker, scheduler, longPoll, false);
    }

    static ChannelExecutionContext standalone() {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(
                4, 16, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
                new NamedThreadFactory("melon-channel"), new ThreadPoolExecutor.AbortPolicy());
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
                4, new NamedThreadFactory("melon-channel-scheduler"));
        ThreadPoolExecutor longPoll = new ThreadPoolExecutor(
                0, 32, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
                new NamedThreadFactory("melon-channel-long-poll"),
                new ThreadPoolExecutor.AbortPolicy());
        longPoll.allowCoreThreadTimeOut(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return new ChannelExecutionContext(worker, scheduler, longPoll, true);
    }

    ExecutorService worker() {
        return worker;
    }

    ScheduledExecutorService scheduler() {
        return scheduler;
    }

    ExecutorService longPoll() {
        return longPoll;
    }

    @Override
    public void close() {
        if (!owned) return;
        ExecutorUtils.shutdownGracefully(worker, Duration.ofSeconds(5));
        ExecutorUtils.shutdownGracefully(longPoll, Duration.ofSeconds(5));
        ExecutorUtils.shutdownGracefully(scheduler, Duration.ofSeconds(5));
    }
}
