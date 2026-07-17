package com.melon.app.auth;

import com.melon.core.config.AgentConfig;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runnable smoke check for immutable Agent publication and rollback snapshots. */
public final class EnterpriseAgentDefinitionServiceSelfCheck {

    private EnterpriseAgentDefinitionServiceSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("melon-agent-definition-check");
        Path database = directory.resolve("enterprise.db");
        try {
            EnterpriseAgentDefinitionService service = new EnterpriseAgentDefinitionService(database);
            AgentConfig config = new AgentConfig();
            config.setName("Project Agent");
            config.setActiveModel("provider:model-v1");
            EnterpriseAgentDefinitionService.AgentDefinition first = service.publish(
                    "project-agent", "PROJECT", "project-1", config, "user-1", "Initial release");
            config.setActiveModel("provider:model-v2");
            EnterpriseAgentDefinitionService.AgentDefinition second = service.publish(
                    "project-agent", "PROJECT", "project-1", config, "user-1", "Model update");

            require(first.currentVersion() == 1 && second.currentVersion() == 2,
                    "version number did not increase");
            require(service.versions("project-agent").size() == 2,
                    "published version history was overwritten");
            AgentConfig restored = service.versionConfig("project-agent", first.currentVersionId());
            require("provider:model-v1".equals(restored.getActiveModel()),
                    "version snapshot cannot restore its model selection");
            boolean duplicateProjectRejected = false;
            try {
                service.publish("second-project-agent", "PROJECT", "project-1",
                        config, "user-1", "Duplicate project Agent");
            } catch (IllegalStateException expected) {
                duplicateProjectRejected = true;
            }
            require(duplicateProjectRejected, "a project accepted more than one active Agent definition");
        } finally {
            try (var files = Files.list(directory)) {
                files.forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (Exception ignored) {
                    }
                });
            }
            Files.deleteIfExists(directory);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
