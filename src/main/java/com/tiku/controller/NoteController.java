package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.dto.NoteLinkRequest;
import com.tiku.dto.NoteQuery;
import com.tiku.dto.NoteRequest;
import com.tiku.dto.NoteResponse;
import com.tiku.dto.PageResult;
import com.tiku.service.NoteService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 笔记（我的，不是题库的）：
 * - 只存本机（`note` 表），**不进内容包、不随题库导出**；
 * - 笔记不隶属于任何题库/题目，挂在哪里由关联决定（0..N 个题库、0..N 道题，未归类也合法）；
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

    /**
     * 笔记列表（按最近更新排序）。过滤可组合：
     * `bankId` 该题库下的、`questionId` 该题的、`unlinked=true` 未归类的、
     * `keyword` 正文关键词、`source` 来源（`user`/`ai`）；都不给 = 全部。
     */
    @GetMapping("/notes")
    public ApiResponse<PageResult<NoteResponse>> list(@RequestParam(required = false) Long bankId,
                                                     @RequestParam(required = false) Long questionId,
                                                     @RequestParam(defaultValue = "false") boolean unlinked,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String source,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(noteService.list(new NoteQuery(bankId, questionId, unlinked, keyword, source), page, size));
    }

    /** 某道题的笔记（做题页/回顾页就地显示，不分页） */
    @GetMapping("/questions/{questionId}/notes")
    public ApiResponse<List<NoteResponse>> listOfQuestion(@PathVariable Long questionId) {
        return ApiResponse.success(noteService.listOfQuestion(questionId));
    }

    /** 新建笔记：`bankId`/`questionId` 都可省略（什么都不挂的随手记） */
    @PostMapping("/notes")
    public ApiResponse<NoteResponse> create(@RequestBody NoteRequest request) {
        return ApiResponse.success(noteService.create(request));
    }

    @PutMapping("/notes/{id}")
    public ApiResponse<NoteResponse> update(@PathVariable Long id, @RequestBody NoteRequest request) {
        return ApiResponse.success(noteService.update(id, request == null ? null : request.content()));
    }

    /** 只改标记色（不动正文）；`color` 传 null/空 = 取消标色 */
    @PutMapping("/notes/{id}/color")
    public ApiResponse<NoteResponse> updateColor(@PathVariable Long id, @RequestBody NoteRequest request) {
        return ApiResponse.success(noteService.updateColor(id, request == null ? null : request.color()));
    }

    @DeleteMapping("/notes/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        noteService.delete(id);
        return ApiResponse.success(null);
    }

    /** 给笔记加一条关联（幂等） */
    @PostMapping("/notes/{id}/links")
    public ApiResponse<NoteResponse> addLink(@PathVariable Long id, @RequestBody NoteLinkRequest request) {
        return ApiResponse.success(noteService.addLink(id, request));
    }

    /** 去掉一条关联（笔记内容保留） */
    @DeleteMapping("/notes/{id}/links")
    public ApiResponse<NoteResponse> removeLink(@PathVariable Long id,
                                               @RequestParam String type,
                                               @RequestParam Long targetId) {
        return ApiResponse.success(noteService.removeLink(id, type, targetId));
    }
}
