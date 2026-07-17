package com.melon.core.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Minimal executable check for encrypted configuration round trips. */
public final class SecretValueCodecSelfCheck {

    private SecretValueCodecSelfCheck() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-secret-codec-");
        SecretValueCodec codec = SecretValueCodec.forHome(home);
        Map<String, Object> source = Map.of(
                "api_key", "provider-secret",
                "mcp", Map.of("env", Map.of("MCP_TOKEN", "mcp-secret")),
                "plain", "visible");
        Map<String, Object> stored = (Map<String, Object>) codec.protect(source);
        if ("provider-secret".equals(stored.get("api_key"))) throw new AssertionError("API key was not encrypted");
        if (!"visible".equals(stored.get("plain"))) throw new AssertionError("Non-secret value changed");
        if (!source.equals(codec.unprotect(stored))) throw new AssertionError("Secret round trip failed");
        if (codec.hasPlaintextSecrets(stored)) throw new AssertionError("Encrypted tree reported plaintext secrets");
    }
}
