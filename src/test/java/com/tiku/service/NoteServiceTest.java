package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tiku.dto.NoteRequest;
import com.tiku.dto.NoteResponse;
import com.tiku.dto.PageResult;
import com.tiku.mapper.NoteMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.model.Note;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 笔记（我的，不是题库的）。
 *
 * 这个模块的价值全在**边界**上，所以测试也主要锁边界：
 * ① 挂题与挂题库（随手记）两种归属都能用；
 * ② `source` 区分"我写的"与"AI 讲解存进来的"（界面上要能一眼看出）；
 * ③ **只存本机**：不进内容包（导出/备份另有其道），题被删/库被删时跟着清掉，不留孤儿行；
 * ④ 内容必填、有长度上限（不做成文档编辑器）。
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
    private QuestionMapper questionMapper;
    @Autowired
    private QuestionService questionService;
    @Autowired
    private QuestionBankService questionBankService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long questionId;

    @BeforeEach
    void setUp() {
        for (Long bankId : List.of(BANK_ID, OTHER_BANK_ID)) {
            jdbc.update("DELETE FROM note WHERE bank_id = ?", bankId);
            jdbc.update("DELETE FROM study_record WHERE bank_id = ?", bankId);
            jdbc.update("DELETE FROM question WHERE bank_id = ?", bankId);
            jdbc.update("DELETE FROM question_bank WHERE id = ?", bankId);
            jdbc.update("INSERT INTO question_bank (id, name, created_at, updated_at) VALUES (?, '笔记测试', NOW(), NOW())", bankId);
        }
        questionId = insertQuestion(BANK_ID, 1);
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

    @Test
    void notesCanHangOnAQuestionOrOnTheBankItself() {
        NoteResponse mine = noteService.create(BANK_ID, new NoteRequest(questionId, "  先看年份再动笔  ", null));
        assertEquals("先看年份再动笔", mine.content(), "首尾空白要去掉");
        assertEquals(Note.SOURCE_USER, mine.source(), "默认是「我写的」");
        assertEquals(1, mine.questionNumber(), "带题号，列表里不用再查");

        NoteResponse quick = noteService.create(BANK_ID, new NoteRequest(null, "最近在练资料分析", null));
        assertNull(quick.questionId(), "不挂题就是题库级随手记");
        assertNull(quick.questionNumber());

        List<NoteResponse> ofQuestion = noteService.listOfQuestion(questionId);
        assertEquals(1, ofQuestion.size(), "按题查只返回这道题的");
        assertEquals(mine.id(), ofQuestion.get(0).id());

        PageResult<NoteResponse> all = noteService.list(BANK_ID, null, 1, 20);
        assertEquals(2, all.total(), "题库列表里两种归属都在");
        PageResult<NoteResponse> onlyQuestion = noteService.list(BANK_ID, questionId, 1, 20);
        assertEquals(1, onlyQuestion.total());
        assertEquals(2, noteService.countByBank(BANK_ID));
    }

    @Test
    void aiSavedNotesAreMarkedAsSuch() {
        NoteResponse fromAi = noteService.create(BANK_ID, new NoteRequest(questionId, "【错在哪】把基期当成了现期", "ai"));
        assertEquals(Note.SOURCE_AI, fromAi.source());
        // 非法 source 一律按"我写的"处理（不引入第三种状态）
        NoteResponse weird = noteService.create(BANK_ID, new NoteRequest(questionId, "随便", "system"));
        assertEquals(Note.SOURCE_USER, weird.source());
    }

    @Test
    void contentIsRequiredAndCapped() {
        assertThrows(IllegalArgumentException.class, () -> noteService.create(BANK_ID, new NoteRequest(questionId, "   ", null)));
        String longText = "字".repeat(3000);
        NoteResponse saved = noteService.create(BANK_ID, new NoteRequest(questionId, longText, null));
        assertEquals(2000, saved.content().length(), "超过上限要截断，不是报错（用户写长了不该丢内容）");
    }

    @Test
    void updateAndDeleteWork() {
        NoteResponse n = noteService.create(BANK_ID, new NoteRequest(questionId, "第一版", null));
        NoteResponse updated = noteService.update(n.id(), "第二版：记住了");
        assertEquals("第二版：记住了", updated.content());
        assertTrue(updated.updatedAt() != null);

        noteService.delete(n.id());
        assertEquals(0, noteService.countByBank(BANK_ID));
        assertThrows(NoSuchElementException.class, () -> noteService.update(n.id(), "再来"));
    }

    /** 笔记挂在题上：题没了就没有挂靠对象，跟着清掉，不留孤儿行 */
    @Test
    void deletingAQuestionRemovesItsNotes() {
        noteService.create(BANK_ID, new NoteRequest(questionId, "这题我要二刷", null));
        noteService.create(BANK_ID, new NoteRequest(null, "库里随便记一条", null));

        questionService.deleteQuestion(questionId);

        assertEquals(0, noteMapper.selectCount(new LambdaQueryWrapper<Note>()
                .eq(Note::getQuestionId, questionId)));
        assertEquals(1, noteService.countByBank(BANK_ID), "题库级随手记不受影响");
    }

    /** 删题库要连笔记一起清掉（否则留下指向已删题库的孤儿行） */
    @Test
    void deletingABankRemovesItsNotes() {
        noteService.create(BANK_ID, new NoteRequest(questionId, "这条随题库一起走", null));
        noteService.create(OTHER_BANK_ID, new NoteRequest(null, "别的库的", null));

        questionBankService.deleteQuestionBank(BANK_ID);

        assertEquals(0, noteMapper.selectCount(new LambdaQueryWrapper<Note>()
                .eq(Note::getBankId, BANK_ID)));
        assertEquals(1, noteService.countByBank(OTHER_BANK_ID), "不影响别的题库");
    }

    @Test
    void notesOfAnotherBankCannotBeAttached() {
        Long otherQuestion = insertQuestion(OTHER_BANK_ID, 2);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> noteService.create(BANK_ID, new NoteRequest(otherQuestion, "跨库挂题", null)));
        assertTrue(e.getMessage().contains("不属于"), e.getMessage());
        assertFalse(e.getMessage().isBlank());
    }
}
