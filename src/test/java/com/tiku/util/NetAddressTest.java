package com.tiku.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本机/私网地址判定（两处共用：AiConfigService 的 http 放行、AiConfigController 的"是否需要 API Key"）。
 * 只做纯字符串/URI 判定，不连网。
 */
class NetAddressTest {

    @Test
    void acceptsLoopbackAndLocalhost() {
        for (String url : List.of(
                "http://localhost:11434/v1",
                "http://localhost",
                "http://LOCALHOST:11434",
                "http://ai.localhost:11434",
                "http://127.0.0.1:11434/v1",
                "http://127.0.0.1",
                "http://127.5.6.7:8080",          // 127.0.0.0/8 整段都是回环
                "http://[::1]:11434/v1",
                "https://localhost/v1")) {
            assertTrue(NetAddress.isLocalOrPrivate(url), url);
        }
    }

    @Test
    void acceptsRfc1918PrivateRanges() {
        for (String url : List.of(
                "http://10.0.0.5:11434",
                "http://10.255.255.254:11434/v1",
                "http://172.16.0.1:11434",
                "http://172.31.255.254:11434/v1",
                "http://192.168.1.50:11434/v1",
                "http://192.168.0.1")) {
            assertTrue(NetAddress.isLocalOrPrivate(url), url);
        }
    }

    @Test
    void rejectsPublicAndBoundaryAddresses() {
        for (String url : List.of(
                "https://api.deepseek.com",
                "https://api.deepseek.com/v1",
                "http://example.com:11434",
                "http://8.8.8.8",
                "http://1.1.1.1:11434/v1",
                // 私网段的两侧边界（172.16-31 之外不是私网）
                "http://172.15.255.255",
                "http://172.32.0.1",
                "http://11.0.0.1",
                "http://9.255.255.255",
                "http://192.169.1.1",
                "http://193.168.1.1",
                // 监听地址/链路本地/IPv4 简写/IPv4-mapped IPv6：一律按"非私网"保守处理
                "http://0.0.0.0:11434",
                "http://169.254.1.1",
                "http://127.1",
                "http://[::ffff:192.168.1.5]:11434",
                // 非法/无 host
                "",
                "   ",
                "http://",
                "not a url")) {
            assertFalse(NetAddress.isLocalOrPrivate(url), url);
        }
    }

    @Test
    void hostOfNormalizesAndStripsIpv6Brackets() {
        assertEquals("127.0.0.1", NetAddress.hostOf("http://127.0.0.1:11434/v1"));
        assertEquals("api.deepseek.com", NetAddress.hostOf("https://API.DeepSeek.com/v1"));
        assertEquals("::1", NetAddress.hostOf("http://[::1]:11434/v1"));
        assertEquals("", NetAddress.hostOf(null));
        assertEquals("", NetAddress.hostOf("https://"));
    }

    @Test
    void isLocalOrPrivateHostAcceptsHostOnly() {
        assertTrue(NetAddress.isLocalOrPrivateHost("localhost"));
        assertTrue(NetAddress.isLocalOrPrivateHost("[::1]"));
        assertTrue(NetAddress.isLocalOrPrivateHost("192.168.1.50"));
        assertFalse(NetAddress.isLocalOrPrivateHost("example.com"));
        assertFalse(NetAddress.isLocalOrPrivateHost(null));
        assertFalse(NetAddress.isLocalOrPrivateHost("999.168.1.1"));
    }
}
