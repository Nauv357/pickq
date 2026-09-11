package com.tiku.service;

import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 题库广场地址与下载地址的统一校验。
 *
 * <p>中心地址由桌面端用户配置，会被后端用于发起服务器端请求；因此不能只依赖字符串前缀。
 * 本类将协议、主机和用户信息校验集中，避免不同 Controller 出现不一致的校验规则。</p>
 */
@Component
public class CenterUrlPolicy {

    public static final String DEFAULT_CENTER = "https://pickq.cn";

    /**
     * 规范化中心根地址。中心根地址不能携带查询串或片段，否则后续拼接 API 路径时会产生歧义。
     */
    public String centerBase(String rawCenter) {
        String value = rawCenter == null || rawCenter.isBlank() ? DEFAULT_CENTER : rawCenter.trim();
        URI uri = parseHttpUri(value, "广场地址");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("广场地址不能包含查询参数或片段");
        }
        String normalized = uri.normalize().toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /** 外链内容包允许携带查询参数，例如对象存储的临时签名。 */
    public String externalDownloadUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        return parseHttpUri(rawUrl.trim(), "下载链接").toString();
    }

    /** 将固定 API 路径拼接到已经校验过的中心根地址。 */
    public String appendPath(String centerBase, String path) {
        if (centerBase == null || centerBase.isBlank()) {
            throw new IllegalArgumentException("广场地址不能为空");
        }
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("广场接口路径不合法");
        }
        return centerBase + path;
    }

    private URI parseHttpUri(String value, String label) {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + "不合法");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(label + "需为 http(s) 链接");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException(label + "不合法");
        }
        return uri;
    }
}
