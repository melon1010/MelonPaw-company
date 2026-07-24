package com.lark.oapi.ws;

import java.time.Duration;

/** Keeps official Feishu connection state and executor shutdown behind one lifecycle API. */
public final class ClientLifecycle {

    private ClientLifecycle() {
    }

    public static boolean connected(Client client) {
        return client != null && client.conn != null;
    }

    public static void awaitReady(Client client, Duration timeout) throws Exception {
        if (client == null) throw new IllegalArgumentException("client is required");
        client.awaitReady(timeout.toMillis());
    }

    public static void stop(Client client) {
        if (client == null) return;
        client.close();
        client.executor.shutdownNow();
    }
}
