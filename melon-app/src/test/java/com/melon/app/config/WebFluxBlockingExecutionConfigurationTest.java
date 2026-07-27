package com.melon.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.config.BlockingExecutionConfigurer;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFluxBlockingExecutionConfigurationTest {

    @Test
    void requestBodyControllerInvocationRunsAfterTheBlockingBoundary() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestApplication.class)) {
            WebTestClient.bindToApplicationContext(context)
                    .build()
                    .post()
                    .uri("/api/body-probe")
                    .bodyValue(Map.of("value", "ok"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class)
                    .value(threadName -> assertTrue(
                            threadName.startsWith("melon-web-blocking-"), threadName));
        }
    }

    @Test
    void blockingPublisherBodyAlsoRunsAfterTheBlockingBoundary() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestApplication.class)) {
            WebTestClient.bindToApplicationContext(context)
                    .build()
                    .get()
                    .uri("/api/publisher-probe")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class)
                    .value(threadName -> assertTrue(
                            threadName.startsWith("melon-web-blocking-"), threadName));
        }
    }

    @Test
    void saturatedControllerBoundaryReturnsStructuredServiceUnavailable() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestApplication.class)) {
            RuntimeExecutors executors = context.getBean(RuntimeExecutors.class);
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try {
                executors.webBlockingScheduler().schedule(() -> {
                    running.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertTrue(running.await(1, TimeUnit.SECONDS));
                executors.webBlockingScheduler().schedule(() -> { });

                WebTestClient.bindToApplicationContext(context)
                        .build()
                        .post()
                        .uri("/api/body-probe")
                        .bodyValue(Map.of("value", "ok"))
                        .exchange()
                        .expectStatus().isEqualTo(503)
                        .expectBody()
                        .jsonPath("$.code").isEqualTo("runtime_overloaded");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void applicationControllersUseTheBoundedBlockingExecutor() throws Exception {
        RuntimeConcurrencyProperties properties = new RuntimeConcurrencyProperties();
        properties.getWebBlocking().setCoreThreads(1);
        properties.getWebBlocking().setMaxThreads(1);
        properties.getWebBlocking().setQueueCapacity(2);
        RuntimeExecutors executors = new RuntimeExecutors(properties);
        try {
            InspectableConfigurer configurer = new InspectableConfigurer();
            new WebFluxBlockingExecutionConfiguration(executors)
                    .configureBlockingExecution(configurer);

            AsyncTaskExecutor executor = configurer.executor();
            Predicate<HandlerMethod> predicate = configurer.predicate();
            assertNotNull(executor);
            assertNotNull(predicate);
            HandlerMethod handlerMethod = new HandlerMethod(
                    new ProbeController(), ProbeController.class.getMethod("probe"));
            assertTrue(predicate.test(handlerMethod));

            Future<String> threadName = executor.submit(() -> Thread.currentThread().getName());
            assertTrue(threadName.get().startsWith("melon-web-blocking-"));
        } finally {
            executors.shutdown();
        }
    }

    @Test
    void nonApplicationControllersAreNotSelected() throws Exception {
        RuntimeExecutors executors = new RuntimeExecutors(new RuntimeConcurrencyProperties());
        try {
            InspectableConfigurer configurer = new InspectableConfigurer();
            new WebFluxBlockingExecutionConfiguration(executors)
                    .configureBlockingExecution(configurer);

            HandlerMethod handlerMethod = new HandlerMethod(
                    new NonControllerProbe(), NonControllerProbe.class.getMethod("probe"));
            assertFalse(configurer.predicate().test(handlerMethod));
        } finally {
            executors.shutdown();
        }
    }

    @Test
    void applicationMvcControllersAreSelectedToo() throws Exception {
        RuntimeExecutors executors = new RuntimeExecutors(new RuntimeConcurrencyProperties());
        try {
            InspectableConfigurer configurer = new InspectableConfigurer();
            new WebFluxBlockingExecutionConfiguration(executors)
                    .configureBlockingExecution(configurer);

            HandlerMethod handlerMethod = new HandlerMethod(
                    new MvcProbeController(), MvcProbeController.class.getMethod("probe"));
            assertTrue(configurer.predicate().test(handlerMethod));
        } finally {
            executors.shutdown();
        }
    }

    private static final class InspectableConfigurer extends BlockingExecutionConfigurer {
        AsyncTaskExecutor executor() {
            return getExecutor();
        }

        Predicate<HandlerMethod> predicate() {
            return getBlockingControllerMethodPredicate();
        }
    }

    @RestController
    private static final class ProbeController {
        @GetMapping("/probe")
        public Mono<String> probe() {
            return Mono.just("ok");
        }
    }

    @Configuration
    @EnableWebFlux
    @Import({WebFluxBlockingExecutionConfiguration.class, RuntimeOverloadExceptionHandler.class,
            BodyProbeController.class})
    static class TestApplication {
        @Bean
        RuntimeExecutors runtimeExecutors() {
            RuntimeConcurrencyProperties properties = new RuntimeConcurrencyProperties();
            properties.getWebBlocking().setCoreThreads(1);
            properties.getWebBlocking().setMaxThreads(1);
            properties.getWebBlocking().setQueueCapacity(1);
            return new RuntimeExecutors(properties);
        }
    }

    @RestController
    static class BodyProbeController {
        @PostMapping("/api/body-probe")
        Mono<String> probe(@RequestBody Map<String, Object> ignored) {
            return Mono.just(Thread.currentThread().getName());
        }

        @GetMapping("/api/publisher-probe")
        Mono<String> publisherProbe() {
            return Mono.fromCallable(() -> Thread.currentThread().getName());
        }
    }

    private static final class NonControllerProbe {
        public Mono<String> probe() {
            return Mono.just("ok");
        }
    }

    @Controller
    private static final class MvcProbeController {
        @GetMapping("/mvc-probe")
        public Mono<String> probe() {
            return Mono.just("ok");
        }
    }
}
