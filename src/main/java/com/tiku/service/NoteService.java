package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tiku.dto.NoteLinkRequest;
import com.tiku.dto.NoteLinkResponse;
import com.tiku.dto.NoteRequest;
import com.tiku.dto.NoteResponse;
import com.tiku.dto.PageResult;
import com.tiku.mapper.NoteLinkMapper;
import com.tiku.mapper.NoteMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.model.Note;
import com.tiku.model.NoteLink;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 笔记（我的，不是题库的）。
 *
 * 与"解析"的分工——这个边界必须守住，否则两个功能会互相蚕食：
 * - **解析**写进 `question.analysis`：属于题库，会随题库文件导出、会给别人看；
 * - **笔记**只存本机（`note` 表，不进内容包、不导出）：是"我自己的记忆钩子与体会"，
 *   既可以在做题时随手记，也可以把 AI 讲解一键存进来（source=ai，界面上能一眼看出）。
 *
 * **归属是关联，不是字段**（2026-09-16 用户要求）：笔记自己不隶属于任何题库或题目，
 * 由 `note_link` 表达"挂在哪里"，一条笔记可以同时挂多个题库、多道题，也可以一个都不挂
 * （未归类）。题库/题目被删时只删关联行、保留笔记内容——用户写下的东西不该因为题库被删而消失。
 */
@Slf4j
@Service
public class NoteService {

    /** 单条笔记的长度上限（纯文本，别做成文档编辑器） */
    private static final int MAX_CHARS = 2000;

    private final NoteMapper noteMapper;
    private final NoteLinkMapper noteLinkMapper;
    private final QuestionMapper questionMapper;
    private final QuestionBankMapper questionBankMapper;

    public NoteService(NoteMapper noteMapper, NoteLinkMapper noteLinkMapper,
                       QuestionMapper questionMapper, QuestionBankMapper questionBankMapper) {
        this.noteMapper = noteMapper;
        this.noteLinkMapper = noteLinkMapper;
        this.questionMapper = questionMapper;
        this.questionBankMapper = questionBankMapper;
    }

    /** 不带关键词的常用形态（题库内的笔记列表、每题的笔记列表） */
    public PageResult<NoteResponse> list(Long bankId, Long questionId, boolean unlinked, int page, int size) {
        return list(bankId, questionId, unlinked, null, page, size);
    }

    /**
     * 笔记列表（按最近更新排序）。
     *
     * @param bankId     只看"这个题库的笔记" = 挂在库上的 + 挂在这个库题目上的（可空）
     * @param questionId 只看关联到该题的笔记（可空）
     * @param unlinked   true = 只看"未归类"（一条关联都没有）
     * @param keyword    正文关键词（可空；用于"我记过什么"的查找）
     */
    public PageResult<NoteResponse> list(Long bankId, Long questionId, boolean unlinked, String keyword,
                                        int page, int size) {
        LambdaQueryWrapper<Note> w = new LambdaQueryWrapper<Note>()
                .orderByDesc(Note::getUpdatedAt)
                .orderByDesc(Note::getId);
        if (questionId != null) {
            w = w.inSql(Note::getId, linkIdsSql(NoteLink.TYPE_QUESTION, questionId));
        } else if (bankId != null) {
            w = w.inSql(Note::getId, bankNoteIdsSql(bankId));
        }
        if (unlinked) {
            w = w.notInSql(Note::getId, "SELECT note_id FROM note_link");
        }
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            w = w.like(Note::getContent, kw);
        }
        IPage<Note> result = noteMapper.selectPage(
                new Page<>(com.tiku.util.Paging.page(page), com.tiku.util.Paging.size(size)), w);
        return new PageResult<>(toResponses(result.getRecords()), result.getTotal(),
                result.getCurrent(), result.getSize(), result.getPages());
    }

    /** 某道题的笔记（做题页/回顾页就地显示，不分页） */
    public List<NoteResponse> listOfQuestion(Long questionId) {
        List<Note> notes = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .inSql(Note::getId, linkIdsSql(NoteLink.TYPE_QUESTION, questionId))
                .orderByDesc(Note::getUpdatedAt)
                .orderByDesc(Note::getId));
        return toResponses(notes);
    }

    /** 新建笔记：内容必填，关联可选（题库/题目）——关联的就是你写下的那些，不做隐式派生 */
    @Transactional
    public NoteResponse create(NoteRequest request) {
        String content = normalize(request == null ? null : request.content());
        Note note = new Note();
        note.setContent(content);
        note.setColor(Note.normalizeColor(request == null ? null : request.color()));
        note.setSource(Note.SOURCE_AI.equalsIgnoreCase(request == null ? null : request.source())
                ? Note.SOURCE_AI : Note.SOURCE_USER);
        note.setCreatedAt(LocalDateTime.now());
        note.setUpdatedAt(LocalDateTime.now());
        noteMapper.insert(note);

        Long bankId = request == null ? null : request.bankId();
        Long questionId = request == null ? null : request.questionId();
        if (questionId != null) {
            requireQuestion(questionId);
            link(note.getId(), NoteLink.TYPE_QUESTION, questionId);
        }
        if (bankId != null) {
            requireBank(bankId);
            link(note.getId(), NoteLink.TYPE_BANK, bankId);
        }
        return toResponse(note, linksOf(note.getId()));
    }

    @Transactional
    public NoteResponse update(Long id, String content) {
        Note note = require(id);
        note.setContent(normalize(content));
        note.setUpdatedAt(LocalDateTime.now());
        noteMapper.updateById(note);
        return toResponse(note, linksOf(note.getId()));
    }

    /**
     * 只改标记色（不动正文，也不动更新时间之外的任何东西）。
     * 传 null / 空串即"取消标色"；非法颜色同样按取消处理（不引入第三种状态）。
     */
    @Transactional
    public NoteResponse updateColor(Long id, String color) {
        Note note = require(id);
        note.setColor(Note.normalizeColor(color));
        // 用 wrapper 显式 set：实体字段为 null 时 MyBatis-Plus 默认不更新，取消标色会失效
        noteMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Note>()
                .eq(Note::getId, id)
                .set(Note::getColor, note.getColor()));
        return toResponse(note, linksOf(id));
    }

    /** 删除笔记（连同它的关联行） */
    @Transactional
    public void delete(Long id) {
        require(id);
        noteLinkMapper.delete(new LambdaQueryWrapper<NoteLink>().eq(NoteLink::getNoteId, id));
        noteMapper.deleteById(id);
    }

    /** 把笔记关联到某处（幂等：已经关联过就不重复插） */
    @Transactional
    public NoteResponse addLink(Long id, NoteLinkRequest request) {
        Note note = require(id);
        String type = normalizeType(request == null ? null : request.type());
        Long targetId = request == null ? null : request.targetId();
        if (targetId == null) {
            throw new IllegalArgumentException("要关联到哪个题库或哪道题？");
        }
        if (NoteLink.TYPE_QUESTION.equals(type)) {
            requireQuestion(targetId);
        } else {
            requireBank(targetId);
        }
        link(id, type, targetId);
        return toResponse(note, linksOf(id));
    }

    /** 去掉一条关联（笔记内容保留） */
    @Transactional
    public NoteResponse removeLink(Long id, String type, Long targetId) {
        Note note = require(id);
        noteLinkMapper.delete(new LambdaQueryWrapper<NoteLink>()
                .eq(NoteLink::getNoteId, id)
                .eq(NoteLink::getTargetType, normalizeType(type))
                .eq(NoteLink::getTargetId, targetId));
        return toResponse(note, linksOf(id));
    }

    /** 题库的笔记条数：挂在库上的 + 挂在这个库题目上的（与列表同一个口径） */
    public int countByBank(Long bankId) {
        return Math.toIntExact(noteMapper.selectCount(new LambdaQueryWrapper<Note>()
                .inSql(Note::getId, bankNoteIdsSql(bankId))));
    }

    private void link(Long noteId, String type, Long targetId) {
        boolean exists = noteLinkMapper.selectCount(new LambdaQueryWrapper<NoteLink>()
                .eq(NoteLink::getNoteId, noteId)
                .eq(NoteLink::getTargetType, type)
                .eq(NoteLink::getTargetId, targetId)) > 0;
        if (!exists) {
            noteLinkMapper.insert(NoteLink.of(noteId, type, targetId));
        }
    }

    private static String linkIdsSql(String type, Long targetId) {
        // targetId 是 Long（已由 Spring 绑定/校验），拼进 SQL 无注入面；type 用常量
        return "SELECT note_id FROM note_link WHERE target_type = '" + type + "' AND target_id = " + targetId;
    }

    /**
     * "这个题库的笔记"口径（列表与计数共用一处，避免两边算法漂移）：
     * 挂在题库上的 **或** 挂在这个库里某道题上的。
     */
    private static String bankNoteIdsSql(Long bankId) {
        return "SELECT note_id FROM note_link WHERE (target_type = '" + NoteLink.TYPE_BANK + "' AND target_id = " + bankId
                + ") OR (target_type = '" + NoteLink.TYPE_QUESTION
                + "' AND target_id IN (SELECT id FROM question WHERE bank_id = " + bankId + "))";
    }

    private static String normalizeType(String raw) {
        String type = raw == null ? "" : raw.trim().toLowerCase();
        if (NoteLink.TYPE_QUESTION.equals(type)) {
            return NoteLink.TYPE_QUESTION;
        }
        if (NoteLink.TYPE_BANK.equals(type)) {
            return NoteLink.TYPE_BANK;
        }
        throw new IllegalArgumentException("关联类型只能是 bank 或 question");
    }

    private Note require(Long id) {
        Note note = noteMapper.selectById(id);
        if (note == null) {
            throw new NoSuchElementException("笔记不存在：" + id);
        }
        return note;
    }

    private Question requireQuestion(Long questionId) {
        Question q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new NoSuchElementException("题目不存在：" + questionId);
        }
        return q;
    }

    private void requireBank(Long bankId) {
        if (questionBankMapper.selectById(bankId) == null) {
            throw new NoSuchElementException("题库不存在：" + bankId);
        }
    }

    private String normalize(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("笔记内容不能为空");
        }
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
    }

    private List<NoteLink> linksOf(Long noteId) {
        return noteLinkMapper.selectList(new LambdaQueryWrapper<NoteLink>()
                .eq(NoteLink::getNoteId, noteId)
                .orderByAsc(NoteLink::getTargetType)
                .orderByAsc(NoteLink::getTargetId));
    }

    /**
     * 批量取关联（列表场景：一页 20 条笔记，不能一条一条查）。
     * 一次查关联 + 一次查题库名 + 一次查题号，与笔记条数无关。
     */
    private List<NoteResponse> toResponses(List<Note> notes) {
        if (notes.isEmpty()) {
            return List.of();
        }
        List<Long> noteIds = notes.stream().map(Note::getId).toList();
        List<NoteLink> links = noteLinkMapper.selectList(new LambdaQueryWrapper<NoteLink>()
                .in(NoteLink::getNoteId, noteIds)
                .orderByAsc(NoteLink::getTargetType)
                .orderByAsc(NoteLink::getTargetId));
        Map<Long, List<NoteLink>> byNote = new LinkedHashMap<>();
        for (NoteLink link : links) {
            byNote.computeIfAbsent(link.getNoteId(), k -> new ArrayList<>()).add(link);
        }

        Set<Long> bankIds = new HashSet<>();
        Set<Long> questionIds = new HashSet<>();
        for (NoteLink link : links) {
            if (NoteLink.TYPE_BANK.equals(link.getTargetType())) {
                bankIds.add(link.getTargetId());
            } else {
                questionIds.add(link.getTargetId());
            }
        }
        Map<Long, String> bankNames = bankNames(bankIds);
        Map<Long, Question> questions = questions(questionIds);
        for (Question q : questions.values()) {
            bankNames.computeIfAbsent(q.getBankId(), this::bankName);
        }

        List<NoteResponse> out = new ArrayList<>();
        for (Note n : notes) {
            out.add(toResponse(n, byNote.getOrDefault(n.getId(), List.of()), bankNames, questions));
        }
        return out;
    }

    private Map<Long, String> bankNames(Set<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (QuestionBank bank : questionBankMapper.selectBatchIds(ids)) {
            names.put(bank.getId(), bank.getName());
        }
        return names;
    }

    private Map<Long, Question> questions(Set<Long> ids) {
        Map<Long, Question> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (Question q : questionMapper.selectBatchIds(ids)) {
            map.put(q.getId(), q);
        }
        return map;
    }

    private String bankName(Long bankId) {
        QuestionBank bank = questionBankMapper.selectById(bankId);
        return bank == null ? null : bank.getName();
    }

    private NoteResponse toResponse(Note n, List<NoteLink> links) {
        Set<Long> bankIds = new HashSet<>();
        Set<Long> questionIds = new HashSet<>();
        for (NoteLink link : links) {
            if (NoteLink.TYPE_BANK.equals(link.getTargetType())) {
                bankIds.add(link.getTargetId());
            } else {
                questionIds.add(link.getTargetId());
            }
        }
        Map<Long, String> names = bankNames(bankIds);
        Map<Long, Question> questions = questions(questionIds);
        for (Question q : questions.values()) {
            names.computeIfAbsent(q.getBankId(), this::bankName);
        }
        return toResponse(n, links, names, questions);
    }

    /** 关联 → 界面可直接显示的形态：题库显示库名，题目显示「库名 · 第 N 题」 */
    private static NoteResponse toResponse(Note n, List<NoteLink> links,
                                           Map<Long, String> bankNames, Map<Long, Question> questions) {
        List<NoteLinkResponse> views = new ArrayList<>();
        for (NoteLink link : links) {
            if (NoteLink.TYPE_BANK.equals(link.getTargetType())) {
                String name = bankNames.get(link.getTargetId());
                views.add(new NoteLinkResponse(NoteLink.TYPE_BANK, link.getTargetId(),
                        name == null ? "已删除的题库" : name, link.getTargetId(), null));
            } else {
                Question q = questions.get(link.getTargetId());
                if (q == null) {
                    views.add(new NoteLinkResponse(NoteLink.TYPE_QUESTION, link.getTargetId(),
                            "已删除的题目", null, null));
                    continue;
                }
                String name = bankNames.get(q.getBankId());
                String where = name == null ? "题库" : name;
                views.add(new NoteLinkResponse(NoteLink.TYPE_QUESTION, q.getId(),
                        q.getQuestionNumber() == null ? where + " · 某道题" : where + " · 第 " + q.getQuestionNumber() + " 题",
                        q.getBankId(), q.getQuestionNumber()));
            }
        }
        return new NoteResponse(n.getId(), n.getContent(), n.getSource(), n.getColor(), views,
                n.getCreatedAt(), n.getUpdatedAt());
    }
}
