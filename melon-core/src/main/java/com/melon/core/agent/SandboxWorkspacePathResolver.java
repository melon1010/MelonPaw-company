package com.melon.core.agent;

import java.nio.file.Path;

/** Maps an application-visible workspace path to the path understood by the Docker daemon. */
public final class SandboxWorkspacePathResolver {

    public static final String APPLICATION_ROOT = "MELON_WORKSPACE_ROOT";
    public static final String HOST_ROOT = "MELON_SANDBOX_HOST_WORKSPACE_ROOT";

    private SandboxWorkspacePathResolver() {
    }

    public static Path resolveHostPath(Path workspace) {
        return resolveHostPath(workspace, setting(APPLICATION_ROOT), setting(HOST_ROOT));
    }

    static Path resolveHostPath(Path workspace, String applicationRootValue, String hostRootValue) {
        if (workspace == null) {
            throw new IllegalArgumentException("Workspace path is required");
        }
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        boolean hasApplicationRoot = applicationRootValue != null && !applicationRootValue.isBlank();
        boolean hasHostRoot = hostRootValue != null && !hostRootValue.isBlank();
        if (!hasHostRoot) {
            return normalizedWorkspace;
        }
        if (!hasApplicationRoot) {
            throw new IllegalStateException(APPLICATION_ROOT + " must be configured when "
                    + HOST_ROOT + " is set");
        }

        Path applicationRoot = absoluteRoot(applicationRootValue, APPLICATION_ROOT);
        Path hostRoot = absoluteRoot(hostRootValue, HOST_ROOT);
        if (normalizedWorkspace.equals(applicationRoot) || !normalizedWorkspace.startsWith(applicationRoot)) {
            throw new IllegalArgumentException("Workspace must be a child of " + APPLICATION_ROOT);
        }
        Path mapped = hostRoot.resolve(applicationRoot.relativize(normalizedWorkspace)).normalize();
        if (mapped.equals(hostRoot) || !mapped.startsWith(hostRoot)) {
            throw new IllegalArgumentException("Mapped workspace escapes " + HOST_ROOT);
        }
        return mapped;
    }

    private static Path absoluteRoot(String value, String name) {
        Path path = Path.of(value.trim());
        if (!path.isAbsolute()) {
            throw new IllegalStateException(name + " must be an absolute path");
        }
        return path.normalize();
    }

    private static String setting(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) value = System.getenv(name);
        return value;
    }
}
