package com.melon.core.util;

import com.melon.core.security.OutboundAddressGuard;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/** Blocks server-side requests to loopback, link-local and private network targets. */
public final class OutboundNetworkPolicy {

    private static final Set<String> HTTP_SCHEMES = Set.of("http", "https");
    private static final Set<String> WEB_SCHEMES = Set.of("http", "https", "ws", "wss");

    private OutboundNetworkPolicy() {
    }

    public static URI requirePublicHttpUri(String value) {
        return requirePublicUri(value, HTTP_SCHEMES);
    }

    public static URI requirePublicWebUri(String value) {
        return requirePublicUri(value, WEB_SCHEMES);
    }

    private static URI requirePublicUri(String value, Set<String> schemes) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid outbound URL", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (!schemes.contains(scheme) || host == null || host.isBlank() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Only public HTTP(S) or WebSocket URLs are allowed");
        }
        if (Boolean.getBoolean("melon.outbound.allow-private-for-tests")) return uri;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivate(address)) {
                    throw new SecurityException("Private or local outbound targets are not allowed");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Outbound host cannot be resolved", e);
        }
        return uri;
    }

    private static boolean isPrivate(InetAddress address) {
        // Project-wide single source of truth for address classification lives in
        // OutboundAddressGuard; anything not publicly routable is private for this policy.
        return !OutboundAddressGuard.isPublicAddress(address);
    }
}
