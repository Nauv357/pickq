package com.tiku.controller;

import com.tiku.dto.*;
import com.tiku.service.QuestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionService questionService;
    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @GetMapping("/{id}")
    public ApiResponse<QuestionDetailResponse> getQuestion(@PathVariable Long id) {
        return ApiResponse.success(questionService.getQuestionDetail(id));
    }

    @PostMapping("/{id}/answer")
    public ApiResponse<AnswerResultResponse> submitAnswer(
            @PathVariable Long id,
            @Valid @RequestBody AnswerRequest request
    ){
        return ApiResponse.success(questionService.checkAnswer(id, request.selectedKeys()));
    }

    @PostMapping
    public ApiResponse<Long> createQuestion(
            @Valid @RequestBody QuestionCreateRequest request
    ){
        return ApiResponse.success(questionService.createQuestion(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> updateQuestion(
            @PathVariable Long id,
            @Valid @RequestBody QuestionUpdateRequest request
    ){
        questionService.updateQuestion(id, request);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteQuestion(
            @PathVariable Long id
    ){
        questionService.deleteQuestion(id);
        return ApiResponse.success(null);
    }

    //收藏/取消收藏
    @PutMapping("/{id}/favorite")
    public ApiResponse<Void> setFavorite(
            @PathVariable Long id,
            @Valid @RequestBody FavoriteRequest request
    ){
        questionService.setFavorite(id, request.favorite());
        return ApiResponse.success(null);
    }

    /**
     * 草稿 AI 解析（编辑/录入面板：基于表单当前内容生成，题目可未保存）。
     * 注意：题目的"讲解"走 `/api/tutor/explain`（统一引擎，见 TutorService）——
     * 这里只剩"编辑时按草稿补一份正式解析"这一条路。
     */
    @PostMapping("/ai-analysis-draft")
    public ApiResponse<String> aiAnalysisDraft(@Valid @RequestBody AnalysisDraftRequest request) {
        return ApiResponse.success(questionService.analyzeDraft(request.bankId(), request.questionTypeLabel(),
                request.content(), request.options(), request.answerKeys(), request.answerText(),
                request.referenceAnswer(), request.materialContent()));
    }

    /** 保存解析文本为题目正式解析（覆盖 analysis 字段） */
    @PutMapping("/{id}/analysis")
    public ApiResponse<Void> saveAnalysis(@PathVariable Long id,
                                          @Valid @RequestBody SaveAnalysisRequest request) {
        questionService.saveAnalysis(id, request.analysis());
        return ApiResponse.success(null);
    }
}
