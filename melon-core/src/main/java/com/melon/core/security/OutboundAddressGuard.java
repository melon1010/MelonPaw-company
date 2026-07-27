package com.melon.core.security;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Outbound-address egress guard shared by outbound HTTP callers (web tools, MCP clients,
 * channels). {@link #isPublicAddress(InetAddress)} is the project-wide best-effort classifier
 * for "is this a public unicast address"; every outbound policy delegates to it. It is a
 * defense-in-depth heuristic, not an exhaustive enumeration of every IANA special-use range, so
 * an absolute egress guarantee must come from the network layer (firewall / container egress).
 *
 * <p>Address tiers, from hardest to softest:
 * <ol>
 *   <li><b>Always blocked</b> &mdash; link-local (covers the {@code 169.254.169.254} cloud
 *       metadata endpoint), any-local and multicast. No switch unlocks these.</li>
 *   <li><b>Loopback</b> &mdash; denied by default. The caller's own host is the highest-value
 *       SSRF target (admin endpoints, sidecars), so it needs an explicit
 *       {@code allowLoopback} opt-in or a host allowlist entry.</li>
 *   <li><b>Private</b> &mdash; site-local, carrier-grade NAT, IPv6 unique-local and other
 *       non-public ranges. Denied by default; unlocked globally via {@code allowPrivate} or
 *       per host via {@code hostAllowlist}.</li>
 *   <li><b>Public unicast</b> &mdash; always allowed.</li>
 * </ol>
 *
 * <p>Unlike callers that only permit the standard web ports 80/443, this guard imposes no port
 * restriction: MCP servers commonly listen on non-standard ports (8080/8443/&hellip;).
 *
 * <p>DNS caveat: {@link #requireAllowedHost(String)} resolves at call time. Callers must
 * re-validate before every request (not just once per connection) to narrow DNS-rebinding
 * windows; an absolute guarantee additionally requires network-layer egress control.
 */
public final class OutboundAddressGuard {

    private final boolean allowLoopback;
    private final boolean allowPrivate;
    private final Set<String> hostAllowlist;

    public OutboundAddressGuard(boolean allowLoopback, boolean allowPrivate, Collection<String> hostAllowlist) {
        this.allowLoopback = allowLoopback;
        this.allowPrivate = allowPrivate;
        Set<String> normalized = new LinkedHashSet<>();
        if (hostAllowlist != null) {
            for (String host : hostAllowlist) {
                if (host != null && !host.isBlank()) {
                    // Canonicalize the same way requests are canonicalized, so an entry like
                    // "[fc00::1]", "Café.example.com" or "host.example.com." actually matches.
                    try {
                        String canonical = canonicalizeHost(host);
                        if (!canonical.isEmpty()) {
                            normalized.add(canonical);
                        }
                    } catch (IllegalArgumentException e) {
                        // A malformed allowlist host (e.g. an STD3 violation) can never match a
                        // request host, which is canonicalized identically and rejected on the
                        // same failure. Drop it fail-closed rather than crash construction.
                    }
                }
            }
        }
        this.hostAllowlist = Set.copyOf(normalized);
    }

    /** Default production policy: loopback and private egress both denied, empty allowlist. */
    public static OutboundAddressGuard strict() {
        return new OutboundAddressGuard(false, false, List.of());
    }

    /**
     * Full validation of an absolute http(s) URL: structural checks plus DNS resolution of the
     * host. Returns the parsed URI.
     *
     * @throws IllegalArgumentException if the URL is malformed, uses an unsupported scheme,
     *     embeds credentials, cannot be resolved, or resolves to a blocked address.
     */
    public URI requireAllowedUrl(String rawUrl) {
        URI parsed = requireAllowedUrlStructure(rawUrl);
        requireAllowedHost(parsed.getHost());
        return parsed;
    }

    /**
     * Static (no-DNS) validation for save time: the URL must parse, use http(s), carry a host
     * and no credentials or fragment. IP-literal hosts are classified immediately &mdash; a
     * metadata or loopback literal fails here, before anything is persisted. Hostnames are NOT
     * resolved; the full DNS check runs at connect time and again before every request.
     */
    public URI requireAllowedUrlStructure(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("URL is required");
        }
        URI parsed;
        try {
            parsed = URI.create(rawUrl.trim()).normalize();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Only http and https URLs are supported");
        }
        if (parsed.getRawUserInfo() != null) {
            throw new IllegalArgumentException("URLs containing credentials are not allowed");
        }
        if (parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("URLs containing fragments are not allowed");
        }
        String host = stripBrackets(parsed.getHost());
        if (host.isBlank()) {
            throw new IllegalArgumentException("URL must contain a valid host");
        }
        if (isIpLiteral(host)) {
            // Literals need no DNS: classify (and possibly reject) right now.
            requireAllowedHost(host);
        }
        return parsed;
    }

    /**
     * Resolves {@code host} and returns EVERY address it maps to, having first verified that all
     * of them are permitted by this guard. This is the single resolve-and-validate step a
     * DNS-pinning HTTP client (see {@code GuardedDns}, an OkHttp {@code Dns}) performs: the client
     * connects to exactly the returned addresses, so the address that was validated is the address
     * that is connected. That removes the second, independent connect-time DNS lookup a plain
     * hostname URL would otherwise trigger &mdash; closing the DNS-rebinding TOCTOU window at the
     * code layer. (An absolute guarantee still requires network-layer egress; see the class
     * javadoc.)
     *
     * <p>Accepts hostnames and IPv4/IPv6 literals (bracketed or not). Resolution happens at call
     * time. Throwing {@link UnknownHostException} &mdash; rather than the {@link
     * IllegalArgumentException} used elsewhere &mdash; is deliberate: OkHttp's {@code Dns} contract
     * requires exactly this exception type to signal an unusable host.
     *
     * @throws UnknownHostException if the host is blank, cannot be resolved, or resolves to any
     *     blocked address.
     */
    public InetAddress[] resolveAllowed(String host) throws UnknownHostException {
        String normalizedHost = canonicalizeHost(host);
        if (normalizedHost.isBlank()) {
            throw new UnknownHostException("URL must contain a valid host");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalizedHost);
        } catch (UnknownHostException e) {
            UnknownHostException wrapped = new UnknownHostException("Host could not be resolved");
            wrapped.initCause(e);
            throw wrapped;
        }
        if (addresses.length == 0) {
            throw new UnknownHostException("Host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (!isAllowed(normalizedHost, address)) {
                throw new UnknownHostException(
                        "URL resolves to a blocked address: " + address.getHostAddress());
            }
        }
        return addresses;
    }

    /**
     * Validates that {@code host} resolves only to addresses permitted by this guard. Accepts
     * hostnames, IPv4 literals, and IPv6 literals (with or without surrounding brackets).
     * Resolution happens at call time &mdash; call once per outbound request as an early reject.
     * The authoritative connect-time check is {@link #resolveAllowed(String)}, run inside the
     * pinning DNS so the validated and connected addresses cannot diverge.
     *
     * @throws IllegalArgumentException if the host cannot be resolved or any resolved address is
     *     blocked.
     */
    public void requireAllowedHost(String host) {
        try {
            resolveAllowed(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private boolean isAllowed(String host, InetAddress address) {
        // Classify on the embedded IPv4 for transition addresses so the loopback/private tier
        // decision (below) can't be evaded by wrapping the target in an IPv6 form.
        InetAddress effective = unwrapEmbeddedIpv4(address);
        if (isAlwaysBlocked(effective)) {
            return false;
        }
        if (effective.isLoopbackAddress()) {
            return allowLoopback || hostAllowlist.contains(host);
        }
        if (isPublicAddress(effective)) {
            return true;
        }
        // Private / site-local / CGNAT / IPv6 ULA and other non-public ranges: explicit opt-in.
        return allowPrivate || hostAllowlist.contains(host);
    }

    /**
     * Addresses that are never a legitimate outbound target, regardless of any opt-in:
     * any-local, link-local (incl. the 169.254.169.254 metadata endpoint), and multicast.
     * An IPv6 transition address wrapping such an IPv4 (e.g. NAT64 of the metadata endpoint)
     * stays always-blocked too — no switch unlocks it.
     */
    public static boolean isAlwaysBlocked(InetAddress address) {
        InetAddress effective = unwrapEmbeddedIpv4(address);
        return effective.isAnyLocalAddress()
                || effective.isLinkLocalAddress()
                || effective.isMulticastAddress();
    }

    /**
     * Returns the IPv4 address embedded in an IPv6 transition/translation address, or the
     * argument unchanged if there is none. Java folds only IPv4-mapped {@code ::ffff:a.b.c.d}
     * to {@link Inet4Address}; IPv4-compatible {@code ::/96}, NAT64 {@code 64:ff9b::/96}, 6to4
     * {@code 2002::/16} and Teredo {@code 2001:0::/32} all stay {@link Inet6Address} and would
     * otherwise slip past every IPv4-range check, letting an attacker reach loopback, private
     * or metadata targets through a v6 wrapper.
     */
    private static InetAddress unwrapEmbeddedIpv4(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address;
        }
        byte[] b = address.getAddress();
        if (b.length != 16) {
            return address;
        }
        // IPv4-compatible ::/96 (high 96 bits zero), excluding the specially-assigned
        // :: (any-local) and ::1 (loopback), which are not IPv4-compatible addresses.
        boolean high96Zero = true;
        for (int i = 0; i < 12; i++) {
            if (b[i] != 0) {
                high96Zero = false;
                break;
            }
        }
        if (high96Zero) {
            int low = ((b[12] & 0xff) << 24) | ((b[13] & 0xff) << 16)
                    | ((b[14] & 0xff) << 8) | (b[15] & 0xff);
            if (low == 0 || low == 1) {
                return address;
            }
            return ipv4(b[12], b[13], b[14], b[15], address);
        }
        // NAT64 well-known prefix 64:ff9b::/96 (RFC 6052).
        if ((b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64
                && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
                && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0
                && b[8] == 0 && b[9] == 0 && b[10] == 0 && b[11] == 0) {
            return ipv4(b[12], b[13], b[14], b[15], address);
        }
        // 6to4 2002::/16 — embedded IPv4 in the next 32 bits.
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
            return ipv4(b[2], b[3], b[4], b[5], address);
        }
        // Teredo 2001:0000::/32 — client IPv4 in the last 32 bits, stored ones-complemented.
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && b[2] == 0 && b[3] == 0) {
            return ipv4((byte) ~b[12], (byte) ~b[13], (byte) ~b[14], (byte) ~b[15], address);
        }
        return address;
    }

    private static InetAddress ipv4(byte a, byte b, byte c, byte d, InetAddress fallback) {
        try {
            return InetAddress.getByAddress(new byte[]{a, b, c, d});
        } catch (UnknownHostException e) {
            return fallback; // unreachable for a 4-byte array
        }
    }

    /**
     * The single host canonicalization used by BOTH allowlist construction and request-time
     * matching, so an allowlist entry and the host it is meant to unlock normalize identically.
     * Strips surrounding brackets and a trailing dot, applies IDN ASCII conversion to names, and
     * folds equivalent IP-literal spellings ({@code [::1]} / {@code ::1} / {@code 0:0:0:0:0:0:0:1})
     * to one representation. Returns {@code ""} for a blank host.
     *
     * <p>Package-private so the test can assert that divergent spellings collapse to one form
     * without needing a real DNS lookup for the IDN case.
     */
    static String canonicalizeHost(String host) {
        String candidate = stripBrackets(host);
        if (candidate.endsWith(".")) {   // absolute FQDN: example.com. == example.com
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.isBlank()) {
            return "";
        }
        if (isIpLiteral(candidate)) {
            try {
                // getByName does not hit the network for an IP literal; getHostAddress folds
                // equivalent IPv6 spellings to a single canonical form.
                return InetAddress.getByName(candidate).getHostAddress().toLowerCase(Locale.ROOT);
            } catch (UnknownHostException e) {
                return candidate.toLowerCase(Locale.ROOT);
            }
        }
        return IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    private static String stripBrackets(String host) {
        String candidate = host == null ? "" : host.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]") && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        return candidate;
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    /**
     * Best-effort test for a public unicast address. Loopback, site-local, private, CGNAT,
     * benchmarking, documentation, and IPv6 unique-local ranges return {@code false}, and IPv6
     * transition forms are classified on their embedded IPv4. This is a defense-in-depth
     * heuristic, not a complete enumeration of every IANA special-use range: some reserved,
     * discard-only or benchmarking IPv6 blocks not listed here may still read as public. Use it
     * as one layer; rely on network-layer egress control for an absolute guarantee.
     */
    public static boolean isPublicAddress(InetAddress address) {
        // Classify IPv6 transition addresses (NAT64, 6to4, IPv4-compatible, Teredo) on their
        // embedded IPv4 so a v6 wrapper can't disguise a loopback/private/metadata target.
        InetAddress effective = unwrapEmbeddedIpv4(address);
        if (effective.isAnyLocalAddress() || effective.isLoopbackAddress()
                || effective.isLinkLocalAddress() || effective.isSiteLocalAddress()
                || effective.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = effective.getAddress();
        if (effective instanceof Inet4Address && bytes.length == 4) {
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
        if (effective instanceof Inet6Address && bytes.length == 16) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean documentation = first == 0x20 && second == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8;
            return !uniqueLocal && !documentation;
        }
        return false;
    }
}
