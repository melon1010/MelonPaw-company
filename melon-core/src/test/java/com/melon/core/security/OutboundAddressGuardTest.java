package com.melon.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the outbound SSRF policy shared by web tools, MCP clients and channels.
 *
 * <p>Resolution-based cases use IP literals so {@link InetAddress#getAllByName} parses locally and
 * no real DNS lookup happens during the test; hostname cases use the reserved {@code .invalid} TLD
 * to prove no resolution is attempted at that stage.
 */
class OutboundAddressGuardTest {

    private final OutboundAddressGuard strict = OutboundAddressGuard.strict();
    private final OutboundAddressGuard allowLoopback = new OutboundAddressGuard(true, false, List.of());
    private final OutboundAddressGuard allowPrivate = new OutboundAddressGuard(false, true, List.of());

    // ---- Always blocked: link-local / metadata / any-local / multicast, no opt-out exists ----

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",   // cloud metadata endpoint
            "http://0.0.0.0/",                            // any-local
            "http://224.0.0.1/",                          // multicast
    })
    void urlAlwaysBlockedRegardlessOfOptIns(String url) {
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedUrl(url));
        assertThrows(IllegalArgumentException.class, () -> allowLoopback.requireAllowedUrl(url));
        assertThrows(IllegalArgumentException.class, () -> allowPrivate.requireAllowedUrl(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fe80::1", "ff02::1"})   // IPv6 link-local + multicast
    void ipv6HostAlwaysBlockedRegardlessOfOptIns(String host) {
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedHost(host));
        assertThrows(IllegalArgumentException.class, () -> allowLoopback.requireAllowedHost(host));
        assertThrows(IllegalArgumentException.class, () -> allowPrivate.requireAllowedHost(host));
    }

    // ---- Loopback: denied by default, unlocked only by allowLoopback (not allowPrivate) ----

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8080/mcp", "https://127.0.0.1:8443/token"})
    void loopbackUrlDeniedByDefaultAllowedOnlyWithLoopbackOptIn(String url) {
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedUrl(url));
        assertThrows(IllegalArgumentException.class, () -> allowPrivate.requireAllowedUrl(url));
        assertDoesNotThrow(() -> allowLoopback.requireAllowedUrl(url));
    }

    @Test
    void ipv6LoopbackDeniedByDefaultAllowedWithLoopbackOptIn() {
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedHost("::1"));
        assertDoesNotThrow(() -> allowLoopback.requireAllowedHost("::1"));
    }

    // ---- Private / CGNAT / ULA: denied by default, unlocked only by allowPrivate ----

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.5:8080/mcp",
            "http://172.16.9.9/mcp",
            "http://192.168.1.20/mcp",
            "http://100.64.0.1/mcp",   // carrier-grade NAT (100.64.0.0/10)
    })
    void privateUrlDeniedByDefaultAllowedOnlyWithPrivateOptIn(String url) {
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedUrl(url));
        assertThrows(IllegalArgumentException.class, () -> allowLoopback.requireAllowedUrl(url));
        assertDoesNotThrow(() -> allowPrivate.requireAllowedUrl(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fd12:3456:789a::1", "fc00::1"})   // IPv6 unique-local (fc00::/7)
    void ipv6UlaDeniedByDefaultAllowedWithPrivateOptIn(String host) {
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedHost(host));
        assertDoesNotThrow(() -> allowPrivate.requireAllowedHost(host));
    }

    // ---- Public unicast: always allowed, even under the strict default ----

    @ParameterizedTest
    @ValueSource(strings = {"http://8.8.8.8/mcp", "https://1.1.1.1:8443/mcp"})
    void publicUrlAllowed(String url) {
        assertDoesNotThrow(() -> strict.requireAllowedUrl(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2606:4700:4700::1111", "2001:4860:4860::8888"})
    void publicIpv6HostAllowed(String host) {
        assertDoesNotThrow(() -> strict.requireAllowedHost(host));
    }

    // ---- Host allowlist: unlocks the listed loopback/private host, never the always-blocked set ----

    @Test
    void allowlistUnlocksOnlyTheListedPrivateHost() {
        OutboundAddressGuard guard = new OutboundAddressGuard(false, false, Set.of("10.0.0.5"));
        assertDoesNotThrow(() -> guard.requireAllowedUrl("http://10.0.0.5:8080/mcp"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.requireAllowedUrl("http://10.0.0.6:8080/mcp"));
    }

    @Test
    void allowlistUnlocksTheListedLoopbackHost() {
        OutboundAddressGuard guard = new OutboundAddressGuard(false, false, Set.of("127.0.0.1"));
        assertDoesNotThrow(() -> guard.requireAllowedUrl("http://127.0.0.1:8080/mcp"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.requireAllowedHost("::1"));   // only the exact listed host unlocks
    }

    @Test
    void allowlistNeverUnlocksMetadataEndpoint() {
        OutboundAddressGuard guard =
                new OutboundAddressGuard(true, true, Set.of("169.254.169.254"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.requireAllowedUrl("http://169.254.169.254/latest/meta-data/"));
    }

    // ---- Save-time structure validation: static checks only, no DNS ----

    @Test
    void structureCheckPassesUnresolvableHostnameWithoutDns() {
        // .invalid never resolves (RFC 2606): if this passed, no DNS lookup happened.
        URI uri = strict.requireAllowedUrlStructure("https://mcp.example.invalid/mcp");
        assertTrue("mcp.example.invalid".equals(uri.getHost()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",   // metadata literal fails before save
            "http://127.0.0.1:8080/mcp",                  // loopback literal fails under strict
            "http://10.0.0.5:8080/mcp",                   // private literal fails under strict
    })
    void structureCheckClassifiesIpLiteralsImmediately(String url) {
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedUrlStructure(url));
    }

    @Test
    void structureCheckHonorsOptInsForIpLiterals() {
        assertDoesNotThrow(() -> allowLoopback.requireAllowedUrlStructure("http://127.0.0.1:8080/mcp"));
        assertDoesNotThrow(() -> allowPrivate.requireAllowedUrlStructure("http://10.0.0.5:8080/mcp"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://8.8.8.8/",                    // unsupported scheme
            "file:///etc/passwd",                // unsupported scheme
            "http://user:pass@8.8.8.8/",         // embedded credentials
            "https://mcp.example.com/mcp#frag",  // fragment
            "8.8.8.8/no-scheme",                 // no scheme
            "   ",                               // blank
    })
    void structureCheckRejectsMalformedOrUnsupportedUrls(String url) {
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedUrlStructure(url));
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedUrl(url));
    }

    @Test
    void rejectsNullUrl() {
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedUrl(null));
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedUrlStructure(null));
    }

    // ---- IPv6 transition addresses must be classified on their embedded IPv4 ----
    // Java folds only ::ffff:a.b.c.d to Inet4Address; NAT64 / 6to4 / IPv4-compatible / Teredo
    // stay Inet6Address and would smuggle a blocked target past the IPv4 range checks.

    @ParameterizedTest
    @ValueSource(strings = {
            "64:ff9b::a9fe:a9fe",   // NAT64 of 169.254.169.254 (metadata)
            "2002:a9fe:a9fe::",     // 6to4 of 169.254.169.254 (metadata)
    })
    void embeddedIpv4MetadataAlwaysBlockedRegardlessOfOptIns(String host) {
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedHost(host));
        assertThrows(IllegalArgumentException.class, () -> allowLoopback.requireAllowedHost(host));
        assertThrows(IllegalArgumentException.class, () -> allowPrivate.requireAllowedHost(host));
        // Even an allowlist naming the wrapper host cannot unlock the metadata endpoint.
        OutboundAddressGuard allowlisted = new OutboundAddressGuard(true, true, Set.of(host));
        assertThrows(IllegalArgumentException.class, () -> allowlisted.requireAllowedHost(host));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "64:ff9b::7f00:1",   // NAT64 of 127.0.0.1 (loopback)
            "64:ff9b::a00:1",    // NAT64 of 10.0.0.1 (private)
            "2002:7f00:1::",     // 6to4 of 127.0.0.1 (loopback)
            "::7f00:1",          // IPv4-compatible ::127.0.0.1 (loopback)
    })
    void embeddedIpv4LoopbackAndPrivateDeniedUnderStrict(String host) {
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedHost(host));
    }

    @Test
    void embeddedIpv4PublicTargetStillAllowed() {
        // NAT64 / 6to4 of a genuine public IPv4 must not be over-blocked.
        assertDoesNotThrow(() -> strict.requireAllowedHost("64:ff9b::808:808"));   // 8.8.8.8
        assertDoesNotThrow(() -> strict.requireAllowedHost("2002:0808:0808::"));   // 8.8.8.8
    }

    // ---- Host canonicalization: allowlist entries and request hosts must normalize identically ----
    // Before unification, an allowlist built with trim+toLowerCase and a request canonicalized with
    // stripBrackets + IDN.toASCII diverged, so bracketed-IPv6, IDN and trailing-dot entries could
    // never match the host they were meant to unlock.

    @Test
    void canonicalizationCollapsesEquivalentSpellingsToOneForm() {
        // Bracketed / compressed / fully-expanded IPv6 literals fold to one representation.
        String ipv6 = OutboundAddressGuard.canonicalizeHost("fc00::1");
        assertTrue(ipv6.equals(OutboundAddressGuard.canonicalizeHost("[fc00::1]")));
        assertTrue(ipv6.equals(OutboundAddressGuard.canonicalizeHost("FC00::1")));
        assertTrue(ipv6.equals(OutboundAddressGuard.canonicalizeHost("fc00:0:0:0:0:0:0:1")));

        // Trailing dot (absolute FQDN) and letter case collapse for names.
        assertTrue("example.com".equals(OutboundAddressGuard.canonicalizeHost("EXAMPLE.com.")));

        // IDN Unicode and its punycode ASCII form collapse to the same entry.
        assertTrue(OutboundAddressGuard.canonicalizeHost("Bücher.example.com")
                .equals(OutboundAddressGuard.canonicalizeHost("xn--bcher-kva.example.com")));
    }

    @Test
    void allowlistMatchesBracketedIpv6EntryAgainstBareRequestHost() {
        // Entry given in bracketed form; request arrives bare. Must match after unification.
        OutboundAddressGuard guard = new OutboundAddressGuard(false, false, Set.of("[fc00::1]"));
        assertDoesNotThrow(() -> guard.requireAllowedHost("fc00::1"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.requireAllowedHost("fc00::2"));   // only the listed ULA is unlocked
    }

    @Test
    void allowlistMatchesAcrossTrailingDotInEitherDirection() {
        // Entry carries the trailing dot; bare request must still match.
        OutboundAddressGuard dottedEntry = new OutboundAddressGuard(false, false, Set.of("10.0.0.5."));
        assertDoesNotThrow(() -> dottedEntry.requireAllowedHost("10.0.0.5"));
        assertThrows(IllegalArgumentException.class,
                () -> dottedEntry.requireAllowedHost("10.0.0.6"));

        // Bare entry; request carries the trailing dot.
        OutboundAddressGuard bareEntry = new OutboundAddressGuard(false, false, Set.of("10.0.0.5"));
        assertDoesNotThrow(() -> bareEntry.requireAllowedHost("10.0.0.5."));
    }

    @Test
    void malformedAllowlistEntryIsDroppedWithoutCrashingConstruction() {
        // An STD3-invalid entry (underscore) can never match a request host, so it is dropped
        // fail-closed at construction rather than throwing. A valid sibling entry still applies.
        OutboundAddressGuard guard =
                assertDoesNotThrow(() -> new OutboundAddressGuard(false, false,
                        List.of("bad_host.example", "10.0.0.5")));
        assertDoesNotThrow(() -> guard.requireAllowedHost("10.0.0.5"));
    }

    // ---- Static judgment helpers ----

    @Test
    void isPublicAddressAndAlwaysBlockedMatchExpectations() throws Exception {
        assertTrue(OutboundAddressGuard.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertFalse(OutboundAddressGuard.isPublicAddress(InetAddress.getByName("10.0.0.1")));
        assertFalse(OutboundAddressGuard.isPublicAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(OutboundAddressGuard.isPublicAddress(InetAddress.getByName("169.254.169.254")));
        assertFalse(OutboundAddressGuard.isPublicAddress(InetAddress.getByName("192.0.2.1")));     // TEST-NET-1
        assertFalse(OutboundAddressGuard.isPublicAddress(InetAddress.getByName("198.18.0.1")));    // benchmarking
        assertFalse(OutboundAddressGuard.isPublicAddress(InetAddress.getByName("2001:db8::1")));   // IPv6 documentation

        assertTrue(OutboundAddressGuard.isAlwaysBlocked(InetAddress.getByName("169.254.169.254")));
        assertFalse(OutboundAddressGuard.isAlwaysBlocked(InetAddress.getByName("127.0.0.1")));
        assertFalse(OutboundAddressGuard.isAlwaysBlocked(InetAddress.getByName("10.0.0.1")));

        // Embedded-IPv4 IPv6 forms classify on the inner IPv4, not the v6 wrapper.
        assertFalse(OutboundAddressGuard.isPublicAddress(InetAddress.getByName("64:ff9b::a9fe:a9fe"))); // NAT64 metadata
        assertFalse(OutboundAddressGuard.isPublicAddress(InetAddress.getByName("64:ff9b::7f00:1")));    // NAT64 loopback
        assertFalse(OutboundAddressGuard.isPublicAddress(InetAddress.getByName("2002:a9fe:a9fe::")));   // 6to4 metadata
        assertTrue(OutboundAddressGuard.isPublicAddress(InetAddress.getByName("64:ff9b::808:808")));    // NAT64 8.8.8.8
        assertTrue(OutboundAddressGuard.isAlwaysBlocked(InetAddress.getByName("64:ff9b::a9fe:a9fe")));  // NAT64 metadata
        assertTrue(OutboundAddressGuard.isAlwaysBlocked(InetAddress.getByName("2002:a9fe:a9fe::")));    // 6to4 metadata
    }

    // ---- resolveAllowed: the DNS-pin primitive returns the exact addresses that get connected ----
    // GuardedDns (OkHttp) delegates to this so the validated address IS the connected address,
    // closing the DNS-rebinding TOCTOU. It throws UnknownHostException (the okhttp3.Dns contract),
    // not IllegalArgumentException. IP literals keep these cases DNS-free: InetAddress.getAllByName
    // parses a literal without any network lookup.

    @Test
    void resolveAllowedReturnsExactlyTheValidatedPublicAddress() throws Exception {
        InetAddress[] resolved = strict.resolveAllowed("8.8.8.8");
        assertTrue(resolved.length >= 1);
        // Each returned address is what OkHttp will dial — and each one passed validation.
        for (InetAddress address : resolved) {
            assertTrue(OutboundAddressGuard.isPublicAddress(address));
        }
        assertEquals(InetAddress.getByName("8.8.8.8"), resolved[0]);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "169.254.169.254",   // metadata, always blocked
            "127.0.0.1",         // loopback, denied under strict
            "10.0.0.5",          // private, denied under strict
    })
    void resolveAllowedThrowsUnknownHostForBlockedAddressUnderStrict(String host) {
        assertThrows(UnknownHostException.class, () -> strict.resolveAllowed(host));
    }

    @Test
    void resolveAllowedThrowsUnknownHostForBlankHost() {
        assertThrows(UnknownHostException.class, () -> strict.resolveAllowed("   "));
    }

    @Test
    void resolveAllowedHonorsTheSameOptInsAsRequireAllowedHost() throws Exception {
        // The opt-in that unlocks a host for requireAllowedHost unlocks it for resolveAllowed too,
        // and the returned address is the loopback literal that will actually be dialed.
        InetAddress[] resolved = allowLoopback.resolveAllowed("127.0.0.1");
        assertEquals(InetAddress.getByName("127.0.0.1"), resolved[0]);
        assertThrows(UnknownHostException.class, () -> allowPrivate.resolveAllowed("127.0.0.1"));
    }

    @Test
    void resolveAllowedAndRequireAllowedHostAgreeOnMetadataViaEmbeddedIpv4() {
        // The NAT64 wrapper of the metadata IP is blocked by both entry points, proving resolveAllowed
        // applies the same embedded-IPv4 classification requireAllowedHost does — one throws
        // UnknownHostException, the other IllegalArgumentException, but neither lets it through.
        assertThrows(UnknownHostException.class, () -> strict.resolveAllowed("64:ff9b::a9fe:a9fe"));
        assertThrows(IllegalArgumentException.class, () -> strict.requireAllowedHost("64:ff9b::a9fe:a9fe"));
    }
}
