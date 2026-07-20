package com.melon.tools.web;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/** Shared outbound policy for public, read-only HTTP tools. */
final class WebAccessPolicy {

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "localhost.localdomain", "metadata.google.internal");

    private WebAccessPolicy() {
    }

    static URI requirePublicHttpUri(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("URL is required");
        }
        URI parsed;
        try {
            parsed = URI.create(rawUrl.trim()).normalize();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }

        String scheme = lower(parsed.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Only http and https URLs are supported");
        }
        if (parsed.getRawUserInfo() != null) {
            throw new IllegalArgumentException("URLs containing credentials are not allowed");
        }
        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must contain a valid host");
        }
        host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (isBlockedHostname(host)) {
            throw new IllegalArgumentException("Local and private hosts are not allowed");
        }
        int port = parsed.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw new IllegalArgumentException("Only standard HTTP ports 80 and 443 are allowed");
        }
        requirePublicAddresses(host);

        try {
            return new URI(scheme, null, host, port, parsed.getRawPath(), parsed.getRawQuery(), null);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }
    }

    static URI requireSafeRedirect(URI original, URI current, String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Redirect response is missing a Location header");
        }
        URI redirected = requirePublicHttpUri(current.resolve(location).toString());
        if (!sameHost(original, redirected)) {
            throw new IllegalArgumentException("Cross-host redirects are not allowed");
        }
        if ("https".equalsIgnoreCase(current.getScheme())
                && "http".equalsIgnoreCase(redirected.getScheme())) {
            throw new IllegalArgumentException("HTTPS redirects may not downgrade to HTTP");
        }
        return redirected;
    }

    private static boolean sameHost(URI left, URI right) {
        return left.getHost() != null && right.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost());
    }

    private static boolean isBlockedHostname(String host) {
        return BLOCKED_HOSTS.contains(host)
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || host.endsWith(".internal")
                || host.endsWith(".lan")
                || host.endsWith(".home.arpa");
    }

    private static void requirePublicAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Host could not be resolved", e);
        }
        if (addresses.length == 0) {
            throw new IllegalArgumentException("Host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address) && !allowFakeDnsAddress(host, address)) {
                throw new IllegalArgumentException("URL resolves to a local or private address");
            }
        }
    }

    private static boolean allowFakeDnsAddress(String host, InetAddress address) {
        if (isIpLiteral(host) || !isBenchmarkAddress(address)) return false;
        String configured = System.getProperty("MELON_WEB_ALLOW_FAKE_IP_DNS");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("MELON_WEB_ALLOW_FAKE_IP_DNS");
        }
        if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured)) {
            return Boolean.parseBoolean(configured);
        }
        // macOS TUN proxies commonly use RFC 2544 fake-IP DNS (198.18.0.0/15).
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean isBenchmarkAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address instanceof Inet4Address && bytes.length == 4
                && (bytes[0] & 0xff) == 198
                && ((bytes[1] & 0xff) == 18 || (bytes[1] & 0xff) == 19);
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            return a != 0
                    && a != 10
                    && a != 127
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254)
                    && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && b == 0 && c == 0)
                    && !(a == 192 && b == 0 && c == 2)
                    && !(a == 192 && b == 88 && c == 99)
                    && !(a == 192 && b == 168)
                    && !(a == 198 && (b == 18 || b == 19))
                    && !(a == 198 && b == 51 && c == 100)
                    && !(a == 203 && b == 0 && c == 113)
                    && a < 224;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean documentation = first == 0x20 && second == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8;
            return !uniqueLocal && !documentation;
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
