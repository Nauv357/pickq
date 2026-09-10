package com.tiku.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * AI 模型预设目录的远端代理 + 内存缓存（接口 GET /api/ai/presets 的数据来源）。
 * <p>
 * 设计要点：
 * - <b>原样透传</b>：只把 https://pickq.cn/config/ai-presets.json 的响应体按字节读成字符串返回，
 *   不做结构解析/字段改写——前端与官网预设字段各自演进，后端不参与耦合（与
 *   {@link com.tiku.controller.CenterProxyController} 的只读透传同思路）；
 * - <b>公开资源、免登录</b>：预设是静态配置，不带 Authorization，也不读 CenterAuthStore
 *   （广场未登录、用户从没配过 AI Key，都能拿到预设目录）；
 * - <b>内存缓存 1 小时</b>：缓存里同时留响应体与抓取时间戳（时间戳既用于 TTL 判定，
 *   也便于排查"这份预设是什么时候取的"）；refresh=true 强制绕过缓存重取；
 * - <b>按 base 匹配缓存</b>：用户可在设置里把 center 指向自建站点/本地调试服务器，
 *   换站点后绝不能拿另一个站点的预设，故缓存命中要求 base 完全相同；
 * - 失败一律抛 IllegalStateException("无法获取模型预设：<原因>")：前端据此回退到内置预设，
 *   所以绝不能抛空/返回半截内容。
 */
@Service
public class AiPresetService {

    /** 中心站点上预设目录的路径（静态 JSON，公开资源） */
    private static final String PRESET_PATH = "/config/ai-presets.json";
    /** 缓存有效期 1 小时 */
    private static final long CACHE_TTL_MS = 60 * 60 * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;
    /** 错误响应片段截断长度（远程返回 HTML/网关页时不至于糊满前端提示） */
    private static final int ERROR_SNIPPET_MAX = 200;

    /**
     * 缓存项：base 命中判定 + 响应体 + 抓取时间戳。
     * 响应体不可变，故整条缓存项用 volatile 发布即可（最坏情况是并发首次请求各拉一次并互相覆盖，
     * 两者内容相同，不会读到半截字符串）。网络请求在锁外进行，不阻塞其他请求。
     */
    private record Cached(String base, String body, long fetchedAt) {
    }

    private volatile Cached cache;

    /**
     * 取预设目录原文：{base}/config/ai-presets.json。
     *
     * @param base    已校验的中心基址（无尾部 /），见 controller 的 checkBase
     * @param refresh true = 跳过缓存强制刷新
     * @return 远端响应体原文（application/json）
     */
    public String fetch(String base, boolean refresh) {
        Cached cached = cache;
        if (!refresh && hit(cached, base)) {
            return cached.body();
        }
        String body = getText(base + PRESET_PATH);
        // 只做"是不是 JSON"的极轻量自检：中心挂了/被网关或登录页拦截时会出现 200 + HTML，
        // 那种内容一旦进缓存，前端会连坏 1 小时；真解析仍交给前端（不做结构耦合）
        if (!looksLikeJson(body)) {
            throw new IllegalStateException("无法获取模型预设：远端返回的不是 JSON（可能被网关或登录页拦截）");
        }
        cache = new Cached(base, body, System.currentTimeMillis());
        return body;
    }

    /** 缓存命中：同一 base 且未过期 */
    private static boolean hit(Cached cached, String base) {
        return cached != null && cached.base().equals(base)
                && System.currentTimeMillis() - cached.fetchedAt() < CACHE_TTL_MS;
    }

    /** GET 转发：返回响应体文本（HttpURLConnection 与项目其余转发的写法保持一致） */
    private String getText(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // 公开配置：不带任何登录态（本接口与广场登录无关）
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = stream == null ? "" : new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            if (code >= 400) {
                throw new IllegalStateException("无法获取模型预设：远端返回错误（HTTP " + code + "）"
                        + (body.isBlank() ? "" : "：" + snippet(body)));
            }
            if (body.isBlank()) {
                throw new IllegalStateException("无法获取模型预设：远端返回空内容");
            }
            return body;
        } catch (IOException e) {
            throw new IllegalStateException("无法获取模型预设：" + e.getMessage(), e);
        }
    }

    /** 响应体是否像 JSON（跳过空白与 BOM，只看首个非空字符是 { 或 [） */
    private static boolean looksLikeJson(String body) {
        if (body == null) {
            return false;
        }
        String text = body.strip();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1).strip();
        }
        return text.startsWith("{") || text.startsWith("[");
    }

    /** 错误片段：压平空白 + 截断 200 字 */
    private static String snippet(String body) {
        String text = body.replaceAll("\\s+", " ").strip();
        return text.length() > ERROR_SNIPPET_MAX ? text.substring(0, ERROR_SNIPPET_MAX) + "…" : text;
    }
}
