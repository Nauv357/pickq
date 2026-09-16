package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tiku.dto.NoteRequest;
import com.tiku.dto.NoteResponse;
import com.tiku.dto.PageResult;
import com.tiku.mapper.NoteMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.model.Note;
import com.tiku.model.Question;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 笔记（我的，不是题库的）。
 *
 * 与"解析"的分工——这个边界必须守住，否则两个功能会互相蚕食：
 * - **解析**写进 `question.analysis`：属于题库，会随题库文件导出、会给别人看；
 * - **笔记**只存本机（`note` 表，不进内容包、不导出）：是"我自己的记忆钩子与体会"，
 *   既可以在做题时随手记，也可以把 AI 讲解一键存进来（source=ai，界面上能一眼看出）。
 *
 * `question_id` 可空：只挂在题库上的是"随手记"（没有具体题时的想法）。
 */
@Slf4j
@Service
public class NoteService {

    /** 单条笔记的长度上限（纯文本，别做成文档编辑器） */
    private static final int MAX_CHARS = 2000;

    private final NoteMapper noteMapper;
    private final QuestionMapper questionMapper;
    private final QuestionBankService questionBankService;

    public NoteService(NoteMapper noteMapper, QuestionMapper questionMapper,
                       QuestionBankService questionBankService) {
        this.noteMapper = noteMapper;
        this.questionMapper = questionMapper;
        this.questionBankService = questionBankService;
    }

    /**
     * 题库内的笔记列表（按最近更新排序）。给了 questionId 就只看这道题的笔记。
     * 返回里带题号，界面上不用再逐题查。
     */
    public PageResult<NoteResponse> list(Long bankId, Long questionId, int page, int size) {
        questionBankService.findByIdOrThrow(bankId);
        LambdaQueryWrapper<Note> w = new LambdaQueryWrapper<Note>()
                .eq(Note::getBankId, bankId)
                .orderByDesc(Note::getUpdatedAt)
                .orderByDesc(Note::getId);
        w = questionId == null ? w : w.eq(Note::getQuestionId, questionId);
        IPage<Note> result = noteMapper.selectPage(
                new Page<>(com.tiku.util.Paging.page(page), com.tiku.util.Paging.size(size)), w);
        return new PageResult<>(toResponses(result.getRecords()), result.getTotal(),
                result.getCurrent(), result.getSize(), result.getPages());
    }

    /** 某道题的笔记（做题页/回顾页就地显示，不分页） */
    public List<NoteResponse> listOfQuestion(Long questionId) {
        List<Note> notes = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getQuestionId, questionId)
                .orderByDesc(Note::getUpdatedAt)
                .orderByDesc(Note::getId));
        return toResponses(notes);
    }

    @Transactional
    public NoteResponse create(Long bankId, NoteRequest request) {
        questionBankService.findByIdOrThrow(bankId);
        String content = normalize(request == null ? null : request.content());
        Long questionId = request == null ? null : request.questionId();
        if (questionId != null) {
            Question q = questionMapper.selectById(questionId);
            if (q == null || !q.getBankId().equals(bankId)) {
                throw new IllegalArgumentException("题目不属于该题库");
            }
        }
        Note note = new Note();
        note.setBankId(bankId);
        note.setQuestionId(questionId);
        note.setContent(content);
        note.setSource(Note.SOURCE_AI.equalsIgnoreCase(request == null ? null : request.source())
                ? Note.SOURCE_AI : Note.SOURCE_USER);
        note.setCreatedAt(LocalDateTime.now());
        note.setUpdatedAt(LocalDateTime.now());
        noteMapper.insert(note);
        return toResponse(note, questionNumberOf(questionId));
    }

    @Transactional
    public NoteResponse update(Long id, String content) {
        Note note = require(id);
        note.setContent(normalize(content));
        note.setUpdatedAt(LocalDateTime.now());
        noteMapper.updateById(note);
        return toResponse(note, questionNumberOf(note.getQuestionId()));
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        noteMapper.deleteById(id);
    }

    /**
     * 题库内的笔记条数。
     * 注：界面上的条数走 {@link #list} 的 `total`（顺带把第一页数据拿回来），这里留给需要"只数数"的调用方与测试。
     * 级联删除不在这里——题库/题目被删时的清理与其它表一样由 `QuestionBankService` / `QuestionService`
     * 直接调 mapper 完成（放这里会让 NoteService ↔ 两者互相依赖）。
     */
    public int countByBank(Long bankId) {
        return Math.toIntExact(noteMapper.selectCount(new LambdaQueryWrapper<Note>().eq(Note::getBankId, bankId)));
    }

    private Note require(Long id) {
        Note note = noteMapper.selectById(id);
        if (note == null) {
            throw new NoSuchElementException("笔记不存在：" + id);
        }
        return note;
    }

    private String normalize(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("笔记内容不能为空");
        }
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
    }

    private Integer questionNumberOf(Long questionId) {
        if (questionId == null) {
            return null;
        }
        Question q = questionMapper.selectById(questionId);
        return q == null ? null : q.getQuestionNumber();
    }

    private List<NoteResponse> toResponses(List<Note> notes) {
        Map<Long, Integer> numbers = new HashMap<>();
        List<NoteResponse> out = new ArrayList<>();
        for (Note n : notes) {
            Integer number = n.getQuestionId() == null ? null
                    : numbers.computeIfAbsent(n.getQuestionId(), this::questionNumberOf);
            out.add(toResponse(n, number));
        }
        return out;
    }

    private static NoteResponse toResponse(Note n, Integer questionNumber) {
        return new NoteResponse(n.getId(), n.getBankId(), n.getQuestionId(), questionNumber,
                n.getContent(), n.getSource(), n.getCreatedAt(), n.getUpdatedAt());
    }
}
