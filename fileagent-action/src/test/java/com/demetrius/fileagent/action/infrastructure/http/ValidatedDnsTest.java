package com.demetrius.fileagent.action.infrastructure.http;

import okhttp3.Dns;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DNS 每次解析校验和公网地址策略测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
class ValidatedDnsTest {

    private final BlockedAddressPolicy policy = new BlockedAddressPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.169.254",
            "172.16.0.1", "192.0.0.1", "192.0.2.1", "192.168.1.1", "198.18.0.1",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1",
            "::", "::1", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1", "2002::1"
    })
    void shouldBlockNonPublicAddressRanges(String literal) throws Exception {
        assertThat(policy.isBlocked(InetAddress.getByName(literal))).isTrue();
    }

    @Test
    void shouldBlockIpv4MappedIpv6() throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = 127;
        mapped[15] = 1;

        InetAddress address = Inet6Address.getByAddress(null, mapped, -1);

        assertThat(policy.isBlocked(address)).isTrue();
    }

    @Test
    void shouldAllowPublicIpv4AndIpv6() throws Exception {
        assertThat(policy.isBlocked(InetAddress.getByName("8.8.8.8"))).isFalse();
        assertThat(policy.isBlocked(InetAddress.getByName("2606:4700:4700::1111"))).isFalse();
    }

    @Test
    void shouldRejectWholeLookupWhenAnyAddressIsBlocked() throws Exception {
        Dns delegate = hostname -> List.of(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("127.0.0.1"));
        ValidatedDns dns = ValidatedDns.forTesting(delegate, policy);

        assertThatThrownBy(() -> dns.lookup("api.example.com"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("HTTP_SSRF_BLOCKED");
    }

    @Test
    void shouldResolveAndValidateAgainForEveryLookup() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        Dns changing = hostname -> lookups.getAndIncrement() == 0
                ? List.of(InetAddress.getByName("8.8.8.8"))
                : List.of(InetAddress.getByName("10.0.0.1"));
        ValidatedDns dns = ValidatedDns.forTesting(changing, policy);

        assertThat(dns.lookup("api.example.com")).containsExactly(InetAddress.getByName("8.8.8.8"));
        assertThatThrownBy(() -> dns.lookup("api.example.com"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("HTTP_SSRF_BLOCKED");
        assertThat(lookups).hasValue(2);
    }
}
