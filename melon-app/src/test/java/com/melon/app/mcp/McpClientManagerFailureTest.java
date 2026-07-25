package com.melon.app.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientManagerFailureTest {

    @Test
    void classifiesPackageRegistryNetworkFailuresAsRetryable() {
        IOException failure = new IOException(
                "MCP server closed stdout: npm error code ENOTFOUND\n"
                        + "npm error request to https://registry.npmjs.org/example failed");

        assertTrue(McpClientManager.isRetryableConnectionFailure("stdio", failure));
        assertTrue(McpClientManager.connectionError(failure).startsWith("MCP_NETWORK_UNAVAILABLE:"));
    }

    @Test
    void reportsMissingSandboxCommandWithoutRetrying() {
        IOException failure = new IOException(
                "MCP server closed stdout: [FATAL tini (7)] exec uvx failed: No such file or directory");

        assertFalse(McpClientManager.isRetryableConnectionFailure("stdio", failure));
        assertTrue(McpClientManager.connectionError(failure).startsWith("MCP_COMMAND_NOT_FOUND:"));
    }

    @Test
    void distinguishesAnIncompatibleSandboxImageFromTheConfiguredMcpCommand() {
        IOException missingRuntime = new IOException(
                "Failed to prepare MCP dependencies in the sandbox. MELON_MCP_RUNTIME_MISSING: npm");
        IOException legacyImage = new IOException(
                "[FATAL tini (7)] exec /opt/melonpaw-runtime/prepare-mcp.sh failed: No such file or directory");

        assertFalse(McpClientManager.isRetryableConnectionFailure("stdio", missingRuntime));
        assertTrue(McpClientManager.connectionError(missingRuntime)
                .startsWith("MCP_SANDBOX_INCOMPATIBLE:"));
        assertTrue(McpClientManager.connectionError(legacyImage)
                .startsWith("MCP_SANDBOX_INCOMPATIBLE:"));
    }

    @Test
    void distinguishesInvalidPackageFromProtocolFailure() {
        IOException missingPackage = new IOException("npm error code E404: package not found");
        IOException invalidProtocol = new IOException("MCP server closed stdout");

        assertFalse(McpClientManager.isRetryableConnectionFailure("stdio", missingPackage));
        assertTrue(McpClientManager.connectionError(missingPackage).startsWith("MCP_PACKAGE_NOT_FOUND:"));
        assertFalse(McpClientManager.isRetryableConnectionFailure("stdio", invalidProtocol));
        assertTrue(McpClientManager.connectionError(invalidProtocol).startsWith("MCP_PROTOCOL_ERROR:"));
    }

    @Test
    void reportsUnavailableGvisorRuntimeWithoutRetrying() {
        IOException failure = new IOException(
                "docker: Error response from daemon: unknown or invalid runtime name: runsc");

        assertFalse(McpClientManager.isRetryableConnectionFailure("stdio", failure));
        assertTrue(McpClientManager.connectionError(failure).startsWith("MCP_SANDBOX_UNAVAILABLE:"));
    }
}
