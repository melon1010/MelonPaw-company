package com.melon.app.mcp;

import com.melon.app.config.McpExecutionProperties;
import com.melon.core.security.OutboundAddressGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves that the Spring-configured egress properties are wired into the guard the way production
 * ships them: a flipped default, a swapped constructor argument, or a mis-parsed allowlist would
 * turn one of these assertions red instead of silently shipping an open SSRF channel.
 */
class McpClientManagerEgressWiringTest {

    @Test
    void defaultPropertiesProduceStrictDenyForLoopbackAndPrivate() {
        // Untouched properties == what a deployment gets with no MELON_MCP_EGRESS_* overrides.
        OutboundAddressGuard guard = McpClientManager.egressGuardFrom(new McpExecutionProperties());

        assertThrows(IllegalArgumentException.class,
                () -> guard.requireAllowedUrl("http://127.0.0.1:8080/mcp"));   // loopback denied
        assertThrows(IllegalArgumentException.class,
                () -> guard.requireAllowedUrl("http://10.0.0.5:8080/mcp"));    // private denied
        assertThrows(IllegalArgumentException.class,
                () -> guard.requireAllowedUrl("http://169.254.169.254/"));     // metadata denied
        assertDoesNotThrow(() -> guard.requireAllowedUrl("http://8.8.8.8/mcp")); // public allowed
    }

    @Test
    void allowLoopbackFlagUnlocksOnlyLoopback() {
        McpExecutionProperties properties = new McpExecutionProperties();
        properties.getEgress().setAllowLoopback(true);
        OutboundAddressGuard guard = McpClientManager.egressGuardFrom(properties);

        assertDoesNotThrow(() -> guard.requireAllowedUrl("http://127.0.0.1:8080/mcp"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.requireAllowedUrl("http://10.0.0.5:8080/mcp"));   // still private-denied
    }

    @Test
    void allowPrivateFlagUnlocksOnlyPrivate() {
        McpExecutionProperties properties = new McpExecutionProperties();
        properties.getEgress().setAllowPrivate(true);
        OutboundAddressGuard guard = McpClientManager.egressGuardFrom(properties);

        assertDoesNotThrow(() -> guard.requireAllowedUrl("http://10.0.0.5:8080/mcp"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.requireAllowedUrl("http://127.0.0.1:8080/mcp"));   // still loopback-denied
    }

    @Test
    void allowlistIsSplitTrimmedAndApplied() {
        McpExecutionProperties properties = new McpExecutionProperties();
        properties.getEgress().setAllowlist(" 127.0.0.1 , 10.0.0.5 ");
        OutboundAddressGuard guard = McpClientManager.egressGuardFrom(properties);

        assertDoesNotThrow(() -> guard.requireAllowedUrl("http://127.0.0.1:8080/mcp"));
        assertDoesNotThrow(() -> guard.requireAllowedUrl("http://10.0.0.5:8080/mcp"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.requireAllowedUrl("http://10.0.0.6:8080/mcp"));   // not listed
        assertThrows(IllegalArgumentException.class,
                () -> guard.requireAllowedUrl("http://169.254.169.254/"));    // allowlist never unlocks metadata
    }
}
