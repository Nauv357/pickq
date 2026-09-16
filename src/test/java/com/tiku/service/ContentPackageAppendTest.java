package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.mapper.MaterialMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.Material;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import com.tiku.model.enums.QuestionType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「从题库包追加」的回归测试（2026-09-16）。
 *
 * 这条路以前不存在：想用别的题库文件增题，只能"导入成一个新题库再合并"，会把题库身份搅乱。
 * 追加必须满足：
 * 1) 只往目标题库里加题，**不动题库身份**（packageKey/checksum/version 一律不写）；
 * 2) 题号接在现有最大题号之后（否则列表顺序错乱）；
 * 3) 同 questionKey 的题跳过（重复追加同一份文件不翻倍）；
 * 4) 材料跟着一起落进来，且挂到目标题库。
 *
 * 不加载 Spring 上下文、不碰真实数据目录。
 */
class ContentPackageAppendTest {

    private static final String PACKAGE_JSON = """
            {
              "schemaVersion": 1,
              "packageKey": "pkg-other",
              "title": "另一个题库",
              "version": "2.1.0",
              "questions": [
                {"questionKey": "OTHER-1", "type": "SINGLE", "content": "追加题干一",
                 "options": [{"key": "A", "text": "甲"}, {"key": "B", "text": "乙"}], "answerKeys": ["A"]},
                {"questionKey": "OTHER-2", "type": "SINGLE", "content": "追加题干二",
                 "options": [{"key": "A", "text": "甲"}, {"key": "B", "text": "乙"}], "answerKeys": ["B"]}
              ],
              "materials": [
                {"materialKey": "M-1", "content": "共用材料正文"}
              ]
            }
            """;

    private static ContentPackageService service(QuestionBankMapper bankMapper, QuestionMapper questionMapper,
                                                 MaterialMapper materialMapper) {
        return new ContentPackageService(bankMapper, questionMapper, materialMapper,
                mock(StudyRecordMapper.class), mock(ImageStorageService.class), new ObjectMapper());
    }

    private static QuestionBank bank(Long id) {
        QuestionBank bank = new QuestionBank();
        bank.setId(id);
        bank.setName("我的题库");
        bank.setPackageKey("pkg-mine");
        bank.setVersion("1.0.0");
        return bank;
    }

    /** 现有最大题号 */
    private static Question maxNumbered(Long bankId, int number) {
        Question q = new Question();
        q.setId(9L);
        q.setBankId(bankId);
        q.setQuestionNumber(number);
        return q;
    }

    @Test
    void appendNumbersQuestionsAfterExistingMaxAndKeepsBankIdentity() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        MaterialMapper materialMapper = mock(MaterialMapper.class);
        when(bankMapper.selectById(7L)).thenReturn(bank(7L));
        when(questionMapper.selectOne(any())).thenReturn(maxNumbered(7L, 12));
        // 包里的题都没进过这个题库
        when(questionMapper.selectActiveIdByBankAndExternalId(any(), any())).thenReturn(null);

        int inserted = service(bankMapper, questionMapper, materialMapper).appendContentPackage(7L, PACKAGE_JSON);

        assertEquals(2, inserted, "包里 2 道新题应全部插入");

        ArgumentCaptor<Question> captor = ArgumentCaptor.forClass(Question.class);
        verify(questionMapper, times(2)).insert(captor.capture());
        List<Question> saved = captor.getAllValues();
        assertEquals(13, saved.get(0).getQuestionNumber(), "题号应接在现有最大题号 12 之后");
        assertEquals(14, saved.get(1).getQuestionNumber(), "题号应连续递增");
        assertEquals(7L, saved.get(0).getBankId(), "题必须落进目标题库");
        assertEquals("OTHER-1", saved.get(0).getExternalId(), "questionKey 作为 externalId 保留");
        assertEquals(QuestionType.SINGLE, saved.get(0).getQuestionType());

        // 题库身份是「原题库的」，追加不能改它——否则之后"内容是否变化/是不是同一题库"全部失真
        verify(bankMapper, never()).updateById(any(QuestionBank.class));
        verify(bankMapper, never()).insert(any(QuestionBank.class));
    }

    @Test
    void appendSkipsQuestionsAlreadyInBank() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        MaterialMapper materialMapper = mock(MaterialMapper.class);
        when(bankMapper.selectById(7L)).thenReturn(bank(7L));
        when(questionMapper.selectOne(any())).thenReturn(maxNumbered(7L, 3));
        // 两道题都已经在这个题库里（重复追加同一份文件）
        when(questionMapper.selectActiveIdByBankAndExternalId(7L, "OTHER-1")).thenReturn(100L);
        when(questionMapper.selectActiveIdByBankAndExternalId(7L, "OTHER-2")).thenReturn(101L);

        int inserted = service(bankMapper, questionMapper, materialMapper).appendContentPackage(7L, PACKAGE_JSON);

        assertEquals(0, inserted, "同 questionKey 的题必须跳过，不能翻倍");
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void appendBringsMaterialsIntoTheTargetBank() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        MaterialMapper materialMapper = mock(MaterialMapper.class);
        when(bankMapper.selectById(7L)).thenReturn(bank(7L));
        when(questionMapper.selectOne(any())).thenReturn(null);
        when(questionMapper.selectActiveIdByBankAndExternalId(any(), any())).thenReturn(null);

        service(bankMapper, questionMapper, materialMapper).appendContentPackage(7L, PACKAGE_JSON);

        ArgumentCaptor<Material> captor = ArgumentCaptor.forClass(Material.class);
        verify(materialMapper).insert(captor.capture());
        Material material = captor.getValue();
        assertEquals(7L, material.getBankId(), "材料必须挂到目标题库");
        assertEquals("共用材料正文", material.getContent());
        assertNotNull(material.getCreatedAt());
    }

    @Test
    void appendToMissingBankFails() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        when(bankMapper.selectById(404L)).thenReturn(null);

        ContentPackageService service = service(bankMapper, mock(QuestionMapper.class), mock(MaterialMapper.class));
        NoSuchElementException e = assertThrows(NoSuchElementException.class,
                () -> service.appendContentPackage(404L, PACKAGE_JSON));
        assertEquals("题库不存在：404", e.getMessage());
    }

    @Test
    void appendEmptyPackageFailsWithPlainLanguage() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        when(bankMapper.selectById(7L)).thenReturn(bank(7L));

        ContentPackageService service = service(bankMapper, mock(QuestionMapper.class), mock(MaterialMapper.class));
        String empty = """
                {"schemaVersion": 1, "packageKey": "pkg-x", "title": "空包", "version": "1.0.0", "questions": []}
                """;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.appendContentPackage(7L, empty));
        assertEquals("这个题库文件里没有题目", e.getMessage());
    }
}
