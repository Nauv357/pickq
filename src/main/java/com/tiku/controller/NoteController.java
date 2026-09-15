package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.dto.NoteRequest;
import com.tiku.dto.NoteResponse;
import com.tiku.dto.PageResult;
import com.tiku.service.NoteService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 笔记（我的，不是题库的）：
 * - 只存本机（`note` 表），**不进内容包、不随题库导出**；
 * - 挂题（questionId）或只挂题库（随手记）都行；
 * - 做题/回顾时随手记，也可以把 AI 讲解一键存进来（source=ai，界面上能一眼看出）。
 *
 * 与"解析"的分工见 {@link NoteService} 的类注释；解析走 `PUT /api/questions/{id}/analysis`。
 */
@RestController
@RequestMapping("/api")
public class NoteController {

    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    /** 题库内的笔记：给 questionId 就只看这道题的，否则是"我的笔记"列表（按最近更新排序） */
    @GetMapping("/banks/{bankId}/notes")
    public ApiResponse<PageResult<NoteResponse>> list(@PathVariable Long bankId,
                                                     @RequestParam(required = false) Long questionId,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(noteService.list(bankId, questionId, page, size));
    }

    /** 某道题的笔记（做题页/回顾页就地显示，不分页） */
    @GetMapping("/questions/{questionId}/notes")
    public ApiResponse<List<NoteResponse>> listOfQuestion(@PathVariable Long questionId) {
        return ApiResponse.success(noteService.listOfQuestion(questionId));
    }

    @PostMapping("/banks/{bankId}/notes")
    public ApiResponse<NoteResponse> create(@PathVariable Long bankId, @RequestBody NoteRequest request) {
        return ApiResponse.success(noteService.create(bankId, request));
    }

    @PutMapping("/notes/{id}")
    public ApiResponse<NoteResponse> update(@PathVariable Long id, @RequestBody NoteRequest request) {
        return ApiResponse.success(noteService.update(id, request == null ? null : request.content()));
    }

    @DeleteMapping("/notes/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        noteService.delete(id);
        return ApiResponse.success(null);
    }
}
