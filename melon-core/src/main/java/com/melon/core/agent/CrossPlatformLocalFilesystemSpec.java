package com.melon.core.agent;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local AgentScope filesystem with portable shell working-directory handling. */
public final class CrossPlatformLocalFilesystemSpec extends LocalFilesystemSpec {

    private Path project;
    private LocalFsMode mode = LocalFsMode.ROOTED;
    private int executeTimeoutSeconds = 120;
    private int maxOutputBytes = 100_000;
    private final Map<String, String> env = new LinkedHashMap<>();
    private boolean inheritEnv;

    @Override
    public CrossPlatformLocalFilesystemSpec project(Path project) {
        super.project(project);
        this.project = project;
        return this;
    }

    @Override
    public CrossPlatformLocalFilesystemSpec mode(LocalFsMode mode) {
        super.mode(mode);
        this.mode = mode;
        return this;
    }

    @Override
    public CrossPlatformLocalFilesystemSpec executeTimeoutSeconds(int seconds) {
        super.executeTimeoutSeconds(seconds);
        this.executeTimeoutSeconds = seconds;
        return this;
    }

    @Override
    public CrossPlatformLocalFilesystemSpec maxOutputBytes(int bytes) {
        super.maxOutputBytes(bytes);
        this.maxOutputBytes = bytes;
        return this;
    }

    @Override
    public CrossPlatformLocalFilesystemSpec env(String name, String value) {
        super.env(name, value);
        env.put(name, value);
        return this;
    }

    @Override
    public CrossPlatformLocalFilesystemSpec inheritEnv(boolean inherit) {
        super.inheritEnv(inherit);
        this.inheritEnv = inherit;
        return this;
    }

    @Override
    public AbstractFilesystem toFilesystem(Path workspace, NamespaceFactory namespaceFactory) {
        Path effectiveProject = project != null
                ? project.toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>();
        roots.add(effectiveProject);
        roots.add(normalizedWorkspace);
        roots.addAll(getAdditionalRoots());
        PathPolicy pathPolicy = PathPolicy.of(roots);

        CrossPlatformLocalFilesystemWithShell upper =
                new CrossPlatformLocalFilesystemWithShell(
                        normalizedWorkspace,
                        mode,
                        pathPolicy,
                        executeTimeoutSeconds,
                        maxOutputBytes,
                        env.isEmpty() ? null : Map.copyOf(env),
                        inheritEnv,
                        namespaceFactory,
                        effectiveProject);
        LocalFilesystem lower = new LocalFilesystem(effectiveProject, true, 10, null);
        if (isProjectWritable()) {
            LocalFilesystem projectFilesystem =
                    new LocalFilesystem(effectiveProject, mode, pathPolicy, 10, namespaceFactory);
            return new ProjectAwareOverlay(
                    upper, lower, projectFilesystem, normalizedWorkspace);
        }
        return io.agentscope.harness.agent.filesystem.OverlayFilesystem.of(upper, lower);
    }
}
