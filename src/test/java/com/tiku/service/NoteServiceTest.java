package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tiku.dto.NoteLinkRequest;
import com.tiku.dto.NoteLinkResponse;
import com.tiku.dto.NoteRequest;
import com.tiku.dto.NoteResponse;
import com.tiku.dto.PageResult;
import com.tiku.mapper.NoteLinkMapper;
import com.tiku.mapper.NoteMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.model.Note;
import com.tiku.model.NoteLink;
import com.tiku.model.OptionItem;
import com.tiku.model.Question;
import com.tiku.model.enums.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 笔记（我的，不是题库的）。
 *
 * 这个模块的价值全在**边界**上，所以测试也主要锁边界：
 * ① 笔记不隶属于任何题库/题目：挂在哪里由关联决定，一条笔记可以同时挂多个题库、多道题；
 * ② 一条都不挂也合法（未归类），题库/题目被删只删关联、笔记内容留着；
 * ③ `source` 区分"我写的"与"AI 讲解存进来的"（界面上要能一眼看出）；
 * ④ **只存本机**：不进内容包（导出/备份另有其道）；
 * ⑤ 内容必填、有长度上限（不做成文档编辑器）。
 */
@SpringBootTest
@ActiveProfiles("test")
class NoteServiceTest {

    private static final Long BANK_ID = 993001L;
    private static final Long OTHER_BANK_ID = 993002L;

    @Autowired
    private NoteService noteService;
    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private NoteLinkMapper noteLinkMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private QuestionService questionService;
    @Autowired
    private QuestionBankService questionBankService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long questionId;
    private Long otherQuestionId;

    @BeforeEach
    void setUp() {
        for (Long bankId : List.of(BANK_ID, OTHER_BANK_ID)) {
            jdbc.update("DELETE FROM note_link WHERE note_id IN (SELECT id FROM note)");
            jdbc.update("DELETE FROM note");
            jdbc.update("DELETE FROM study_record WHERE bank_id = ?", bankId);
            jdbc.update("DELETE FROM question WHERE bank_id = ?", bankId);
            jdbc.update("DELETE FROM question_bank WHERE id = ?", bankId);
            jdbc.update("INSERT INTO question_bank (id, name, created_at, updated_at) VALUES (?, '笔记测试', NOW(), NOW())", bankId);
        }
        questionId = insertQuestion(BANK_ID, 1);
        otherQuestionId = insertQuestion(OTHER_BANK_ID, 2);
    }

    private Long insertQuestion(Long bankId, int number) {
        Question q = new Question();
        q.setQuestionNumber(number);
        q.setExternalId("NOTE_" + bankId + "_" + System.nanoTime() + "_" + number);
        q.setBankId(bankId);
        q.setVolume(0);
        q.setQuestionType(QuestionType.SINGLE);
        q.setContent("第 " + number + " 题：增长率怎么算？");
        q.setOptions(List.of(new OptionItem("A", "现期/基期-1"), new OptionItem("B", "基期/现期-1")));
        q.setAnswerKeys("A");
        q.setScore(1.0);
        questionMapper.insert(q);
        return q.getId();
    }

    private static NoteLinkResponse link(NoteResponse note, String type) {
        return note.links().stream().filter(l -> l.type().equals(type)).findFirst().orElseThrow();
    }

    @Test
    void notesCanHangOnAQuestionOrStayUnlinked() {
        NoteResponse mine = noteService.create(new NoteRequest(BANK_ID, questionId, "  先看年份再动笔  ", null));
        assertEquals("先看年份再动笔", mine.content(), "首尾空白要去掉");
        assertEquals(Note.SOURCE_USER, mine.source(), "默认是「我写的」");
        assertEquals(2, mine.links().size(), "做题页写下的笔记同时带上题库与题目两条关联");
        assertEquals(1, link(mine, NoteLink.TYPE_QUESTION).questionNumber(), "题目关联带题号，列表里不用再查");
        assertEquals(BANK_ID, link(mine, NoteLink.TYPE_BANK).targetId());

        NoteResponse free = noteService.create(new NoteRequest(null, null, "最近在练资料分析", null));
        assertTrue(free.links().isEmpty(), "什么都不挂就是未归类，合法状态");

        List<NoteResponse> ofQuestion = noteService.listOfQuestion(questionId);
        assertEquals(1, ofQuestion.size(), "按题查只返回挂在这道题上的");
        assertEquals(mine.id(), ofQuestion.get(0).id());

        PageResult<NoteResponse> all = noteService.list(null, null, false, 1, 20);
        assertEquals(2, all.total(), "全部笔记两种都在");
        assertEquals(1, noteService.list(BANK_ID, null, false, 1, 20).total(), "按题库查只返回这个库的");
        PageResult<NoteResponse> unlinked = noteService.list(null, null, true, 1, 20);
        assertEquals(1, unlinked.total(), "未归类只返回一条关联都没有的");
        assertEquals(free.id(), unlinked.records().get(0).id());
        assertEquals(1, noteService.countByBank(BANK_ID));
    }

    /** 只挂题、不挂库：仍算"这个题库的笔记"（口径：挂库上的 + 挂这个库题目上的） */
    @Test
    void questionOnlyNotesStillCountAsThatBankNotes() {
        NoteResponse byLink = noteService.create(new NoteRequest(null, null, "只挂题不挂库", null));
        noteService.addLink(byLink.id(), new NoteLinkRequest(NoteLink.TYPE_QUESTION, questionId));

        assertEquals(1, noteService.list(BANK_ID, null, false, 1, 20).total());
        assertEquals(1, noteService.countByBank(BANK_ID));
        assertEquals(0, noteService.list(OTHER_BANK_ID, null, false, 1, 20).total(), "别的库不受影响");
        assertEquals(1, noteService.list(BANK_ID, questionId, false, 1, 20).total());
    }

    @Test
    void oneNoteCanBeLinkedToManyBanksAndQuestions() {
        NoteResponse note = noteService.create(new NoteRequest(null, null, "增长率题都要先看年份", null));

        noteService.addLink(note.id(), new NoteLinkRequest(NoteLink.TYPE_QUESTION, questionId));
        noteService.addLink(note.id(), new NoteLinkRequest(NoteLink.TYPE_QUESTION, otherQuestionId));
        NoteResponse linked = noteService.addLink(note.id(), new NoteLinkRequest(NoteLink.TYPE_BANK, OTHER_BANK_ID));

        assertEquals(3, linked.links().size(), "一条笔记同时挂 2 道题（各在不同题库）+ 另一个题库");
        // 挂在这个库题目上的笔记也算这个库的（口径见 bankNoteIdsSql）
        assertEquals(1, noteService.list(BANK_ID, null, false, 1, 20).total());
        assertEquals(1, noteService.list(OTHER_BANK_ID, null, false, 1, 20).total());
        assertEquals(1, noteService.listOfQuestion(otherQuestionId).size(), "跨题库的题也能挂");

        // 重复挂同一处是幂等的（复合主键，不会出现第二条）
        NoteResponse again = noteService.addLink(note.id(), new NoteLinkRequest(NoteLink.TYPE_BANK, OTHER_BANK_ID));
        assertEquals(3, again.links().size());
        assertEquals(1, noteLinkMapper.selectCount(new LambdaQueryWrapper<NoteLink>()
                .eq(NoteLink::getNoteId, note.id())
                .eq(NoteLink::getTargetType, NoteLink.TYPE_BANK)
                .eq(NoteLink::getTargetId, OTHER_BANK_ID)));

        // 去掉题库关联：题上的关联还在，笔记仍属于那个库
        NoteResponse afterRemove = noteService.removeLink(note.id(), NoteLink.TYPE_BANK, OTHER_BANK_ID);
        assertEquals(2, afterRemove.links().size());
        assertEquals(1, noteService.list(OTHER_BANK_ID, null, false, 1, 20).total(), "题在别的库，仍能查到");
    }

    @Test
    void aiSavedNotesAreMarkedAsSuch() {
        NoteResponse fromAi = noteService.create(new NoteRequest(BANK_ID, questionId, "【错在哪】把基期当成了现期", "ai"));
        assertEquals(Note.SOURCE_AI, fromAi.source());
        // 非法 source 一律按"我写的"处理（不引入第三种状态）
        NoteResponse weird = noteService.create(new NoteRequest(BANK_ID, questionId, "随便", "system"));
        assertEquals(Note.SOURCE_USER, weird.source());
    }

    @Test
    void contentIsRequiredAndCapped() {
        assertThrows(IllegalArgumentException.class,
                () -> noteService.create(new NoteRequest(BANK_ID, null, "   ", null)));
        String longText = "字".repeat(3000);
        NoteResponse saved = noteService.create(new NoteRequest(BANK_ID, null, longText, null));
        assertEquals(2000, saved.content().length(), "超过上限要截断，不是报错（用户写长了不该丢内容）");
    }

    @Test
    void updateAndDeleteWork() {
        NoteResponse n = noteService.create(new NoteRequest(BANK_ID, questionId, "第一版", null));
        NoteResponse updated = noteService.update(n.id(), "第二版：记住了");
        assertEquals("第二版：记住了", updated.content());
        assertTrue(updated.updatedAt() != null);
        assertEquals(2, updated.links().size(), "改内容不动关联");
        noteService.delete(n.id());
        assertEquals(0, noteService.countByBank(BANK_ID));
        assertEquals(0, noteLinkMapper.selectCount(new LambdaQueryWrapper<NoteLink>().eq(NoteLink::getNoteId, n.id())),
                "删笔记要连关联一起删，不留孤儿行");
        assertThrows(NoSuchElementException.class, () -> noteService.update(n.id(), "再来"));
    }

    /** 题被删：挂在这道题的关联没了，笔记内容还在（挂了库的仍归那个库，只挂题的变成未归类） */
    @Test
    void deletingAQuestionOnlyDropsThatLink() {
        NoteResponse questionOnly = noteService.create(new NoteRequest(null, questionId, "这题我要二刷", null));
        NoteResponse alsoBank = noteService.create(new NoteRequest(BANK_ID, questionId, "库里的想法", null));
        long before = noteMapper.selectCount(null);

        questionService.deleteQuestion(questionId);

        assertEquals(0, noteLinkMapper.selectCount(new LambdaQueryWrapper<NoteLink>()
                .eq(NoteLink::getTargetType, NoteLink.TYPE_QUESTION)
                .eq(NoteLink::getTargetId, questionId)), "题目关联要清掉");
        assertEquals(before, noteMapper.selectCount(null), "笔记内容不能因为题被删就消失");
        assertEquals(2, noteService.list(null, null, false, 1, 20).total());
        assertEquals(0, noteService.listOfQuestion(questionId).size(), "按已删题目查不会再返回它");
        assertEquals("这题我要二刷", questionOnly.content());
        assertEquals(1, noteService.list(BANK_ID, null, false, 1, 20).total(), "挂了库的那条仍归这个库");
        assertEquals(1, noteService.list(null, null, true, 1, 20).total(), "只挂题的那条变成未归类");
        assertEquals(alsoBank.id(), noteService.list(BANK_ID, null, false, 1, 20).records().get(0).id());
    }

    /** 题库被删：只清关联，笔记留成"未归类"（用户写的东西不该跟着题库消失） */
    @Test
    void deletingABankKeepsTheNoteButDropsLinks() {
        NoteResponse mine = noteService.create(new NoteRequest(BANK_ID, questionId, "这条要留着", null));
        noteService.create(new NoteRequest(OTHER_BANK_ID, null, "别的库的", null));

        questionBankService.deleteQuestionBank(BANK_ID);

        assertEquals(0, noteLinkMapper.selectCount(new LambdaQueryWrapper<NoteLink>()
                .eq(NoteLink::getTargetType, NoteLink.TYPE_BANK)
                .eq(NoteLink::getTargetId, BANK_ID)));
        assertEquals(0, noteService.countByBank(BANK_ID));
        NoteResponse kept = noteService.list(null, null, true, 1, 20).records().stream()
                .filter(n -> n.id().equals(mine.id())).findFirst().orElseThrow();
        assertEquals("这条要留着", kept.content(), "笔记内容必须还在");
        assertTrue(kept.links().isEmpty(), "关联清空 → 显示为未归类");
        assertEquals(1, noteService.countByBank(OTHER_BANK_ID), "不影响别的题库");
    }

    @Test
    void linkingToSomethingThatDoesNotExistIsRejected() {
        NoteResponse note = noteService.create(new NoteRequest(null, null, "随便记一条", null));
        assertThrows(NoSuchElementException.class,
                () -> noteService.addLink(note.id(), new NoteLinkRequest(NoteLink.TYPE_QUESTION, 987654321L)));
        assertThrows(NoSuchElementException.class,
                () -> noteService.addLink(note.id(), new NoteLinkRequest(NoteLink.TYPE_BANK, 987654321L)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> noteService.addLink(note.id(), new NoteLinkRequest("topic", 1L)));
        assertTrue(e.getMessage().contains("bank"), e.getMessage());
        assertFalse(e.getMessage().isBlank());
    }
}
