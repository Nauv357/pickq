package com.tiku.controller;

import com.tiku.dto.AiModelsRequest;
import com.tiku.dto.AiModelsResponse;
import com.tiku.dto.ApiResponse;
import com.tiku.service.AiConfigService;
import com.tiku.service.AiModelCatalogService;
import com.tiku.service.AiPresetService;
import com.tiku.util.NetAddress;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AI 模型预设远端化 + 可用模型动态获取（两条接口都<b>免登录</b>：本地功能，不依赖广场登录态）。
 * <p>
 * AI 配置本身的读写仍在 {@link AiImportController}（/api/ai/settings*）；这里只放"配置之前的辅助能力"，不改动既有接口行为：
 * - GET  /api/ai/presets?center=&refresh=  代理远端预设目录（原样透传 + 内存缓存 1 小时，见 {@link AiPresetService}）；
 * - POST /api/ai/models                    { baseUrl, apiKey? } → 该端点可用模型列表（见 {@link AiModelCatalogService}）。
 * <p>
 * 本控制器不注入 CenterAuthStore：预设是公开静态配置，模型列表鉴权用的是用户自己的 AI Key，
 * 与广场登录态完全无关（未登录也能用）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiConfigController {

    private final AiPresetService aiPresetService;
    private final AiModelCatalogService aiModelCatalogService;
    private final AiConfigService aiConfigService;

    public AiConfigController(AiPresetService aiPresetService,
                              AiModelCatalogService aiModelCatalogService,
                              AiConfigService aiConfigService) {
        this.aiPresetService = aiPresetService;
        this.aiModelCatalogService = aiModelCatalogService;
        this.aiConfigService = aiConfigService;
    }

    /**
     * GET /api/ai/presets?center=&refresh=false — 代理远端模型预设目录。
     * - center 缺省 https://pickq.cn（校验规则同 {@link CenterProxyController} 的 checkBase）；
     *   实际拉取 {center}/config/ai-presets.json；
     * - <b>原样返回</b>远端 JSON（application/json，不解析不改写，避免前后端字段耦合），
     *   前端 http 拦截器只在有 code 字段时才解包，故这里返回的就是预设对象本身；
     * - 内存缓存 1 小时（内容 + 时间戳都留着），refresh=true 强制刷新；
     * - 免登录、且不要求桌面已配置 AI Key；
     * - 失败（网络错误/非 2xx/非 JSON）→ IllegalStateException("无法获取模型预设：<原因>")，
     *   前端据此回退到内置预设。
     */
    @GetMapping("/presets")
    public ResponseEntity<String> getPresets(@RequestParam(required = false) String center,
                                             @RequestParam(defaultValue = "false") boolean refresh) {
        String base = checkBase(center);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(aiPresetService.fetch(base, refresh));
    }

    /**
     * POST /api/ai/models { baseUrl, apiKey? } — 列出该端点可用模型。
     * - baseUrl 必填：去尾部 /；协议规则与保存配置一致（https 一律允许，http 仅本机/局域网，
     *   见 {@link AiConfigService#validateBaseUrl}）；
     * - apiKey 缺省 = 用本地 ai-config.json 里已保存的 Key；
     *   <b>本机/局域网地址（Ollama 等）允许留空</b>：查不到 Key 也照常探测，请求不带鉴权头；
     *   公网地址两者都没有 → 400「请先填写 API Key」；
     * - 候选地址依次尝试 {base}/models → {base}/v1/models（本机/局域网再兜底 Ollama 的 /api/tags），
     *   取第一个成功且能解析出模型名的，resolvedBaseUrl 即那条的 base 形态；
     * - 返回 { models, resolvedBaseUrl, count }（ApiResponse 包装，前端拦截器解包后即 data 内容）；
     * - <b>安全</b>：Key 只用于本次请求的鉴权头，不写日志、不在响应里回显（响应只有模型名与地址）。
     */
    @PostMapping("/models")
    public ApiResponse<AiModelsResponse> listModels(@RequestBody(required = false) AiModelsRequest request) {
        String raw = request == null ? null : request.baseUrl();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("请填写服务地址（baseUrl）");
        }
        // 规范化：去首尾空白 + 去尾部 /（用户常从文档里连 / 一起复制）
        String base = raw.trim().replaceAll("/+$", "");
        // 与保存 AI 配置同一套地址规则：https 一律允许；http 仅本机/局域网（公网 http 拒绝）
        aiConfigService.validateBaseUrl(base);
        // apiKey 缺省 → 用本地已保存的 Key（用户已配过就不用再贴一次）
        String apiKey = blankToNull(request.apiKey());
        if (apiKey == null) {
            apiKey = blankToNull(aiConfigService.load().getApiKey());
        }
        // 本机/局域网服务（Ollama 等）不校验 Key：允许留空，请求不带鉴权头；
        // 公网服务商必须带 Key，缺则先行报错（一个请求都不发）
        if (apiKey == null && !NetAddress.isLocalOrPrivate(base)) {
            throw new IllegalArgumentException("请先填写 API Key");
        }
        AiModelCatalogService.Result result = aiModelCatalogService.list(base, apiKey == null ? null : apiKey.trim());
        return ApiResponse.success(new AiModelsResponse(
                result.models(), result.resolvedBaseUrl(), result.models().size()));
    }

    /** 空白字符串归一化为 null（前端可能送 "" ，语义与"未填写"一致） */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 校验中心基址：仅 http/https、禁止带用户信息、去尾部 /（规则与 CenterProxyController#checkBase 一致） */
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
}
