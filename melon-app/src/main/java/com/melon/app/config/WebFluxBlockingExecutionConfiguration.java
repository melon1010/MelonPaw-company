package com.melon.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.stereotype.Controller;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.config.BlockingExecutionConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Invokes application controllers on the bounded blocking executor after request arguments have
 * been resolved. This is the WebFlux-native boundary for legacy JDBC, filesystem and synchronous
 * SDK calls that can still occur before a controller returns its Publisher.
 */
@Configuration
public class WebFluxBlockingExecutionConfiguration implements WebFluxConfigurer {

    private static final String APPLICATION_PACKAGE = "com.melon.app";

    private final RuntimeExecutors executors;

    public WebFluxBlockingExecutionConfiguration(RuntimeExecutors executors) {
        this.executors = executors;
    }

    @Override
    public void configureBlockingExecution(BlockingExecutionConfigurer configurer) {
        configurer.setExecutor(new TaskExecutorAdapter(executors.webBlockingExecutor()));
        configurer.setControllerMethodPredicate(this::isApplicationController);
    }

    private boolean isApplicationController(HandlerMethod handlerMethod) {
        Class<?> beanType = handlerMethod.getBeanType();
        return beanType.getPackageName().startsWith(APPLICATION_PACKAGE)
                && AnnotatedElementUtils.hasAnnotation(beanType, Controller.class);
    }
}
