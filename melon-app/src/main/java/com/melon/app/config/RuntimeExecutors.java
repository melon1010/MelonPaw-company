package com.melon.app.config;

import com.melon.core.util.ExecutorUtils;
import com.melon.core.util.NamedThreadFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Owns the bounded executors used by application infrastructure. */
@Component
@EnableConfigurationProperties(RuntimeConcurrencyProperties.class)
public class RuntimeExecutors {

    private final ExecutorService channel;
    private final ExecutorService webBlocking;
    private final Scheduler webBlockingScheduler;
    private final ExecutorService history;
    private final ExecutorService skillInstall;
    private final ExecutorService channelLongPoll;
    private final ScheduledExecutorService channelScheduler;
    private final ScheduledExecutorService mcpLifecycle;
    private final ScheduledExecutorService cron;
    private final ScheduledExecutorService heartbeat;
    private final Duration shutdownTimeout;

    public RuntimeExecutors(RuntimeConcurrencyProperties properties) {
        channel = bounded("melon-channel", properties.getChannel(), new ThreadPoolExecutor.AbortPolicy());
        webBlocking = bounded("melon-web-blocking", properties.getWebBlocking(), new ThreadPoolExecutor.AbortPolicy());
        webBlockingScheduler = Schedulers.fromExecutorService(webBlocking);
        history = bounded("melon-history", 1, 1, properties.getHistoryQueueCapacity(),
                new RunCallerOnSaturationPolicy());
        skillInstall = bounded("melon-skill-install", properties.getSkillInstall(), new ThreadPoolExecutor.AbortPolicy());
        channelLongPoll = directHandoff("melon-channel-long-poll", properties.getChannelLongPollThreads());
        channelScheduler = scheduled("melon-channel-scheduler", properties.getChannelSchedulerThreads());
        mcpLifecycle = scheduled("melon-mcp-lifecycle", properties.getMcpLifecycleThreads());
        cron = scheduled("melon-cron", properties.getCronThreads());
        heartbeat = scheduled("melon-heartbeat", 1);
        shutdownTimeout = properties.getShutdownTimeout();
    }

    public ExecutorService channel() {
        return channel;
    }

    /** Scheduler used by WebFlux boundaries around blocking application services. */
    public Scheduler webBlockingScheduler() {
        return webBlockingScheduler;
    }

    /** Executor used by Spring WebFlux to invoke blocking application controllers. */
    public ExecutorService webBlockingExecutor() {
        return webBlocking;
    }

    public ExecutorService history() {
        return history;
    }

    public ExecutorService skillInstall() {
        return skillInstall;
    }

    public ScheduledExecutorService mcpLifecycle() {
        return mcpLifecycle;
    }

    public ScheduledExecutorService channelScheduler() {
        return channelScheduler;
    }

    public ExecutorService channelLongPoll() {
        return channelLongPoll;
    }

    public ScheduledExecutorService cron() {
        return cron;
    }

    public ScheduledExecutorService heartbeat() {
        return heartbeat;
    }

    @PreDestroy
    public void shutdown() {
        // Schedulers.fromExecutorService owns no separate threads. Disposing it first would call
        // shutdownNow() on the shared executor and interrupt in-flight database or file writes.
        for (ExecutorService executor : List.of(
                channel, webBlocking, history, skillInstall, channelLongPoll, channelScheduler,
                mcpLifecycle, cron, heartbeat)) {
            ExecutorUtils.shutdownGracefully(executor, shutdownTimeout);
        }
    }

    private static ThreadPoolExecutor bounded(
            String name, RuntimeConcurrencyProperties.Pool settings,
            RejectedExecutionHandler rejectionHandler) {
        settings.validate(name);
        return bounded(name, settings.getCoreThreads(), settings.getMaxThreads(),
                settings.getQueueCapacity(), rejectionHandler);
    }

    private static ThreadPoolExecutor bounded(
            String name, int coreThreads, int maxThreads, int queueCapacity,
            RejectedExecutionHandler rejectionHandler) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreThreads, maxThreads, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory(name), rejectionHandler);
        executor.allowCoreThreadTimeOut(coreThreads > 1);
        return executor;
    }

    private static ScheduledThreadPoolExecutor scheduled(String name, int threads) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                threads, new NamedThreadFactory(name));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static ThreadPoolExecutor directHandoff(String name, int maxThreads) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0, maxThreads, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new NamedThreadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** Applies backpressure without silently losing history writes. */
    private static final class RunCallerOnSaturationPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) throw new java.util.concurrent.RejectedExecutionException("Executor is shut down");
            task.run();
        }
    }
}
