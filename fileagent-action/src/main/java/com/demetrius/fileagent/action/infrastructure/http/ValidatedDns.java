package com.demetrius.fileagent.action.infrastructure.http;

import okhttp3.Dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

/**
 * OkHttp DNS 适配器：每次连接解析都重新校验全部地址，并将已校验结果固定给本次连接。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
public class ValidatedDns implements Dns {

    private final Dns delegate;
    private final BlockedAddressPolicy policy;

    public ValidatedDns(Dns delegate, BlockedAddressPolicy policy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (hostname == null || hostname.isBlank()) {
            throw blocked();
        }
        List<InetAddress> addresses = delegate.lookup(hostname);
        if (addresses == null || addresses.isEmpty() || addresses.stream().anyMatch(policy::isBlocked)) {
            throw blocked();
        }
        return List.copyOf(addresses);
    }

    private static UnknownHostException blocked() {
        return new UnknownHostException("HTTP_SSRF_BLOCKED");
    }
}
