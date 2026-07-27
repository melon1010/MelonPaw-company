package com.melon.core.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import okhttp3.Dns;

/**
 * An OkHttp {@link Dns} that resolves a hostname and validates every resulting address against an
 * {@link OutboundAddressGuard} in one atomic step, returning only the validated addresses.
 *
 * <p>This closes the DNS-rebinding TOCTOU that a plain hostname URL leaves open. With the JDK
 * {@code HttpClient}, the guard resolved the host to validate it, but the client then re-resolved
 * the same hostname independently at connect time; a hostile resolver could return a public IP for
 * the validation lookup and a blocked IP (loopback, private, {@code 169.254.169.254}) for the
 * connect lookup. OkHttp instead delegates its single connect-time resolution to this class: the
 * addresses this method validates are the exact addresses OkHttp opens TCP sockets to. Validation
 * and connection can no longer diverge.
 *
 * <p>Only the IP-to-socket step is affected. OkHttp still drives TLS SNI and X.509 hostname
 * verification from the request's real hostname, so certificate checking is unchanged. As with the
 * rest of {@code OutboundAddressGuard}, application-layer validation is not a substitute for
 * network-layer egress control; strict deployments should still restrict egress at the network.
 */
public final class GuardedDns implements Dns {

    private final OutboundAddressGuard guard;

    public GuardedDns(OutboundAddressGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        Objects.requireNonNull(hostname, "hostname");
        // Throws UnknownHostException (the Dns contract) if the host is blank, unresolvable, or
        // resolves to any blocked address, so OkHttp aborts the connection before any socket opens.
        return Arrays.asList(guard.resolveAllowed(hostname));
    }
}
