package com.tiku.controller;

import com.tiku.dto.*;
import com.tiku.model.ContentPackageFile;
import com.tiku.model.enums.QuestionType;
import com.tiku.service.AnswerFillService;
import com.tiku.service.BankMergeService;
import com.tiku.service.ContentPackageService;
import com.tiku.service.QuestionBankService;
import com.tiku.service.QuestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/banks")
public class QuestionBankController {
    private final QuestionBankService questionBankService;
    private final ContentPackageService contentPackageService;
    private final QuestionService questionService;
    private final BankMergeService bankMergeService;
    private final AnswerFillService answerFillService;

    public QuestionBankController(QuestionBankService questionBankService,
                                  ContentPackageService contentPackageService,
                                  QuestionService questionService,
                                  BankMergeService bankMergeService,
                                  AnswerFillService answerFillService) {
        this.questionBankService = questionBankService;
        this.contentPackageService = contentPackageService;
        this.questionService = questionService;
        this.bankMergeService = bankMergeService;
        this.answerFillService = answerFillService;
    }

    //创建题库
    @PostMapping
    public ApiResponse<Long> createQuestionBank(@Valid @RequestBody QuestionBankCreateRequest request) {
        return ApiResponse.success(questionBankService.createQuestionBank(request));
    }

    //列出题库（keyword 搜索名称/描述；sort=created|updated|name，默认最近创建）
    @GetMapping
    public ApiResponse<PageResult<QuestionBankResponse>> listQuestionBanks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort) {
        return ApiResponse.success(questionBankService.listQuestionBanks(page, size, keyword, sort));
    }

    //获取题库详情（点击题库名后展示的题库情况和下面题库题目列表在同一界面但分两个接口加载）
    @GetMapping("/{id}")
    public ApiResponse<QuestionBankDetailResponse> getBankDetail(
            @PathVariable Long id
    ) {
        return ApiResponse.success(questionBankService.getBankDetail(id));
    }

    //题库详情中的题目列表
    //题目列表（支持搜索与筛选：keyword/questionType/category/topic/scope=all|favorite|wrong|undone；
    //响应含 answerKeys/analysis/materialContent——"解析"弹窗数据一次到位）
    @GetMapping("/{id}/questions")
    public ApiResponse<PageResult<QuestionSummaryResponse>> getBankQuestion(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) com.tiku.model.enums.QuestionType questionType,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String scope
    ) {
        return ApiResponse.success(questionBankService.getBankQuestions(
                id, page, size, keyword, questionType, category, topic, scope));
    }

    //题号导航（题库详情右侧目录）：与题目列表同过滤同排序（questionNumber ASC + id ASC），
    //全量轻量字段返回；前端据此渲染题号网格 / 输入直达跳页
    @GetMapping("/{id}/question-nav")
    public ApiResponse<List<QuestionNavItemResponse>> getQuestionNav(
            @PathVariable Long id,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) com.tiku.model.enums.QuestionType questionType,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String scope
    ) {
        return ApiResponse.success(questionBankService.listQuestionNavItems(
                id, keyword, questionType, category, topic, scope));
    }


    //更新题库（仅 name/description/source/authorName；身份字段为系统字段不可改）
    @PutMapping("/{id}")
    public ApiResponse<Void> updateQuestionBank(
            @PathVariable Long id,
            @Valid @RequestBody QuestionBankUpdateRequest request
    ) {
        questionBankService.updateQuestionBank(id, request);
        return ApiResponse.success(null);
    }

    //删除题库：级联逻辑删除其下所有题目与刷题记录，返回删除数量与影响记录数
    @DeleteMapping("/{id}")
    public ApiResponse<DeleteBankResult> deleteQuestionBank(@PathVariable Long id) {
        return ApiResponse.success(questionBankService.deleteQuestionBank(id));
    }

    /**
     * 一次性整理：把旧的「分类」值并入「试卷 / 章节」（归属维度），只在归属为空时填。
     * 界面上「分类」已下线，老题库（从旧文件导入、分类有值）用这个动作把内容搬过去。
     */
    @PostMapping("/{id}/merge-category-into-topic")
    public ApiResponse<java.util.Map<String, Object>> mergeCategoryIntoTopic(@PathVariable Long id) {
        int n = questionBankService.mergeCategoryIntoTopic(id);
        return ApiResponse.success(java.util.Map.of("affected", n));
    }

    //导入内容包（body = 内容包 JSON 原文，v1 纯文本），返回导入结果与题库 id
    @PostMapping("/import")
    public ApiResponse<ImportResultResponse> importContentPackage(@RequestBody String json) {
        return ApiResponse.success(contentPackageService.importContentPackage(json));
    }

    //导入题库压缩包（body = zip 字节，v2；.zip 或旧的 .tiku），返回导入结果与题库 id
    @PostMapping(value = "/import-tiku", consumes = "application/octet-stream")
    public ApiResponse<ImportResultResponse> importTikuPackage(@RequestBody byte[] container) {
        return ApiResponse.success(contentPackageService.importTikuPackage(container));
    }

    //导出内容包（body 可选：version / authorName），返回内容包 JSON 对象，前端保存为文件
    @PostMapping("/{id}/export")
    public ApiResponse<ContentPackageFile> exportContentPackage(
            @PathVariable Long id,
            @RequestBody(required = false) ExportRequest request
    ) {
        return ApiResponse.success(contentPackageService.exportContentPackage(id, request));
    }

    //导出题库压缩包（v2：zip = package.json + media/），返回文件字节流，前端保存为 .zip
    @PostMapping("/{id}/export-tiku")
    public ResponseEntity<byte[]> exportTikuPackage(
            @PathVariable Long id,
            @RequestBody(required = false) ExportRequest request
    ) {
        byte[] bytes = contentPackageService.exportTikuPackage(id, request);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition", "attachment; filename=\"content.zip\"")
                .body(bytes);
    }

    //合并多个题库为新题库（复制 + 血缘；源库保留不动）
    @PostMapping("/merge")
    public ApiResponse<BankMergeService.MergeResult> mergeBanks(
            @Valid @RequestBody BankMergeRequest request
    ) {
        return ApiResponse.success(bankMergeService.mergeBanks(
                request.name(), request.description(), request.sourceBankIds()));
    }

    //选题另存 / 并入：勾选题目复制到新题库（targetBankId 空）或并入现有题库（源库保留）
    @PostMapping("/{id}/questions/selection-copy")
    public ApiResponse<BankMergeService.MergeResult> copySelection(
            @PathVariable Long id,
            @Valid @RequestBody QuestionSelectionCopyRequest request
    ) {
        return ApiResponse.success(bankMergeService.copySelection(
                id, request.questionIds(), request.name(), request.description(), request.targetBankId()));
    }

    //AI 批量补答案：对库内无答案客观题用思考模式判定回填（答案后配的批量兑现；同步执行较慢）
    @PostMapping("/{id}/questions/ai-fill-answers")
    public ApiResponse<AnswerFillService.FillResult> aiFillAnswers(
            @PathVariable Long id,
            @RequestBody(required = false) AiFillAnswersRequest request
    ) {
        QuestionType type = request == null ? null : request.questionType();
        boolean withAnalysis = request != null && Boolean.TRUE.equals(request.withAnalysis());
        return ApiResponse.success(answerFillService.fill(id, type, withAnalysis));
    }

    //启用/关闭复习计划（默认关闭，用户显式开启；控制"今日待复习"队列）
    @PutMapping("/{id}/review-enabled")
    public ApiResponse<Void> setReviewEnabled(
            @PathVariable Long id,
            @Valid @RequestBody ReviewEnabledRequest request
    ) {
        questionBankService.setReviewEnabled(id, request.enabled());
        return ApiResponse.success(null);
    }

    //批量建题（AI 整理数据 / 粘贴导入场景），返回插入数量
    @PostMapping("/{id}/questions/batch")
    public ApiResponse<Integer> batchCreateQuestions(
            @PathVariable Long id,
            @Valid @RequestBody QuestionBatchCreateRequest request
    ) {
        return ApiResponse.success(questionService.batchCreateQuestions(id, request));
    }
}
