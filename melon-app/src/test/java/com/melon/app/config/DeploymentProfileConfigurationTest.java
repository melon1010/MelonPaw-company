package com.melon.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DeploymentProfileConfigurationTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void usesHostJavaWithDockerSandboxAsTheDefaultProfile() throws Exception {
        PropertySource<?> common = load("application.yaml");

        assertEquals("docker", common.getProperty("spring.profiles.default"));
    }

    @Test
    void keepsExecutionBoundariesInDedicatedProfiles() throws Exception {
        assertProfile("application-local.yaml", "local", "${MELON_EXECUTION_SANDBOX:local}");
        assertProfile("application-docker.yaml", "docker", "${MELON_EXECUTION_SANDBOX:docker}");
        assertProfile("application-container.yaml", "container", "${MELON_EXECUTION_SANDBOX:docker}");
    }

    private void assertProfile(String resource, String profile, String sandboxMode) throws Exception {
        PropertySource<?> source = load(resource);
        assertEquals(profile, source.getProperty("spring.config.activate.on-profile"));
        assertEquals(sandboxMode, source.getProperty("melon.execution.sandbox-mode"));
    }

    private PropertySource<?> load(String resource) throws IOException {
        List<PropertySource<?>> sources = loader.load(resource, new ClassPathResource(resource));
        assertFalse(sources.isEmpty(), () -> resource + " must contain configuration");
        return sources.get(0);
    }
}
