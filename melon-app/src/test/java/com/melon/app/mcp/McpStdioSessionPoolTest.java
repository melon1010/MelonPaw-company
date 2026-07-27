package com.melon.app.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpStdioSessionPoolTest {

    @Test
    void evictsLeastRecentlyUsedIdleSessionWhenCapacityIsFull() throws Exception {
        McpStdioSessionPool pool = new McpStdioSessionPool(
                1, 1, Duration.ofSeconds(1), Duration.ofMinutes(1));
        McpStdioSessionPool.SessionKey first = pool.key("personal", "first");
        AtomicInteger closes = new AtomicInteger();
        try (McpStdioSessionPool.Reservation reservation = pool.reserve(first)) {
            reservation.activate(() -> {
                closes.incrementAndGet();
                pool.release(first);
            });
        }

        McpStdioSessionPool.SessionKey second = pool.key("personal", "second");
        try (McpStdioSessionPool.Reservation ignored = pool.reserve(second)) {
            assertEquals(1, closes.get());
            assertEquals(1, pool.activeCount());
        }
        assertEquals(0, pool.activeCount());
    }

    @Test
    void neverEvictsSessionWithAnActiveToolCall() throws Exception {
        McpStdioSessionPool pool = new McpStdioSessionPool(
                1, 1, Duration.ofMillis(25), Duration.ofMinutes(1));
        McpStdioSessionPool.SessionKey first = pool.key("personal", "first");
        try (McpStdioSessionPool.Reservation reservation = pool.reserve(first)) {
            reservation.activate(() -> pool.release(first));
        }

        McpStdioSessionPool.UseLease use = pool.tryAcquireUse(first, true);
        assertNotNull(use);
        try {
            IOException error = assertThrows(IOException.class,
                    () -> pool.reserve(pool.key("personal", "second")));
            assertTrue(error.getMessage().contains("MCP_SESSION_CAPACITY_EXCEEDED"));
        } finally {
            use.close();
            pool.release(first);
        }
    }

    @Test
    void idleExpirationRequiresNoInflightCalls() throws Exception {
        McpStdioSessionPool pool = new McpStdioSessionPool(
                2, 2, Duration.ofSeconds(1), Duration.ofMillis(1));
        McpStdioSessionPool.SessionKey key = pool.key("team", "server");
        try (McpStdioSessionPool.Reservation reservation = pool.reserve(key)) {
            reservation.activate(() -> pool.release(key));
        }
        McpStdioSessionPool.UseLease use = pool.tryAcquireUse(key, true);
        Thread.sleep(5);
        assertTrue(!pool.claimIfExpired(key));
        use.close();
        Thread.sleep(5);
        assertTrue(pool.claimIfExpired(key));
        pool.release(key);
    }

    @Test
    void isolatesSameServerIdAcrossWorkspaceScopes() throws Exception {
        McpStdioSessionPool pool = new McpStdioSessionPool(
                2, 1, Duration.ofSeconds(1), Duration.ofMinutes(1));
        McpStdioSessionPool.SessionKey personal = pool.key("personal-user", "shared-server");
        McpStdioSessionPool.SessionKey team = pool.key("team-project", "shared-server");

        try (McpStdioSessionPool.Reservation reservation = pool.reserve(personal)) {
            reservation.activate(() -> pool.release(personal));
        }
        try (McpStdioSessionPool.Reservation reservation = pool.reserve(team)) {
            reservation.activate(() -> pool.release(team));
        }

        assertEquals(2, pool.activeCount());
        McpStdioSessionPool.UseLease personalUse = pool.tryAcquireUse(personal, true);
        McpStdioSessionPool.UseLease teamUse = pool.tryAcquireUse(team, true);
        assertNotNull(personalUse);
        assertNotNull(teamUse);
        personalUse.close();
        teamUse.close();
        pool.release(personal);
        pool.release(team);
    }
}
