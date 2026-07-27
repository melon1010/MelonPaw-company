package com.melon.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment-level settings for importing and sandboxing stdio MCP servers. */
@Component
@ConfigurationProperties(prefix = "melon.execution.mcp")
public class McpExecutionProperties {

    private String stdioMode = "";
    private String dockerCommand = "docker";
    private String sandboxImage = "melonpaw-company-sandbox:latest";
    private String sandboxNetwork = "bridge";
    private String sandboxRuntime = "";
    private String sandboxMemory = "512m";
    private String sandboxCpus = "2";
    private String sandboxPids = "256";
    private String sandboxUser = "10001:10001";
    private long prepareTimeoutSeconds = 300L;
    private int maxActiveStdioSessions = 32;
    private int maxActiveStdioSessionsPerWorkspace = 8;
    private long sessionAcquireTimeoutSeconds = 30L;
    private long sessionIdleTimeoutSeconds = 600L;
    private final Import sourceImport = new Import();
    private final Egress egress = new Egress();

    public String getStdioMode() {
        return stdioMode;
    }

    public void setStdioMode(String stdioMode) {
        this.stdioMode = text(stdioMode);
    }

    public String getDockerCommand() {
        return dockerCommand;
    }

    public void setDockerCommand(String dockerCommand) {
        this.dockerCommand = required(dockerCommand, "dockerCommand");
    }

    public String getSandboxImage() {
        return sandboxImage;
    }

    public void setSandboxImage(String sandboxImage) {
        this.sandboxImage = required(sandboxImage, "sandboxImage");
    }

    public String getSandboxNetwork() {
        return sandboxNetwork;
    }

    public void setSandboxNetwork(String sandboxNetwork) {
        this.sandboxNetwork = required(sandboxNetwork, "sandboxNetwork");
    }

    public String getSandboxRuntime() {
        return sandboxRuntime;
    }

    public void setSandboxRuntime(String sandboxRuntime) {
        this.sandboxRuntime = text(sandboxRuntime);
    }

    public String getSandboxMemory() {
        return sandboxMemory;
    }

    public void setSandboxMemory(String sandboxMemory) {
        this.sandboxMemory = required(sandboxMemory, "sandboxMemory");
    }

    public String getSandboxCpus() {
        return sandboxCpus;
    }

    public void setSandboxCpus(String sandboxCpus) {
        this.sandboxCpus = required(sandboxCpus, "sandboxCpus");
    }

    public String getSandboxPids() {
        return sandboxPids;
    }

    public void setSandboxPids(String sandboxPids) {
        this.sandboxPids = required(sandboxPids, "sandboxPids");
    }

    public String getSandboxUser() {
        return sandboxUser;
    }

    public void setSandboxUser(String sandboxUser) {
        this.sandboxUser = required(sandboxUser, "sandboxUser");
    }

    public long getPrepareTimeoutSeconds() {
        return prepareTimeoutSeconds;
    }

    public void setPrepareTimeoutSeconds(long prepareTimeoutSeconds) {
        this.prepareTimeoutSeconds = positive(prepareTimeoutSeconds, "prepareTimeoutSeconds");
    }

    public int getMaxActiveStdioSessions() {
        return maxActiveStdioSessions;
    }

    public void setMaxActiveStdioSessions(int maxActiveStdioSessions) {
        this.maxActiveStdioSessions = (int) positive(maxActiveStdioSessions,
                "maxActiveStdioSessions");
    }

    public int getMaxActiveStdioSessionsPerWorkspace() {
        return maxActiveStdioSessionsPerWorkspace;
    }

    public void setMaxActiveStdioSessionsPerWorkspace(int value) {
        this.maxActiveStdioSessionsPerWorkspace = (int) positive(value,
                "maxActiveStdioSessionsPerWorkspace");
    }

    public long getSessionAcquireTimeoutSeconds() {
        return sessionAcquireTimeoutSeconds;
    }

    public void setSessionAcquireTimeoutSeconds(long sessionAcquireTimeoutSeconds) {
        this.sessionAcquireTimeoutSeconds = positive(sessionAcquireTimeoutSeconds,
                "sessionAcquireTimeoutSeconds");
    }

    public long getSessionIdleTimeoutSeconds() {
        return sessionIdleTimeoutSeconds;
    }

    public void setSessionIdleTimeoutSeconds(long sessionIdleTimeoutSeconds) {
        this.sessionIdleTimeoutSeconds = positive(sessionIdleTimeoutSeconds,
                "sessionIdleTimeoutSeconds");
    }

    public Import getImport() {
        return sourceImport;
    }

    public Egress getEgress() {
        return egress;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String required(String value, String name) {
        String normalized = text(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private static long positive(long value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    /** SSRF egress policy for remote (HTTP/SSE) MCP endpoints and their OAuth token URLs. */
    public static final class Egress {
        private boolean allowLoopback = false;
        private boolean allowPrivate = false;
        private String allowlist = "";

        public boolean isAllowLoopback() {
            return allowLoopback;
        }

        public void setAllowLoopback(boolean allowLoopback) {
            this.allowLoopback = allowLoopback;
        }

        public boolean isAllowPrivate() {
            return allowPrivate;
        }

        public void setAllowPrivate(boolean allowPrivate) {
            this.allowPrivate = allowPrivate;
        }

        public String getAllowlist() {
            return allowlist;
        }

        public void setAllowlist(String allowlist) {
            this.allowlist = text(allowlist);
        }
    }

    public static final class Import {
        private String roots = "";
        private String pathMappings = "";
        private long maxBytes = 256L * 1024L * 1024L;
        private int maxFiles = 20_000;

        public String getRoots() {
            return roots;
        }

        public void setRoots(String roots) {
            this.roots = text(roots);
        }

        public String getPathMappings() {
            return pathMappings;
        }

        public void setPathMappings(String pathMappings) {
            this.pathMappings = text(pathMappings);
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = positive(maxBytes, "import.maxBytes");
        }

        public int getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(int maxFiles) {
            this.maxFiles = (int) positive(maxFiles, "import.maxFiles");
        }
    }
}
