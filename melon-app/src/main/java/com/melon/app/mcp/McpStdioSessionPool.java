package com.melon.app.mcp;

import com.melon.app.config.McpExecutionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Global admission controller for sandboxed stdio MCP sessions.
 *
 * <p>A session is keyed by workspace scope and MCP server. Capacity can be shared across
 * workspaces, but processes and credentials never are. When capacity is exhausted, the least
 * recently used session with no in-flight calls is closed before a new session is admitted.
 */
@Component
public class McpStdioSessionPool {

    private final int maxActive;
    private final int maxPerWorkspace;
    private final Duration acquireTimeout;
    private final Duration idleTimeout;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition capacityChanged = lock.newCondition();
    private final Map<SessionKey, Entry> entries = new HashMap<>();

    @Autowired
    public McpStdioSessionPool(McpExecutionProperties properties) {
        this(properties.getMaxActiveStdioSessions(),
                properties.getMaxActiveStdioSessionsPerWorkspace(),
                Duration.ofSeconds(properties.getSessionAcquireTimeoutSeconds()),
                Duration.ofSeconds(properties.getSessionIdleTimeoutSeconds()));
    }

    McpStdioSessionPool(int maxActive, int maxPerWorkspace,
                        Duration acquireTimeout, Duration idleTimeout) {
        if (maxActive < 1) throw new IllegalArgumentException("maxActive must be positive");
        if (maxPerWorkspace < 1) {
            throw new IllegalArgumentException("maxPerWorkspace must be positive");
        }
        this.maxActive = maxActive;
        this.maxPerWorkspace = Math.min(maxPerWorkspace, maxActive);
        this.acquireTimeout = requirePositive(acquireTimeout, "acquireTimeout");
        this.idleTimeout = requirePositive(idleTimeout, "idleTimeout");
    }

    static McpStdioSessionPool standalone() {
        return new McpStdioSessionPool(32, 8, Duration.ofSeconds(30), Duration.ofMinutes(10));
    }

    SessionKey key(String workspaceScope, String serverId) {
        return new SessionKey(workspaceScope, serverId);
    }

    Reservation reserve(SessionKey key) throws IOException {
        Objects.requireNonNull(key, "key");
        long remainingNanos = acquireTimeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        while (true) {
            Runnable idleCloser = null;
            SessionKey evictedKey = null;
            try {
                lock.lockInterruptibly();
                try {
                    Entry existing = entries.get(key);
                    if (existing != null) {
                        throw new IOException("MCP stdio session is already starting or active: "
                                + key.serverId());
                    }
                    boolean globalFull = entries.size() >= maxActive;
                    boolean workspaceFull = countWorkspace(key.workspaceScope()) >= maxPerWorkspace;
                    if (!globalFull && !workspaceFull) {
                        entries.put(key, Entry.starting(key));
                        return new Reservation(this, key);
                    }

                    Entry candidate = evictionCandidate(workspaceFull ? key.workspaceScope() : null);
                    if (candidate != null) {
                        candidate.closing = true;
                        idleCloser = candidate.idleCloser;
                        evictedKey = candidate.key;
                    } else {
                        if (remainingNanos <= 0) {
                            throw capacityError(key, globalFull, workspaceFull);
                        }
                        remainingNanos = capacityChanged.awaitNanos(remainingNanos);
                    }
                } finally {
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for MCP stdio session capacity", e);
            }

            if (idleCloser != null) {
                try {
                    idleCloser.run();
                } catch (RuntimeException failure) {
                    release(evictedKey);
                }
            }
            remainingNanos = deadline - System.nanoTime();
        }
    }

    private IOException capacityError(SessionKey key, boolean globalFull, boolean workspaceFull) {
        String limit = workspaceFull
                ? "workspace limit " + maxPerWorkspace
                : "global limit " + maxActive;
        return new IOException("MCP_SESSION_CAPACITY_EXCEEDED: Timed out after "
                + acquireTimeout.toSeconds() + " seconds waiting for " + limit
                + " while starting " + key.serverId());
    }

    private long countWorkspace(String workspaceScope) {
        return entries.keySet().stream()
                .filter(key -> key.workspaceScope().equals(workspaceScope))
                .count();
    }

    private Entry evictionCandidate(String preferredWorkspace) {
        Comparator<Entry> byLastUsed = Comparator.comparingLong(entry -> entry.lastUsedNanos);
        return entries.values().stream()
                .filter(entry -> entry.active && !entry.closing && entry.inFlight == 0)
                .filter(entry -> preferredWorkspace == null
                        || entry.key.workspaceScope().equals(preferredWorkspace))
                .min(byLastUsed)
                .orElse(null);
    }

    private void activate(SessionKey key, Runnable idleCloser) {
        lock.lock();
        try {
            Entry entry = entries.get(key);
            if (entry == null || entry.active || entry.closing) {
                throw new IllegalStateException("MCP stdio reservation is no longer valid: " + key);
            }
            entry.active = true;
            entry.lastUsedNanos = System.nanoTime();
            entry.idleCloser = Objects.requireNonNull(idleCloser, "idleCloser");
        } finally {
            lock.unlock();
        }
    }

    UseLease tryAcquireUse(SessionKey key, boolean userActivity) {
        lock.lock();
        try {
            Entry entry = entries.get(key);
            if (entry == null || !entry.active || entry.closing) return null;
            entry.inFlight++;
            if (userActivity) entry.lastUsedNanos = System.nanoTime();
            return new UseLease(this, key, userActivity);
        } finally {
            lock.unlock();
        }
    }

    boolean claimIfExpired(SessionKey key) {
        lock.lock();
        try {
            Entry entry = entries.get(key);
            if (entry == null || !entry.active || entry.closing || entry.inFlight != 0) return false;
            if (System.nanoTime() - entry.lastUsedNanos < idleTimeout.toNanos()) return false;
            entry.closing = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    void release(SessionKey key) {
        if (key == null) return;
        lock.lock();
        try {
            if (entries.remove(key) != null) capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void releaseUse(SessionKey key, boolean userActivity) {
        lock.lock();
        try {
            Entry entry = entries.get(key);
            if (entry == null) return;
            if (entry.inFlight > 0) entry.inFlight--;
            if (userActivity) entry.lastUsedNanos = System.nanoTime();
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    int activeCount() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    record SessionKey(String workspaceScope, String serverId) {
        SessionKey {
            if (workspaceScope == null || workspaceScope.isBlank()) {
                throw new IllegalArgumentException("workspaceScope must not be blank");
            }
            if (serverId == null || serverId.isBlank()) {
                throw new IllegalArgumentException("serverId must not be blank");
            }
        }
    }

    static final class Reservation implements AutoCloseable {
        private final McpStdioSessionPool owner;
        private final SessionKey key;
        private boolean activated;

        private Reservation(McpStdioSessionPool owner, SessionKey key) {
            this.owner = owner;
            this.key = key;
        }

        void activate(Runnable idleCloser) {
            if (activated) throw new IllegalStateException("MCP stdio reservation is already active");
            owner.activate(key, idleCloser);
            activated = true;
        }

        @Override
        public void close() {
            if (!activated) owner.release(key);
        }
    }

    static final class UseLease implements AutoCloseable {
        private final McpStdioSessionPool owner;
        private final SessionKey key;
        private final boolean userActivity;
        private boolean closed;

        private UseLease(McpStdioSessionPool owner, SessionKey key, boolean userActivity) {
            this.owner = owner;
            this.key = key;
            this.userActivity = userActivity;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            owner.releaseUse(key, userActivity);
        }
    }

    private static final class Entry {
        private SessionKey key;
        private boolean active;
        private boolean closing;
        private int inFlight;
        private long lastUsedNanos;
        private Runnable idleCloser;

        private static Entry starting(SessionKey key) {
            Entry entry = new Entry();
            entry.key = key;
            return entry;
        }
    }
}
