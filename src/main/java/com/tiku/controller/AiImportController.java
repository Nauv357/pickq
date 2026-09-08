package com.tiku.controller;

import com.tiku.config.AiSettings;
import com.tiku.dto.*;
import com.tiku.service.AiClientService;
import com.tiku.service.AiConfigService;
import com.tiku.service.AiImportService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * AI 辅助文件导入 + 模型配置（BYOK）
 */
@RestController
@RequestMapping("/api")
public class AiImportController {

    private final AiImportService aiImportService;
    private final AiConfigService aiConfigService;
    private final AiClientService aiClientService;

    public AiImportController(AiImportService aiImportService,
                              AiConfigService aiConfigService,
                              AiClientService aiClientService) {
        this.aiImportService = aiImportService;
        this.aiConfigService = aiConfigService;
        this.aiClientService = aiClientService;
    }

    //创建 AI 导入任务（multipart：files 多选 + 可选 bankId + 可选 aiSupplement + 可选 thinking + 可选 engine）
    @PostMapping("/ai-import/jobs")
    public ApiResponse<Long> createJob(@RequestPart("files") List<MultipartFile> files,
                                       @RequestParam(required = false) Long bankId,
                                       @RequestParam(required = false) Boolean aiSupplement,
                                       @RequestParam(required = false) Boolean thinking,
                                       @RequestParam(required = false) String engine) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个文件");
        }
        for (MultipartFile f : files) {
            if (f.isEmpty()) {
                throw new IllegalArgumentException("文件不能为空");
            }
        }
        List<String> names = new java.util.ArrayList<>();
        List<byte[]> bytes = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            names.add(f.getOriginalFilename());
            bytes.add(f.getBytes());
        }
        return ApiResponse.success(aiImportService.createJob(names, bytes, bankId, aiSupplement, thinking, engine));
    }

    //轮询任务状态（PARSING/AI_GENERATING/VALIDATING/DONE + progress + 结果）
    @GetMapping("/ai-import/jobs/{id}")
    public ApiResponse<AiJobResponse> getJob(@PathVariable Long id) {
        return ApiResponse.success(aiImportService.getJob(id));
    }

    //进行中的任务列表（前端全局监控：侧边栏徽标 + 完成通知）
    @GetMapping("/ai-import/jobs/active")
    public ApiResponse<List<AiJobResponse>> listActiveJobs() {
        return ApiResponse.success(aiImportService.listActiveJobs());
    }

    //最近未确认导入的任务（防止用户丢失预览结果；侧边栏"最近 AI 导入"入口）
    @GetMapping("/ai-import/jobs/recent")
    public ApiResponse<List<AiJobResponse>> listRecentJobs(
            @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.success(aiImportService.listRecentJobs(limit));
    }

    //取消/删除任务：进行中标记 CANCELED（线程检查后停止）+ 清文件；终态物理删除 + 清文件
    @DeleteMapping("/ai-import/jobs/{id}")
    public ApiResponse<Void> deleteJob(@PathVariable Long id) {
        aiImportService.deleteJob(id);
        return ApiResponse.success(null);
    }

    //任务临时图片列表（预览页"图片素材区"：展示所有提取图片，用户拖入/点击插入 [图片N] 标记）
    @GetMapping("/ai-import/jobs/{id}/images")
    public ApiResponse<List<AiImportService.JobImageInfo>> listJobImages(@PathVariable Long id) {
        return ApiResponse.success(aiImportService.listJobImages(id));
    }

    //任务材料素材列表（预览页"材料素材区"：资料分析/阅读材料题的共享材料，用户拖入/点击关联到题目材料区）
    @GetMapping("/ai-import/jobs/{id}/material-snippets")
    public ApiResponse<List<com.tiku.model.ContentPackageMaterial>> listMaterialSnippets(@PathVariable Long id) {
        return ApiResponse.success(aiImportService.listMaterialSnippets(id));
    }

    //读取任务临时图片（素材区缩略图/预览用；confirm 后正式图片走 /api/banks/{bankId}/images/{date}/{file}）
    @GetMapping("/ai-import/jobs/{id}/images/{num}")
    public org.springframework.http.ResponseEntity<byte[]> readJobImage(@PathVariable Long id, @PathVariable int num)
            throws java.io.IOException {
        byte[] bytes = aiImportService.readJobImage(id, num);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "image/png")
                .body(bytes);
    }

    //SSE 事件流：任务阶段变化/完成实时推送（与轮询并存，断开自动回退轮询）
    @GetMapping(value = "/ai-import/jobs/{id}/stream", produces = "text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamJob(@PathVariable Long id) {
        return aiImportService.subscribeJob(id);
    }

    //预览确认后导入：新建题库（bankId 空）或追加指定题库；支持提交预览页编辑后的题目/材料
    @PostMapping("/ai-import/jobs/{id}/confirm")
    public ApiResponse<AiImportConfirmResponse> confirmImport(
            @PathVariable Long id,
            @RequestBody(required = false) AiImportConfirmRequest request) {
        Long bankId = request == null ? null : request.bankId();
        List<com.tiku.model.ContentPackageQuestion> questions = request == null ? null : request.questions();
        List<com.tiku.model.ContentPackageMaterial> materials = request == null ? null : request.materials();
        return ApiResponse.success(aiImportService.confirmImport(id, bankId, questions, materials));
    }

    //查询 AI 配置（Key 脱敏）
    @GetMapping("/ai/settings")
    public ApiResponse<AiSettingsResponse> getSettings() {
        AiSettings s = aiConfigService.load();
        return ApiResponse.success(new AiSettingsResponse(
                s.getBaseUrl(), s.getApiKey() != null && !s.getApiKey().isBlank(),
                aiConfigService.maskKey(s.getApiKey()), s.getModel(), s.getVisionModel(), s.getThinking(),
                s.getMineruKey() != null && !s.getMineruKey().isBlank(),
                aiConfigService.maskKey(s.getMineruKey())));
    }

    //保存 AI 配置（apiKey 为空 = 保留旧 Key）
    @PostMapping("/ai/settings")
    public ApiResponse<Void> saveSettings(@RequestBody AiSettingsRequest request) {
        if (request.baseUrl() != null) {
            aiConfigService.validateBaseUrl(request.baseUrl());
        }
        AiSettings merged = aiConfigService.merge(aiConfigService.load(),
                request.baseUrl(), request.apiKey(), request.model(), request.visionModel(), request.thinking(),
                request.mineruKey());
        if (!aiConfigService.isConfigured() && merged.getApiKey() == null) {
            throw new IllegalArgumentException("请填写 apiKey");
        }
        aiConfigService.save(merged);
        return ApiResponse.success(null);
    }

    //测试连接（最小请求验证配置）
    @PostMapping("/ai/settings/test")
    public ApiResponse<AiClientService.TestResult> testConnection() {
        AiSettings s = aiConfigService.load();
        if (!aiConfigService.isConfigured()) {
            throw new IllegalArgumentException("请先填写完整的模型配置");
        }
        try {
            return ApiResponse.success(aiClientService.test(s));
        } catch (IllegalStateException e) {
            //配置类错误（Key 无效/模型名错/限流）→ 400，让用户看到真实原因并修复
            throw new IllegalArgumentException(e.getMessage());
        }
    }
}
