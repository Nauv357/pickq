package com.tiku.util;

import java.net.URI;
import java.util.Locale;

/**
 * 本机 / 局域网（私网）地址判定。
 * <p>
 * 抽成工具类的意义：两处需要完全一致的口径，各写一份正则迟早会走偏——
 * 1. {@link com.tiku.service.AiConfigService#validateBaseUrl}：http 只放行本机与私网（公网必须 https），
 *    让"Ollama 跑在另一台机器"（如 http://192.168.1.50:11434/v1）可以被配置；
 * 2. {@link com.tiku.service.AiModelCatalogService} / {@link com.tiku.controller.AiConfigController#listModels}：
 *    本机/私网服务（Ollama 等）不校验 API Key，允许留空且请求不带鉴权头；公网地址仍必须填 Key。
 * <p>
 * 判定为"本机或私网"的形态：
 * - 主机名 {@code localhost}（含 {@code *.localhost}）；
 * - IPv6 回环 {@code ::1}；
 * - IPv4 回环网段 {@code 127.0.0.0/8}；
 * - RFC 1918 私网：{@code 10.0.0.0/8}、{@code 172.16.0.0/12}（即 172.16 ~ 172.31）、{@code 192.168.0.0/16}。
 * <p>
 * 一律<b>不认</b>的形态：公网域名/公网 IP、{@code 0.0.0.0}（表示"监听所有网卡"，不是可访问地址）、
 * {@code 169.254.x.x}（链路本地自动配置，不是用户自己跑服务的常规写法）、IPv4 简写（{@code 127.1}）、
 * IPv4-mapped IPv6（{@code ::ffff:192.168.1.5}）——这些一律按"非本机/非私网"处理（保守：宁可要求填 Key）。
 */
public final class NetAddress {

    private NetAddress() {
    }

    /** 取 host（小写、去掉 IPv6 方括号）；URL 为空/不合法/无 host 时返回空串（不抛异常，交由调用方按"非私网"处理） */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String host;
        try {
            // URI.getHost() 对 IPv6 会带方括号（http://[::1]:11434 → "[::1]"），此处统一剥掉
            host = URI.create(url.strip()).getHost();
        } catch (RuntimeException e) {
            return "";
        }
        return normalizeHost(host);
    }

    /** 是否本机或私网地址（入参为完整 URL；建议先做 http(s) 前缀校验） */
    public static boolean isLocalOrPrivate(String url) {
        return isLocalOrPrivateHost(hostOf(url));
    }

    /** 是否本机或私网 host（判定规则见类注释） */
    public static boolean isLocalOrPrivateHost(String host) {
        String h = normalizeHost(host);
        if (h.isEmpty()) {
            return false;
        }
        if ("localhost".equals(h) || h.endsWith(".localhost")) {
            return true;
        }
        if ("::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h)) {
            return true;
        }
        String[] parts = h.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!isAsciiDigits(parts[i])) {
                return false;
            }
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) {
                return false;
            }
        }
        // 回环 127.0.0.0/8
        if (octets[0] == 127) {
            return true;
        }
        // 10.0.0.0/8
        if (octets[0] == 10) {
            return true;
        }
        // 172.16.0.0/12 → 第二段 16 ~ 31
        if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) {
            return true;
        }
        // 192.168.0.0/16
        return octets[0] == 192 && octets[1] == 168;
    }

    /** host 归一化：去空白、转小写、剥 IPv6 方括号；null → 空串 */
    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h;
    }

    /** 只接受 ASCII 数字（Character.isDigit 会放过全角/其它语种数字，Integer.parseInt 也会失败） */
    private static boolean isAsciiDigits(String s) {
        if (s.isEmpty() || s.length() > 3) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
