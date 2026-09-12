package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.dto.ExportRequest;
import com.tiku.dto.PageResult;
import com.tiku.dto.QuestionBankResponse;
import com.tiku.mapper.AiImportJobMapper;
import com.tiku.mapper.MaterialMapper;
import com.tiku.mapper.PracticeSessionMapper;
import com.tiku.mapper.PracticeSessionQuestionMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.ReviewStateMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.ContentPackageFile;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import com.tiku.model.StudyRecord;
import com.tiku.model.enums.QuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出范围与题库列表计数的回归测试（2026-09-12）。
 *
 * 1) 「勾选若干题后导出」：ExportRequest.questionIds 只导出勾选的题，
 *    且**不认领题库身份**（子集内容不能写进题库的 checksum/packageKey）。
 * 2) 修一个老 bug：`scope为空但带 category` 的导出过去被误判成"完整导出"并认领身份
 *    （`a || b && c` 的优先级坑），把子集指纹写进题库 → 之后"内容是否变化"判断全部失真。
 * 3) 题库列表要显示「共 N 题 · 已做 M」，计数由列表接口批量填充。
 *
 * 不加载 Spring 上下文、不碰真实数据目录。
 */
class ContentPackageExportScopeTest {

    private static Question question(long id, long bankId, String category, String content) {
        Question q = new Question();
        q.setId(id);
        q.setBankId(bankId);
        q.setExternalId("Q" + id);
        q.setQuestionType(QuestionType.SINGLE);
        q.setContent(content);
        q.setCategory(category);
        q.setAnswerKeys("A");
        return q;
    }

    private static ContentPackageService service(QuestionBankMapper bankMapper, QuestionMapper questionMapper,
                                                 MaterialMapper materialMapper, StudyRecordMapper studyRecordMapper) {
        return new ContentPackageService(bankMapper, questionMapper, materialMapper, studyRecordMapper,
                mock(ImageStorageService.class), new ObjectMapper());
    }

    private static QuestionBank bank(Long id, String packageKey) {
        QuestionBank bank = new QuestionBank();
        bank.setId(id);
        bank.setName("测试题库");
        bank.setPackageKey(packageKey);
        bank.setVersion("1.0.0");
        return bank;
    }

    @Test
    void exportByQuestionIdsKeepsOnlySelectedQuestionsAndDoesNotClaimIdentity() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        MaterialMapper materialMapper = mock(MaterialMapper.class);
        StudyRecordMapper studyRecordMapper = mock(StudyRecordMapper.class);

        QuestionBank bank = bank(5L, null); // 自建题库：无身份
        when(bankMapper.selectById(5L)).thenReturn(bank);
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(11L, 5L, null, "第一题"),
                question(12L, 5L, null, "第二题"),
                question(13L, 5L, null, "第三题")));

        ContentPackageFile file = service(bankMapper, questionMapper, materialMapper, studyRecordMapper)
                .exportContentPackage(5L, new ExportRequest(null, null, null, null, null, null, List.of(11L, 13L)));

        assertEquals(2, file.getQuestions().size(), "只导出勾选的两题");
        assertEquals(List.of("第一题", "第三题"), file.getQuestions().stream()
                .map(q -> q.getContent()).toList());
        // 子集导出不认领身份：不把指纹/packageKey 回写题库
        verify(bankMapper, never()).updateById(any(QuestionBank.class));
        assertNotNull(file.getPackageKey(), "文件仍带一个临时身份（便于分享/导入）");
    }

    @Test
    void exportFilteredByCategoryOnlyDoesNotClaimIdentity() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        MaterialMapper materialMapper = mock(MaterialMapper.class);
        StudyRecordMapper studyRecordMapper = mock(StudyRecordMapper.class);

        QuestionBank bank = bank(6L, null);
        when(bankMapper.selectById(6L)).thenReturn(bank);
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(21L, 6L, "基础", "基础题"),
                question(22L, 6L, "进阶", "进阶题")));

        // scope 为空、只带 category：这是"范围导出"，不是完整导出（老代码在这里误认领身份）
        ContentPackageFile file = service(bankMapper, questionMapper, materialMapper, studyRecordMapper)
                .exportContentPackage(6L, new ExportRequest(null, null, null, null, "基础", null, null));

        assertEquals(1, file.getQuestions().size());
        assertEquals("基础题", file.getQuestions().get(0).getContent());
        verify(bankMapper, never()).updateById(any(QuestionBank.class));
    }

    @Test
    void fullExportStillClaimsIdentity() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        MaterialMapper materialMapper = mock(MaterialMapper.class);
        StudyRecordMapper studyRecordMapper = mock(StudyRecordMapper.class);

        QuestionBank bank = bank(7L, null);
        when(bankMapper.selectById(7L)).thenReturn(bank);
        when(questionMapper.selectList(any())).thenReturn(List.of(question(31L, 7L, null, "唯一题")));

        ContentPackageFile file = service(bankMapper, questionMapper, materialMapper, studyRecordMapper)
                .exportContentPackage(7L, null);

        assertEquals(1, file.getQuestions().size());
        // 完整导出：认领身份（packageKey/version/checksum 回写题库）
        verify(bankMapper, times(1)).updateById(any(QuestionBank.class));
        assertEquals(file.getPackageKey(), bank.getPackageKey());
    }

    @Test
    void bankListFillsQuestionAndAnsweredCounts() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        StudyRecordMapper studyRecordMapper = mock(StudyRecordMapper.class);

        QuestionBank a = bank(1L, null);
        QuestionBank b = bank(2L, null);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<QuestionBank> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 50);
        page.setRecords(List.of(a, b));
        page.setTotal(2);
        when(bankMapper.selectPage(any(), any())).thenReturn(page);
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(101L, 1L, null, "a1"), question(102L, 1L, null, "a2"),
                question(201L, 2L, null, "b1")));
        StudyRecord r1 = new StudyRecord();
        r1.setBankId(1L);
        r1.setQuestionId(101L);
        StudyRecord r2 = new StudyRecord();
        r2.setBankId(1L);
        r2.setQuestionId(101L); // 同一题做两次：只算一次
        StudyRecord r3 = new StudyRecord();
        r3.setBankId(1L);
        r3.setQuestionId(102L);
        when(studyRecordMapper.selectList(any())).thenReturn(List.of(r1, r2, r3));

        QuestionBankService service = new QuestionBankService(bankMapper, questionMapper, studyRecordMapper,
                mock(ReviewStateMapper.class), mock(PracticeSessionMapper.class),
                mock(PracticeSessionQuestionMapper.class), mock(MaterialMapper.class), mock(AiImportJobMapper.class));

        PageResult<QuestionBankResponse> result = service.listQuestionBanks(1, 50, null, null);

        assertEquals(2, result.records().size());
        QuestionBankResponse first = result.records().get(0);
        assertEquals(2L, first.questionCount());
        assertEquals(2L, first.answeredCount(), "同一题做两次只算一次");
        QuestionBankResponse second = result.records().get(1);
        assertEquals(1L, second.questionCount());
        assertEquals(0L, second.answeredCount(), "没有记录的题库显示已做 0");
    }
}
