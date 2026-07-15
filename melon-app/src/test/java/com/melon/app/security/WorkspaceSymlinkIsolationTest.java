package com.melon.app.security;

import com.melon.core.util.SafePathUtil;
import com.melon.core.util.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceSymlinkIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsTraversalOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));

        assertThrows(SecurityException.class,
                () -> SafePathUtil.resolveSafe(workspace, "../outside.txt"));
        assertThrows(SecurityException.class,
                () -> new WorkspacePathResolver(workspace.toString())
                        .resolve(tempDir.resolve("outside.txt").toString()));
    }

    @Test
    void rejectsExistingSymbolicLinkSegmentsWhenSupported() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = workspace.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        assertThrows(SecurityException.class,
                () -> SafePathUtil.resolveSafe(workspace, "linked/secret.txt"));
        assertThrows(SecurityException.class,
                () -> new WorkspacePathResolver(workspace.toString())
                        .resolve(link.resolve("secret.txt").toString()));
    }
}
