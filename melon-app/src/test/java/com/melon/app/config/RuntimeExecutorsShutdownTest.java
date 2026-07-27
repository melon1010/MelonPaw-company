package com.melon.app.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeExecutorsShutdownTest {

    @Test
    void shutdownLetsInFlightWebBlockingWorkFinish() throws Exception {
        RuntimeConcurrencyProperties properties = new RuntimeConcurrencyProperties();
        properties.getWebBlocking().setCoreThreads(1);
        properties.getWebBlocking().setMaxThreads(1);
        properties.getWebBlocking().setQueueCapacity(1);
        properties.setShutdownTimeout(Duration.ofSeconds(2));
        RuntimeExecutors executors = new RuntimeExecutors(properties);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        executors.webBlockingExecutor().execute(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        Thread shutdown = new Thread(executors::shutdown, "runtime-executors-shutdown-test");
        shutdown.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!executors.webBlockingExecutor().isShutdown() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(executors.webBlockingExecutor().isShutdown());
        assertFalse(finished.await(50, TimeUnit.MILLISECONDS));

        release.countDown();
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        shutdown.join(1000);
        assertFalse(shutdown.isAlive());
        assertFalse(interrupted.get());
    }
}
