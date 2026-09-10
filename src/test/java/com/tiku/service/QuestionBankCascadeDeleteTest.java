package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.dto.DeleteBankResult;
import com.tiku.mapper.AiImportJobMapper;
import com.tiku.mapper.MaterialMapper;
import com.tiku.mapper.PracticeSessionMapper;
import com.tiku.mapper.PracticeSessionQuestionMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.ReviewStateMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 两个已修复缺陷的回归测试（2026-09-11）：
 *
 * 1) 删题库必须**物理**删除题目、并解除 AI 导入任务的 bank_id 引用。
 *    过去走 @TableLogic 逻辑删除，会留下 bank_id 指向已删题库的孤儿行，
 *    且继续占用唯一键 uk_question_bank_external_id_deleted。
 * 2) 导入必须幂等：同题库内已存在同 questionKey 时跳过，不能插入第二条 deleted=0 的同键行
 *    （否则撞唯一键 → 500）。重复确认同一 AI 导入任务、同一份文件导入两次都会命中。
 *
 * 不加载 Spring 上下文、不碰真实数据目录。
 */
class QuestionBankCascadeDeleteTest {

    // ==================== 1. 删题库：物理删除 + 解引用 ====================

    @Test
    void deleteBankPhysicallyDeletesQuestionsAndClearsJobReference() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        StudyRecordMapper studyRecordMapper = mock(StudyRecordMapper.class);
        ReviewStateMapper reviewStateMapper = mock(ReviewStateMapper.class);
        PracticeSessionMapper sessionMapper = mock(PracticeSessionMapper.class);
        PracticeSessionQuestionMapper sessionQuestionMapper = mock(PracticeSessionQuestionMapper.class);
        MaterialMapper materialMapper = mock(MaterialMapper.class);
        AiImportJobMapper jobMapper = mock(AiImportJobMapper.class);

        QuestionBank bank = new QuestionBank();
        bank.setId(9L);
        bank.setName("测试题库");
        when(bankMapper.selectById(9L)).thenReturn(bank);
        when(questionMapper.deletePhysicallyByBankId(9L)).thenReturn(4);
        when(studyRecordMapper.delete(any())).thenReturn(7);

        QuestionBankService service = new QuestionBankService(bankMapper, questionMapper, studyRecordMapper,
                reviewStateMapper, sessionMapper, sessionQuestionMapper, materialMapper, jobMapper);

        DeleteBankResult result = service.deleteQuestionBank(9L);

        // 题目走物理删除（注解 SQL 绕开 @TableLogic），不再调用带逻辑删除的 delete(Wrapper)
        verify(questionMapper).deletePhysicallyByBankId(9L);
        verify(questionMapper, never()).delete(any());
        // AI 导入任务的 bank_id 解引用（任务本身保留）
        verify(jobMapper).clearBankId(9L);
        // 级联清理照旧
        verify(sessionQuestionMapper).deleteByBankId(9L);
        verify(materialMapper).delete(any());
        verify(bankMapper).deleteById(9L);
        // 返回给前端的计数不变
        assertEquals(4L, result.deletedQuestions());
        assertEquals(7L, result.affectedRecords());
    }

    // ==================== 2. 导入幂等：同 questionKey 跳过 ====================

    @Test
    void importSkipsQuestionsWhoseKeyAlreadyExistsInBank() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        MaterialMapper materialMapper = mock(MaterialMapper.class);
        StudyRecordMapper studyRecordMapper = mock(StudyRecordMapper.class);
        ImageStorageService imageStorageService = mock(ImageStorageService.class);

        ContentPackageService service = new ContentPackageService(bankMapper, questionMapper, materialMapper,
                studyRecordMapper, imageStorageService, new ObjectMapper());

        // 注意：Mockito 对未打桩的 Long 返回 0（不是 null），必须显式打桩默认「查不到」，
        // 否则会被服务当成"该 questionKey 已存在"而全部跳过。
        when(questionMapper.selectActiveIdByBankAndExternalId(anyLong(), anyString())).thenReturn(null);
        // Q1 已存在于题库 7；Q2 / Q3 不存在（走上面的默认桩 → 插入）
        when(questionMapper.selectActiveIdByBankAndExternalId(eq(7L), eq("Q1"))).thenReturn(101L);

        int imported = service.importQuestionsToBank(7L, List.of(
                question("Q1", "已存在的题干"),
                question("Q2", "新题干二"),
                question("Q3", "新题干三")));

        // 只插入了两条（重复的那条被跳过，而不是撞唯一键抛异常）
        assertEquals(2, imported);
        verify(questionMapper, times(2)).insert(any(Question.class));
        verify(questionMapper, never()).insert(argThat((Question q) -> "Q1".equals(q.getExternalId())));
    }

    @Test
    void importStillInsertsWhenBankIsEmpty() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        MaterialMapper materialMapper = mock(MaterialMapper.class);
        StudyRecordMapper studyRecordMapper = mock(StudyRecordMapper.class);
        ImageStorageService imageStorageService = mock(ImageStorageService.class);

        ContentPackageService service = new ContentPackageService(bankMapper, questionMapper, materialMapper,
                studyRecordMapper, imageStorageService, new ObjectMapper());
        // 空题库：两道题都查不到同 questionKey 的行（Mockito 对 Long 默认返回 0，必须显式给 null）
        when(questionMapper.selectActiveIdByBankAndExternalId(anyLong(), anyString())).thenReturn(null);

        int imported = service.importQuestionsToBank(3L, List.of(question("A1", "题干一"), question("A2", "题干二")));

        assertEquals(2, imported);
        verify(questionMapper, times(2)).insert(any(Question.class));
        // 每道题都做了去重检查（未命中即正常插入）
        verify(questionMapper, times(2)).selectActiveIdByBankAndExternalId(eq(3L), anyString());
    }

    private static ContentPackageQuestion question(String key, String content) {
        ContentPackageQuestion q = new ContentPackageQuestion();
        q.setQuestionKey(key);
        q.setType("SINGLE");
        q.setContent(content);
        return q;
    }
}
