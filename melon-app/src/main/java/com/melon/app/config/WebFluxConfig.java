package com.melon.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * WebFlux 配置. CORS 和 console 静态资源.
 */
@Configuration
@EnableWebFlux
public class WebFluxConfig implements WebFluxConfigurer {

    private final String[] allowedOrigins;

    public WebFluxConfig(@Value("${melon.enterprise.cors.allowed-origins:"
            + "http://127.0.0.1:8088,http://localhost:8088,http://127.0.0.1:5173,http://localhost:5173}")
                         String allowedOrigins) {
        this.allowedOrigins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        var handler = registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)));
        Path consoleDist = Path.of("console", "dist").toAbsolutePath().normalize();
        if (Files.isDirectory(consoleDist)) {
            handler.addResourceLocations(consoleDist.toUri().toString());
        }
    }
}
