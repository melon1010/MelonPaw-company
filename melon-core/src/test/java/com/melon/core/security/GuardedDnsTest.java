package com.melon.core.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OkHttp DNS that pins MCP outbound connections to guard-validated addresses. These cases prove
 * the TOCTOU-closing contract without a socket: {@code lookup} returns exactly the addresses the
 * guard validated (so OkHttp connects only to those), and throws {@link UnknownHostException} for a
 * blocked or unusable host so OkHttp aborts before any socket opens. IP literals keep every case
 * DNS-free.
 */
class GuardedDnsTest {

    @Test
    void lookupReturnsExactlyTheGuardValidatedAddresses() throws Exception {
        GuardedDns dns = new GuardedDns(OutboundAddressGuard.strict());
        List<InetAddress> addresses = dns.lookup("8.8.8.8");
        // The list OkHttp will dial is precisely what the guard returned from its single lookup.
        assertEquals(List.of(InetAddress.getByName("8.8.8.8")), addresses);
    }

    @Test
    void lookupThrowsUnknownHostForMetadataAddressRegardlessOfOptIns() {
        // 169.254.169.254 is always blocked; even the most permissive guard must refuse it.
        GuardedDns permissive = new GuardedDns(new OutboundAddressGuard(true, true, List.of()));
        assertThrows(UnknownHostException.class, () -> permissive.lookup("169.254.169.254"));
    }

    @Test
    void lookupThrowsUnknownHostForLoopbackUnderStrictButHonorsLoopbackOptIn() throws Exception {
        assertThrows(UnknownHostException.class,
                () -> new GuardedDns(OutboundAddressGuard.strict()).lookup("127.0.0.1"));

        GuardedDns allowLoopback = new GuardedDns(new OutboundAddressGuard(true, false, List.of()));
        assertEquals(List.of(InetAddress.getByName("127.0.0.1")), allowLoopback.lookup("127.0.0.1"));
    }

    @Test
    void lookupThrowsUnknownHostForBlankHost() {
        assertThrows(UnknownHostException.class,
                () -> new GuardedDns(OutboundAddressGuard.strict()).lookup("   "));
    }

    @Test
    void constructorRejectsNullGuardAndLookupRejectsNullHost() {
        assertThrows(NullPointerException.class, () -> new GuardedDns(null));
        GuardedDns dns = new GuardedDns(OutboundAddressGuard.strict());
        assertThrows(NullPointerException.class, () -> dns.lookup(null));
    }

    @Test
    void everyReturnedAddressWouldPassTheGuardIndependently() throws Exception {
        // Defence-in-depth restatement: the pin can only ever hand OkHttp public, allowed addresses.
        GuardedDns dns = new GuardedDns(OutboundAddressGuard.strict());
        for (InetAddress address : dns.lookup("1.1.1.1")) {
            assertTrue(OutboundAddressGuard.isPublicAddress(address));
        }
    }
}
