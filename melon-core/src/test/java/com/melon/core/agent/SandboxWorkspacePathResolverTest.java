package com.melon.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxWorkspacePathResolverTest {

    @Test
    void preservesPathWhenApplicationRunsOnDockerHost() {
        Path workspace = Path.of("/srv/melon/workspaces/personal-1");
        assertEquals(workspace, SandboxWorkspacePathResolver.resolveHostPath(workspace, null, null));
    }

    @Test
    void mapsContainerWorkspaceToDockerHostPath() {
        Path mapped = SandboxWorkspacePathResolver.resolveHostPath(
                Path.of("/app-data/workspaces/team-1"),
                "/app-data/workspaces",
                "/srv/melon/workspaces");
        assertEquals(Path.of("/srv/melon/workspaces/team-1"), mapped);
    }

    @Test
    void rejectsPartialOrEscapingMappings() {
        assertEquals(Path.of("/app-data/workspaces/team-1"),
                SandboxWorkspacePathResolver.resolveHostPath(
                        Path.of("/app-data/workspaces/team-1"), "/app-data/workspaces", null));
        assertThrows(IllegalStateException.class, () -> SandboxWorkspacePathResolver.resolveHostPath(
                Path.of("/app-data/workspaces/team-1"), null, "/srv/melon/workspaces"));
        assertThrows(IllegalArgumentException.class, () -> SandboxWorkspacePathResolver.resolveHostPath(
                Path.of("/other/team-1"), "/app-data/workspaces", "/srv/melon/workspaces"));
    }
}
