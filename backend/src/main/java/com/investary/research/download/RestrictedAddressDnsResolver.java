package com.investary.research.download;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/**
 * DNS resolver for the download client that refuses hosts resolving to internal addresses.
 * <p>
 * The HTTP client connects to exactly the addresses returned here, so the address that is checked is
 * the address that is used. A hostname that resolves to a public address during one lookup and to an
 * internal one during the next (DNS rebinding) is therefore checked on every connection.
 * If any returned address is restricted, the whole host is rejected.
 */
public class RestrictedAddressDnsResolver implements DnsResolver {

    private final DnsResolver delegate;
    private final boolean allowPrivateNetworks;

    public RestrictedAddressDnsResolver(boolean allowPrivateNetworks) {
        this(SystemDefaultDnsResolver.INSTANCE, allowPrivateNetworks);
    }

    RestrictedAddressDnsResolver(DnsResolver delegate, boolean allowPrivateNetworks) {
        this.delegate = delegate;
        this.allowPrivateNetworks = allowPrivateNetworks;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = delegate.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        if (!allowPrivateNetworks) {
            for (InetAddress address : addresses) {
                if (isRestricted(address)) {
                    throw new BlockedAddressException("Host " + host + " resolves to a restricted network address");
                }
            }
        }
        return addresses;
    }

    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        return Arrays.stream(resolve(host)).map(address -> new InetSocketAddress(address, port)).toList();
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return delegate.resolveCanonicalHostname(host);
    }

    /**
     * Loopback, unspecified, private (RFC 1918), carrier-grade NAT, link-local (incl. cloud metadata
     * 169.254.169.254), multicast, documentation/benchmark ranges, reserved space, IPv6 unique-local,
     * and IPv4-mapped / NAT64 addresses whose embedded IPv4 address is restricted.
     */
    public static boolean isRestricted(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isRestrictedIpv4(b);
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return true; // fc00::/7 unique local
            }
            if (first == 0x20 && second == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return true; // 2001:db8::/32 documentation
            }
            if (isIpv4Mapped(b) || isNat64(b) || isIpv4Compatible(b)) {
                return isRestrictedIpv4(Arrays.copyOfRange(b, 12, 16));
            }
        }
        return false;
    }

    private static boolean isRestrictedIpv4(byte[] b) {
        int a0 = b[0] & 0xff;
        int a1 = b[1] & 0xff;
        int a2 = b[2] & 0xff;
        return a0 == 0                                   // 0.0.0.0/8
                || a0 == 10                              // 10.0.0.0/8
                || a0 == 127                             // loopback
                || (a0 == 100 && (a1 & 0xc0) == 64)      // 100.64.0.0/10 carrier-grade NAT
                || (a0 == 169 && a1 == 254)              // link-local / cloud metadata
                || (a0 == 172 && (a1 & 0xf0) == 16)      // 172.16.0.0/12
                || (a0 == 192 && a1 == 168)              // 192.168.0.0/16
                || (a0 == 192 && a1 == 0 && a2 == 0)     // 192.0.0.0/24 protocol assignments
                || (a0 == 192 && a1 == 0 && a2 == 2)     // TEST-NET-1
                || (a0 == 198 && (a1 & 0xfe) == 18)      // 198.18.0.0/15 benchmarking
                || (a0 == 198 && a1 == 51 && a2 == 100)  // TEST-NET-2
                || (a0 == 203 && a1 == 0 && a2 == 113)   // TEST-NET-3
                || a0 >= 224;                            // multicast, reserved, broadcast
    }

    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    private static boolean isIpv4Compatible(byte[] b) {
        for (int i = 0; i < 12; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNat64(byte[] b) {
        // 64:ff9b::/96
        if ((b[0] & 0xff) != 0x00 || (b[1] & 0xff) != 0x64 || (b[2] & 0xff) != 0xff || (b[3] & 0xff) != 0x9b) {
            return false;
        }
        for (int i = 4; i < 12; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
