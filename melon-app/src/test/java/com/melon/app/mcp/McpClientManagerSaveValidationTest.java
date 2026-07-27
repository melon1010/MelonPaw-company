package com.melon.app.mcp;

import com.melon.core.config.ConfigManager;
import com.melon.core.security.OutboundAddressGuard;
import com.melon.core.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save-time semantics for remote MCP configurations: the egress policy (structure + DNS) runs
 * synchronously inside register/replace, so a configuration the policy forbids is rejected with
 * an error instead of "created OK" flipping to an async ERROR state. Uses only IP literals and
 * locally-resolvable names, so no real DNS lookup or socket is needed.
 */
class McpClientManagerSaveValidationTest {

    @TempDir
    Path tempDir;

    private McpClientManager manager(OutboundAddressGuard guard) {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        return new McpClientManager(configManager, guard);
    }

    @Test
    void registerRejectsHostnameResolvingToBlockedAddressAtSaveTime() {
        // "localhost" resolves via the hosts file (no network) to loopback, which the strict
        // policy denies. Before the fix this saved fine and only failed on async connect.
        McpClientManager manager = manager(OutboundAddressGuard.strict());
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> manager.register(httpConfig("blocked", "http://localhost:9090/mcp")));
            assertTrue(e.getMessage().contains("Invalid remote MCP configuration"));
            assertNull(manager.getServer("blocked"));   // nothing was persisted
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void registerRejectsUnresolvableHostnameAtSaveTime() {
        // .invalid never resolves (RFC 2606): an unresolvable endpoint is a save error now,
        // not a deferred connection error.
        McpClientManager manager = manager(OutboundAddressGuard.strict());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> manager.register(httpConfig("dead", "https://mcp.example.invalid/mcp")));
            assertNull(manager.getServer("dead"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void embeddedOAuthTokenUrlUsesSameSaveTimePolicyAsEndpoint() {
        // OAuth embedded in the create body previously skipped validation until the first token
        // fetch. The metadata endpoint must be rejected at save even with every opt-in enabled.
        McpClientManager manager = manager(new OutboundAddressGuard(true, true, List.of()));
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> manager.register(httpConfig("oauth-bad", "http://127.0.0.1:9090/mcp", Map.of(
                            "token_url", "http://169.254.169.254/token",
                            "grant_type", "client_credentials"))));
            assertTrue(e.getMessage().contains("Invalid OAuth token_url"));
            assertNull(manager.getServer("oauth-bad"));

            // A policy-allowed token URL saves fine (no token fetch happens at save).
            assertNotNull(manager.register(httpConfig("oauth-ok", "http://127.0.0.1:9090/mcp", Map.of(
                    "token_url", "http://127.0.0.1:9090/token",
                    "grant_type", "client_credentials",
                    "client_id", "client",
                    "client_secret", "secret"))));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void embeddedOAuthMustContainRequiredFieldsBeforeItCanBeSaved() {
        McpClientManager manager = manager(new OutboundAddressGuard(true, false, List.of()));
        try {
            IllegalArgumentException missingUrl = assertThrows(IllegalArgumentException.class,
                    () -> manager.register(httpConfig("oauth-no-url", "http://127.0.0.1:9090/mcp", Map.of(
                            "grant_type", "client_credentials",
                            "client_id", "client",
                            "client_secret", "secret"))));
            assertTrue(missingUrl.getMessage().contains("token_url is required"));

            IllegalArgumentException missingCredentials = assertThrows(IllegalArgumentException.class,
                    () -> manager.register(httpConfig("oauth-no-credentials", "http://127.0.0.1:9090/mcp", Map.of(
                            "token_url", "http://127.0.0.1:9090/token",
                            "grant_type", "client_credentials"))));
            assertTrue(missingCredentials.getMessage().contains("client_id and client_secret"));

            IllegalArgumentException unsupportedGrant = assertThrows(IllegalArgumentException.class,
                    () -> manager.register(httpConfig("oauth-refresh", "http://127.0.0.1:9090/mcp", Map.of(
                            "token_url", "http://127.0.0.1:9090/token",
                            "grant_type", "refresh_token",
                            "client_id", "client",
                            "client_secret", "secret"))));
            assertTrue(unsupportedGrant.getMessage().contains("client_credentials"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void enablingLegacyConfigurationRevalidatesBeforePersisting() throws Exception {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        Path persisted = tempDir.resolve("legacy-mcp.json");
        Files.writeString(persisted, JsonUtils.toJson(Map.of(
                "servers", List.of(Map.of(
                        "id", "legacy",
                        "name", "legacy",
                        "transport", "streamable_http",
                        "url", "http://localhost:9090/mcp",
                        "enabled", false)))));

        McpClientManager manager = new McpClientManager(configManager, persisted);
        try {
            manager.init();
            assertThrows(IllegalArgumentException.class, () -> manager.setEnabled("legacy", true));
            assertFalse(manager.getServer("legacy").isEnabled());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void replaceKeepsExistingConfigurationWhenReplacementViolatesEgressPolicy() {
        // §5.3 invariant: validation runs before the old definition is touched, so a rejected
        // replacement leaves the persisted configuration exactly as it was.
        McpClientManager manager = manager(new OutboundAddressGuard(true, false, List.of()));
        try {
            manager.register(httpConfig("server", "http://127.0.0.1:9090/mcp"));

            assertThrows(IllegalArgumentException.class,
                    () -> manager.replace("server", httpConfig("server", "http://10.0.0.5/mcp")));

            assertEquals("http://127.0.0.1:9090/mcp", manager.getServer("server").getUrl());
        } finally {
            manager.shutdown();
        }
    }

    private McpClientManager.McpServerConfig httpConfig(String id, String url) {
        return McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", id,
                "name", id,
                "transport", "streamable_http",
                "url", url,
                "enabled", false));
    }

    private McpClientManager.McpServerConfig httpConfig(String id, String url, Map<String, Object> oauth) {
        return McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", id,
                "name", id,
                "transport", "streamable_http",
                "url", url,
                "enabled", false,
                "oauth", oauth));
    }
}
