package com.melon.core.util;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates consistently named threads with an explicit daemon policy. */
public final class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final boolean daemon;
    private final AtomicInteger sequence = new AtomicInteger();

    public NamedThreadFactory(String prefix) {
        this(prefix, true);
    }

    public NamedThreadFactory(String prefix, boolean daemon) {
        String value = Objects.requireNonNull(prefix, "prefix").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Thread name prefix is required");
        this.prefix = value;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler((failedThread, error) ->
                System.getLogger(NamedThreadFactory.class.getName()).log(
                        System.Logger.Level.ERROR,
                        "Uncaught exception in " + failedThread.getName(), error));
        return thread;
    }
}
