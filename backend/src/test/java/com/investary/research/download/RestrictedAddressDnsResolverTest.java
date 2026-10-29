package com.investary.research.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Deque;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RestrictedAddressDnsResolverTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "224.0.0.1", "255.255.255.255", "192.0.2.10", "198.18.0.1",
            "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "64:ff9b::a00:1", "2001:db8::1"})
    void restrictedAddresses(String address) throws Exception {
        assertThat(RestrictedAddressDnsResolver.isRestricted(InetAddress.getByName(address))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.113.131.1", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2606:4700::6810:85e5"})
    void publicAddresses(String address) throws Exception {
        assertThat(RestrictedAddressDnsResolver.isRestricted(InetAddress.getByName(address))).isFalse();
    }

    @Test
    void rejectsHostWhenAnyResolvedAddressIsRestricted() {
        var resolver = new RestrictedAddressDnsResolver(fixed("8.8.8.8", "10.0.0.5"), false);

        assertThatThrownBy(() -> resolver.resolve("mixed.example")).isInstanceOf(BlockedAddressException.class);
    }

    @Test
    void checksEveryResolutionSoDnsRebindingIsBlocked() throws Exception {
        Deque<String> answers = new ArrayDeque<>();
        answers.add("8.8.8.8");
        answers.add("127.0.0.1");
        DnsResolver rebinding = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return new InetAddress[] {InetAddress.getByName(answers.removeFirst())};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        var resolver = new RestrictedAddressDnsResolver(rebinding, false);

        assertThat(resolver.resolve("rebind.example")).extracting(InetAddress::getHostAddress).containsExactly("8.8.8.8");
        assertThatThrownBy(() -> resolver.resolve("rebind.example")).isInstanceOf(BlockedAddressException.class);
    }

    @Test
    void privateNetworksCanBeAllowedForLocalTesting() throws Exception {
        var resolver = new RestrictedAddressDnsResolver(fixed("127.0.0.1"), true);

        assertThat(resolver.resolve("localhost")).hasSize(1);
    }

    private static DnsResolver fixed(String... addresses) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] result = new InetAddress[addresses.length];
                for (int i = 0; i < addresses.length; i++) {
                    result[i] = InetAddress.getByName(addresses[i]);
                }
                return result;
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }
}
