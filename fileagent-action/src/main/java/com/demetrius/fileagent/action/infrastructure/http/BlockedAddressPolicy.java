package com.demetrius.fileagent.action.infrastructure.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * HTTP 出站地址策略：仅允许公网单播地址，阻断本机、内网、元数据和保留地址段。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
public class BlockedAddressPolicy {

    /** 任一非公网或可映射到受限 IPv4 的地址都必须阻断。 */
    public boolean isBlocked(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            return isBlockedIpv4(address.getAddress());
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            if (isIpv4Mapped(bytes)) {
                return true;
            }
            return isBlockedIpv6(bytes);
        }
        return true;
    }

    private static boolean isBlockedIpv4(byte[] address) {
        int first = unsigned(address[0]);
        int second = unsigned(address[1]);
        int third = unsigned(address[2]);
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return true;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return true;
        }
        if (first == 169 && second == 254) {
            return true;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }
        if (first == 192 && second == 168) {
            return true;
        }
        if (first == 192 && second == 0 && (third == 0 || third == 2)) {
            return true;
        }
        if (first == 192 && second == 88 && third == 99) {
            return true;
        }
        if (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) {
            return true;
        }
        return first == 203 && second == 0 && third == 113;
    }

    private static boolean isBlockedIpv6(byte[] address) {
        int first = unsigned(address[0]);
        if ((first & 0xe0) != 0x20) {
            return true;
        }
        return matches(address, new int[]{0x20, 0x01, 0x00, 0x00}, 32)
                || matches(address, new int[]{0x20, 0x01, 0x00, 0x02, 0x00, 0x00}, 48)
                || matches(address, new int[]{0x20, 0x01, 0x00, 0x10}, 28)
                || matches(address, new int[]{0x20, 0x01, 0x00, 0x20}, 28)
                || matches(address, new int[]{0x20, 0x01, 0x0d, 0xb8}, 32)
                || matches(address, new int[]{0x20, 0x02}, 16)
                || matches(address, new int[]{0x3f, 0xfe}, 16);
    }

    private static boolean isIpv4Mapped(byte[] address) {
        if (address.length != 16) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (address[index] != 0) {
                return false;
            }
        }
        return unsigned(address[10]) == 0xff && unsigned(address[11]) == 0xff;
    }

    private static boolean matches(byte[] address, int[] prefix, int bits) {
        int fullBytes = bits / 8;
        int remainingBits = bits % 8;
        for (int index = 0; index < fullBytes; index++) {
            if (unsigned(address[index]) != prefix[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return (unsigned(address[fullBytes]) & mask) == (prefix[fullBytes] & mask);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
