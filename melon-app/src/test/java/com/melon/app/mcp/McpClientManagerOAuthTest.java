package com.melon.app.mcp;

import com.melon.core.config.ConfigManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientManagerOAuthTest {

    @TempDir
    Path tempDir;

    @Test
    void clientCredentialsAreVerifiedPersistedAndReplacedTransactionally() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicReference<Map<String, String>> submittedForm = new AtomicReference<>();
        HttpServer tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenServer.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            submittedForm.set(parseForm(exchange));
            respond(exchange, 200,
                    "{\"access_token\":\"access-1\",\"token_type\":\"Bearer\",\"expires_in\":30}");
        });
        tokenServer.createContext("/failure", exchange -> {
            tokenCalls.incrementAndGet();
            respond(exchange, 401, "{\"error\":\"invalid_client\"}");
        });
        tokenServer.start();

        ConfigManager configManager = new ConfigManager();
        configManager.setConfigPath(tempDir.resolve("config.yaml"));
        configManager.setHomeDir(tempDir.toString());
        McpClientManager manager = new McpClientManager(configManager);
        try {
            McpClientManager.McpServerConfig config = McpClientManager.McpServerConfig.fromMap(Map.of(
                    "id", "remote",
                    "name", "remote",
                    "transport", "streamable_http",
                    "url", "https://mcp.example.test/mcp",
                    "enabled", false));
            manager.register(config);

            String baseUrl = "http://127.0.0.1:" + tokenServer.getAddress().getPort();
            Map<String, Object> status = manager.configureOAuth("remote", Map.of(
                    "grant_type", "client_credentials",
                    "token_url", baseUrl + "/token",
                    "client_id", "client id",
                    "client_secret", "s&cr=t",
                    "scope", "read write",
                    "extra_token_params", Map.of(
                            "grant_type", "refresh_token",
                            "client_id", "overridden",
                            "client_secret", "overridden")));

            assertEquals(true, status.get("authorized"));
            assertEquals("client id", status.get("client_id"));
            assertEquals("read write", status.get("scope"));
            assertTrue(((Number) status.get("expires_at")).longValue() > Instant.now().getEpochSecond());
            assertEquals(Map.of(
                    "grant_type", "client_credentials",
                    "client_id", "client id",
                    "client_secret", "s&cr=t",
                    "scope", "read write"), submittedForm.get());

            String persisted = Files.readString(tempDir.resolve("state/mcp-clients.json"));
            assertTrue(persisted.contains("ENC[v1]:"));
            assertFalse(persisted.contains("s&cr=t"));

            IOException failure = assertThrows(IOException.class, () -> manager.configureOAuth("remote", Map.of(
                    "grant_type", "client_credentials",
                    "token_url", baseUrl + "/failure",
                    "client_id", "replacement",
                    "client_secret", "wrong")));
            assertTrue(failure.getMessage().contains("HTTP 401"));
            Map<String, Object> preserved = manager.oauthStatus("remote");
            assertEquals(true, preserved.get("authorized"));
            assertEquals("client id", preserved.get("client_id"));
            assertEquals(baseUrl + "/token", preserved.get("token_url"));

            assertThrows(IOException.class, () -> manager.configureOAuth("remote", Map.of(
                    "grant_type", "refresh_token",
                    "token_url", baseUrl + "/token",
                    "refresh_token", "refresh")));
            assertEquals(2, tokenCalls.get());

            assertTrue(manager.clearOAuth("remote"));
            assertEquals(false, manager.oauthStatus("remote").get("authorized"));
            assertNull(manager.getServer("remote").getOAuth());
        } finally {
            manager.shutdown();
            tokenServer.stop(0);
        }
    }

    private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        assertTrue(exchange.getRequestHeaders().getFirst("Content-Type")
                .startsWith("application/x-www-form-urlencoded"));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
