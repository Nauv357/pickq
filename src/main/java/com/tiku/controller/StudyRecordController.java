package com.tiku.controller;

import com.tiku.dto.*;
import com.tiku.model.StudyRecordFile;
import com.tiku.service.StudyRecordService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class StudyRecordController {

    private final StudyRecordService studyRecordService;

    public StudyRecordController(StudyRecordService studyRecordService) {
        this.studyRecordService = studyRecordService;
    }

    //提交作答：判题 + 写入刷题记录（做题页主提交接口）
    @PostMapping("/study-records")
    public ApiResponse<StudyRecordSubmitResponse> submitAnswer(@Valid @RequestBody StudyRecordSubmitRequest request) {
        return ApiResponse.success(studyRecordService.submitAnswer(request));
    }

    //主观题自评：自由给分（0 ~ 满分；后端派生对/部分/错档位，非满分按答错参与错题本/复习）
    @PutMapping("/study-records/{id}/self-grade")
    public ApiResponse<SelfGradeResponse> selfGrade(@PathVariable Long id, @Valid @RequestBody SelfGradeRequest request) {
        return ApiResponse.success(studyRecordService.selfGrade(id, request.earnedScore()));
    }

    //题库刷题记录（分页，时间倒序）
    @GetMapping("/banks/{bankId}/records")
    public ApiResponse<PageResult<StudyRecordResponse>> listBankRecords(
            @PathVariable Long bankId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(studyRecordService.listBankRecords(bankId, page, size));
    }

    //单题作答历史
    @GetMapping("/questions/{questionId}/records")
    public ApiResponse<PageResult<StudyRecordResponse>> listQuestionRecords(
            @PathVariable Long questionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(studyRecordService.listQuestionRecords(questionId, page, size));
    }

    //错题列表（每道题最近一次作答错误的题目；最近答对则移出错题）
    @GetMapping("/banks/{bankId}/wrong-questions")
    public ApiResponse<PageResult<WrongQuestionResponse>> listWrongQuestions(
            @PathVariable Long bankId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(studyRecordService.listWrongQuestions(bankId, page, size));
    }

    //待复习队列（due_at <= now 且未暂停；答错立即可复习，到期题答对也复习防遗忘）
    @GetMapping("/banks/{bankId}/review/due")
    public ApiResponse<PageResult<ReviewDueItemResponse>> listReviewDue(
            @PathVariable Long bankId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(studyRecordService.listReviewDue(bankId, page, size));
    }

    //复习队列摘要（徽标计数/开启提示）：dueTotal + 其中逾期 overdueTotal；开关关闭时为 0
    @GetMapping("/banks/{bankId}/review/summary")
    public ApiResponse<ReviewSummaryResponse> getReviewSummary(@PathVariable Long bankId) {
        return ApiResponse.success(studyRecordService.getReviewSummary(bankId));
    }

    //重置复习计划：清空该题库全部复习状态（含暂停标记），作答记录与错题本不受影响
    @DeleteMapping("/banks/{bankId}/review-states")
    public ApiResponse<Integer> resetReviewStates(@PathVariable Long bankId) {
        return ApiResponse.success(studyRecordService.resetReviewStates(bankId));
    }

    //暂停/恢复复习（"不再复习此题"，不进待复习队列，可随时恢复）
    @PutMapping("/questions/{questionId}/review-suspend")
    public ApiResponse<Void> setReviewSuspended(
            @PathVariable Long questionId,
            @Valid @RequestBody ReviewSuspendRequest request) {
        studyRecordService.setReviewSuspended(questionId, request.suspended());
        return ApiResponse.success(null);
    }

    //题库学习进度
    @GetMapping("/banks/{bankId}/progress")
    public ApiResponse<BankProgressResponse> getBankProgress(@PathVariable Long bankId) {
        return ApiResponse.success(studyRecordService.getBankProgress(bankId));
    }

    //导出全部刷题记录（body = 记录文件 JSON 原文）
    @PostMapping("/study-records/import")
    public ApiResponse<RecordImportResultResponse> importRecords(@RequestBody String json) {
        return ApiResponse.success(studyRecordService.importRecords(json));
    }

    //导出刷题记录文件（返回记录文件 JSON 对象，前端保存为 .json）
    @PostMapping("/study-records/export")
    public ApiResponse<StudyRecordFile> exportRecords() {
        return ApiResponse.success(studyRecordService.exportRecords());
    }
}
