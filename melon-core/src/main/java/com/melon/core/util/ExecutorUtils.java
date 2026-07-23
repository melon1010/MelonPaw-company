package com.melon.core.util;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Executor lifecycle helpers shared by standalone and Spring-managed components. */
public final class ExecutorUtils {

    private ExecutorUtils() {
    }

    public static void shutdownGracefully(ExecutorService executor, Duration timeout) {
        if (executor == null) return;
        executor.shutdown();
        long waitMillis = Math.max(1L, timeout == null ? 5000L : timeout.toMillis());
        try {
            if (!executor.awaitTermination(waitMillis, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(Math.min(waitMillis, 1000L), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
