package com.tiku.controller;

import com.tiku.dto.*;
import com.tiku.service.PracticeSessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PracticeSessionController {

    private final PracticeSessionService practiceSessionService;

    public PracticeSessionController(PracticeSessionService practiceSessionService) {
        this.practiceSessionService = practiceSessionService;
    }

    //创建刷题会话（选范围 + 数量，服务端抽题）
    @PostMapping("/banks/{bankId}/sessions")
    public ApiResponse<SessionCreateResponse> createSession(
            @PathVariable Long bankId,
            @RequestBody(required = false) SessionCreateRequest request) {
        return ApiResponse.success(practiceSessionService.createSession(bankId, request));
    }

    //会话历史（含每场成绩）
    @GetMapping("/banks/{bankId}/sessions")
    public ApiResponse<PageResult<SessionResponse>> listSessions(
            @PathVariable Long bankId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(practiceSessionService.listSessions(bankId, page, size));
    }

    //会话详情（完整题目列表 + 每题作答结果/用时/答案解析，回顾页；交卷前不含答案）
    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<SessionDetailResponse> getSessionDetail(@PathVariable Long sessionId) {
        return ApiResponse.success(practiceSessionService.getSessionDetail(sessionId));
    }

    //交卷：标记完成并返回成绩报告（总题数/已答/答对/总分/满分/总用时/每题用时）；幂等，允许未答完交卷。
    //body 可选 {answers:[{questionId, selectedKeys|userAnswer, seconds?}]}——统一判分模型：交卷时一次性提交全部作答
    @PostMapping("/sessions/{sessionId}/finish")
    public ApiResponse<SessionFinishResponse> finishSession(
            @PathVariable Long sessionId,
            @RequestBody(required = false) SessionFinishRequest request) {
        return ApiResponse.success(practiceSessionService.finishSession(sessionId, request));
    }

    //题库分类聚合（topic/category 去重，会话筛选选项）
    @GetMapping("/banks/{bankId}/categories")
    public ApiResponse<BankCategoriesResponse> getBankCategories(@PathVariable Long bankId) {
        return ApiResponse.success(practiceSessionService.getBankCategories(bankId));
    }
}
