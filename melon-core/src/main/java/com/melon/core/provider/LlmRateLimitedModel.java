package com.melon.core.provider;

import com.melon.core.config.RunningConfig;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Agent-scoped LLM admission control. The wrapper sits below HarnessAgent, so
 * ordinary turns, compaction, rubric evaluation and subagents all share it.
 */
public final class LlmRateLimitedModel implements Model {

    private static final ConcurrentMap<String, AdmissionController> SHARED_CONTROLLERS =
            new ConcurrentHashMap<>();

    private final Model delegate;
    private final AdmissionController admission;

    private LlmRateLimitedModel(Model delegate, AdmissionController admission) {
        this.delegate = delegate;
        this.admission = admission;
    }

    public static Model wrap(Model delegate, RunningConfig config) {
        if (delegate == null || config == null) return delegate;
        return new LlmRateLimitedModel(delegate, new AdmissionController(config));
    }

    public static Model wrap(Model delegate, String scope, RunningConfig config) {
        if (delegate == null || config == null) return delegate;
        String key = scope == null || scope.isBlank() ? "default" : scope;
        AdmissionController controller = SHARED_CONTROLLERS.compute(key, (ignored, previous) ->
                previous == null || !previous.matches(config) ? new AdmissionController(config) : previous);
        return new LlmRateLimitedModel(delegate, controller);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Mono.fromCallable(() -> {
                    admission.acquire();
                    return Boolean.TRUE;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ignored -> Flux.defer(() -> delegate.stream(messages, tools, options))
                        .doOnError(admission::observeFailure)
                        .doFinally(signal -> admission.release()));
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }

    private static final class AdmissionController {
        private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

        private final Semaphore concurrent;
        private final int maxConcurrent;
        private final int maxQpm;
        private final long pauseNanos;
        private final long jitterNanos;
        private final long acquireTimeoutNanos;
        private final Object qpmMonitor = new Object();
        private final Deque<Long> requestTimes = new ArrayDeque<>();
        private volatile long pausedUntilNanos;

        private AdmissionController(RunningConfig config) {
            this.maxConcurrent = Math.max(1, config.getLlmMaxConcurrent());
            this.concurrent = new Semaphore(maxConcurrent, true);
            this.maxQpm = Math.max(0, config.getLlmMaxQpm());
            this.pauseNanos = secondsToNanos(Math.max(0, config.getLlmRateLimitPause()));
            this.jitterNanos = secondsToNanos(Math.max(0, config.getLlmRateLimitJitter()));
            this.acquireTimeoutNanos = secondsToNanos(Math.max(1, config.getLlmAcquireTimeout()));
        }

        private boolean matches(RunningConfig config) {
            return maxConcurrent == Math.max(1, config.getLlmMaxConcurrent())
                    && maxQpm == Math.max(0, config.getLlmMaxQpm())
                    && pauseNanos == secondsToNanos(Math.max(0, config.getLlmRateLimitPause()))
                    && jitterNanos == secondsToNanos(Math.max(0, config.getLlmRateLimitJitter()))
                    && acquireTimeoutNanos == secondsToNanos(Math.max(1, config.getLlmAcquireTimeout()));
        }

        private void acquire() {
            long deadline = System.nanoTime() + acquireTimeoutNanos;
            boolean slot = false;
            try {
                slot = concurrent.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS);
                if (!slot) {
                    throw timeout("concurrent slot");
                }
                awaitPause(deadline);
                awaitQpm(deadline);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException("Interrupted while acquiring the LLM rate-limit slot", e);
            } catch (RuntimeException e) {
                if (slot) concurrent.release();
                throw e;
            }
        }

        private void awaitPause(long deadline) throws InterruptedException {
            while (true) {
                long wait = pausedUntilNanos - System.nanoTime();
                if (wait <= 0) return;
                if (wait > remaining(deadline)) throw timeout("rate-limit pause");
                TimeUnit.NANOSECONDS.sleep(wait);
            }
        }

        private void awaitQpm(long deadline) throws InterruptedException {
            if (maxQpm <= 0) return;
            while (true) {
                long now = System.nanoTime();
                synchronized (qpmMonitor) {
                    while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() >= WINDOW_NANOS) {
                        requestTimes.removeFirst();
                    }
                    if (requestTimes.size() < maxQpm) {
                        requestTimes.addLast(now);
                        return;
                    }
                    long wait = WINDOW_NANOS - (now - requestTimes.peekFirst());
                    if (wait > remaining(deadline)) throw timeout("QPM limit");
                    long millis = TimeUnit.NANOSECONDS.toMillis(wait);
                    int nanos = (int) (wait - TimeUnit.MILLISECONDS.toNanos(millis));
                    qpmMonitor.wait(millis, nanos);
                }
            }
        }

        private void observeFailure(Throwable error) {
            if (!hasStatus(error, 429)) return;
            long delay = pauseNanos;
            if (jitterNanos > 0) {
                delay += ThreadLocalRandom.current().nextLong(jitterNanos + 1);
            }
            long candidate = System.nanoTime() + delay;
            pausedUntilNanos = Math.max(pausedUntilNanos, candidate);
            synchronized (qpmMonitor) {
                qpmMonitor.notifyAll();
            }
        }

        private void release() {
            concurrent.release();
        }

        private long remaining(long deadline) {
            long value = deadline - System.nanoTime();
            if (value <= 0) throw timeout("acquire timeout");
            return value;
        }

        private ModelException timeout(String resource) {
            return new ModelException("Timed out acquiring LLM " + resource + " after "
                    + Duration.ofNanos(acquireTimeoutNanos).toSeconds() + " seconds");
        }

        private boolean hasStatus(Throwable error, int status) {
            Throwable current = error;
            while (current != null) {
                if (current instanceof ModelHttpException http) {
                    Integer code = http.getStatusCode();
                    if (code != null && status == code) return true;
                }
                current = current.getCause();
            }
            return false;
        }

        private static long secondsToNanos(double seconds) {
            double bounded = Math.min(Math.max(0, seconds), TimeUnit.DAYS.toSeconds(1));
            return (long) (bounded * 1_000_000_000L);
        }
    }
}
