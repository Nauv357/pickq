package com.tiku.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.dto.ApiResponse;
import com.tiku.service.CenterAuthStore;
import com.tiku.service.CenterHttpClient;
import com.tiku.service.CenterUrlPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 题库广场账号（桌面端经本地代理登录/注册/登出/查我）。
 * 流程：本地后端把表单转发官网 /api/auth/login（X-Desktop: 1）→ 官网响应体返回
 * session token → 存 CenterAuthStore（前端不接触 token）→ 后续代理请求自动带
 * Authorization: Bearer。同一张官网 session 表，网页与桌面会话互不干扰。
 *
 * GitHub 登录（RFC 8252 本地回环回调，见 /github/start 与 /github/callback）：
 * 桌面端起一个本地 state → 系统浏览器打开官网 GitHub 授权页 → 授权后官网回调
 * 302 回 http://127.0.0.1:{本地随机端口}/api/center/auth/github/callback?ticket=...&state=...
 * → 本地后端用一次性 ticket 向官网换 session token 并保存 → 浏览器显示结果页。
 */
@RestController
@RequestMapping("/api/center/auth")
public class CenterAuthController {

    /** 桌面登录 state 有效期：5 分钟（够走完浏览器授权；过期或已用一律拒绝） */
    private static final long DESKTOP_STATE_TTL_MS = 5 * 60 * 1000L;
    /** 桌面本地回环回调路径（与官网 web/server/utils/github.ts 的白名单路径一致） */
    private static final String DESKTOP_CALLBACK_PATH = "/api/center/auth/github/callback";
    /** 官网票据换取接口（一次性 ticket → session token） */
    private static final String DESKTOP_EXCHANGE_PATH = "/api/auth/desktop-exchange";

    private final CenterAuthStore authStore;
    private final ObjectMapper objectMapper;
    private final CenterUrlPolicy urlPolicy;
    private final CenterHttpClient httpClient;
    /**
     * 本地 web 服务器上下文：**延迟获取**（ObjectProvider）。
     * 生产运行（真实内嵌服务器）一定拿得到；@SpringBootTest 默认 MOCK 环境下没有该 bean，
     * 若构造期强依赖会让整个应用上下文加载失败（测试无法启动）。这里改成只在 /github/start
     * 真正需要端口时解析，拿不到时仍走原来的可读错误。
     */
    private final ObjectProvider<WebServerApplicationContext> webContextProvider;
    /** 桌面 GitHub 登录 state → 过期时间戳（毫秒）。校验后立即移除（单次使用）；启动时随机端口由 webContext 取 */
    private final Map<String, Long> desktopStates = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public CenterAuthController(CenterAuthStore authStore, ObjectMapper objectMapper,
            ObjectProvider<WebServerApplicationContext> webContextProvider, CenterUrlPolicy urlPolicy,
            CenterHttpClient httpClient) {
        this.authStore = authStore;
        this.objectMapper = objectMapper;
        this.webContextProvider = webContextProvider;
        this.urlPolicy = urlPolicy;
        this.httpClient = httpClient;
    }

    /** 保留给独立端口测试和手工构造使用的便捷构造。 */
    public CenterAuthController(CenterAuthStore authStore, ObjectMapper objectMapper,
            ObjectProvider<WebServerApplicationContext> webContextProvider) {
        this(authStore, objectMapper, webContextProvider, new CenterUrlPolicy(),
                new CenterHttpClient(authStore, objectMapper));
    }

    /** 请求官网；会话令牌、超时和远端错误格式均由基础设施客户端统一处理。 */
    private String request(String method, String url, String jsonBody, boolean desktop) {
        return httpClient.forwardJson(method, url, jsonBody, CenterHttpClient.DEFAULT_READ_TIMEOUT_MS, desktop);
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
        String base = urlPolicy.centerBase(req.center());
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

    /** POST /api/center/auth/register { center?, username, password, nickname?, turnstileToken? } — 注册并登录 */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody AuthReq req) {
        if (req.username() == null || req.username().isBlank() || req.password() == null || req.password().isEmpty()) {
            throw new IllegalArgumentException("请输入用户名和密码");
        }
        String base = urlPolicy.centerBase(req.center());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("username", req.username().trim());
        fields.put("password", req.password());
        if (req.nickname() != null && !req.nickname().isBlank()) {
            fields.put("nickname", req.nickname().trim());
        }
        // 人机验证 token（官网配置 TURNSTILE_SECRET 时必填；未配置时官网忽略）
        if (req.turnstileToken() != null && !req.turnstileToken().isBlank()) {
            fields.put("turnstileToken", req.turnstileToken().trim());
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
        String base = urlPolicy.centerBase(req == null ? null : req.get("center"));
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
        String base = urlPolicy.centerBase(center);
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

    // ---------- GitHub 登录（RFC 8252：系统浏览器授权 + 本地回环回调） ----------

    /**
     * 本地服务端口：桌面版以 --server.port=0 随机端口启动，前端窗口地址即该端口。
     * 延迟解析（见 {@link #webContextProvider}）：真实内嵌服务器一定存在；MOCK 环境/单元测试下
     * 拿不到上下文时给出可读错误，而不是让应用上下文启动失败。
     */
    private int localPort() {
        WebServerApplicationContext webContext = webContextProvider.getIfAvailable();
        if (webContext != null) {
            try {
                int port = webContext.getWebServer().getPort();
                if (port > 0) {
                    return port;
                }
            } catch (RuntimeException e) {
                /* 落到下面的可读错误 */
            }
        }
        throw new IllegalStateException("无法获取本地服务端口，请重启拾题后重试");
    }

    /** 32 位 hex state（128 bit 随机；与官网 OAuth state 同规格） */
    private String randomState() {
        byte[] buf = new byte[16];
        secureRandom.nextBytes(buf);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : buf) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /** 清理已过期 state（每次发起登录顺手做，避免 Map 无限增长） */
    private void purgeDesktopStates(long now) {
        desktopStates.entrySet().removeIf(e -> e.getValue() <= now);
    }

    /**
     * POST /api/center/auth/github/start { center? }
     * — 生成桌面 state（5 分钟、单次）并返回官网授权地址，由前端用系统浏览器打开。
     * callback 用本机随机端口（127.0.0.1），官网只接受白名单内的回环地址。
     */
    @PostMapping("/github/start")
    public ApiResponse<Map<String, Object>> githubStart(@RequestBody(required = false) Map<String, String> req) {
        String base = urlPolicy.centerBase(req == null ? null : req.get("center"));
        int port = localPort();
        long now = System.currentTimeMillis();
        purgeDesktopStates(now);
        String state = randomState();
        desktopStates.put(state, now + DESKTOP_STATE_TTL_MS);
        String callback = "http://127.0.0.1:" + port + DESKTOP_CALLBACK_PATH;
        String url = base + "/api/auth/github/start?desktop=1"
                + "&callback=" + URLEncoder.encode(callback, StandardCharsets.UTF_8)
                + "&state=" + state;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        return ApiResponse.success(data);
    }

    /**
     * GET /api/center/auth/github/callback?ticket=&state= — 官网授权后浏览器回环回调（匿名访问，返回 HTML）。
     * - state 必须由本进程发出且未过期：校验后立即移除（单次使用，防重放）
     * - ticket 一次性（60 秒、官网只存哈希）：向官网换 session token 并保存到本地
     * 结果一律渲染成 HTML 结果页（错误页同为 200：回环地址只服务人眼，页面稳定渲染优先）。
     */
    @GetMapping("/github/callback")
    public ResponseEntity<String> githubCallback(
            @RequestParam(required = false) String ticket,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        String st = state == null ? "" : state.trim();
        Long expiresAt = st.isEmpty() ? null : desktopStates.remove(st);
        if (expiresAt == null || expiresAt <= System.currentTimeMillis()) {
            return resultPage(false, "登录链接无效或已过期，请回到拾题应用重试");
        }
        // 官网授权失败（error=github|banned）：给出对应提示，不再尝试换票
        if (error != null && !error.isBlank()) {
            return resultPage(false, githubErrorText(error));
        }
        String t = ticket == null ? "" : ticket.trim();
        if (t.isEmpty()) {
            return resultPage(false, "登录失败或链接已过期，请回到拾题应用重试");
        }
        try {
            String body = request("POST", urlPolicy.centerBase(null) + DESKTOP_EXCHANGE_PATH,
                    jsonOf(Map.of("ticket", t)), false);
            JsonNode data = parseData(body);
            String token = data.path("token").asText(null);
            JsonNode user = data.path("user");
            String username = user.path("username").asText("");
            if (token == null || token.isBlank() || username.isBlank()) {
                return resultPage(false, "登录失败或链接已过期，请回到拾题应用重试");
            }
            authStore.save(token, username);
            return resultPage(true, "已登录为 " + username + "，请回到拾题应用");
        } catch (RuntimeException e) {
            // 官网 exchange 的错误信息（票据无效/过期、封禁、网络异常）原样展示在页面上
            String msg = e.getMessage();
            return resultPage(false, msg == null || msg.isBlank()
                    ? "登录失败或链接已过期，请回到拾题应用重试" : msg);
        }
    }

    /** 官网 error 参数 → 页面上的人话提示 */
    private static String githubErrorText(String error) {
        String e = error.trim().toLowerCase();
        if ("banned".equals(e)) {
            return "该账号已被封禁，无法登录题库广场，请回到拾题应用重试";
        }
        return "GitHub 授权未完成（已取消或失败），请回到拾题应用重试";
    }

    /** 结果页（浏览器直接访问，故返回 text/html; charset=utf-8 而非 JSON；浅色，与应用风格一致） */
    private ResponseEntity<String> resultPage(boolean ok, String message) {
        String title = ok ? "登录成功" : "登录失败";
        String accent = ok ? "#1f9d55" : "#b42318";
        String html = "<!doctype html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + title + " · 拾题</title>\n<style>\n"
                + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:#f7f8fa;color:#1f2329;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',"
                + "'PingFang SC','Microsoft YaHei',sans-serif}\n"
                + ".card{width:calc(100% - 48px);max-width:420px;background:#fff;border:1px solid #e5e6eb;"
                + "border-radius:14px;padding:32px 36px;box-shadow:0 8px 28px rgba(31,35,41,.08);text-align:center}\n"
                + ".mark{width:44px;height:44px;margin:0 auto 16px;border-radius:50%;background:" + accent
                + ";color:#fff;font-size:22px;line-height:44px}\n"
                + "h1{margin:0 0 10px;font-size:19px;font-weight:600;color:" + accent + "}\n"
                + "p{margin:0;font-size:14px;line-height:1.75;color:#4e5969}\n"
                + "small{display:block;margin-top:18px;font-size:12px;color:#86909c}\n"
                + "</style>\n</head>\n<body>\n<div class=\"card\">\n"
                + "<div class=\"mark\">" + (ok ? "&#10003;" : "!") + "</div>\n"
                + "<h1>" + escapeHtml(title) + "</h1>\n<p>" + escapeHtml(message) + "</p>\n"
                + "<small>拾题 · 题库广场</small>\n</div>\n</body>\n</html>\n";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(html);
    }

    /** 结果页文本转义（用户名/错误信息来自外部响应，避免把 HTML 注入页面） */
    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 登录/注册请求体 */
    public record AuthReq(String center, String username, String password, String nickname, String turnstileToken) {
    }
}
