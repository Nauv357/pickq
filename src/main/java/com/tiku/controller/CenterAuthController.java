package com.tiku.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.dto.ApiResponse;
import com.tiku.service.CenterAuthStore;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 题库广场账号（桌面端经本地代理登录/注册/登出/查我）。
 * 流程：本地后端把表单转发官网 /api/auth/login（X-Desktop: 1）→ 官网响应体返回
 * session token → 存 CenterAuthStore（前端不接触 token）→ 后续代理请求自动带
 * Authorization: Bearer。同一张官网 session 表，网页与桌面会话互不干扰。
 */
@RestController
@RequestMapping("/api/center/auth")
public class CenterAuthController {

    private final CenterAuthStore authStore;
    private final ObjectMapper objectMapper;

    public CenterAuthController(CenterAuthStore authStore, ObjectMapper objectMapper) {
        this.authStore = authStore;
        this.objectMapper = objectMapper;
    }

    /** 校验中心地址：仅 http/https，禁止带用户信息（官方地址 https://pickq.cn） */
    private String checkBase(String center) {
        String base = center == null || center.isBlank() ? "https://pickq.cn" : center.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalArgumentException("广场地址需为 http(s) 链接");
        }
        if (base.contains("@")) {
            throw new IllegalArgumentException("广场地址不合法");
        }
        return base.replaceAll("/+$", "");
    }

    /** 请求官网；已登录自动带 Authorization。HTTP >= 400 → 提取官网错误信息抛出 */
    private String request(String method, String url, String jsonBody, boolean desktop) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);
            String token = authStore.token();
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (desktop) {
                conn.setRequestProperty("X-Desktop", "1");
            }
            conn.setRequestProperty("Accept", "application/json");
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = stream == null ? ""
                    : new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
            if (code >= 400) {
                throw new IllegalStateException(extractError(body, code));
            }
            return body;
        } catch (IOException e) {
            throw new IllegalStateException("无法连接题库广场：" + e.getMessage());
        }
    }

    /** 从官网错误响应体提取用户可读 message（h3 错误 JSON：顶层 message / data.message / statusMessage） */
    private String extractError(String body, int code) {
        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                JsonNode m = root.path("message");
                if (m.isTextual() && !m.asText().isBlank()) {
                    return m.asText();
                }
                m = root.path("data").path("message");
                if (m.isTextual() && !m.asText().isBlank()) {
                    return m.asText();
                }
                m = root.path("statusMessage");
                if (m.isTextual() && !m.asText().isBlank()) {
                    return m.asText();
                }
            } catch (IOException ignored) {
                /* 非 JSON 错误体 */
            }
        }
        return "题库广场返回错误（HTTP " + code + "）";
    }

    /** 成功响应体 → data 节点 */
    private JsonNode parseData(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isObject() && !data.isArray()) {
                throw new IllegalStateException("题库广场响应格式异常");
            }
            return data;
        } catch (IOException e) {
            throw new IllegalStateException("题库广场响应解析失败");
        }
    }

    private String jsonOf(Map<String, Object> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (IOException e) {
            throw new IllegalStateException("请求序列化失败");
        }
    }

    private static Map<String, Object> userMap(JsonNode userNode) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("user", userNode == null || userNode.isNull() || userNode.isMissingNode() ? null : userNode);
        return map;
    }

    /** POST /api/center/auth/login { center?, username, password } — 登录并保存会话 */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody AuthReq req) {
        if (req.username() == null || req.username().isBlank() || req.password() == null || req.password().isEmpty()) {
            throw new IllegalArgumentException("请输入用户名和密码");
        }
        String base = checkBase(req.center());
        String body = request("POST", base + "/api/auth/login",
                jsonOf(Map.of("username", req.username().trim(), "password", req.password())), true);
        JsonNode data = parseData(body);
        String token = data.path("token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("登录成功但未返回会话令牌，请重试");
        }
        authStore.save(token, req.username().trim());
        return ApiResponse.success(Collections.singletonMap("user", data.path("user")));
    }

    /** POST /api/center/auth/register { center?, username, password, nickname? } — 注册并登录 */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody AuthReq req) {
        if (req.username() == null || req.username().isBlank() || req.password() == null || req.password().isEmpty()) {
            throw new IllegalArgumentException("请输入用户名和密码");
        }
        String base = checkBase(req.center());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("username", req.username().trim());
        fields.put("password", req.password());
        if (req.nickname() != null && !req.nickname().isBlank()) {
            fields.put("nickname", req.nickname().trim());
        }
        String body = request("POST", base + "/api/auth/register", jsonOf(fields), true);
        JsonNode data = parseData(body);
        String token = data.path("token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("注册成功但未返回会话令牌，请重试");
        }
        authStore.save(token, req.username().trim());
        return ApiResponse.success(Collections.singletonMap("user", data.path("user")));
    }

    /** POST /api/center/auth/logout { center? } — 退出登录（尽力通知官网并清除本地） */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody(required = false) Map<String, String> req) {
        String base = checkBase(req == null ? null : req.get("center"));
        try {
            request("POST", base + "/api/auth/logout", null, false);
        } catch (RuntimeException e) {
            /* token 可能已失效；本地照常清除 */
        }
        authStore.clear();
        return ApiResponse.success(null);
    }

    /** GET /api/center/auth/me?center= — 当前登录用户（未登录 / 会话失效均返回 user: null） */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(@RequestParam(required = false) String center) {
        if (!authStore.isLoggedIn()) {
            return ApiResponse.success(userMap(null));
        }
        String base = checkBase(center);
        String body = request("GET", base + "/api/auth/me", null, false);
        JsonNode data = parseData(body);
        JsonNode user = data.path("user");
        if ((user.isNull() || user.isMissingNode()) && authStore.isLoggedIn()) {
            // 本地有 token 但官网已无此会话：清除（会话过期/在其他端登出）
            authStore.clear();
        }
        return ApiResponse.success(userMap(user));
    }

    /** GET /api/center/auth/status — 本地是否已保存登录（快速判断，不发远程请求） */
    @GetMapping("/status")
    public ApiResponse<Map<String, Boolean>> status() {
        return ApiResponse.success(Map.of("loggedIn", authStore.isLoggedIn()));
    }

    /** 登录/注册请求体 */
    public record AuthReq(String center, String username, String password, String nickname) {
    }
}
